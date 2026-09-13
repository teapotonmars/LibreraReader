package com.foobnix.work;

import com.foobnix.dao2.FileMeta;
import com.foobnix.ui2.FileMetaCore;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LibraryMergeTest {
    private FileMeta book(String id, long size, long date) {
        FileMeta row = new FileMeta("content://fixture/document/" + id);
        row.setTitle("Library title"); row.setAuthor("Author"); row.setState(FileMetaCore.STATE_FULL);
        row.setSize(size); row.setDate(date); row.setIsSearchBook(true);
        row.setIsRecent(true); row.setIsRecentProgress(0.75f); row.setIsStar(true); return row;
    }
    private LibraryMerge.Result merge(FileMeta stored, FileMeta... discoveries) {
        return LibraryMerge.merge(Arrays.asList(discoveries), Collections.singletonMap(stored.getPath(), stored),
                Collections.emptySet(), Collections.emptySet());
    }
    private void assertReadingState(FileMeta row) {
        assertEquals(Boolean.TRUE, row.getIsRecent()); assertEquals(Float.valueOf(0.75f), row.getIsRecentProgress());
        assertEquals(Boolean.TRUE, row.getIsStar()); assertEquals("Library title", row.getTitle()); assertEquals("Author", row.getAuthor());
    }
    @Test public void unchangedRescanPreservesEntityMetadataAndReadingState() {
        FileMeta stored = book("a",100,200), discovery = book("a",100,200);
        discovery.setTitle("filename.epub"); discovery.setAuthor("Filename guess"); discovery.setPathTxt("filename.epub");
        LibraryMerge.Result result = merge(stored, discovery);
        assertSame(stored, result.books.get(0)); assertReadingState(stored); assertTrue(result.extract.isEmpty());
        assertEquals("filename.epub", stored.getPathTxt()); assertEquals(Integer.valueOf(FileMetaCore.STATE_FULL), stored.getState());
    }
    @Test public void changedRevisionIsDetectedBeforeStoredSizeAndDateAreRefreshed() {
        FileMeta stored = book("a",100,200); LibraryMerge.Result result = merge(stored, book("a",101,201));
        assertEquals(Collections.singletonList(stored), result.extract); assertReadingState(stored);
        assertEquals(Long.valueOf(101), stored.getSize()); assertEquals(Long.valueOf(201), stored.getDate());
        assertEquals(Integer.valueOf(FileMetaCore.STATE_BASIC), stored.getState());
    }
    @Test public void removedBookLeavesRecentProgressAndFavoritesIntact() {
        FileMeta stored = book("a",100,200); LibraryMerge.Result result = merge(stored);
        assertTrue(result.books.isEmpty()); assertEquals(Collections.singletonList(stored), result.removed);
        assertEquals(Boolean.FALSE, stored.getIsSearchBook()); assertReadingState(stored);
    }
    @Test public void duplicateDiscoveriesPublishAndExtractOnlyOnce() {
        FileMeta stored = book("a",100,200), fresh = book("new",100,200);
        LibraryMerge.Result result = merge(stored, stored, fresh, fresh);
        assertEquals(2, result.books.size()); assertEquals(Collections.singletonList(fresh), result.extract);
    }
    @Test public void calibreMetadataChangeForcesExtractionWithoutChangingReadingState() {
        FileMeta stored = book("a",100,200);
        LibraryMerge.Result result = LibraryMerge.merge(Collections.singletonList(book("a",100,200)),
                Collections.singletonMap(stored.getPath(), stored), Collections.singleton(stored.getPath()), Collections.emptySet());
        assertEquals(Collections.singletonList(stored), result.extract); assertReadingState(stored);
    }
    @Test public void eagerExtractionIsNotRepeatedDuringFinalMerge() {
        FileMeta stored = book("a",100,200);
        LibraryMerge.Result result = LibraryMerge.merge(Collections.singletonList(book("a",101,201)),
                Collections.singletonMap(stored.getPath(), stored), Collections.singleton(stored.getPath()), Collections.singleton(stored.getPath()));
        assertTrue(result.extract.isEmpty()); assertEquals(Integer.valueOf(FileMetaCore.STATE_FULL), stored.getState()); assertReadingState(stored);
    }
    @Test public void unknownProviderRevisionDoesNotEraseKnownPropertiesAndRetriesExtraction() {
        FileMeta stored = book("a",100,200), discovered = book("a",100,200);
        discovered.setSize(null); discovered.setDate(null); LibraryMerge.Result result = merge(stored, discovered);
        assertEquals(Collections.singletonList(stored), result.extract);
        assertEquals(Long.valueOf(100), stored.getSize()); assertEquals(Long.valueOf(200), stored.getDate()); assertReadingState(stored);
    }
    @Test public void unrelatedNonLibraryRowsAreNotMarkedRemoved() {
        FileMeta stored = book("manual",100,200); stored.setIsSearchBook(false);
        assertTrue(merge(stored).removed.isEmpty()); assertReadingState(stored);
    }
}
