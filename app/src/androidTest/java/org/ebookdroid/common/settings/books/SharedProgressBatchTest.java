package org.ebookdroid.common.settings.books;

import com.foobnix.android.utils.Objects;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppProfile;
import org.junit.After;
import org.junit.Test;
import org.librera.LinkedJSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SharedProgressBatchTest {
    private final List<String> paths = new ArrayList<>();
    private String path(int id) {
        String path = "content://progress-batch/" + UUID.randomUUID() + "/Book" + id + ".epub";
        paths.add(path); return path;
    }
    @After public void cleanUp() { for (String path : paths) SharedBooks.cache.remove(path); }

    @Test public void cold500BookBatchReadsProfilesOnceAndPreservesLatestProgressAndLocalSettings() throws Exception {
        File original = AppProfile.syncProgress;
        File local = new File("batch-local-fixture.json");
        LinkedJSONObject localJson = new LinkedJSONObject(), remoteJson = new LinkedJSONObject();
        for (int i=0; i<500; i++) {
            String path = path(i), key = "Book" + i + ".epub";
            AppBook own = new AppBook(path); own.p = 0.2f; own.t = 100; own.z = 125;
            AppBook remote = new AppBook(path); remote.p = 0.8f; remote.t = 200; remote.z = 200;
            localJson.put(key, Objects.toJSONObject(own)); remoteJson.put(key, Objects.toJSONObject(remote));
        }
        Map<File, LinkedJSONObject> sources = new LinkedHashMap<>();
        sources.put(local, localJson); sources.put(new File("batch-remote-fixture.json"), remoteJson);
        AtomicInteger reads = new AtomicInteger();
        try {
            AppProfile.syncProgress = local;
            List<AppBook> result = SharedBooks.loadAll(paths, () -> { reads.incrementAndGet(); return sources; });
            assertEquals(1, reads.get()); assertEquals(500, result.size());
            for (int i=0; i<result.size(); i++) {
                AppBook book = result.get(i);
                assertEquals(paths.get(i), book.path); assertEquals(0.8f, book.p, 0.0001f);
                assertEquals(200, book.t); assertEquals(125, book.z);
            }
        } finally { AppProfile.syncProgress = original; }
    }

    @Test public void warmBatchDoesNotReadAnyProfileFiles() {
        for (int i=0; i<500; i++) {
            String path = path(i); AppBook book = new AppBook(path); book.p = 0.75f;
            SharedBooks.cache.put(path, book);
        }
        List<AppBook> result = SharedBooks.loadAll(paths, () -> { throw new AssertionError("Warm batch read profiles"); });
        assertEquals(500, result.size());
        for (AppBook book : result) assertEquals(0.75f, book.p, 0.0001f);
    }

    @Test public void readingAnOldSnapshotCannotReplaceConcurrentlySavedProgress() throws Exception {
        String path = path(0); CountDownLatch loading = new CountDownLatch(1), resume = new CountDownLatch(1);
        LinkedJSONObject json = new LinkedJSONObject(); AppBook old = new AppBook(path); old.p = 0.2f;
        json.put("Book0.epub", Objects.toJSONObject(old));
        Map<File, LinkedJSONObject> sources = new LinkedHashMap<File, LinkedJSONObject>() {
            @Override public Set<Map.Entry<File, LinkedJSONObject>> entrySet() {
                loading.countDown();
                try { assertTrue(resume.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return super.entrySet();
            }
        };
        sources.put(new File("batch-race-fixture.json"), json);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<AppBook>> future = executor.submit(() -> SharedBooks.loadAll(paths, () -> sources));
            assertTrue(loading.await(3, TimeUnit.SECONDS));
            AppBook saved = new AppBook(path); saved.p = 0.8f; saved.t = 200;
            SharedBooks.cache.put(path, saved); resume.countDown();
            assertSame(saved, future.get(3, TimeUnit.SECONDS).get(0));
            assertSame(saved, SharedBooks.cache.get(path));
        } finally { resume.countDown(); executor.shutdownNow(); }
    }
}
