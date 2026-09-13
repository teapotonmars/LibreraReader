package com.foobnix.work;

import com.foobnix.dao2.FileMeta;
import com.foobnix.ui2.FileMetaCore;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchAllBooksWorkerTest {
    private FileMeta book(Long size, Long date) {
        FileMeta meta = new FileMeta("content://provider/tree/library/document/book.epub");
        meta.setSize(size);
        meta.setDate(date);
        meta.setState(FileMetaCore.STATE_FULL);
        return meta;
    }

    @Test public void unchangedSafBookSkipsExtraction() {
        assertFalse(BookRevision.needsFullUpdate(book(100L, 200L), book(100L, 200L)));
    }

    @Test public void changedSafRevisionNeedsExtraction() {
        FileMeta stored = book(100L, 200L);
        assertTrue(BookRevision.needsFullUpdate(book(101L, 200L), stored));
        assertTrue(BookRevision.needsFullUpdate(book(100L, 201L), stored));
        assertEquals(Long.valueOf(100L), stored.getSize());
        assertEquals(Long.valueOf(200L), stored.getDate());
    }

    @Test public void unknownRevisionAndFailedExtractionAreRetried() {
        assertTrue(BookRevision.needsFullUpdate(book(100L, null), book(100L, 200L)));
        assertTrue(BookRevision.needsFullUpdate(book(100L, 0L), book(100L, 0L)));
        FileMeta failed = book(100L, 200L);
        failed.setState(FileMetaCore.STATE_BASIC);
        assertTrue(BookRevision.needsFullUpdate(book(100L, 200L), failed));
        assertTrue(BookRevision.needsFullUpdate(book(100L, 200L), null));
    }
    @Test public void temporaryFilenameMetadataIsRepairedOnRescan() {
        FileMeta damaged = book(100L, 200L);
        damaged.setAuthor("Saf_cover_1_Fluent Python");
        assertTrue(BookRevision.needsFullUpdate(book(100L, 200L), damaged));
    }

    @Test public void missingStoredRevisionAndStateNeverSkipExtraction() {
        FileMeta discovered = book(100L, 200L);
        assertTrue(BookRevision.needsFullUpdate(discovered, book(null, 200L)));
        assertTrue(BookRevision.needsFullUpdate(discovered, book(100L, null)));
        FileMeta stored = book(100L, 200L); stored.setState(null);
        assertTrue(BookRevision.needsFullUpdate(discovered, stored));
    }

    @Test public void missingProviderSizeAndNegativeTimestampAreConservative() {
        assertTrue(BookRevision.needsFullUpdate(book(null, 200L), book(100L, 200L)));
        assertTrue(BookRevision.needsFullUpdate(book(100L, -1L), book(100L, 200L)));
    }

    @Test public void genuineTitlesAndZeroByteRevisionAreNotMistakenForLegacyMetadata() {
        FileMeta stored = book(0L, 200L); stored.setTitle("Fluent Python"); stored.setAuthor("Luciano Ramalho");
        assertFalse(BookRevision.needsFullUpdate(book(0L, 200L), stored));
        stored.setTitle("SAF_META_12_wrong.epub");
        assertTrue(BookRevision.needsFullUpdate(book(0L, 200L), stored));
    }

    @Test public void localRevisionUsesTheActualFileWithoutMutatingStoredMetadata() throws Exception {
        java.io.File file = java.io.File.createTempFile("revision-test-", ".epub");
        try {
            java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3});
            FileMeta stored = new FileMeta(file.getPath());
            stored.setSize(file.length()); stored.setDate(file.lastModified()); stored.setState(FileMetaCore.STATE_FULL);
            FileMeta discovered = new FileMeta(file.getPath());
            assertFalse(BookRevision.needsFullUpdate(discovered, stored));
            java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
            assertTrue(BookRevision.needsFullUpdate(discovered, stored));
            assertEquals(Long.valueOf(3), stored.getSize());
        } finally { file.delete(); }
    }
}
