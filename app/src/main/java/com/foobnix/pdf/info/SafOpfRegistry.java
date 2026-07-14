package com.foobnix.pdf.info;

import android.content.ContentResolver;
import android.net.Uri;

import com.BaseExtractor;
import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CalirbeExtractor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class SafOpfRegistry {

    public static class Entry {
        public final Uri opfUri;
        public final Map<String, Uri> siblingByLowerName;

        public Entry(Uri opfUri, Map<String, Uri> siblingByLowerName) {
            this.opfUri = opfUri;
            this.siblingByLowerName = siblingByLowerName;
        }
    }

    private static final Map<String, Entry> byBookUri = new HashMap<>();

    public static synchronized void register(String bookUri, Entry entry) {
        byBookUri.put(bookUri, entry);
    }

    public static synchronized Entry get(String bookUri) {
        return byBookUri.get(bookUri);
    }

    public static synchronized void clear() {
        byBookUri.clear();
    }

    public static CalirbeExtractor.CoverResolver coverResolver(final ContentResolver cr,
                                                               final Map<String, Uri> siblingByLowerName) {
        return href -> {
            if (href == null) return null;
            String key = href.toLowerCase(Locale.US);
            int slash = key.lastIndexOf('/');
            if (slash >= 0) key = key.substring(slash + 1);
            Uri imgUri = siblingByLowerName.get(key);
            if (imgUri == null) return null;
            try (InputStream is = cr.openInputStream(imgUri)) {
                if (is == null) return null;
                return BaseExtractor.getEntryAsByte(is);
            } catch (Exception e) {
                LOG.e(e);
                return null;
            }
        };
    }
}
