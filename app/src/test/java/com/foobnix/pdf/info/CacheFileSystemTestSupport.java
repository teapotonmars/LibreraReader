package com.foobnix.pdf.info;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Real host filesystem operations in place of the JVM Android OS stubs. */
public final class CacheFileSystemTestSupport {
    private CacheFileSystemTestSupport() { }
    public static AutoCloseable install() {
        BookCacheLeases.FileSystem original = BookCacheLeases.fileSystem;
        BookCacheLeases.fileSystem = new BookCacheLeases.FileSystem() {
            @Override public boolean isDirectory(File file) throws IOException {
                return Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isDirectory();
            }
            @Override public void rename(File source, File destination) throws IOException {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        };
        return () -> BookCacheLeases.fileSystem = original;
    }
}
