package com.foobnix.pdf.info;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.system.ErrnoException;
import android.system.Os;

/** Coordinates publication, reader handoff and eviction of book cache files. */
public final class BookCacheLeases {
    /** Package seam for JVM tests; Android always uses lstat and atomic rename. */
    interface FileSystem {
        boolean isDirectory(File file) throws IOException;
        void rename(File source, File destination) throws IOException;
    }
    static FileSystem fileSystem = new FileSystem() {
        @Override public boolean isDirectory(File file) throws IOException {
            try { return android.system.OsConstants.S_ISDIR(Os.lstat(file.getAbsolutePath()).st_mode); }
            catch (ErrnoException failure) { throw new IOException(failure); }
        }
        @Override public void rename(File source, File destination) throws IOException {
            try { Os.rename(source.getAbsolutePath(), destination.getAbsolutePath()); }
            catch (ErrnoException failure) { throw new IOException(failure); }
        }
    };

    private static final Map<String, Integer> leases = new HashMap<>();
    static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(work ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                work.run();
            }, "book-cache-cleanup"));

    /** Cleanup must never queue ahead of document loading, page work, or settings saves. */
    public static void scheduleCleanup(Runnable work) { CLEANUP.execute(work); }

    private static final Set<String> initializedFolders = new HashSet<>();

    /** Crash leftovers only: ordinary cache outputs and active leases remain untouched. */
    public static void sweepAbandoned(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".evicted-")) {
                evictTree(file);
            }
        }
    }

    public static synchronized File temporary(File directory, String prefix) throws IOException {
        if (initializedFolders.add(key(directory))) {
            // This runs before any operation in this process creates a temporary file here.
            File[] abandoned = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".part") || file.getName().startsWith(".evicted-"));
            if (abandoned != null) for (File file : abandoned) retireTree(file);
        }
        return File.createTempFile(prefix, ".part", directory);
    }

    /** A temporary path and its ownership are created in one operation. */
    public static final class LeasedFile implements AutoCloseable {
        public final File file;
        private final AutoCloseable lease;
        private LeasedFile(File file, AutoCloseable lease) {
            this.file = file;
            this.lease = lease;
        }
        @Override public void close() throws Exception { lease.close(); }
    }

    public static synchronized LeasedFile temporaryWithLease(File directory, String prefix) throws IOException {
        File file = temporary(directory, prefix);
        return new LeasedFile(file, acquire(file));
    }

    public static synchronized AutoCloseable acquireIfFile(File file) {
        return file.isFile() ? acquire(file) : null;
    }

    /** Racing publishers share the complete winner; the losing temporary is retired. */
    public static synchronized AutoCloseable publishOrReuse(File temporary, File destination) throws IOException {
        if (!destination.isFile()) publish(temporary, destination);
        else deleteDetachedTree(temporary);
        return acquire(destination);
    }

    /** Hide a whole cache unit or leave every member in place. */
    public static synchronized List<File> detachAllOrNone(Collection<File> files) {
        for (File file : files) {
            if (isProtected(file)) {
                long now = System.currentTimeMillis();
                for (File member : files) member.setLastModified(now);
                return null;
            }
        }
        List<File> originals = new ArrayList<>(), detached = new ArrayList<>();
        for (File file : files) {
            // lstat also sees dangling links, which exists() would miss.
            try { fileSystem.isDirectory(file); }
            catch (IOException missing) { continue; }
            File tombstone = new File(file.getParentFile(), ".evicted-" + UUID.randomUUID());
            try { fileSystem.rename(file, tombstone); }
            catch (IOException failure) {
                for (int i = detached.size() - 1; i >= 0; i--) {
                    try { fileSystem.rename(detached.get(i), originals.get(i)); }
                    catch (IOException rollback) { throw new IllegalStateException("Cannot restore cache unit", rollback); }
                }
                return null;
            }
            originals.add(file);
            detached.add(tombstone);
        }
        return detached;
    }

    public static void deleteDetachedTrees(Collection<File> files) {
        for (File file : files) deleteDetachedTree(file);
    }

    private static String key(File file) { return file.getAbsolutePath(); }
    private static void add(Map<String, Integer> counts, File file) {
        counts.merge(key(file), 1, Integer::sum);
    }
    private static void remove(Map<String, Integer> counts, File file) {
        counts.computeIfPresent(key(file), (path, count) -> count == 1 ? null : count - 1);
    }




    public static synchronized AutoCloseable acquire(File file) {
        add(leases, file);
        return new AutoCloseable() {
            private boolean closed;
            @Override public void close() {
                synchronized (BookCacheLeases.class) {
                    if (!closed) { closed = true; remove(leases, file); }
                }
            }
        };
    }

    public static synchronized void readerOpened(File file) { add(leases, file); }
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

    /** A held directory protects descendants; a held child protects its ancestors. */
    public static synchronized boolean isProtected(File file) {
        return protectedBy(leases, file);
    }

    public static synchronized boolean evict(File file) {
        if (isProtected(file)) return false;
        return file.delete();
    }

    /** Do not partly remove a directory while a reader uses anything inside it. */
    public static boolean evictTree(File file) {
        File tombstone = detachTree(file);
        return tombstone != null && deleteDetachedTree(tombstone);
    }

    public static synchronized File detachTree(File file) {
        if (isProtected(file) || !file.exists()) return null;
        File tombstone = new File(file.getParentFile(), ".evicted-" + UUID.randomUUID());
        try { fileSystem.rename(file, tombstone); }
        catch (IOException unavailable) { return null; }
        return tombstone;
    }

    /** Detach synchronously; recursive deletion never runs in a caller's lease monitor. */
    private static boolean retireTree(File file) {
        File tombstone = detachTree(file);
        if (tombstone == null) return false;
        scheduleCleanup(() -> deleteDetachedTree(tombstone));
        return true;
    }

    private static boolean deleteDetachedTree(File file) {
        boolean directory;
        try { directory = fileSystem.isDirectory(file); }
        catch (IOException unavailable) { return false; }
        if (directory) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) if (!deleteDetachedTree(child)) return false;
        }
        return file.delete();
    }

    /** Atomic replacement never removes the previous good file before publication succeeds. */
    public static synchronized void publish(File temporary, File destination) throws IOException {
        try {
            fileSystem.rename(temporary, destination);
        } catch (IOException failure) {
            throw new IOException("Cannot publish book cache file", failure);
        }
    }
}
