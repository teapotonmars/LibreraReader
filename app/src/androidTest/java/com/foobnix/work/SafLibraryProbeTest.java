package com.foobnix.work;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.pdf.info.ExtUtils;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import static org.junit.Assert.*;

/** Opt-in device probe: pass a granted library root as the rootUri instrumentation argument. */
public class SafLibraryProbeTest {
    @Test public void inspectCalibreManifest() throws Exception {
        String argument = InstrumentationRegistry.getArguments().getString("rootUri");
        org.junit.Assume.assumeTrue("Opt-in provider diagnostic: pass rootUri", argument != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Uri root = Uri.parse(argument);
        Uri manifest = null;
        try (Cursor cursor = SafFolderQuery.query(context.getContentResolver(),
                ExtUtils.getChildUri(context, root), new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID}, () -> false)) {
            while (cursor.moveToNext()) {
                if ("metadata.db".equals(cursor.getString(0))) {
                    manifest = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(1));
                }
                if (cursor.getString(0).startsWith("metadata")) Log.i("SafProbe", "Root sidecar=" + cursor.getString(0));
            }
        }
        Log.i("SafProbe", "Calibre metadata.db present=" + (manifest != null));
        if (manifest == null) return;
        Log.i("SafProbe", "Provider refresh attempted=" + context.getContentResolver().refresh(manifest, null, null));
        try (var descriptor = context.getContentResolver().openFileDescriptor(manifest, "r")) {
            if (descriptor != null) Log.i("SafProbe", "Returned file mtime seconds="
                    + android.system.Os.fstat(descriptor.getFileDescriptor()).st_mtime);
        }
        try (Cursor properties = context.getContentResolver().query(manifest, new String[]{
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (properties != null && properties.moveToFirst()) Log.i("SafProbe", "Database provider size="
                    + properties.getLong(0) + " modified=" + properties.getLong(1));
        }
        File local = File.createTempFile("calibre-probe-", ".db", context.getCacheDir());
        long start = android.os.SystemClock.elapsedRealtime();
        try {
            try (InputStream input = context.getContentResolver().openInputStream(manifest);
                 FileOutputStream output = new FileOutputStream(local)) {
                assertNotNull(input);
                byte[] bytes = new byte[128 * 1024];
                int read;
                while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(local.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor books = db.rawQuery("SELECT count(*) FROM books", null);
                 Cursor formats = db.rawQuery("SELECT count(*) FROM data", null)) {
                assertTrue(books.moveToFirst());
                assertTrue(formats.moveToFirst());
                Log.i("SafProbe", "Downloaded snapshot bytes=" + local.length());
                Log.i("SafProbe", "Manifest books=" + books.getInt(0) + " formats=" + formats.getInt(0)
                        + " read ms=" + (android.os.SystemClock.elapsedRealtime() - start));
                try (Cursor latest = db.rawQuery("SELECT id,title,last_modified FROM books ORDER BY id DESC LIMIT 4", null)) {
                    while (latest.moveToNext()) Log.i("SafProbe", "Latest DB book id=" + latest.getLong(0)
                            + " title=" + latest.getString(1) + " modified=" + latest.getString(2));
                }
            }
        } finally {
            local.delete();
        }
    }
}
