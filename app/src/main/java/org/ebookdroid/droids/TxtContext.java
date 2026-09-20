package org.ebookdroid.droids;

import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.TxtExtract;
import com.foobnix.ext.EpubProcessingSettings;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.SafCacheFiles;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.IOException;
import java.io.File;

public class TxtContext extends PdfContext {
    File cacheDirectory;

    @Override
    public CodecDocument openDocumentInner(String fileName, String password) {

        boolean preformatted = EpubProcessingSettings.isPreText();
        String mainName = preformatted ? "truetxt.html" : fileName.hashCode() + "_.fb2";
        File directory = cacheDirectory = new File(CacheZipUtils.CACHE_BOOK_DIR,
                (fileName + sourceRevisionKey(fileName)
                        + EpubProcessingSettings.key()).hashCode() + "-txt-v2");
        try (SafCacheFiles.PublishedFile converted = SafCacheFiles.buildDirectory(
                directory, mainName, staging -> {
                    if (preformatted) TxtExtract.extract(fileName, staging.getPath());
                    else TxtExtract.extract1(fileName, staging.getPath());
                })) {
            MuPdfDocument document;
            if (preformatted) {
                document = new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF,
                        converted.file.getPath(), password);
            } else {
                document = (MuPdfDocument) new Fb2Context().openDocumentInner(
                        converted.file.getPath(), "");
            }
            document.retainSource(directory);
            return document;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot convert TXT book", failure);
        }

    }
}
