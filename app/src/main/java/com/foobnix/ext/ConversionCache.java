package com.foobnix.ext;

import com.foobnix.pdf.info.BookCacheLeases;
import com.foobnix.pdf.info.BuildConfig;
import com.foobnix.sys.TempHolder;
import org.ebookdroid.droids.mupdf.codec.exceptions.MuPdfPasswordException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CancellationException;

/** Release-scoped conversion identities and recovery of unusable published units. */
public final class ConversionCache {
    private ConversionCache() { }

    public static String key(String input) { return key(input, BuildConfig.VERSION_CODE); }
    static String key(String input, int release) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("conversion-v1|release=" + release + "|" + input).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte value : digest) key.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return key.toString();
        } catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    /** Never remove original books: only immediate conversion units in the owned Book directory. */
    private static File unit(File path) {
        File root = CacheZipUtils.CACHE_BOOK_DIR;
        if (root == null || path == null) return null;
        String prefix = root.getAbsolutePath() + File.separator;
        String absolute = path.getAbsolutePath();
        if (!absolute.startsWith(prefix)) return null;
        String name = absolute.substring(prefix.length()).split(java.util.regex.Pattern.quote(File.separator), 2)[0];
        if (name.equals(".") || name.equals("..") || name.isEmpty()) return null;
        return new File(root, CacheZipUtils.cacheUnitKey(new File(root, name)));
    }

    static boolean isCancellationOrPassword(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MuPdfPasswordException || current instanceof InterruptedException
                    || current instanceof CancellationException) return true;
            if (current == current.getCause()) break;
        }
        return false;
    }

    /** A marker survives restart even when another reader prevents immediate eviction. */
    public static void invalidate(File path, Throwable failure) {
        if (TempHolder.get().loadingCancelled.get() || Thread.currentThread().isInterrupted()
                || isCancellationOrPassword(failure)) return;
        File unit = unit(path);
        if (unit == null) return;
        BookCacheLeases.invalidateConversion(unit);
    }

    /** Active readers finish; new opens cannot reuse a bad unit. */
    public static void prepare(File path) {
        File unit = unit(path);
        if (unit != null) BookCacheLeases.prepareConversion(unit, CacheZipUtils::cacheUnitKey);
    }

    public static BookCacheLeases.PublishedFile buildFile(File target, BookCacheLeases.FileWriter writer) throws Exception {
        prepare(target);
        return BookCacheLeases.buildFile(target, writer);
    }
    public static BookCacheLeases.PublishedFile buildDirectory(File target, String main, BookCacheLeases.DirectoryWriter writer) throws Exception {
        prepare(target);
        return BookCacheLeases.buildDirectory(target, main, writer);
    }
}
