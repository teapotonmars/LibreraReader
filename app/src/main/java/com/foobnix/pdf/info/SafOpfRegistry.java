package com.foobnix.pdf.info;

import android.content.Context;
import android.net.Uri;

import com.BaseExtractor;
import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CalirbeExtractor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import org.json.JSONObject;

public class SafOpfRegistry {

    public static class Entry {
        public final Uri opfUri;
        public final Map<String, Uri> siblingByLowerName;
        public final String revision;

        public Entry(Uri opfUri, Map<String, Uri> siblingByLowerName) {
            this(opfUri, siblingByLowerName, "");
        }

        public Entry(Uri opfUri, Map<String, Uri> siblingByLowerName, String revision) {
            this.opfUri = opfUri;
            this.siblingByLowerName = Collections.unmodifiableMap(new HashMap<>(siblingByLowerName));
            this.revision = revision;
        }
    }

    private static final Map<String, Entry> byBookUri = new HashMap<>();
    private static boolean restored;

    /** Called on a background thread before rendering or scanning. */
    public static synchronized void restore(Context context) {
        if (restored) return;
        restored = true;
        try {
            Map<String, Entry> saved = decode(context.getSharedPreferences("SafSidecars", Context.MODE_PRIVATE)
                    .getString("entries", "{}"));
            normalizedKeys(saved).forEach(byBookUri::putIfAbsent);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public static void save(Context context) {
        Map<String, Entry> snapshot;
        synchronized (SafOpfRegistry.class) { snapshot = new HashMap<>(byBookUri); }
        try {
            context.getSharedPreferences("SafSidecars", Context.MODE_PRIVATE).edit()
                    .putString("entries", encode(snapshot)).apply();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    static Map<String, Entry> decode(String json) throws org.json.JSONException {
        Map<String, Entry> result = new HashMap<>();
        JSONObject saved = new JSONObject(json);
        var books = saved.keys();
        while (books.hasNext()) {
            String book = books.next();
            JSONObject entry = saved.getJSONObject(book);
            JSONObject siblings = entry.getJSONObject("siblings");
            Map<String, Uri> uris = new HashMap<>();
            var names = siblings.keys();
            while (names.hasNext()) {
                String name = names.next();
                uris.put(name, Uri.parse(siblings.getString(name)));
            }
            result.put(book, new Entry(Uri.parse(entry.getString("opf")), uris, entry.optString("revision")));
        }
        return result;
    }

    /** A legacy tree-grant key and its document key refer to the same book. */
    static Map<String, Entry> normalizedKeys(Map<String, Entry> saved) {
        Map<String, Entry> result = new HashMap<>();
        saved.forEach((book, entry) -> {
            if (book.equals(canonicalKey(book))) result.put(book, entry);
        });
        saved.forEach((book, entry) -> result.putIfAbsent(canonicalKey(book), entry));
        return result;
    }

    static String encode(Map<String, Entry> entries) throws org.json.JSONException {
        JSONObject saved = new JSONObject();
        for (Map.Entry<String, Entry> book : entries.entrySet()) {
            JSONObject siblings = new JSONObject();
            for (Map.Entry<String, Uri> sibling : book.getValue().siblingByLowerName.entrySet()) {
                siblings.put(sibling.getKey(), sibling.getValue().toString());
            }
            saved.put(book.getKey(), new JSONObject().put("opf", book.getValue().opfUri.toString())
                    .put("siblings", siblings).put("revision", book.getValue().revision));
        }
        return saved.toString();
    }

    public static synchronized void register(String bookUri, Entry entry) {
        byBookUri.put(canonicalKey(bookUri), entry);
    }

    public static synchronized Entry get(String bookUri) {
        return byBookUri.get(canonicalKey(bookUri));
    }

    public static synchronized void unregister(String bookUri) {
        byBookUri.remove(canonicalKey(bookUri));
    }

    private static String canonicalKey(String bookUri) {
        if (bookUri == null) return null;
        try {
            return SafDocumentIdentity.canonical(Uri.parse(bookUri)).toString();
        } catch (RuntimeException invalid) {
            return bookUri;
        }
    }

    public static synchronized void clear() {
        byBookUri.clear();
    }

    public static CalirbeExtractor.CoverResolver coverResolver(final Context context,
                                                               final Map<String, Uri> siblingByLowerName) {
        return coverResolver(context, siblingByLowerName, failure -> {});
    }

    public static CalirbeExtractor.CoverResolver coverResolver(final Context context,
                                                               final Map<String, Uri> siblingByLowerName,
                                                               final java.util.function.Consumer<Exception> onFailure) {
        return href -> {
            if (href == null) return null;
            String key = href.toLowerCase(Locale.US);
            int slash = key.lastIndexOf('/');
            if (slash >= 0) key = key.substring(slash + 1);
            Uri imgUri = siblingByLowerName.get(key);
            if (imgUri == null) return null;
            try (InputStream is = SafDocumentIdentity.openInputStream(context, imgUri)) {
                if (is == null) return null;
                return BaseExtractor.getEntryAsByte(is);
            } catch (Exception e) {
                LOG.e(e);
                onFailure.accept(e);
                return null;
            }
        };
    }
}
