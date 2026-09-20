package org.ebookdroid.droids;

import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.EpubProcessingSettings;
import com.foobnix.ext.HtmlExtractor;
import com.foobnix.pdf.info.SafCacheFiles;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.File;

public class MhtContext extends PdfContext {
    File cacheDirectory;
    @Override public CodecDocument openDocumentInner(String fileName, String password) {
        File directory = cacheDirectory = new File(CacheZipUtils.CACHE_BOOK_DIR,
                (fileName + sourceRevisionKey(fileName)
                        + EpubProcessingSettings.key()).hashCode() + "-mht-v2");
        try (SafCacheFiles.PublishedFile output = SafCacheFiles.buildDirectory(
                directory, HtmlExtractor.OUT_FB2_XML,
                staging -> HtmlExtractor.extractMht(fileName, staging.getPath()))) {
            MuPdfDocument document = new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF,
                    output.file.getPath(), password);
            document.retainSource(directory);
            return document;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot convert MHT book", failure);
        }
    }
}
