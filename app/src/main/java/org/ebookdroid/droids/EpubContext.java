package org.ebookdroid.droids;

import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.EpubExtractor;
import com.foobnix.ext.EpubProcessingSettings;
import com.foobnix.pdf.info.SafCacheFiles;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.JsonHelper;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.TempHolder;


import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.File;
import java.util.Locale;
import java.util.Map;


public class
EpubContext extends PdfContext {

    private static final String TAG = "EpubContext";
    File cacheFile;

    public static boolean isProcessingEnabled() {
        return AppState.get().isEnableTextReplacement || BookCSS.get().isAutoHypens
                || AppState.get().isReferenceMode || AppState.get().isShowFooterNotesInText
                || BookCSS.get().isEnableBBCode;
    }

    public static void prepareProcessingLanguage(String metadataLanguage) {
        if (AppState.get().isDefaultHyphenLanguage) {
            AppSP.get().hypenLang = canonicalLanguage(AppState.get().defaultHyphenLanguageCode);
        } else {
            AppSP.get().hypenLang = canonicalLanguage(metadataLanguage);
        }
    }

    private static String canonicalLanguage(String language) {
        if (language == null || language.trim().isEmpty()) return null;
        String code = language.trim().toLowerCase(Locale.US);
        int separator = code.indexOf('-');
        if (separator < 0) separator = code.indexOf('_');
        if (separator > 0) code = code.substring(0, separator);
        if (code.length() == 2) return code;
        if (code.length() == 3) {
            for (String iso2 : Locale.getISOLanguages()) {
                try {
                    if (code.equals(Locale.forLanguageTag(iso2).getISO3Language())) return iso2;
                } catch (Exception ignored) {
                }
            }
        }
        return code;
    }

    public static String processingSettingsKey() {
        return EpubProcessingSettings.key();
    }

    private static boolean isSafSource(String name) {
        File file = new File(name);
        return file.getName().startsWith("source-") && file.getParentFile() != null
                && "saf-open".equals(file.getParentFile().getName());
    }

    private static boolean isProcessedSafEpub(String fileName) {
        File file = new File(fileName);
        return file.getName().startsWith("processed-") && file.getParentFile() != null
                && "saf-open".equals(file.getParentFile().getName());
    }

    @Override
    public File getCacheFileName(String fileNameOriginal) {
        LOG.d(TAG, "getCacheFileName", fileNameOriginal, AppSP.get().hypenLang);
        if (isSafSource(fileNameOriginal)) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest((fileNameOriginal + processingSettingsKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder key = new StringBuilder();
                for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 255));
                cacheFile = new File(new File(fileNameOriginal).getParentFile(), "processed-" + key + ".epub");
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        } else {
            cacheFile = new File(CacheZipUtils.CACHE_BOOK_DIR, (fileNameOriginal + processingSettingsKey())
                    .hashCode() + ".epub");
        }
        return cacheFile;
    }

    @Override
    public CodecDocument openDocumentInner(final String fileName, String password) {
        try (EpubProcessingSettings.Scope settings = EpubProcessingSettings.capture();
             AutoCloseable sourceLease = SafCacheFiles.acquire(new File(fileName))) {
            SafCacheFiles.cancelReservation(new File(fileName));
            cacheFile = isProcessedSafEpub(fileName) ? new File(fileName) : getCacheFileName(fileName);
            try (AutoCloseable outputLease = SafCacheFiles.acquire(cacheFile)) {
                return openCapturedDocument(fileName, password);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException("Cannot open EPUB", failure);
        }
    }

    private CodecDocument openCapturedDocument(String fileName, String password) {
        final boolean alreadyProcessedSafEpub = isProcessedSafEpub(fileName);
        final File safProcessingTarget = isSafSource(fileName) ? cacheFile : null;
        Map<String, String> notes = null;
        if (EpubProcessingSettings.isShowFooterNotesInText()) {
            notes = getNotes(fileName);
            LOG.d("footer-notes-extracted");
        }
        if (EpubProcessingSettings.enabled() && !alreadyProcessedSafEpub && !cacheFile.isFile()) {
            if (safProcessingTarget != null) {
                File tempFile = null;
                try {
                    tempFile = SafCacheFiles.temporary(safProcessingTarget.getParentFile(), "epub-process-");
                    EpubExtractor.proccessHypensApache(fileName, tempFile.getPath(), notes);
                    if (TempHolder.get().loadingCancelled.get()) {
                        throw new java.io.IOException("SAF EPUB processing cancelled");
                    }
                    ExtUtils.publishSafProcessed(new File(fileName), tempFile, safProcessingTarget);
                } catch (Exception e) {
                    LOG.e(e);
                    cacheFile = new File(fileName);
                } finally {
                    if (tempFile != null) tempFile.delete();
                }
            } else {
                EpubExtractor.proccessHypens(fileName, cacheFile.getPath(), notes);
            }
        }

        final String bookPath = (EpubProcessingSettings.enabled() || alreadyProcessedSafEpub) ? cacheFile.getPath() : fileName;

        if (AppsConfig.IS_LOG) {//accelerate open books
            File out = new File(cacheFile.getPath() + "-source");
            try {
                if (!out.isDirectory()) {
                    out.mkdirs();
                    new ZipFile(bookPath).extractAll(out.getPath());
                    LOG.d("EpubContext unzip all", out.getPath());

                }
                //bookPath = out.getPath() + "/META-INF/container.xml";
                LOG.d("EpubContext open", bookPath);
            } catch (ZipException e) {
                LOG.e(e);
            }

        }

        final MuPdfDocument muPdfDocument = new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF, bookPath, password);
        muPdfDocument.retainCacheSource(new File(fileName));
        muPdfDocument.cacheFilename = bookPath;

        if (notes != null) {
            muPdfDocument.setFootNotes(notes);
        }

        AutoCloseable metadataLease = SafCacheFiles.acquire(new File(bookPath));
        Thread t = new Thread("@T openDocument") {
            @Override
            public void run() {
                try {

                    if (muPdfDocument.getFootNotes() == null) {
                        muPdfDocument.setFootNotes(getNotes(bookPath));
                    }
                    muPdfDocument.setMediaAttachment(EpubExtractor.getAttachments(bookPath));

                    removeTempFilesIfCancel();
                } catch (Throwable e) {
                    LOG.e(e);
                } finally {
                    try { metadataLease.close(); } catch (Exception e) { LOG.e(e); }
                }
            }

        };
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();

        return muPdfDocument;
    }

    public Map<String, String> getNotes(String fileName) {
        Map<String, String> notes = null;
        final File jsonFile = new File(cacheFile + ".json");
        if (/** !LibreraBuildConfig.DEBUG && **/jsonFile.isFile()) {
            LOG.d("getNotes cache", fileName);
            notes = JsonHelper.fileToMap(jsonFile);
        } else {
            LOG.d("getNotes extract", fileName);
            notes = EpubExtractor.get().getFooterNotes(fileName);
            // a cancelled extraction is empty or partial, it must not stay in the cache
            if (!TempHolder.get().loadingCancelled.get()) {
                JsonHelper.mapToFile(jsonFile, notes);
                LOG.d("save notes to file", jsonFile);
            }
        }
        return notes;
    }

}
