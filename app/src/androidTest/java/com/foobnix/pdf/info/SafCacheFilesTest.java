package com.foobnix.pdf.info;

import com.foobnix.ext.CacheZipUtils;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test public void cacheCleanupRetainsAnActiveFileAndItsSiblingResources() throws Exception {
        File root = directory(), book = new File(root, "book"), page = new File(book, "page.html"),
                image = new File(book, "image.png");
        assertTrue(book.mkdir());
        Files.write(page.toPath(), new byte[]{1});
        Files.write(image.toPath(), new byte[]{2});
        try (AutoCloseable lease = SafCacheFiles.acquire(page)) {
            CacheZipUtils.deleteDir(book);
            assertTrue(page.exists());
            assertTrue(image.exists());
            CacheZipUtils.removeFiles(new File[]{page});
            assertTrue(page.exists());
        }
        CacheZipUtils.deleteDir(book);
        assertFalse(book.exists());
        assertTrue(root.delete());
    }

    @Test public void reservationProtectsParentDirectoryUntilHandoffEnds() throws Exception {
        File root = directory(), book = new File(root, "book"), source = new File(book, "source.epub");
        assertTrue(book.mkdir());
        Files.write(source.toPath(), new byte[]{1});
        SafCacheFiles.reserve(source);
        try {
            CacheZipUtils.deleteDir(book);
            assertTrue(source.exists());
        } finally {
            SafCacheFiles.cancelReservation(source);
        }
        CacheZipUtils.deleteDir(book);
        assertFalse(book.exists());
        assertTrue(root.delete());
    }

    @Test public void delegatedOpenConsumesReservationOnlyAtTheOuterBoundary() throws Exception {
        File dir = directory(), source = new File(dir, "source.epub");
        Files.write(source.toPath(), new byte[]{1});
        SafCacheFiles.reserve(source);
        SafCacheFiles.beginManagedOpen();
        try {
            SafCacheFiles.readerOpened(source);
            SafCacheFiles.readerClosed(source);
            assertFalse("Nested reader consumed the outer handoff", SafCacheFiles.evict(source));
        } finally {
            SafCacheFiles.endManagedOpen();
            SafCacheFiles.cancelReservation(source);
        }
        assertTrue(SafCacheFiles.evict(source));
        assertTrue(dir.delete());
    }

    @Test public void asynchronousMetadataKeepsItsPathUntilWorkFinishes() throws Exception {
        File dir = directory(), source = new File(dir, "converted.epub");
        Files.write(source.toPath(), new byte[]{1});
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread worker = SafCacheFiles.startLeasedThread("metadata-test", Thread.NORM_PRIORITY, () -> {
            started.countDown();
            try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, source);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(SafCacheFiles.evict(source));
        } finally {
            finish.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertTrue(SafCacheFiles.evict(source));
        assertTrue(dir.delete());
    }
}
