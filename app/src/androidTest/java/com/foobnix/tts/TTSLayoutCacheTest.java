package com.foobnix.tts;

import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.sys.TempHolder;
import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercise the actual TTS cache and native EPUB layout after reader settings change. */
public class TTSLayoutCacheTest {
    private TTSService service;
    private File book;
    private String oldPath, oldCss;
    private int oldWidth, oldHeight, oldFontSize;
    private boolean oldCancelled;

    @Before public void setUp() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        CacheZipUtils.init(instrumentation.getTargetContext());
        AppSP settings = AppSP.get();
        oldPath = settings.lastBookPath;
        oldWidth = settings.lastBookWidth;
        oldHeight = settings.lastBookHeight;
        oldFontSize = BookCSS.get().fontSizeSp;
        oldCss = BookCSS.get().customCSS2;
        // Instrumentation starts without a reader activity loading the CSS profile.
        BookCSS.get().customCSS2 = "";
        oldCancelled = TempHolder.get().loadingCancelled.getAndSet(false);
        book = File.createTempFile("tts-layout-", ".epub", instrumentation.getTargetContext().getCacheDir());
        try (InputStream input = instrumentation.getContext().getAssets().open("reader-fixtures/book.epub")) {
            Files.write(book.toPath(), com.BaseExtractor.getEntryAsByte(input));
        }
        settings.lastBookPath = book.getPath();
        settings.lastBookWidth = 1080;
        settings.lastBookHeight = 2073;
        BookCSS.get().fontSizeSp = 20;
        AtomicReference<TTSService> created = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> created.set(new TTSService()));
        service = created.get();
    }

    @After public void tearDown() {
        if (service != null && service.cache != null) service.cache.recycle();
        AppSP.get().lastBookPath = oldPath;
        AppSP.get().lastBookWidth = oldWidth;
        AppSP.get().lastBookHeight = oldHeight;
        BookCSS.get().fontSizeSp = oldFontSize;
        BookCSS.get().customCSS2 = oldCss;
        TempHolder.get().loadingCancelled.set(oldCancelled);
        if (book != null) book.delete();
    }

    @Test public void styleChangeAtTheSameFontSizeReopensTheSpeechDocument() {
        CodecDocument old = service.getDC();
        assertNotNull(old);
        assertSame("Unchanged layout should reuse the document", old, service.getDC());
        BookCSS.get().customCSS2 = "body {font-family: monospace !important; line-height: 2 !important;}";
        CodecDocument refreshed = service.getDC();
        assertNotNull(refreshed);
        assertNotSame("A font/style change must discard the old TTS layout", old, refreshed);
        assertTrue(old.isRecycled());
        assertMatchesReader(refreshed);
    }

    @Test public void fontSizeChangeReopensTheSpeechDocument() {
        CodecDocument old = service.getDC();
        assertNotNull(old);
        BookCSS.get().fontSizeSp = 28;
        CodecDocument refreshed = service.getDC();
        assertNotNull(refreshed);
        assertNotSame(old, refreshed);
        assertTrue(old.isRecycled());
        assertMatchesReader(refreshed);
    }

    @Test public void rotatedDimensionsWithTheSameSumReopenTheSpeechDocument() {
        CodecDocument old = service.getDC();
        assertNotNull(old);
        AppSP.get().lastBookWidth = 2073;
        AppSP.get().lastBookHeight = 1080;
        CodecDocument refreshed = service.getDC();
        assertNotNull(refreshed);
        assertNotSame(old, refreshed);
        assertTrue(old.isRecycled());
        assertMatchesReader(refreshed);
    }

    private void assertMatchesReader(CodecDocument speech) {
        CodecDocument reader = ImageExtractor.singleCodecContext(book.getPath(), "");
        assertNotNull(reader);
        try {
            int count = reader.getPageCount(AppSP.get().lastBookWidth,
                    AppSP.get().lastBookHeight, BookCSS.get().fontSizeSp);
            assertTrue(count > 0);
            assertEquals(count, speech.getPageCount());
            CodecPage readerPage = reader.getPage(0);
            CodecPage speechPage = speech.getPage(0);
            try {
                assertEquals(readerPage.getPageHTML(), speechPage.getPageHTML());
            } finally {
                readerPage.recycle();
                speechPage.recycle();
            }
        } finally { reader.recycle(); }
    }
}
