package com.foobnix.work;

import com.foobnix.dao2.FileMeta;
import com.foobnix.ui2.FileMetaCore;
import java.io.File;

/** Compares a discovery snapshot without mutating the stored metadata. */
final class BookRevision {
    private BookRevision() {}

    static boolean needsFullUpdate(FileMeta row, FileMeta existing) {
        if (existing == null) return true;
        // Earlier SAF cover extraction persisted temporary filenames and inferred series numbers.
        if (temporaryName(existing.getTitle()) || temporaryName(existing.getAuthor())) return true;
        Integer state = existing.getState();
        if (state == null || state != FileMetaCore.STATE_FULL) return true;
        Long storedSize = existing.getSize();
        Long storedDate = existing.getDate();
        if (storedSize == null || storedDate == null) return true;
        if (row.getPath() != null && row.getPath().startsWith("content:/")) {
            Long discSize = row.getSize();
            Long discDate = row.getDate();
            if (discSize == null || discDate == null || discDate <= 0) return true;
            if (!discSize.equals(storedSize)) return true;
            if (!discDate.equals(storedDate)) return true;
            return false;
        }
        File f = new File(row.getPath());
        return f.lastModified() != storedDate || f.length() != storedSize;
    }

    private static boolean temporaryName(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
                .matches("saf_(?:meta|cover)_[0-9]+_.*");
    }
}
