package com.foobnix.work;

import com.foobnix.pdf.info.SafStateStore;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.SafOpfRegistry;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real SQLite manifests and cache JSON; deterministic SAF listings, no user library mutations. */
public class CalibreLibraryIndexTest {
    private Context context;
    private Context target;
    private String namespace;
    private File database;
    private Uri root;
    private final Map<Uri, List<SafDocuments.Document>> tree = new HashMap<>();
    private final Map<String, SafDocuments.Document> books = new HashMap<>();
    private int queries;
    private long sourceRevision = 1;
    private Long advertisedSize;
    private boolean hasManifest = true;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Before public void setUp() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppProfile.init(target);
        namespace = "calibre-test-" + UUID.randomUUID();
        context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(namespace + "-" + name, mode);
            }
        };
        root = Uri.parse("content://fixture/tree/" + namespace);
        database = File.createTempFile(namespace, ".db", target.getCacheDir());
        tree.put(root, new ArrayList<>());
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database, null)) {
            db.execSQL("CREATE TABLE books (id INTEGER PRIMARY KEY, path TEXT, last_modified TEXT, has_cover INTEGER)");
            db.execSQL("CREATE TABLE data (book INTEGER, name TEXT, format TEXT, uncompressed_size INTEGER)");
            db.beginTransaction();
            try {
                for (int id = 0; id < 500; id++) add(db, id);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }

    @After public void tearDown() {
        target.deleteSharedPreferences(namespace + "-CalibreDiscovery");
        database.delete();
        new File(database + "-journal").delete();
    }
    private String folder(int id) { return "Author" + (id % 10) + "/Book" + id; }
    private String relative(int id) { return folder(id) + "/Book" + id + ".epub"; }
    private Uri uri(String relative) { return Uri.parse(root + "/" + relative); }
    private SafDocuments.Document dir(String name, Uri uri) {
        return new SafDocuments.Document(name, uri, true, null, null);
    }
    private void add(SQLiteDatabase db, int id) {
        String author = "Author" + (id % 10);
        Uri authorUri = uri(author);
        if (!tree.containsKey(authorUri)) {
            tree.put(authorUri, new ArrayList<>());
            tree.get(root).add(dir(author, authorUri));
        }
        Uri bookUri = uri(folder(id));
        tree.get(authorUri).add(dir("Book" + id, bookUri));
        SafDocuments.Document document = new SafDocuments.Document("Book" + id + ".epub",
                uri(relative(id)), false, 100L, 1000L);
        tree.put(bookUri, new ArrayList<>(Collections.singletonList(document)));
        books.put(relative(id), document);
        db.execSQL("INSERT INTO books VALUES (?, ?, 'revision-1', 1)", new Object[]{id, folder(id)});
        db.execSQL("INSERT INTO data VALUES (?, ?, 'EPUB', 100)", new Object[]{id, "Book" + id});
    }
    private List<SafDocuments.Document> list(Uri parent) throws IOException {
        queries++;
        List<SafDocuments.Document> docs = tree.get(parent);
        if (docs == null) throw new IOException("Folder unavailable: " + parent);
        List<SafDocuments.Document> result = new ArrayList<>(docs);
        if (parent.equals(root) && hasManifest) result.add(new SafDocuments.Document("metadata.db",
                Uri.fromFile(database), false, advertisedSize == null ? database.length() : advertisedSize, sourceRevision));
        return result;
    }
    private CalibreLibraryIndex open() throws Exception {
        return CalibreLibraryIndex.open(context, root, stopped::get, this::list, java.util.Arrays.asList(".epub", ".pdf"));
    }
    @Test public void activeWalFallsBackInsteadOfReadingAnIncompleteDatabase() {
        tree.get(root).add(new SafDocuments.Document("metadata.db-wal", uri("metadata.db-wal"), false, 100L, 1L));
        assertThrows(IOException.class, this::open);
        assertEquals(1, queries);
    }
    @Test public void unknownJournalSizeAlsoFallsBack() {
        tree.get(root).add(new SafDocuments.Document("metadata.db-journal", uri("metadata.db-journal"), false, null, 1L));
        assertThrows(IOException.class, this::open);
    }
    @Test public void emptyWalDoesNotDisableFastScanning() throws Exception {
        tree.get(root).add(new SafDocuments.Document("metadata.db-wal", uri("metadata.db-wal"), false, 0L, 1L));
        assertNotNull(open());
    }
    @Test public void walModeDatabaseFallsBackEvenWhenProviderOmitsJournal() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            assertTrue(db.enableWriteAheadLogging());
            db.execSQL("UPDATE books SET last_modified='wal-revision' WHERE id=1");
        }
        assertThrows(IOException.class, this::open);
    }
    private void baseline() throws Exception {
        CalibreLibraryIndex index = open();
        assertNotNull(index);
        assertEquals(500, CalibreManifest.read(context, Uri.fromFile(database), () -> false, Collections.singletonList(".epub")).paths().size());
        for (Uri folder : tree.keySet()) {
            if (!folder.equals(root)) index.folder(folder.toString().substring(root.toString().length()+1), folder);
        }
        for (Map.Entry<String, SafDocuments.Document> book : books.entrySet()) index.file(book.getKey(), book.getValue().book());
        index.save();
        assertTrue(SafStateStore.get(context, "CalibreDiscovery").contains(root.toString()));
        queries = 0;
    }
    private void sql(String sql) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database, null)) { db.execSQL(sql); }
        sourceRevision++;
    }

    @Test public void unchanged500BooksOnlyListsRootAndExtractsNothing() throws Exception {
        baseline();
        List<FileMeta> result = new ArrayList<>(), extracted = new ArrayList<>();
        assertTrue(open().discoverCached(result, extracted::add));
        assertEquals(500, result.size()); assertTrue(extracted.isEmpty()); assertEquals(1, queries);
        assertEquals(500, result.stream().map(FileMeta::getPath).distinct().count());
    }
    @Test public void completeScannerTraversalBuildsReusableIndex() throws Exception {
        CalibreLibraryIndex index = open();
        List<FileMeta> scanned = new ArrayList<>();
        Map<String, com.foobnix.pdf.info.SafOpfRegistry.Entry> sidecars = new HashMap<>();
        CalibreIndexedDiscovery.collect(root, "", scanned, sidecars, stopped::get,
                (folder, cancelled) -> list(folder), index, (batch, entries) -> {});
        assertEquals(500, scanned.size());
        assertTrue("Full discovery should list every book folder", queries > 500);
        index.save();
        queries = 0;
        List<FileMeta> cached = new ArrayList<>();
        assertTrue(open().discoverCached(cached, row -> fail("Unchanged book extracted")));
        assertEquals(500, cached.size());
        assertEquals(1, queries);
    }
    @Test public void additionOnlyListsRootAuthorAndNewBookFolder() throws Exception {
        baseline();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database, null)) { add(db, 500); }
        sourceRevision++;
        List<FileMeta> result = new ArrayList<>(), extracted = new ArrayList<>();
        CalibreLibraryIndex index = open();
        assertTrue(index.discoverCached(result, extracted::add));
        assertEquals(501, result.size()); assertEquals(1, extracted.size()); assertEquals(3, queries);
        assertEquals(uri(relative(500)).toString(), extracted.get(0).getPath());
        assertTrue(index.changedPaths().contains(extracted.get(0).getPath()));
        index.save(); queries = 0;
        assertTrue(open().discoverCached(new ArrayList<>(), book -> fail("Addition extracted twice")));
        assertEquals(1, queries);
    }
    @Test public void metadataChangeForcesExtractionDespiteIdenticalFileRevision() throws Exception {
        baseline(); sql("UPDATE books SET last_modified='revision-2' WHERE id=7");
        List<FileMeta> extracted = new ArrayList<>(); CalibreLibraryIndex index = open();
        assertTrue(index.discoverCached(new ArrayList<>(), extracted::add));
        assertEquals(1, extracted.size()); assertEquals(2, queries);
        assertEquals(Long.valueOf(1000), extracted.get(0).getDate());
        assertTrue(index.changedPaths().contains(uri(relative(7)).toString()));
    }
    @Test public void coverChangeInvalidatesBookRevision() throws Exception {
        baseline(); sql("UPDATE books SET has_cover=0 WHERE id=7");
        List<FileMeta> extracted = new ArrayList<>();
        assertTrue(open().discoverCached(new ArrayList<>(), extracted::add));
        assertEquals(1, extracted.size()); assertEquals(2, queries);
    }
    @Test public void formatSizeChangeInvalidatesEvenWithoutMetadataTimestampChange() throws Exception {
        baseline(); sql("UPDATE data SET uncompressed_size=200 WHERE book=7");
        List<FileMeta> extracted = new ArrayList<>();
        assertTrue(open().discoverCached(new ArrayList<>(), extracted::add));
        assertEquals(1, extracted.size());
    }
    @Test public void removalDropsOnlyRemovedBookWithoutVisitingFolders() throws Exception {
        baseline(); sql("DELETE FROM books WHERE id=7");
        List<FileMeta> result = new ArrayList<>();
        assertTrue(open().discoverCached(result, book -> fail("Unchanged book extracted")));
        assertEquals(499, result.size()); assertEquals(1, queries);
        assertFalse(result.stream().anyMatch(book -> book.getPath().equals(uri(relative(7)).toString())));
    }
    @Test public void unavailableNewFormatFailsWithoutPublishingPartialReplacement() throws Exception {
        baseline(); sql("UPDATE data SET name='Missing' WHERE book=7");
        List<FileMeta> result = new ArrayList<>(), extracted = new ArrayList<>();
        CalibreLibraryIndex index = open();
        assertThrows(IOException.class, () -> index.discoverCached(result, extracted::add));
        assertTrue(result.isEmpty()); assertTrue(extracted.isEmpty());
    }
    @Test public void failedFastPathCannotSaveAnOldBookAfterFullFallback() throws Exception {
        baseline();
        tree.get(uri(folder(7))).clear(); // Removed from the provider, not yet from metadata.db.
        sql("UPDATE data SET name='Missing' WHERE book=8"); // Force cached discovery to fail.
        CalibreLibraryIndex index = open();
        assertThrows(IOException.class,
                () -> index.discoverCached(new ArrayList<>(), row -> {}));
        index.resetForFullTraversal();
        List<FileMeta> complete = new ArrayList<>();
        CalibreIndexedDiscovery.collect(root, "", complete, new HashMap<>(), stopped::get,
                (folder, cancelled) -> list(folder), index, (batch, entries) -> {});
        assertFalse(complete.stream().anyMatch(row -> row.getPath().equals(uri(relative(7)).toString())));
        index.save();
        List<FileMeta> reopened = new ArrayList<>();
        assertTrue(open().discoverCached(reopened, row -> {}));
        assertFalse("Stale cached book must not be resurrected",
                reopened.stream().anyMatch(row -> row.getPath().equals(uri(relative(7)).toString())));
    }
    @Test public void inspectedMissingOpfClearsOldRegistryAndStemOpfIsFound() throws Exception {
        String removed = uri(relative(7)).toString();
        Uri oldOpf = uri(folder(7) + "/metadata.opf");
        tree.get(uri(folder(7))).add(new SafDocuments.Document(
                "metadata.opf", oldOpf, false, 20L, 1L));
        baseline();
        SafOpfRegistry.register(removed, new SafOpfRegistry.Entry(oldOpf,
                Collections.singletonMap("metadata.opf", oldOpf), "old"));
        try {
            tree.get(uri(folder(7))).removeIf(doc -> "metadata.opf".equals(doc.name));
            Uri stemOpf = uri(folder(8) + "/Book8.opf");
            tree.get(uri(folder(8))).add(new SafDocuments.Document(
                    "Book8.opf", stemOpf, false, 20L, 2L));
            sql("UPDATE books SET last_modified='revision-2' WHERE id IN (7,8)");
            CalibreLibraryIndex index = open();
            List<FileMeta> books = new ArrayList<>();
            assertTrue(index.discoverCached(books, row -> {}));
            Map<String, SafOpfRegistry.Entry> resolved = index.sidecarsWithUninspectedFallback(books);
            assertFalse("A freshly inspected absent OPF must not use the old entry",
                    resolved.containsKey(removed));
            assertEquals(stemOpf, resolved.get(uri(relative(8)).toString()).opfUri);
            SearchAllBooksWorker.updateCompletedSidecars(context, books, resolved);
            assertNull(SafOpfRegistry.get(removed));
            assertFalse("Removed OPF must disappear from the persistent registry",
                    SafStateStore.get(context, "SafSidecars").contains(removed));
        } finally {
            SafOpfRegistry.unregister(removed);
            SafOpfRegistry.unregister(uri(relative(8)).toString());
            target.deleteSharedPreferences(namespace + "-SafSidecars");
        }
    }
    @Test public void savedManifestCannotAcknowledgeUnfinishedMetadataRefresh() throws Exception {
        baseline();
        String path = uri(relative(7)).toString();
        String settings = MetadataRefreshPolicy.settingsKey();
        String key = "fixture|" + path;
        SafStateStore applied = SafStateStore.get(context, "ScanMetadataRevisions");
        CalibreLibraryIndex oldIndex = open();
        List<FileMeta> oldRows = new ArrayList<>();
        assertTrue(oldIndex.discoverCached(oldRows, row -> {}));
        FileMeta known = oldRows.stream().filter(row -> path.equals(row.getPath())).findFirst().get();
        known.setState(FileMetaCore.STATE_FULL);
        known.setTitle("Known metadata");
        AppDB.get().saveAll(Collections.singletonList(known));
        String oldRevision = MetadataRefreshPolicy.revision(known, null, settings,
                oldIndex.metadataRevisions().get(path));
        applied.edit().putString(key, oldRevision).commit();
        try {
            sql("UPDATE books SET last_modified='revision-2' WHERE id=7");
            CalibreLibraryIndex interrupted = open();
            assertTrue(interrupted.discoverCached(new ArrayList<>(), row -> {}));
            interrupted.save(); // Discovery checkpoint precedes metadata extraction.

            CalibreLibraryIndex restarted = open();
            List<FileMeta> rows = new ArrayList<>();
            assertTrue(restarted.discoverCached(rows, row -> {}));
            FileMeta found = rows.stream().filter(row -> path.equals(row.getPath())).findFirst().get();
            String required = MetadataRefreshPolicy.revision(found, null, settings,
                    restarted.metadataRevisions().get(path));
            assertNotEquals(oldRevision, required);
            assertTrue(MetadataRefreshPolicy.needsExtraction(AppDB.get().load(path),
                    applied.getString(key, null), required));

            FileMeta extracted = new FileMeta(path);
            extracted.setTitle("New metadata");
            extracted.setState(FileMetaCore.STATE_FULL);
            long owner = ScanOwnership.claim();
            assertTrue(SearchAllBooksWorker.publishMetadataResult(owner, () -> false,
                    extracted, AppDB.get().load(path), false, applied, key, required, settings));
            assertEquals(oldRevision, applied.getString(key, null));
            assertTrue(MetadataRefreshPolicy.needsExtraction(AppDB.get().load(path),
                    applied.getString(key, null), required));

            assertTrue(SearchAllBooksWorker.publishMetadataResult(owner, () -> false,
                    extracted, AppDB.get().load(path), true, applied, key, required, settings));
            assertEquals(required, applied.getString(key, null));
            assertFalse(MetadataRefreshPolicy.needsExtraction(AppDB.get().load(path),
                    applied.getString(key, null), required));
        } finally {
            AppDB.get().deleteBy(path);
            target.deleteSharedPreferences(namespace + "-ScanMetadataRevisions");
        }
    }
    @Test public void changedPropertiesWithUnchangedDatabaseBodyRefusesFastPath() throws Exception {
        baseline(); sourceRevision++;
        List<FileMeta> result = new ArrayList<>();
        assertFalse(open().discoverCached(result, book -> fail("Stale stream published")));
        assertTrue(result.isEmpty()); assertEquals(1, queries);
    }
    @Test public void advertisedSizeMismatchRejectsStaleDatabase() throws Exception {
        advertisedSize = database.length() + 4096;
        assertThrows(IOException.class, this::open);
    }
    @Test public void genericSafRootDoesNotBecomeCalibreLibrary() throws Exception {
        hasManifest = false; assertNull(open()); assertEquals(1, queries);
    }
    @Test public void corruptCacheFallsBackWithoutPublishingRows() throws Exception {
        baseline();
        SafStateStore.get(context, "CalibreDiscovery").edit().putString(root.toString(), "{broken").commit();
        List<FileMeta> result = new ArrayList<>();
        assertFalse(open().discoverCached(result, book -> fail("Corrupt index published")));
        assertTrue(result.isEmpty());
        assertFalse(SafStateStore.get(context, "CalibreDiscovery").contains(root.toString()));
    }
    @Test public void partialFullScanCannotSaveAnIndexThatHidesBooks() throws Exception {
        CalibreLibraryIndex index = open(); index.file(relative(0), books.get(relative(0)).book()); index.save();
        assertFalse(SafStateStore.get(context, "CalibreDiscovery").contains(root.toString()));
    }

    private void recordTree(CalibreLibraryIndex index, Uri parent, String relative, List<FileMeta> result) throws Exception {
        index.folder(relative, parent);
        for (SafDocuments.Document document : list(parent)) {
            String path = relative.isEmpty() ? document.name : relative + "/" + document.name;
            if (document.directory) recordTree(index, document.uri, path, result);
            else if (path.endsWith(".epub")) {
                FileMeta row = document.book(); index.file(path, row); result.add(row);
            }
        }
    }

    @Test public void cachedBookFromOlderTreeGrantUsesLogicalDocumentIdentity() throws Exception {
        CalibreLibraryIndex index = open();
        recordTree(index, root, "", new ArrayList<>());
        index.save();
        SafStateStore prefs = SafStateStore.get(context, "CalibreDiscovery");
        org.json.JSONObject saved = new org.json.JSONObject(prefs.getString(root.toString(), ""));
        Uri oldGrant = DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri("fixture", "parent"), "book:unchanged");
        saved.getJSONObject("files").getJSONObject(relative(0)).put("uri", oldGrant.toString());
        prefs.edit().putString(root.toString(), saved.toString()).commit();

        CalibreLibraryIndex reopened = open();
        List<FileMeta> rows = new ArrayList<>();
        assertTrue(reopened.discoverCached(rows, row -> {}));
        assertTrue(rows.stream().anyMatch(row ->
                com.foobnix.pdf.info.SafDocumentIdentity.canonical(oldGrant).toString()
                        .equals(row.getPath())));
    }

    @Test public void mismatchedBookRechecksItsAuthorWithoutDiscardingOther450Books() throws Exception {
        SafDocuments.Document renamed = new SafDocuments.Document("Renamed.epub", uri(folder(0) + "/Renamed.epub"), false, 100L, 1000L);
        tree.put(uri(folder(0)), new ArrayList<>(Collections.singletonList(renamed)));
        CalibreLibraryIndex index = open(); recordTree(index, root, "", new ArrayList<>()); index.save();
        assertTrue(SafStateStore.get(context, "CalibreDiscovery").contains(root.toString()));
        queries = 0; index = open(); List<FileMeta> rows = new ArrayList<>();
        assertTrue(index.discoverCached(rows, row -> fail("Unchanged matching book extracted")));
        assertEquals(450, rows.size()); assertEquals(1, queries);
        assertEquals(Collections.singletonMap("Author0", uri("Author0")), index.foldersToCheck());
        recordTree(index, uri("Author0"), "Author0", rows); index.save();
        assertEquals(500, rows.size());
        assertTrue(rows.stream().anyMatch(row -> row.getPath().equals(renamed.uri.toString())));
        assertFalse(rows.stream().anyMatch(row -> row.getPath().equals(uri(relative(0)).toString())));
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database, null)) { add(db, 500); }
        sql("UPDATE books SET last_modified='revision-2' WHERE id=10");
        CalibreLibraryIndex again = open(); List<FileMeta> next = new ArrayList<>();
        assertTrue(again.discoverCached(next, row -> fail("Unchanged matching book extracted")));
        recordTree(again, uri("Author0"), "Author0", next);
        assertEquals(501, next.size());
        assertTrue(next.stream().anyMatch(row -> row.getPath().equals(uri(relative(500)).toString())));
        assertTrue("Metadata revisions still extract inside checked author folders",
                again.changedPaths().contains(uri(relative(10)).toString()));
        tree.put(uri(folder(0)), new ArrayList<>(Collections.singletonList(books.get(relative(0)))));
        CalibreLibraryIndex repaired = open(); List<FileMeta> repairedRows = new ArrayList<>();
        assertTrue(repaired.discoverCached(repairedRows, row -> {}));
        recordTree(repaired, uri("Author0"), "Author0", repairedRows); repaired.save();
        assertEquals(501, repairedRows.size());
        queries = 0; CalibreLibraryIndex finalIndex = open();
        assertTrue(finalIndex.discoverCached(new ArrayList<>(), row -> fail("Unchanged book extracted")));
        assertTrue(finalIndex.foldersToCheck().isEmpty()); assertEquals(1, queries);
    }

    @Test public void newUnindexedAuthorFolderIsCheckedAlongsideCachedBooks() throws Exception {
        baseline(); Uri manual = uri("Manual");
        tree.get(root).add(dir("Manual", manual));
        tree.put(manual, new ArrayList<>(Collections.singletonList(
                new SafDocuments.Document("Extra.epub", uri("Manual/Extra.epub"), false, 100L, 1000L))));
        CalibreLibraryIndex index = open(); List<FileMeta> rows = new ArrayList<>();
        assertTrue(index.discoverCached(rows, row -> fail("Unchanged book extracted")));
        assertEquals(500, rows.size()); assertEquals(Collections.singletonMap("Manual", manual), index.foldersToCheck());
        recordTree(index, manual, "Manual", rows); index.save(); assertEquals(501, rows.size());
        CalibreLibraryIndex next = open(); assertTrue(next.discoverCached(new ArrayList<>(), row -> {}));
        assertEquals(Collections.singletonMap("Manual", manual), next.foldersToCheck());
    }

    @Test public void partialAuthorCheckCannotReplacePreviousVerifiedIndex() throws Exception {
        baseline();
        String before = SafStateStore.get(context, "CalibreDiscovery").getString(root.toString(), "");
        CalibreLibraryIndex index = open();
        assertTrue(index.discoverCached(new ArrayList<>(), row -> {}));
        index.file("Author0/Extra.epub", new FileMeta(uri("Author0/Extra.epub").toString()));
        index.save();
        assertEquals(before, SafStateStore.get(context, "CalibreDiscovery").getString(root.toString(), ""));
    }
    @Test public void newLooseRootBookCannotBeHiddenByAnExistingCalibreIndex() throws Exception {
        baseline();
        tree.get(root).add(new SafDocuments.Document("Loose.epub", uri("Loose.epub"), false, 100L, 1000L));
        List<FileMeta> rows = new ArrayList<>();
        assertFalse(open().discoverCached(rows, row -> fail("Partial library published")));
        assertTrue(rows.isEmpty());
    }
    @Test public void extraUnmanagedBookPreventsCalibreOnlyFastPath() throws Exception {
        baseline(); CalibreLibraryIndex index = open();
        for (Map.Entry<String, SafDocuments.Document> book : books.entrySet()) index.file(book.getKey(), book.getValue().book());
        index.file("loose.epub", new FileMeta(uri("loose.epub").toString())); index.save();
        assertFalse(SafStateStore.get(context, "CalibreDiscovery").contains(root.toString()));
    }
    @Test public void cancellationDoesNotPublishOrReplaceCachedLibrary() throws Exception {
        baseline(); CalibreLibraryIndex index = open(); stopped.set(true);
        List<FileMeta> result = new ArrayList<>();
        assertThrows(IOException.class, () -> index.discoverCached(result, book -> fail("Cancelled scan published")));
        assertTrue(result.isEmpty());
        assertTrue(SafStateStore.get(context, "CalibreDiscovery").contains(root.toString()));
    }
    @Test public void manifestReadDoesNotModifySourceAndRemovesPrivateSnapshot() throws Exception {
        long size = database.length(), modified = database.lastModified();
        File[] before = context.getCacheDir().listFiles((dir, name) -> name.startsWith("calibre-manifest-"));
        CalibreManifest manifest = CalibreManifest.read(context, Uri.fromFile(database), () -> false, Collections.singletonList(".epub"));
        assertEquals(500, manifest.books.size()); assertEquals(64, manifest.fingerprint.length());
        assertEquals(size, database.length()); assertEquals(modified, database.lastModified());
        assertEquals(before.length, context.getCacheDir().listFiles((dir, name) -> name.startsWith("calibre-manifest-")).length);
    }

    @Test public void convertedFormatRefreshesOnlyItsExistingBookFolder() throws Exception {
        baseline();
        sql("INSERT INTO data VALUES (7, 'Book7', 'PDF', 200)");
        tree.get(uri(folder(7))).add(new SafDocuments.Document("Book7.pdf", uri(folder(7) + "/Book7.pdf"), false, 200L, 1000L));
        List<FileMeta> result = new ArrayList<>(), extracted = new ArrayList<>();
        assertTrue(open().discoverCached(result, extracted::add));
        assertEquals(501, result.size()); assertEquals(2, extracted.size()); assertEquals(2, queries);
    }

    @Test public void unsafeDatabasePathsAreRejected() {
        sql("UPDATE books SET path='../Outside' WHERE id=7");
        assertThrows(IOException.class, this::open);
    }

    @Test public void invalidSqliteCannotBeMistakenForAnEmptyCalibreLibrary() throws Exception {
        java.nio.file.Files.write(database.toPath(), new byte[]{1,2,3});
        assertThrows(IOException.class, this::open);
    }

    @Test public void authorFolderReplacementCannotReuseObsoleteDocumentUris() throws Exception {
        baseline();
        List<SafDocuments.Document> rootDocs = tree.get(root);
        rootDocs.removeIf(document -> document.name.equals("Author0"));
        rootDocs.add(dir("Author0", uri("replacement-author0")));
        List<FileMeta> result = new ArrayList<>();
        assertFalse("Changed author identity requires rebuilding its child identities",
                open().discoverCached(result, book -> fail("Obsolete identity published")));
        assertTrue(result.isEmpty());
    }

    @Test public void manifestHonorsSelectedFormatsRatherThanIndexingEveryCalibreFile() throws Exception {
        sql("INSERT INTO data VALUES (7, 'Book7', 'PDF', 200)");
        CalibreManifest epub = CalibreManifest.read(context, Uri.fromFile(database), () -> false, Collections.singletonList(".epub"));
        CalibreManifest pdf = CalibreManifest.read(context, Uri.fromFile(database), () -> false, Collections.singletonList(".pdf"));
        assertEquals(500, epub.paths().size()); assertFalse(epub.paths().contains(folder(7) + "/Book7.pdf"));
        assertEquals(Collections.singleton(folder(7) + "/Book7.pdf"), pdf.paths());
    }
    @Test public void manifestRejectsViewsBeforeExecutingTheirQuery() throws Exception {
        sql("ALTER TABLE books RENAME TO real_books");
        sql("CREATE VIEW books AS SELECT * FROM real_books");
        assertThrows(java.io.IOException.class, () -> CalibreManifest.read(context, Uri.fromFile(database), () -> false));
    }

    @Test public void manifestRejectsTrailingParentSegment() throws Exception {
        assertFalse(CalibreManifest.safeRelativePath("Author/.."));
        assertFalse(CalibreManifest.safeRelativePath("Author/./Book"));
        assertTrue(CalibreManifest.safeRelativePath("Author/Book"));
        sql("UPDATE books SET path='Author/..' WHERE id=7");
        assertThrows(java.io.IOException.class, () -> CalibreManifest.read(context, Uri.fromFile(database), () -> false));
    }
}
