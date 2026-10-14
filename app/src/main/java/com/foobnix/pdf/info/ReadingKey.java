package com.foobnix.pdf.info;

import android.net.Uri;
import android.provider.DocumentsContract;
import com.foobnix.android.utils.LOG;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ui2.AppDB;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Filename key used by reading state and cross-device recent/favorite matching. */
public final class ReadingKey {
    private static final Map<String, String> names = new ConcurrentHashMap<>();
    private ReadingKey() { }

    public static void remember(String identity, String displayName) {
        if (identity != null && identity.startsWith("content://")
                && displayName != null && !displayName.isEmpty())
            names.put(SafDocumentIdentity.canonical(Uri.parse(identity)).toString(), displayName);
    }

    public static void remember(FileMeta row) {
        if (row != null) remember(row.getPath(), row.getPathTxt());
    }

    public static String of(String libraryIdentityOrPath) {
        if (ArchiveMemberIdentity.isIdentity(libraryIdentityOrPath))
            return ExtUtils.getFileName(ArchiveMemberIdentity.entry(libraryIdentityOrPath));
        if (libraryIdentityOrPath == null || !libraryIdentityOrPath.startsWith("content://"))
            return ExtUtils.getFileName(libraryIdentityOrPath);
        Uri canonical = SafDocumentIdentity.canonical(Uri.parse(libraryIdentityOrPath));
        String identity = canonical.toString();
        String name = names.get(identity);
        if (name != null) return name;
        // Only the app's DB is read here. Never contact a provider from a UI caller.
        FileMeta row = AppDB.get().load(identity);
        if (row != null && row.getPathTxt() != null && !row.getPathTxt().isEmpty()) {
            remember(row);
            return row.getPathTxt();
        }
        String fallback;
        try { fallback = DocumentsContract.getDocumentId(canonical); }
        catch (IllegalArgumentException notDocument) { fallback = canonical.getLastPathSegment(); }
        LOG.d("ReadingKey: unknown provider display name; using document ID", identity, fallback);
        return fallback == null ? "" : fallback;
    }
}
