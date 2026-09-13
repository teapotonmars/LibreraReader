package com.foobnix.ui2;

import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class AppDbSnapshotTest {
    private final List<String> paths = new ArrayList<>();
    @Before public void setUp() { AppProfile.init(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
    @After public void tearDown() { for (String path : paths) AppDB.get().deleteBy(path); }
    private FileMeta book() {
        String path = "content://snapshot-fixture/document/" + UUID.randomUUID(); paths.add(path);
        FileMeta row = AppDB.get().getOrCreate(path); row.setTitle("Old title");
        row.setIsRecent(true); row.setIsRecentProgress(0.75f); row.setIsStar(true);
        AppDB.get().save(row); return row;
    }
    @Test public void freshReadSeesDatabaseChangesWithoutEvictingSharedDaoEntity() {
        FileMeta cached = book();
        AppDB.get().getDao().getDatabase().execSQL("UPDATE FILE_META SET TITLE=? WHERE PATH=?",
                new Object[]{"New title", cached.getPath()});
        FileMeta fresh = AppDB.get().loadFresh(Collections.singleton(cached.getPath())).get(0);
        assertNotSame(cached, fresh); assertEquals("New title", fresh.getTitle());
        assertSame(cached, AppDB.get().load(cached.getPath())); assertEquals("Old title", cached.getTitle());
        assertEquals(Boolean.TRUE, fresh.getIsRecent()); assertEquals(Float.valueOf(0.75f), fresh.getIsRecentProgress());
        assertEquals(Boolean.TRUE, fresh.getIsStar());
    }
    @Test public void batchedReadsReturnAll300RowsWithoutMutatingReadingState() {
        for (int i=0; i<300; i++) book();
        List<FileMeta> result = AppDB.get().loadFresh(paths); assertEquals(300, result.size());
        assertEquals(new java.util.HashSet<>(paths), new java.util.HashSet<>(
                result.stream().map(FileMeta::getPath).collect(java.util.stream.Collectors.toList())));
        for (FileMeta row : result) { assertEquals(Boolean.TRUE, row.getIsRecent()); assertEquals(Float.valueOf(0.75f), row.getIsRecentProgress()); }
    }
    @Test public void emptyAndMissingLookupsDoNotCreateLibraryRows() {
        assertTrue(AppDB.get().loadFresh(Collections.emptyList()).isEmpty());
        String missing = "content://snapshot-fixture/missing/" + UUID.randomUUID();
        assertTrue(AppDB.get().loadFresh(Collections.singleton(missing)).isEmpty()); assertNull(AppDB.get().load(missing));
    }
    @Test public void readerProgressUpdatesBothCachedAndDetachedRowsWithoutChangingMetadata() {
        FileMeta cached = book();
        AppDB.get().updateReadingProgress(cached.getPath(), 0.796f);
        assertEquals(0.796f, cached.getIsRecentProgress(), 0.0001f);
        FileMeta fresh = AppDB.get().loadFresh(Collections.singleton(cached.getPath())).get(0);
        assertEquals(0.796f, fresh.getIsRecentProgress(), 0.0001f);
        assertEquals("Old title", fresh.getTitle()); assertEquals(Boolean.TRUE, fresh.getIsStar());
    }
}
