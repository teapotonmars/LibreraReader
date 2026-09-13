package org.ebookdroid.droids;

import com.foobnix.model.AppSP;
import com.foobnix.ext.EpubProcessingSettings;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.model.BookCSS;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class EpubProcessingCacheTest {
    @Test public void transformationUsesCapturedOptionsAfterLiveSettingsChange() throws Exception {
        boolean canceled = com.foobnix.sys.TempHolder.get().loadingCancelled.getAndSet(false);
        boolean replacement = AppState.get().isEnableTextReplacement;
        boolean bionic = AppState.get().isBionicMode, hyphens = BookCSS.get().isAutoHypens;
        boolean reference = AppState.get().isReferenceMode, footer = AppState.get().isShowFooterNotesInText;
        boolean experimental = AppState.get().isExperimental;
        try {
            AppState.get().isEnableTextReplacement = true; AppState.get().isBionicMode = true;
            AppState.get().isReferenceMode = false; AppState.get().isShowFooterNotesInText = false;
            AppState.get().isExperimental = false; BookCSS.get().isAutoHypens = false;
            try (EpubProcessingSettings.Scope scope = EpubProcessingSettings.capture()) {
                AppState.get().isEnableTextReplacement = false; AppState.get().isBionicMode = false;
                File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getCacheDir().toPath(), "epub-transform-").toFile();
                File source = new File(directory, "source.epub"), output = new File(directory, "output.part");
                try {
                    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(source))) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("chapter.xhtml"));
                        zip.write("<html><body><p>testing</p></body></html>".getBytes("UTF-8"));
                        zip.closeEntry();
                    }
                    com.foobnix.ext.EpubExtractor.proccessHypensApache(source.getPath(), output.getPath(), null);
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(output);
                         java.io.InputStream chapter = zip.getInputStream(zip.getEntry("chapter.xhtml"))) {
                        String transformed = new String(com.BaseExtractor.getEntryAsByte(chapter), "UTF-8");
                        assertTrue(transformed, transformed.contains("<b>test</b>ing"));
                    }
                } finally { source.delete(); output.delete(); directory.delete(); }
            }
        } finally {
            AppState.get().isEnableTextReplacement = replacement; AppState.get().isBionicMode = bionic;
            BookCSS.get().isAutoHypens = hyphens; AppState.get().isReferenceMode = reference;
            AppState.get().isShowFooterNotesInText = footer; AppState.get().isExperimental = experimental;
            com.foobnix.sys.TempHolder.get().loadingCancelled.set(canceled);
        }
    }
    @Test public void processingSettingsRemainStableWhileUiSettingsChange() throws Exception {
        boolean footer = AppState.get().isShowFooterNotesInText;
        String language = AppSP.get().hypenLang;
        try {
            String original;
            try (EpubProcessingSettings.Scope scope = EpubProcessingSettings.capture()) {
                original = EpubContext.processingSettingsKey();
                Thread ui = new Thread(() -> {
                    AppState.get().isShowFooterNotesInText = !footer;
                    AppSP.get().hypenLang = "de";
                });
                ui.start(); ui.join(3000); assertFalse(ui.isAlive());
                assertEquals(original, EpubContext.processingSettingsKey());
                assertEquals(footer, EpubProcessingSettings.isShowFooterNotesInText());
                assertEquals(language, EpubProcessingSettings.language());
            }
            assertNotEquals(original, EpubContext.processingSettingsKey());
        } finally { AppState.get().isShowFooterNotesInText = footer; AppSP.get().hypenLang = language; }
    }
    @Test public void simultaneousSourcesDoNotShareAProcessingDestinationRegistration() throws Exception {
        File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "epub-targets-").toFile();
        File saf = new File(directory, "saf-open"); saf.mkdir();
        File source = new File(saf, "source-book.epub");
        boolean footer = AppState.get().isShowFooterNotesInText;
        try {
            File first;
            try (EpubProcessingSettings.Scope scope = EpubProcessingSettings.capture()) {
                first = new EpubContext().getCacheFileName(source.getAbsolutePath());
                AppState.get().isShowFooterNotesInText = !footer;
                assertEquals(first, new EpubContext().getCacheFileName(source.getAbsolutePath()));
            }
            assertNotEquals(first, new EpubContext().getCacheFileName(source.getAbsolutePath()));
        } finally { AppState.get().isShowFooterNotesInText = footer; saf.delete(); directory.delete(); }
    }
    @Test public void changingReplacementSettingsInvalidatesProcessedCacheKey() {
        boolean enabled = AppState.get().isEnableTextReplacement;
        long hash = AppState.get().textReplacementHash;
        try {
            String original = EpubContext.processingSettingsKey();
            assertEquals(original, EpubContext.processingSettingsKey());
            AppState.get().isEnableTextReplacement = !enabled;
            assertNotEquals(original, EpubContext.processingSettingsKey());
            AppState.get().isEnableTextReplacement = enabled;
            AppState.get().textReplacementHash = hash + 1;
            assertNotEquals(original, EpubContext.processingSettingsKey());
        } finally { AppState.get().isEnableTextReplacement = enabled; AppState.get().textReplacementHash = hash; }
    }
    @Test public void hyphenationLanguageAndFooterSettingsInvalidateProcessedCache() {
        String lang = AppSP.get().hypenLang;
        boolean footer = AppState.get().isShowFooterNotesInText;
        try {
            String original = EpubContext.processingSettingsKey(); AppSP.get().hypenLang = "fixture-language";
            assertNotEquals(original, EpubContext.processingSettingsKey()); AppSP.get().hypenLang = lang;
            AppState.get().isShowFooterNotesInText = !footer;
            assertNotEquals(original, EpubContext.processingSettingsKey());
        } finally { AppSP.get().hypenLang = lang; AppState.get().isShowFooterNotesInText = footer; }
    }
    @Test public void processingLanguageNormalizesIsoCodesAndHonorsExplicitDefault() {
        String lang = AppSP.get().hypenLang, defaultLang = AppState.get().defaultHyphenLanguageCode;
        boolean useDefault = AppState.get().isDefaultHyphenLanguage;
        try {
            AppState.get().isDefaultHyphenLanguage = false;
            EpubContext.prepareProcessingLanguage("eng"); assertEquals("en", AppSP.get().hypenLang);
            EpubContext.prepareProcessingLanguage("pt_BR"); assertEquals("pt", AppSP.get().hypenLang);
            AppState.get().isDefaultHyphenLanguage = true; AppState.get().defaultHyphenLanguageCode = "de-DE";
            EpubContext.prepareProcessingLanguage("en"); assertEquals("de", AppSP.get().hypenLang);
        } finally { AppSP.get().hypenLang = lang; AppState.get().defaultHyphenLanguageCode = defaultLang; AppState.get().isDefaultHyphenLanguage = useDefault; }
    }
    @Test public void incompleteProcessingDoesNotReplaceExistingOutputOrDeleteSource() throws Exception {
        File dir = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(), "epub-cache-test-").toFile();
        File source = new File(dir,"source-book.epub"), output = new File(dir,"processed-book.epub"), partial = new File(dir,"failed.part");
        try {
            Files.write(source.toPath(), new byte[]{1}); Files.write(output.toPath(), new byte[]{2}); Files.write(partial.toPath(), new byte[0]);
            assertThrows(java.io.IOException.class, () -> ExtUtils.publishSafProcessed(source, partial, output));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(source.toPath()));
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(output.toPath())); assertFalse(partial.exists());
        } finally { source.delete(); output.delete(); partial.delete(); dir.delete(); }
    }
    @Test public void completedProcessingPublishesOutputAndKeepsSourceForDifferentSettings() throws Exception {
        File dir = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(), "epub-cache-test-").toFile();
        File source = new File(dir,"source-book.epub"), output = new File(dir,"processed-book.epub"), partial = new File(dir,"complete.part");
        try {
            Files.write(source.toPath(), new byte[]{1}); Files.write(partial.toPath(), new byte[]{2,3});
            ExtUtils.publishSafProcessed(source, partial, output);
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(source.toPath()));
            assertArrayEquals(new byte[]{2,3}, Files.readAllBytes(output.toPath())); assertFalse(partial.exists());
        } finally { source.delete(); output.delete(); partial.delete(); dir.delete(); }
    }
}
