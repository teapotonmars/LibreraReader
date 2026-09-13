package com.foobnix.work;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppState;
import org.junit.Test;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CoverWarmupSchedulingTest {
    @Test public void rescanReplacesRunningWarmupAndWarmsTheNewBook() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean images = AppState.get().isShowImages;
        CountDownLatch firstStarted = new CountDownLatch(1), firstStopped = new CountDownLatch(1);
        CountDownLatch newBookWarmed = new CountDownLatch(1);
        AtomicInteger generations = new AtomicInteger();
        AtomicReference<List<FileMeta>> library = new AtomicReference<>(Collections.singletonList(new FileMeta("old-book")));
        WorkerFactory factory = new WorkerFactory() {
            @Override public ListenableWorker createWorker(Context ctx, String name, WorkerParameters params) {
                if (!name.equals(CoverWarmupWorker.class.getName())) throw new AssertionError(name);
                int generation = generations.incrementAndGet();
                return new CoverWarmupWorker(ctx, params) {
                    @Override protected List<FileMeta> loadBooks() { return library.get(); }
                    @Override protected void warmBook(Context ctx, FileMeta book) {
                        if (generation == 1) {
                            firstStarted.countDown();
                            try {
                                while (!isStopped()) Thread.sleep(10);
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                            } finally { firstStopped.countDown(); }
                        } else if ("new-book".equals(book.getPath())) {
                            newBookWarmed.countDown();
                        }
                    }
                };
            }
        };
        AppState.get().isShowImages = true;
        try (WorkManagerFixture fixture = new WorkManagerFixture(context, factory)) {
            String name = "cover-replacement-" + UUID.randomUUID();
            CoverWarmupWorker.enqueue(fixture.manager, name).getResult().get(5, TimeUnit.SECONDS);
            assertTrue("Old warmup never started", firstStarted.await(5, TimeUnit.SECONDS));
            library.set(Arrays.asList(new FileMeta("old-book"), new FileMeta("new-book")));
            CoverWarmupWorker.enqueue(fixture.manager, name).getResult().get(5, TimeUnit.SECONDS);
            assertTrue("Old warmup not canceled", firstStopped.await(5, TimeUnit.SECONDS));
            assertTrue("Replacement dropped the newly added book", newBookWarmed.await(5, TimeUnit.SECONDS));
            List<WorkInfo> work = fixture.manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS);
            assertEquals(2, generations.get());
            assertFalse(work.isEmpty());
        } finally { AppState.get().isShowImages = images; }
    }
}
