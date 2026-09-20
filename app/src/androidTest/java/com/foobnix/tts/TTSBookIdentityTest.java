package com.foobnix.tts;

import com.foobnix.model.AppSP;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TTSBookIdentityTest {
    @Test public void progressUsesOriginalSafUriAcrossStagedFiles() {
        AppSP settings = AppSP.get();
        String oldPath = settings.lastBookPath;
        String oldOriginal = settings.lastBookOriginalUri;
        try {
            settings.lastBookPath = "/cache/staged-version-1.epub";
            settings.lastBookOriginalUri = "content://books/wandering-inn-9";
            assertEquals(settings.lastBookOriginalUri, TTSService.bookIdentity());
            settings.lastBookPath = "/cache/staged-version-2.epub";
            assertEquals(settings.lastBookOriginalUri, TTSService.bookIdentity());

            settings.lastBookOriginalUri = null;
            assertEquals(settings.lastBookPath, TTSService.bookIdentity());
        } finally {
            settings.lastBookPath = oldPath;
            settings.lastBookOriginalUri = oldOriginal;
        }
    }

    @Test public void notificationReopensPhysicalFileWithOriginalIdentity() {
        AppSP settings = AppSP.get();
        String oldPath = settings.lastBookPath;
        String oldOriginal = settings.lastBookOriginalUri;
        try {
            settings.lastBookPath = "/cache/staged.epub";
            settings.lastBookOriginalUri = "content://books/wandering-inn-9";
            assertEquals(settings.lastBookOriginalUri,
                    TTSNotification.originalUriForPath(settings.lastBookPath));
            var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            var intent = TTSNotification.readerIntent(context, settings.lastBookPath, 42);
            assertEquals("file:///cache/staged.epub", intent.getDataString());
            assertEquals(settings.lastBookOriginalUri, intent.getStringExtra("SAF_ORIGINAL_URI"));
            assertEquals(41, intent.getIntExtra("page", -1));

            var other = TTSNotification.readerIntent(context, "/books/local.epub", 1);
            org.junit.Assert.assertFalse(other.hasExtra("SAF_ORIGINAL_URI"));
            org.junit.Assert.assertNull(TTSNotification.originalUriForPath("/books/local.epub"));
        } finally {
            settings.lastBookPath = oldPath;
            settings.lastBookOriginalUri = oldOriginal;
        }
    }
}
