package com.foobnix.work;

import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CalirbeExtractor;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.SafOpfRegistry;
import com.foobnix.ui2.FileMetaCore;

import java.io.File;

/** A prior full extraction is reusable only when all known metadata inputs match. */
final class MetadataRefreshPolicy {
    private MetadataRefreshPolicy() {}

    static String settingsKey() {
        AppState state = AppState.get();
        return "v1|" + state.isShowOnlyOriginalFileNames + '|'
                + state.isUseCalibreOpf + '|'
                + state.isAuthorTitleFromMetaPDF + '|'
                + state.isFirstSurname;
    }

    static String revision(FileMeta found, SafOpfRegistry.Entry sidecar, String settings) {
        return revision(found, sidecar, settings, null);
    }

    static String revision(FileMeta found, SafOpfRegistry.Entry sidecar, String settings,
                           String calibreRevision) {
        Long size;
        Long modified;
        String opf = "none";
        if (ExtUtils.isExteralSD(found.getPath())) {
            size = found.getSize();
            modified = found.getDate();
            if (sidecar != null) {
                if (sidecar.revision.contains("unknown:")) return null;
                opf = sidecar.opfUri + "|" + sidecar.revision;
            }
        } else {
            File file = new File(found.getPath());
            if (!file.isFile()) return null;
            size = file.length();
            modified = file.lastModified();
            File localOpf = CalirbeExtractor.getCalibreOPF(found.getPath());
            if (localOpf != null) {
                if (localOpf.lastModified() <= 0) return null;
                opf = localOpf.length() + ":" + localOpf.lastModified();
            }
        }
        if (size == null || size <= 0 || modified == null || modified <= 0) return null;
        String revision = settings + "|" + size + ":" + modified + "|" + opf;
        return calibreRevision == null ? revision : revision + "|calibre:" + calibreRevision;
    }

    static boolean needsExtraction(FileMeta before, String previousRevision, String currentRevision) {
        return before == null || before.getState() == null
                || before.getState() != FileMetaCore.STATE_FULL || currentRevision == null
                || !currentRevision.equals(previousRevision);
    }
}
