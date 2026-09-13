package com.foobnix.pdf.info;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@SdkSuppress(minSdkVersion = 29)
public class SafStagingCacheTest {
    private Context context;
    private File directory;
    private Uri uri;
    private Provider provider;
    private static class Provider extends ContentProvider {
        File remote;
        Long modified = 1000L;
        long size = 3;
        final AtomicInteger opened = new AtomicInteger();
        volatile java.util.concurrent.CountDownLatch openBarrier;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
            MatrixCursor result = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i=0; i<columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "Book.pdf";
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = size;
                if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(columns[i])) row[i] = modified;
            }
            result.addRow(row); return result;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            opened.incrementAndGet();
            java.util.concurrent.CountDownLatch barrier = openBarrier;
            if (barrier != null) {
                barrier.countDown();
                try {
                    if (!barrier.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new FileNotFoundException("Concurrent download did not reach provider");
                } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Interrupted fixture"); }
            }
            return ParcelFileDescriptor.open(remote, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "application/pdf"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
    @Before public void setUp() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(target.getCacheDir().toPath(), "staging-fixture-").toFile();
        provider = new Provider(); provider.remote = new File(directory,"remote.pdf");
        Files.write(provider.remote.toPath(), new byte[]{1,2,3});
        ProviderInfo info = new ProviderInfo(); info.authority = "staging.fixture";
        provider.attachInfo(target, info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        context = new ContextWrapper(target) {
            @Override public File getCacheDir() { return directory; }
            @Override public ContentResolver getContentResolver() { return resolver; }
        };
        uri = Uri.parse("content://staging.fixture/document/" + UUID.randomUUID());
    }
    @After public void tearDown() { delete(directory); }
    private void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    @Test public void unchangedRevisionReusesCompleteDownloadedBook() throws Exception {
        File first = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        File second = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        assertEquals(first, second); assertEquals(1, provider.opened.get());
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(second.toPath()));
    }
    @Test public void updatedProviderRevisionDownloadsWithoutDeletingAPendingReadersVersion() throws Exception {
        File first = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        provider.modified = 2000L; provider.size = 4; Files.write(provider.remote.toPath(), new byte[]{4,5,6,7});
        File second = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        assertNotEquals(first, second); assertTrue(first.exists()); assertEquals(2, provider.opened.get());
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(first.toPath()));
        assertArrayEquals(new byte[]{4,5,6,7}, Files.readAllBytes(second.toPath()));
    }
    @Test public void missingModificationDateCannotReusePotentiallyStaleDownload() throws Exception {
        provider.modified = null;
        File first = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        Files.write(provider.remote.toPath(), new byte[]{4,5,6});
        File second = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        assertNotEquals(first, second); assertEquals(2, provider.opened.get());
        assertArrayEquals(new byte[]{4,5,6}, Files.readAllBytes(second.toPath()));
    }
    @Test public void incompleteDownloadDoesNotPublishPartialFileOrDeleteLastGoodVersion() throws Exception {
        File first = ExtUtils.stageSafFile(context, uri, "fallback.pdf");
        provider.modified = 2000L; provider.size = 100;
        assertThrows(java.io.IOException.class, () -> ExtUtils.stageSafFile(context, uri, "fallback.pdf"));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(first.toPath()));
        assertEquals(0, first.getParentFile().listFiles((dir,name) -> name.endsWith(".part")).length);
        assertEquals(1, first.getParentFile().listFiles().length);
    }
    @Test public void concurrentOpensUseIndependentTemporaryFiles() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int iteration=0; iteration<10; iteration++) {
                provider.modified = 2000L + iteration;
                provider.openBarrier = new java.util.concurrent.CountDownLatch(2);
                var first = executor.submit(() -> ExtUtils.stageSafFile(context, uri, "Book.pdf"));
                var second = executor.submit(() -> ExtUtils.stageSafFile(context, uri, "Book.pdf"));
                assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(first.get(5,java.util.concurrent.TimeUnit.SECONDS).toPath()));
                assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(second.get(5,java.util.concurrent.TimeUnit.SECONDS).toPath()));
            }
        } finally { executor.shutdownNow(); }
    }
}
