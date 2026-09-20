package com.foobnix.pdf.info;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.system.ErrnoException;
import android.system.Os;

/** Coordinates publication, reader handoff and eviction of private SAF cache files. */
public final class SafCacheFiles {
    @FunctionalInterface public interface FileWriter {
        void write(File temporary) throws Exception;
    }

    /** Owns a complete reusable output until its reader has acquired the path. */
    public static final class PublishedFile implements AutoCloseable {
        public final File file;
        private final AutoCloseable lease;
        private PublishedFile(File file, AutoCloseable lease) {
            this.file = file;
            this.lease = lease;
        }
        @Override public void close() throws Exception { lease.close(); }
    }
    private static final Map<String, Integer> leases = new HashMap<>();
    private static final Map<String, Integer> reservations = new HashMap<>();
    private static final Set<String> initializedFolders = new HashSet<>();
    private static final Set<String> immutableRevisionNamedSources = new HashSet<>();
    private static final ThreadLocal<Integer> managedOpenDepth = ThreadLocal.withInitial(() -> 0);

    public static void beginManagedOpen() { managedOpenDepth.set(managedOpenDepth.get() + 1); }
    public static void endManagedOpen() {
        int depth = managedOpenDepth.get() - 1;
        if (depth == 0) managedOpenDepth.remove(); else managedOpenDepth.set(depth);
    }

    public static synchronized File temporary(File directory, String prefix) throws IOException {
        cleanAbandoned(directory);
        return File.createTempFile(prefix, ".part", directory);
    }

    private static synchronized void cleanAbandoned(File directory) {
        if (initializedFolders.add(key(directory))) {
            // This runs before any operation in this process creates a temporary file here.
            File[] abandoned = directory.listFiles(file -> file.getName().endsWith(".part"));
            if (abandoned != null) for (File file : abandoned) evictTree(file);
        }
    }

    /** Writers use private names; readers see only a complete output, even when writers race. */
    public static PublishedFile buildFile(File destination, FileWriter writer) throws Exception {
        synchronized (SafCacheFiles.class) {
            if (destination.isFile()) return new PublishedFile(destination, acquire(destination));
        }
        File temporary;
        AutoCloseable writing;
        synchronized (SafCacheFiles.class) {
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
                throw new IOException("Cannot create conversion cache: " + parent);
            temporary = temporary(parent, "conversion-");
            writing = acquire(temporary);
        }
        try {
            writer.write(temporary);
            if (!temporary.isFile() || temporary.length() == 0)
                throw new IOException("Conversion produced no complete output: " + destination);
            synchronized (SafCacheFiles.class) {
                if (!destination.isFile()) publish(temporary, destination);
                return new PublishedFile(destination, acquire(destination));
            }
        } finally {
            writing.close();
            evict(temporary);
        }
    }

    @FunctionalInterface public interface DirectoryWriter {
        void write(File directory) throws Exception;
    }

    /** Publish an HTML document and every sibling resource as one directory. */
    public static PublishedFile buildDirectory(File destination, String mainName,
                                               DirectoryWriter writer) throws Exception {
        File ready = new File(destination, mainName);
        synchronized (SafCacheFiles.class) {
            if (ready.isFile()) return new PublishedFile(ready, acquire(destination));
        }
        File staging;
        AutoCloseable writing;
        synchronized (SafCacheFiles.class) {
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
                throw new IOException("Cannot create conversion cache: " + parent);
            cleanAbandoned(parent);
            staging = new File(parent, UUID.randomUUID() + ".part");
            writing = acquire(staging);
            if (!staging.mkdir()) {
                writing.close();
                throw new IOException("Cannot create conversion directory: " + staging);
            }
        }
        try {
            writer.write(staging);
            File stagedMain = new File(staging, mainName);
            if (!stagedMain.isFile() || stagedMain.length() == 0)
                throw new IOException("Conversion produced no complete output: " + ready);
            synchronized (SafCacheFiles.class) {
                if (!ready.isFile()) {
                    if (destination.exists() && !evictTree(destination))
                        throw new IOException("Cannot replace incomplete conversion cache: " + destination);
                    publish(staging, destination);
                }
                return new PublishedFile(ready, acquire(destination));
            }
        } finally {
            writing.close();
            evictTree(staging);
        }
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
    public static synchronized boolean hasReservation(File file) { return reservations.containsKey(key(file)); }

    /** Only cache publishers may mark paths whose immutable revision is in the name. */
    public static synchronized void registerImmutableRevisionNamedSource(File file) {
        if (file.isFile()) immutableRevisionNamedSources.add(key(file));
    }
    public static synchronized void unregisterImmutableRevisionNamedSource(File file) {
        immutableRevisionNamedSources.remove(key(file));
    }
    /** Called under the lease monitor after a published cache path is hidden. */
    public static synchronized void retireImmutableRevisionNamedSources(File root) {
        String path = key(root);
        immutableRevisionNamedSources.removeIf(registered -> registered.equals(path)
                || registered.startsWith(path + File.separator));
    }
    public static synchronized boolean isImmutableRevisionNamedSource(File file) {
        return file.isFile() && immutableRevisionNamedSources.contains(key(file));
    }

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

    /** Acquire before starting a background path reader, including its failure-to-start case. */
    public static Thread startLeasedThread(String name, int priority, Runnable work, File... files) {
        List<AutoCloseable> held = new ArrayList<>(files.length);
        for (File file : files) held.add(acquire(file));
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } finally {
                for (AutoCloseable lease : held) {
                    try { lease.close(); } catch (Exception ignored) { }
                }
            }
        }, name);
        try {
            thread.setPriority(priority);
            thread.start();
            return thread;
        } catch (RuntimeException | Error failure) {
            for (AutoCloseable lease : held) {
                try { lease.close(); } catch (Exception ignored) { }
            }
            throw failure;
        }
    }

    public static synchronized void readerOpened(File file) {
        readerOpened(file, true);
    }
    public static synchronized void readerOpened(File file, boolean consumeReservation) {
        add(leases, file);
        // AbstractCodecContext owns the reservation for the whole conversion/delegation.
        // Direct MuPdfDocument callers still consume their own reservation here.
        if (consumeReservation && managedOpenDepth.get() == 0) remove(reservations, file);
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
        boolean removed = file.delete();
        if (removed) immutableRevisionNamedSources.remove(key(file));
        return removed;
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
        boolean removed = file.delete();
        if (removed) immutableRevisionNamedSources.remove(key(file));
        return removed;
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
