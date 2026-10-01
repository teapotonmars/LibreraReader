package com.foobnix.work;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.SafOpfRegistry;
import com.foobnix.ui2.AppDB;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppProfile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import org.junit.Test;

public class CoverWarmupIntegrationTest {
    @Test public void warmsARealDisposableEpubThroughNormalGlideRequest() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppProfile.init(context);
        CacheZipUtils.init(context);
        File source = new File(context.getCacheDir(), "warmup-" + UUID.randomUUID() + ".epub");
        try {
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("reader-fixtures/book.epub");
                 FileOutputStream output = new FileOutputStream(source)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            FileMeta book = new FileMeta(source.getPath());
            book.setSize(source.length());
            book.setDate(source.lastModified());
            assertTrue(CoverWarmupWorker.warmBook(context, book, () -> false));
        } finally {
            source.delete();
        }
    }
    @Test public void warmedSafSidecarUsesTheVisibleRowsDurableCacheKey() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppProfile.init(context);
        CacheZipUtils.init(context);
        File cover = File.createTempFile("warmup-sidecar-", ".png", context.getCacheDir());
        FileMeta book = new FileMeta("content://warmup-fixture/document/" + UUID.randomUUID());
        book.setIsSearchBook(true); book.setSize(100L); book.setDate(200L);
        Bitmap image = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.RED);
        try (FileOutputStream output = new FileOutputStream(cover)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
        AppDB.get().saveAll(Collections.singletonList(book));
        AppDB.get().updateSidecarRevision(book, "cover-one");
        SafOpfRegistry.restore(context);
        SafOpfRegistry.register(book.getPath(), new SafOpfRegistry.Entry(
                Uri.fromFile(new File(cover.getParentFile(), "missing-" + UUID.randomUUID() + ".opf")),
                Collections.singletonMap("cover.jpg", Uri.fromFile(cover)), "cover-one"));
        try {
            FileMeta queued = CoverWarmupWorker.booksToWarm(Collections.singletonList(book), true, path -> true).get(0);
            assertTrue(CoverWarmupWorker.warmBook(context, queued, () -> false));
            SafOpfRegistry.clear();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> Glide.get(context).clearMemory());
            assertTrue(cover.delete());
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (true) {
                FutureTarget<Bitmap> request = IMG
                        .getCoverPageWithEffect(context, AppDB.get().load(book.getPath()), null)
                        .skipMemoryCache(true).onlyRetrieveFromCache(true).submit();
                try {
                    Bitmap result = request.get(10, TimeUnit.SECONDS);
                    assertEquals(Color.RED,
                            result.getPixel(result.getWidth() / 2, result.getHeight() / 2));
                    break;
                } catch (ExecutionException encoding) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw encoding;
                    Thread.sleep(20);
                } finally { Glide.with(context).clear(request); }
            }
        } finally {
            SafOpfRegistry.restore(context);
            SafOpfRegistry.unregister(book.getPath());
            AppDB.get().deleteBy(book.getPath()); cover.delete();
        }
    }

}
