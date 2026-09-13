package com.foobnix.work;

import com.foobnix.dao2.FileMeta;
import com.foobnix.ui2.FileMetaCore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies a completed discovery without replacing the entities holding user reading state. */
final class LibraryMerge {
    static final class Result {
        final List<FileMeta> books = new ArrayList<>();
        final List<FileMeta> extract = new ArrayList<>();
        final List<FileMeta> removed = new ArrayList<>();
    }

    static Result merge(List<FileMeta> discoveries, Map<String, FileMeta> existingByPath,
                        Set<String> forced, Set<String> completed) {
        Result result = new Result();
        Set<String> paths = new HashSet<>();
        for (FileMeta discovered : discoveries) {
            if (!paths.add(discovered.getPath())) continue;
            FileMeta existing = existingByPath.get(discovered.getPath());
            // Revision comparison must precede refreshing the stored provider properties.
            boolean extract = !completed.contains(discovered.getPath())
                    && (forced.contains(discovered.getPath()) || BookRevision.needsFullUpdate(discovered, existing));
            FileMeta row = existing == null ? discovered : existing;
            if (existing != null) {
                row.setIsSearchBook(discovered.getIsSearchBook());
                if (discovered.getPath() != null && discovered.getPath().startsWith("content:/")) {
                    if (discovered.getSize() != null) row.setSize(discovered.getSize());
                    if (discovered.getDate() != null) row.setDate(discovered.getDate());
                    String name = discovered.getPathTxt();
                    if (name != null && !name.trim().isEmpty()) row.setPathTxt(name);
                }
            }
            result.books.add(row);
            if (extract) {
                row.setState(FileMetaCore.STATE_BASIC);
                result.extract.add(row);
            }
        }
        for (Map.Entry<String, FileMeta> entry : existingByPath.entrySet()) {
            if (!paths.contains(entry.getKey()) && Boolean.TRUE.equals(entry.getValue().getIsSearchBook())) {
                entry.getValue().setIsSearchBook(false);
                result.removed.add(entry.getValue());
            }
        }
        return result;
    }
}
