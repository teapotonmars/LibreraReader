package com.foobnix.tts;

import com.foobnix.model.AppSP;
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
}
