package com.foobnix.pdf.info;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SafCacheFilesTest {
    @Test public void startupRemovesAbandonedPartsButConcurrentRequestsKeepActiveParts() throws Exception {
        File dir = directory(), abandoned = new File(dir, "old.part");
        Files.write(abandoned.toPath(), new byte[]{1});
        File first = SafCacheFiles.temporary(dir, "first-");
        File second = SafCacheFiles.temporary(dir, "second-");
        try {
            assertFalse(abandoned.exists());
            assertNotEquals(first, second);
            assertTrue(first.exists()); assertTrue(second.exists());
        } finally { first.delete(); second.delete(); abandoned.delete(); dir.delete(); }
    }
    @Test public void epubContextHandoffCannotConsumeAnotherReadersReservation() throws Exception {
        File dir = directory(), file = new File(dir, "source.epub");
        Files.write(file.toPath(), new byte[]{1});
        try {
            SafCacheFiles.reserve(file); SafCacheFiles.reserve(file);
            try (AutoCloseable context = SafCacheFiles.acquire(file)) {
                SafCacheFiles.cancelReservation(file);
                SafCacheFiles.readerOpened(file, false);
            }
            SafCacheFiles.readerClosed(file);
            assertFalse("Another pending open was consumed", SafCacheFiles.evict(file));
            SafCacheFiles.cancelReservation(file);
            assertTrue(SafCacheFiles.evict(file));
        } finally { file.delete(); dir.delete(); }
    }
    private File directory() throws Exception {
        return Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "saf-cache-leases-").toFile();
    }
    @Test public void readersAndPendingOpensPreventEvictionUntilLastReaderCloses() throws Exception {
        File dir = directory(), file = new File(dir, "source.epub");
        Files.write(file.toPath(), new byte[]{1});
        try {
            SafCacheFiles.reserve(file);
            SafCacheFiles.reserve(file);
            assertFalse(SafCacheFiles.evict(file));
            SafCacheFiles.readerOpened(file);
            SafCacheFiles.readerClosed(file);
            assertFalse("Second pending reader lost its file", SafCacheFiles.evict(file));
            SafCacheFiles.readerOpened(file);
            try (AutoCloseable metadata = SafCacheFiles.acquire(file)) {
                SafCacheFiles.readerClosed(file);
                assertFalse("Metadata extraction lost its source", SafCacheFiles.evict(file));
            }
            assertTrue(SafCacheFiles.evict(file));
        } finally {
            file.delete(); dir.delete();
        }
    }
    @Test public void failedAtomicPublicationKeepsPreviousGoodOutput() throws Exception {
        File dir = directory(), output = new File(dir, "processed.epub"), absent = new File(dir, "missing.part");
        Files.write(output.toPath(), new byte[]{2,3});
        try {
            assertThrows(java.io.IOException.class, () -> SafCacheFiles.publish(absent, output));
            assertArrayEquals(new byte[]{2,3}, Files.readAllBytes(output.toPath()));
        } finally { output.delete(); dir.delete(); }
    }
    @Test public void replacementIsCompleteAndDoesNotEvictAnActiveOutput() throws Exception {
        File dir = directory(), output = new File(dir, "processed.epub"), partial = new File(dir, "output.part");
        Files.write(output.toPath(), new byte[]{1}); Files.write(partial.toPath(), new byte[]{2,3});
        try (AutoCloseable lease = SafCacheFiles.acquire(output)) {
            SafCacheFiles.publish(partial, output);
            assertArrayEquals(new byte[]{2,3}, Files.readAllBytes(output.toPath()));
            assertFalse(SafCacheFiles.evict(output));
        } finally { output.delete(); partial.delete(); dir.delete(); }
    }
}
