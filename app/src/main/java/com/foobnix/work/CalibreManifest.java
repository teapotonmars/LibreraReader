package com.foobnix.work;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.io.SearchCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.security.MessageDigest;

/** Reads a fresh, private snapshot of Calibre's database; never writes to the synced database. */
final class CalibreManifest {
    static final class Book {
        final String folder;
        final Set<String> filenames = new LinkedHashSet<>();
        String revision;
        Book(String folder, String revision) { this.folder = folder; this.revision = revision; }
    }

    final Map<String, Book> books = new LinkedHashMap<>();
    String fingerprint;
    Long sourceSize;
    Long sourceModified;
    long bytes;

    Set<String> paths() {
        Set<String> paths = new LinkedHashSet<>();
        for (Book book : books.values()) {
            for (String name : book.filenames) paths.add(book.folder + "/" + name);
        }
        return paths;
    }

    private static void requireTable(SQLiteDatabase db, String name, String... required) throws IOException {
        try (Cursor schema = db.rawQuery("SELECT type,sql FROM sqlite_master WHERE name=?", new String[]{name})) {
            if (!schema.moveToFirst() || !"table".equals(schema.getString(0))
                    || schema.getString(1) == null
                    || !schema.getString(1).trim().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE"))
                throw new IOException("Calibre requires an ordinary table: " + name);
        }
        Set<String> columns = new LinkedHashSet<>();
        try (Cursor schema = db.rawQuery("PRAGMA table_info(" + name + ")", null)) {
            while (schema.moveToNext()) columns.add(schema.getString(1));
        }
        for (String column : required) if (!columns.contains(column))
            throw new IOException("Missing Calibre column: " + name + "." + column);
    }

    static boolean safeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0) return false;
        for (String component : path.split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals("..")) return false;
        return true;
    }

    static CalibreManifest read(Context context, Uri uri, BooleanSupplier stopped) throws IOException {
        return read(context, uri, stopped, new java.util.ArrayList<>(ExtUtils.seachExts));
    }

    static CalibreManifest read(Context context, Uri uri, BooleanSupplier stopped, java.util.List<String> formats) throws IOException {
        File snapshot = File.createTempFile("calibre-manifest-", ".db", context.getCacheDir());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Always open the provider stream: Nextcloud checks the server ETag here, whereas
            // a cached document size/date alone cannot establish that metadata.db is unchanged.
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(snapshot)) {
                if (input == null) throw new IOException("Cannot read Calibre database");
                byte[] buffer = new byte[128 * 1024];
                long bytes = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (stopped.getAsBoolean()) throw new IOException("Calibre scan cancelled");
                    bytes += read;
                    if (bytes > 64L * 1024 * 1024) throw new IOException("Calibre database exceeds snapshot limit");
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            CalibreManifest manifest = new CalibreManifest();
            manifest.bytes = snapshot.length();
            try (java.io.RandomAccessFile header = new java.io.RandomAccessFile(snapshot, "r")) {
                if (header.length() >= 20) {
                    header.seek(18);
                    if (header.readUnsignedByte() == 2 || header.readUnsignedByte() == 2) {
                        // A provider may omit the WAL from its listing or the sync itself.
                        // The SQLite header still tells us that this file can depend on it.
                        throw new IOException("Calibre WAL-mode database requires folder scanning");
                    }
                }
            }
            StringBuilder fingerprint = new StringBuilder();
            for (byte value : digest.digest()) fingerprint.append(String.format(Locale.ROOT, "%02x", value & 255));
            manifest.fingerprint = fingerprint.toString();
            if ("content".equals(uri.getScheme())) {
                try (Cursor properties = context.getContentResolver().query(uri, new String[]{
                        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    if (properties != null && properties.moveToFirst()) {
                        manifest.sourceSize = properties.isNull(0) ? null : properties.getLong(0);
                        manifest.sourceModified = properties.isNull(1) ? null : properties.getLong(1);
                    }
                }
                if (manifest.sourceSize != null && manifest.sourceSize > 0 && manifest.sourceSize != manifest.bytes) {
                    throw new IOException("Provider returned an outdated Calibre database: expected "
                            + manifest.sourceSize + " bytes, received " + manifest.bytes);
                }
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(snapshot.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                requireTable(db, "books", "id", "path", "last_modified", "has_cover");
                requireTable(db, "data", "book", "name", "format", "uncompressed_size");
                try (Cursor rows = db.rawQuery("SELECT b.path, b.last_modified, b.has_cover, d.name, d.format, "
                         + "d.uncompressed_size FROM books b JOIN data d ON d.book=b.id "
                         + "ORDER BY b.path, d.format", null)) {
                while (rows.moveToNext()) {
                    if (stopped.getAsBoolean()) throw new IOException("Calibre scan cancelled");
                    String folder = rows.getString(0);
                    String name = rows.getString(3) + "." + rows.getString(4).toLowerCase(Locale.ROOT);
                    if (!SearchCore.endWith(name, formats)) continue;
                    if (!safeRelativePath(folder) || !safeRelativePath(name) || name.contains("/"))
                        throw new IOException("Invalid Calibre library path");
                    Book book = manifest.books.get(folder);
                    if (book == null) {
                        book = new Book(folder, rows.getString(1) + ":" + rows.getInt(2));
                        manifest.books.put(folder, book);
                    }
                    book.filenames.add(name);
                    // Include format names and sizes: conversions and replacements must invalidate
                    // discovery even if metadata's last_modified value did not change.
                    book.revision += "\n" + name + ":" + rows.getLong(5);
                }
            }
            }
            return manifest;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read Calibre database", invalid);
        } finally {
            snapshot.delete();
        }
    }
}
