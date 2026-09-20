package com.foobnix.work;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.foobnix.dao2.FileMeta;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.SafStateStore;
import com.foobnix.pdf.info.SafDocumentIdentity;
import com.foobnix.pdf.info.SafOpfRegistry;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Calibre-managed roots reuse verified document identities and refresh only changed book folders. */
final class CalibreLibraryIndex {
    private static final int VERSION = 3;
    interface DirectoryListing {
        List<SafDocuments.Document> list(Uri parent) throws IOException, InterruptedException;
    }
    private final Context context;
    private final Uri root;
    private final BooleanSupplier stopped;
    private final CalibreManifest manifest;
    private final List<SafDocuments.Document> rootDocuments;
    private final DirectoryListing listing;
    private final String sourceRevision;
    private final List<String> formats;
    private final Map<String, String> folders = new ConcurrentHashMap<>();
    private final Map<String, FileMeta> files = new ConcurrentHashMap<>();
    private final Map<String, SafOpfRegistry.Entry> sidecars = new ConcurrentHashMap<>();
    private final Set<String> changedPaths = ConcurrentHashMap.newKeySet();
    private final Set<String> checkedAuthors = ConcurrentHashMap.newKeySet();
    private final Set<String> visitedFolders = ConcurrentHashMap.newKeySet();
    private final Set<String> revisedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> inspectedSidecars = ConcurrentHashMap.newKeySet();

    Map<String, Uri> foldersToCheck() {
        Map<String, Uri> result = new HashMap<>();
        for (SafDocuments.Document document : rootDocuments) {
            if (document.directory && checkedAuthors.contains(document.name)) result.put(document.name, document.uri);
        }
        return result;
    }

    private static String author(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    private CalibreLibraryIndex(Context context, Uri root, BooleanSupplier stopped,
                               CalibreManifest manifest, List<SafDocuments.Document> rootDocuments,
                               DirectoryListing listing, String sourceRevision, List<String> formats) {
        this.context = context;
        this.root = root;
        this.stopped = stopped;
        this.manifest = manifest;
        this.rootDocuments = rootDocuments;
        this.listing = listing;
        this.sourceRevision = sourceRevision;
        this.formats = new ArrayList<>(formats);
        folders.put("", root.toString());
    }

    static CalibreLibraryIndex open(Context context, Uri root, BooleanSupplier stopped)
            throws IOException, InterruptedException {
        return open(context, root, stopped, parent -> SafDocuments.list(context, parent, stopped));
    }

    static CalibreLibraryIndex open(Context context, Uri root, BooleanSupplier stopped, DirectoryListing listing)
            throws IOException, InterruptedException {
        return open(context, root, stopped, listing, new ArrayList<>(ExtUtils.seachExts));
    }

    static CalibreLibraryIndex open(Context context, Uri root, BooleanSupplier stopped, DirectoryListing listing,
                                    List<String> formats) throws IOException, InterruptedException {
        List<SafDocuments.Document> documents = listing.list(root);
        for (SafDocuments.Document document : documents) {
            if (!document.directory && ("metadata.db-wal".equals(document.name)
                    || "metadata.db-journal".equals(document.name))) {
                // An unknown-size journal is also unsafe. Do not trust a standalone DB copy.
                if (document.size == null || document.size != 0) {
                    throw new IOException("Calibre database has an active journal; scanning folders instead");
                }
            }
        }
        for (SafDocuments.Document document : documents) {
            if (!document.directory && "metadata.db".equals(document.name)) {
                CalibreManifest manifest = CalibreManifest.read(context, document.uri, stopped, formats);
                Long size = manifest.sourceSize != null ? manifest.sourceSize : document.size;
                Long modified = manifest.sourceModified != null ? manifest.sourceModified : document.modified;
                if (size != null && size > 0 && size != manifest.bytes) throw new IOException("Outdated Calibre database stream");
                return new CalibreLibraryIndex(context, root, stopped, manifest, documents, listing, size + ":" + modified, formats);
            }
        }
        return null;
    }

    void folder(String relativePath, Uri uri) {
        folders.put(relativePath, uri.toString());
        visitedFolders.add(relativePath);
    }
    void file(String relativePath, FileMeta book) {
        files.put(relativePath, book);
        if (revisedFiles.contains(relativePath)) changedPaths.add(book.getPath());
    }
    Set<String> changedPaths() { return changedPaths; }
    Map<String, SafOpfRegistry.Entry> sidecars() { return new HashMap<>(sidecars); }

    Map<String, String> metadataRevisions() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, FileMeta> entry : files.entrySet()) {
            String revision = revision(entry.getKey());
            if (!revision.isEmpty()) result.put(entry.getValue().getPath(), revision);
        }
        return result;
    }

    Map<String, SafOpfRegistry.Entry> sidecarsWithUninspectedFallback(List<FileMeta> books) {
        Map<String, SafOpfRegistry.Entry> result = sidecars();
        for (FileMeta row : books) {
            if (inspectedSidecars.contains(row.getPath())) continue;
            SafOpfRegistry.Entry old = SafOpfRegistry.get(row.getPath());
            if (old != null) result.putIfAbsent(row.getPath(), old);
        }
        return result;
    }

    /** Failed fast discovery is speculative; full traversal must start from observed folders. */
    void resetForFullTraversal() {
        files.clear();
        sidecars.clear();
        changedPaths.clear();
        checkedAuthors.clear();
        visitedFolders.clear();
        revisedFiles.clear();
        inspectedSidecars.clear();
        folders.clear();
        folders.put("", root.toString());
    }

    String revision(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        CalibreManifest.Book book = slash < 0 ? null : manifest.books.get(relativePath.substring(0, slash));
        return book == null ? "" : book.revision;
    }

    boolean discoverCached(List<FileMeta> result, Consumer<FileMeta> discovered)
            throws IOException, InterruptedException {
        if (stopped.getAsBoolean()) throw new IOException("Calibre scan cancelled");
        for (SafDocuments.Document document : rootDocuments) {
            if (!document.directory && com.foobnix.pdf.info.io.SearchCore.endWith(document.name, formats)) return false;
        }
        JSONObject cached;
        try {
            cached = new JSONObject(SafStateStore.get(context, "CalibreDiscovery")
                    .getString(root.toString(), "{}"));
            if (cached.optInt("version") != VERSION) return false;
            // Some providers return an older cached body despite newer server properties.
            // An unchanged body with changed source properties cannot establish unchanged books.
            if (!sourceRevision.equals(cached.optString("sourceRevision"))
                    && manifest.fingerprint.equals(cached.optString("fingerprint"))) return false;
            JSONObject savedFolders = cached.getJSONObject("folders");
            JSONArray checked = cached.getJSONArray("checkedAuthors");
            for (int i = 0; i < checked.length(); i++) checkedAuthors.add(checked.getString(i));
            Set<String> manifestAuthors = new java.util.HashSet<>();
            for (CalibreManifest.Book book : manifest.books.values()) manifestAuthors.add(book.folder.split("/", 2)[0]);
            Map<String, String> rootFolders = new HashMap<>();
            for (SafDocuments.Document document : rootDocuments) {
                if (document.directory) {
                    rootFolders.put(document.name, document.uri.toString());
                    if (!savedFolders.has(document.name) && !manifestAuthors.contains(document.name)) checkedAuthors.add(document.name);
                }
            }
            for (CalibreManifest.Book book : manifest.books.values()) {
                String author = book.folder.split("/", 2)[0];
                String savedAuthor = savedFolders.optString(author, null);
                // Replacing a parent directory can replace every child identity even when
                // Calibre metadata is unchanged. A full traversal must establish new URIs.
                if (!checkedAuthors.contains(author) && savedAuthor != null
                        && !savedAuthor.equals(rootFolders.get(author))) return false;
            }
            var names = savedFolders.keys();
            while (names.hasNext()) {
                String name = names.next();
                folders.put(name, savedFolders.getString(name));
            }
            // Root is freshly listed: do not reuse stale author-folder identities after replacement.
            for (SafDocuments.Document document : rootDocuments) {
                if (document.directory) folders.put(document.name, document.uri.toString());
            }
            JSONObject revisions = cached.getJSONObject("revisions");
            JSONObject savedFiles = cached.getJSONObject("files");
            List<CalibreManifest.Book> changed = new ArrayList<>();
            for (CalibreManifest.Book book : manifest.books.values()) {
                if (stopped.getAsBoolean()) throw new IOException("Calibre scan cancelled");
                if (checkedAuthors.contains(book.folder.split("/", 2)[0])) {
                    if (!book.revision.equals(revisions.optString(book.folder))) {
                        for (String name : book.filenames) revisedFiles.add(book.folder + "/" + name);
                    }
                    continue;
                }
                if (!book.revision.equals(revisions.optString(book.folder))) {
                    changed.add(book);
                    continue;
                }
                for (String name : book.filenames) {
                    String relative = book.folder + "/" + name;
                    JSONObject saved = savedFiles.getJSONObject(relative);
                    FileMeta row = new FileMeta(SafDocumentIdentity.canonical(
                            Uri.parse(saved.getString("uri"))).toString());
                    row.setTitle(name);
                    row.setPathTxt(name);
                    row.setExt(ExtUtils.getFileExtension(name));
                    row.setSize(saved.has("size") ? saved.getLong("size") : null);
                    row.setDate(saved.has("date") ? saved.getLong("date") : null);
                    files.put(relative, row);
                }
            }
            // Resolve a few changed folders at a time, never enqueue all books or download ebooks.
            boolean complete = BoundedTasks.run(changed, 3, stopped, book -> {
                try { return discoverChanged(book); }
                catch (Exception failed) { throw new DiscoveryFailure(failed); }
            }, rows -> rows.forEach(discovered));
            if (!complete) throw new IOException("Calibre scan cancelled");
            if (stopped.getAsBoolean()) throw new IOException("Calibre scan cancelled");
            result.addAll(files.values());
            Log.i("SafScan", "Calibre index books=" + files.size() + " changed folders=" + changed.size()
                    + " author folders to check=" + foldersToCheck().size());
            return true;
        } catch (JSONException corrupt) {
            files.clear();
            folders.clear();
            folders.put("", root.toString());
            SafStateStore.get(context, "CalibreDiscovery").edit()
                    .remove(root.toString()).commit();
            Log.w("SafScan", "Invalid Calibre discovery cache; rebuilding", corrupt);
            return false;
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception invalid) {
            throw new IOException("Cannot reuse Calibre discovery index", invalid);
        }
    }

    private List<FileMeta> discoverChanged(CalibreManifest.Book book) throws IOException, InterruptedException {
        Uri folder = resolveFolder(book.folder);
        List<SafDocuments.Document> documents;
        try {
            documents = listing.list(folder);
        } catch (IOException stale) {
            // A rename/replacement can invalidate a saved folder URI. Resolve it from its parent.
            folders.remove(book.folder);
            folder = resolveFolder(book.folder);
            documents = listing.list(folder);
        }
        Map<String, SafOpfRegistry.Entry> inspected = new HashMap<>();
        SafDiscovery.recordSidecars(documents, inspected);
        Map<String, SafDocuments.Document> byName = new HashMap<>();
        for (SafDocuments.Document document : documents) {
            if (!document.directory) {
                byName.put(document.name, document);
            }
        }
        List<FileMeta> rows = new ArrayList<>();
        for (String name : book.filenames) {
            SafDocuments.Document document = byName.get(name);
            // The database may sync before its files. Do not publish a partial replacement index.
            if (document == null) throw new IOException("Calibre format is not available yet: " + book.folder + "/" + name);
            FileMeta row = document.book();
            String relative = book.folder + "/" + name;
            files.put(relative, row);
            changedPaths.add(row.getPath());
            inspectedSidecars.add(row.getPath());
            SafOpfRegistry.Entry sidecar = inspected.get(row.getPath());
            if (sidecar != null) sidecars.put(row.getPath(), sidecar);
            rows.add(row);
        }
        return rows;
    }

    private Uri resolveFolder(String relative) throws IOException, InterruptedException {
        String known = folders.get(relative);
        if (known != null) return Uri.parse(known);
        int slash = relative.lastIndexOf('/');
        String parent = slash < 0 ? "" : relative.substring(0, slash);
        String name = relative.substring(slash + 1);
        Uri parentUri = resolveFolder(parent);
        // Multiple new books by the same author share this lookup.
        synchronized (folders) {
            known = folders.get(relative);
            if (known != null) return Uri.parse(known);
            List<SafDocuments.Document> documents = parent.isEmpty() ? rootDocuments
                    : listing.list(parentUri);
            for (SafDocuments.Document document : documents) {
                if (document.directory) {
                    String path = parent.isEmpty() ? document.name : parent + "/" + document.name;
                    folders.put(path, document.uri.toString());
                }
            }
        }
        known = folders.get(relative);
        if (known == null) throw new IOException("Calibre folder is not available yet: " + relative);
        return Uri.parse(known);
    }

    void save() {
        if (stopped.getAsBoolean()) return;
        for (String author : foldersToCheck().keySet()) {
            if (!visitedFolders.contains(author)) {
                Log.i("SafScan", "Unchecked author folder; Calibre index not saved: " + author);
                return;
            }
        }
        for (SafDocuments.Document document : rootDocuments) {
            if (document.directory && !folders.containsKey(document.name)) {
                SafStateStore.get(context, "CalibreDiscovery")
                        .edit().remove(root.toString()).commit();
                Log.i("SafScan", "Incomplete folder coverage; Calibre index not saved");
                return;
            }
        }
        // Cache verified books; recheck author subtrees whose paths disagree with Calibre.
        // Root-level unmanaged files cannot be isolated this way, so retain full traversal.
        checkedAuthors.clear();
        if (!files.keySet().equals(manifest.paths()) || stopped.getAsBoolean()) {
            Log.i("SafScan", "Calibre coverage differs: scanned=" + files.size()
                    + " database=" + manifest.paths().size());
            Set<String> expectedPaths = manifest.paths();
            for (String expected : expectedPaths) {
                if (!files.containsKey(expected)) {
                    checkedAuthors.add(author(expected));
                    Log.d("SafScan", "Missing Calibre path: " + expected);
                }
            }
            boolean rootFile = false;
            for (String scanned : files.keySet()) {
                if (!expectedPaths.contains(scanned)) {
                    String author = author(scanned);
                    if (author == null) rootFile = true;
                    else checkedAuthors.add(author);
                    Log.d("SafScan", "Unindexed path: " + scanned);
                }
            }
            if (stopped.getAsBoolean()) return;
            if (rootFile) {
                SafStateStore.get(context, "CalibreDiscovery")
                        .edit().remove(root.toString()).commit();
                return;
            }
        }
        for (String author : foldersToCheck().keySet()) {
            if (!visitedFolders.contains(author)) {
                Log.i("SafScan", "Unchecked author folder; Calibre index not saved: " + author);
                return;
            }
        }
        try {
            JSONObject revisions = new JSONObject();
            for (CalibreManifest.Book book : manifest.books.values()) revisions.put(book.folder, book.revision);
            JSONObject savedFiles = new JSONObject();
            for (Map.Entry<String, FileMeta> file : files.entrySet()) {
                FileMeta row = file.getValue();
                savedFiles.put(file.getKey(), new JSONObject().put("uri", row.getPath())
                        .put("size", row.getSize()).put("date", row.getDate()));
            }
            JSONObject saved = new JSONObject().put("version", VERSION)
                    .put("folders", new JSONObject(folders)).put("files", savedFiles).put("revisions", revisions)
                    .put("checkedAuthors", new JSONArray(checkedAuthors))
                    .put("fingerprint", manifest.fingerprint).put("sourceRevision", sourceRevision);
            SafStateStore.get(context, "CalibreDiscovery").edit()
                    .putString(root.toString(), saved.toString()).commit();
            Log.i("SafScan", "Saved Calibre index; author folders to check=" + foldersToCheck().size());
        } catch (Exception failed) { Log.w("SafScan", "Cannot save Calibre discovery index", failed); }
    }

    private static final class DiscoveryFailure extends RuntimeException {
        DiscoveryFailure(Exception cause) { super(cause); }
    }
}
