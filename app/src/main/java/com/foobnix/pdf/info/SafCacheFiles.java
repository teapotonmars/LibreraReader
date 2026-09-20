package com.foobnix.pdf.info;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import android.system.ErrnoException;
import android.system.Os;

/** Coordinates publication, reader handoff and eviction of private SAF cache files. */
public final class SafCacheFiles {
    private static final Map<String, Integer> leases = new HashMap<>();
    private static final Map<String, Integer> reservations = new HashMap<>();
    private static final Set<String> initializedFolders = new HashSet<>();

    public static synchronized File temporary(File directory, String prefix) throws IOException {
        if (initializedFolders.add(key(directory))) {
            // This runs before any operation in this process creates a temporary file here.
            File[] abandoned = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".part"));
            if (abandoned != null) for (File file : abandoned) evict(file);
        }
        return File.createTempFile(prefix, ".part", directory);
    }

    private static String key(File file) { return file.getAbsolutePath(); }
    private static void add(Map<String, Integer> counts, File file) {
        counts.merge(key(file), 1, Integer::sum);
    }
    private static void remove(Map<String, Integer> counts, File file) {
        counts.computeIfPresent(key(file), (path, count) -> count == 1 ? null : count - 1);
    }

    /** Keeps a returned staging file alive until its reader opens it. */
    public static synchronized void reserve(File file) { add(reservations, file); }
    public static synchronized void cancelReservation(File file) { remove(reservations, file); }

    public static synchronized AutoCloseable acquire(File file) {
        add(leases, file);
        return new AutoCloseable() {
            private boolean closed;
            @Override public void close() {
                synchronized (SafCacheFiles.class) {
                    if (!closed) { closed = true; remove(leases, file); }
                }
            }
        };
    }

    public static synchronized void readerOpened(File file) {
        readerOpened(file, true);
    }
    public static synchronized void readerOpened(File file, boolean consumeReservation) {
        add(leases, file);
        if (consumeReservation) remove(reservations, file);
    }
    public static synchronized void readerClosed(File file) { remove(leases, file); }

    private static boolean overlaps(String path, String protectedPath) {
        return path.equals(protectedPath) || path.startsWith(protectedPath + File.separator)
                || protectedPath.startsWith(path + File.separator);
    }

    private static boolean protectedBy(Map<String, Integer> counts, File file) {
        String path = key(file);
        for (String held : counts.keySet()) {
            if (overlaps(path, held)) return true;
        }
        return false;
    }

    /** A held directory also protects its siblings, and a held child protects its parent. */
    public static synchronized boolean isProtected(File file) {
        return protectedBy(leases, file) || protectedBy(reservations, file);
    }

    public static synchronized boolean evict(File file) {
        if (isProtected(file)) return false;
        return file.delete();
    }

    /** Do not partly remove a directory while a reader uses anything inside it. */
    public static synchronized boolean evictTree(File file) {
        if (isProtected(file)) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!evictTree(child)) return false;
            }
        }
        return file.delete();
    }

    /** Atomic replacement never removes the previous good file before publication succeeds. */
    public static synchronized void publish(File temporary, File destination) throws IOException {
        try {
            Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
        } catch (ErrnoException failure) {
            throw new IOException("Cannot publish SAF cache file", failure);
        }
    }
}
