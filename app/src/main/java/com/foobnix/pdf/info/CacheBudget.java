package com.foobnix.pdf.info;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Soft LRU budget shared by conversion units and staged provider copies. */
public final class CacheBudget {
    private CacheBudget() { }

    public static void prune(File directory, long softLimitBytes, Function<File, String> unitKey) {
        if (directory == null) return;
        File[] entries = directory.listFiles();
        if (entries == null) return;
        Map<String, List<File>> groups = new HashMap<>();
        for (File entry : entries)
            groups.computeIfAbsent(unitKey.apply(entry), ignored -> new ArrayList<>()).add(entry);
        List<List<File>> units = new ArrayList<>(groups.values());
        Map<File, Long> sizes = new HashMap<>();
        long total = 0;
        for (List<File> unit : units) {
            BookCacheLeases.refreshIfProtected(unit);
            for (File member : unit) {
                long bytes = bytes(member);
                sizes.put(member, bytes);
                total += bytes;
            }
        }
        units.sort((left, right) -> Long.compare(modified(left), modified(right)));
        for (List<File> unit : units) {
            boolean incomplete = unit.stream().anyMatch(file -> file.getName().endsWith(".part"));
            if (total <= softLimitBytes && !incomplete) continue;
            List<File> hidden = BookCacheLeases.detachAllOrNone(unit);
            if (hidden == null) {
                BookCacheLeases.refreshIfProtected(unit);
                continue;
            }
            for (File member : unit) total -= sizes.getOrDefault(member, 0L);
            BookCacheLeases.deleteDetachedTrees(hidden);
            // Failed cleanup still counts against the budget until a later pass.
            for (File tombstone : hidden) total += bytes(tombstone);
        }
    }

    private static long modified(List<File> unit) {
        long modified = 0;
        for (File member : unit) modified = Math.max(modified, member.lastModified());
        return modified;
    }

    private static long bytes(File entry) {
        try {
            android.system.StructStat stat = Os.lstat(entry.getAbsolutePath());
            if (!OsConstants.S_ISDIR(stat.st_mode)) return stat.st_size;
            File[] children = entry.listFiles();
            if (children == null) return 0;
            long total = 0;
            for (File child : children) total += bytes(child);
            return total;
        } catch (ErrnoException missing) { return 0; }
    }
}
