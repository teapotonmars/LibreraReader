package com.foobnix.pdf.info;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.File;
import java.io.IOException;

/** A descriptor-backed local path whose basename remains the provider's display name. */
public final class SafFileLink implements AutoCloseable {
    public final File file;
    private final File directory;
    private final ParcelFileDescriptor descriptor;

    public SafFileLink(Context context, Uri uri, String displayName) throws Exception {
        descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("Cannot open SAF book: " + uri);
        File temporaryDirectory = null;
        File temporaryLink = null;
        try {
            temporaryDirectory = File.createTempFile("saf-book-", "", context.getCacheDir());
            if (!temporaryDirectory.delete() || !temporaryDirectory.mkdir()) {
                throw new IOException("Cannot create SAF link directory");
            }
            temporaryLink = new File(temporaryDirectory, new File(displayName).getName());
            Os.symlink("/proc/self/fd/" + descriptor.getFd(), temporaryLink.getAbsolutePath());
        } catch (Exception e) {
            if (temporaryLink != null) temporaryLink.delete();
            if (temporaryDirectory != null) temporaryDirectory.delete();
            descriptor.close();
            throw e;
        }
        directory = temporaryDirectory;
        file = temporaryLink;
    }

    @Override public void close() throws IOException {
        file.delete();
        directory.delete();
        descriptor.close();
    }
}
