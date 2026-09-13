package com.foobnix.work;

import com.foobnix.pdf.info.SafFileLink;
import com.bumptech.glide.Priority;
import android.util.Log;
import android.os.SystemClock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.io.IOException;
import static com.foobnix.pdf.info.AppsConfig.SEARCH_FRAGMENT_WORKER_NAME;
import static com.foobnix.pdf.info.AppsConfig.WORKER_POLICY;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.CalirbeExtractor;
import com.foobnix.ext.EbookMeta;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.SimpleMeta;
import com.foobnix.model.Tags2;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.Prefs;
import com.foobnix.pdf.info.SafOpfRegistry;
import com.foobnix.pdf.info.Tunables;
import com.foobnix.pdf.info.io.SearchCore;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import org.ebookdroid.common.settings.books.SharedBooks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;


public class SearchAllBooksWorker extends MessageWorker {

    private volatile String folderScanStatus;

    @Override protected void sendProggressMessage(java.util.Collection<?> books) {
        super.sendProggressMessage(books);
        if (folderScanStatus != null) sendTextMessage(folderScanStatus);
    }


    private ThreadPoolExecutor eagerMetadata;
    private Set<String> knownPaths;
    private Set<SimpleMeta> excludedAtStart;
    private Set<String> syncTitlesAtStart;
    private final Set<String> eagerCompleted = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean discoveriesPublished = new AtomicBoolean();
    private long lastLibraryPublish;

    Handler handler;
    List<FileMeta> itemsMeta;

    public SearchAllBooksWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        handler = new Handler(Looper.getMainLooper());

    }

    private final Set<String> changedCalibrePaths = ConcurrentHashMap.newKeySet();
    private final List<CalibreLibraryIndex> calibreIndices = new ArrayList<>();

    public static void run(Context context) {


        OneTimeWorkRequest workRequest = new OneTimeWorkRequest
                .Builder(SearchAllBooksWorker.class).build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(SEARCH_FRAGMENT_WORKER_NAME, WORKER_POLICY, workRequest);
    }


    public boolean doWorkInner() throws IOException, InterruptedException {
        LOG.d("worker-starts", "SearchAllBooksWorker");
        String errorID = AppProfile.getCurrent();
        Prefs.get().put(errorID, 0);
        try {
            Tags2.migration();
            // Synchronized because SafSearchTask (fork-join) writes to it from worker
            // threads in parallel with the timer reading its size for progress display.
            itemsMeta = Collections.synchronizedList(new LinkedList<FileMeta>());

            AppProfile.init(getApplicationContext());

            ImageExtractor.clearErrors();
            // Incremental: keep IMG disc/memory cache — covers of unchanged books stay valid.

            SafOpfRegistry.restore(getApplicationContext());

            // Snapshot existing DB rows so we can diff against discovery and reuse rows
            // (preserving user fields like stars, tags, annotations).
            Map<String, FileMeta> existingByPath = new HashMap<>();
            for (FileMeta m : AppDB.get().getAll()) {
                existingByPath.put(m.getPath(), m);
            }

            knownPaths = ConcurrentHashMap.newKeySet();
            knownPaths.addAll(existingByPath.keySet());
            excludedAtStart = new HashSet<>(AppData.get().getAllExcluded());
            syncTitlesAtStart = new HashSet<>();
            for (FileMeta synced : AppData.get().getAllSyncBooks()) syncTitlesAtStart.add(synced.getTitle());
            int eagerThreads = Math.max(1, Tunables.METADATA_EXTRACTION_PARALLELISM);
            eagerMetadata = new ThreadPoolExecutor(eagerThreads, eagerThreads,
                    0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(16));
            handler.post(timer);
            LOG.d("SearchAllBooksWorker", "searchPaths-all", 3, BookCSS.get().searchPathsJson);
            for (final String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
                if (path == null) continue;
                if (ExtUtils.isExteralSD(path)) {
                    LOG.d("SAF Search in: " + path);
                    searchSAF(getApplicationContext(), Uri.parse(path), itemsMeta);
                } else {
                    final File root = new File(path);
                    if (root.isDirectory()) {
                        LOG.d("Search in: " + root.getPath());
                        SearchCore.search(itemsMeta, root, ExtUtils.seachExts);
                    }
                }
                if (isStopped()) {
                    return false;
                }
            }

            if (itemsMeta.isEmpty()) {
                File downloadsDir = AppSP.get().getTempDownloadBooks(getApplicationContext());
                downloadsDir.mkdirs();

                try {
                    String[] books = getApplicationContext().getAssets().list("books");
                    for (String book : books) {
                        File outFile = new File(downloadsDir, book);
                        FileOutputStream out = new FileOutputStream(outFile);
                        IOUtils.copyClose(getApplicationContext().getAssets().open("books/" + book), out);
                        LOG.d("copyBook", book, outFile);
                    }
                } catch (Exception e) {
                    LOG.e(e);
                }

                SearchCore.search(itemsMeta, downloadsDir, ExtUtils.seachExts);
            }

            if (AppState.get().isExperimental) {
                if (itemsMeta.isEmpty()) {
                    File path = AppProfile.DOWNLOADS_DIR;
                    BookCSS.get().searchPathsJson = JsonDB.set(List.of(path.getPath()));
                    SearchCore.search(itemsMeta, AppProfile.DOWNLOADS_DIR, ExtUtils.seachExts);
                    LOG.d("SearchAllBooksWorker", "Files-emtpy", "DOWNLOADS_DIR");
                }
            }


            // New-book extraction overlaps traversal, with a bounded queue. Drain before merging
            // so discovery and extraction cannot mutate the same row during the final pass.
            eagerMetadata.shutdown();
            while (!eagerMetadata.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                if (isStopped()) return false;
            }

            for (FileMeta meta : itemsMeta) {
                meta.setIsSearchBook(true);
            }

            final List<SimpleMeta> allExcluded = AppData.get().getAllExcluded();

            if (TxtUtils.isListNotEmpty(allExcluded)) {
                for (FileMeta meta : itemsMeta) {
                    if (isStopped()) {
                        return false;
                    }
                    if (allExcluded.contains(SimpleMeta.SyncSimpleMeta(meta.getPath()))) {
                        meta.setIsSearchBook(false);
                    }
                }
            }

            final List<FileMeta> allSyncBooks = AppData.get().getAllSyncBooks();
            if (TxtUtils.isListNotEmpty(allSyncBooks)) {
                for (FileMeta meta : itemsMeta) {
                    for (FileMeta sync : allSyncBooks) {
                        if (isStopped()) {
                            return false;
                        }
                        if (meta.getTitle() != null
                                && meta.getTitle().equals(sync.getTitle())
                                && !meta.getPath().equals(sync.getPath())) {
                            meta.setIsSearchBook(false);
                            LOG.d("Worker", "remove-dublicate", meta.getPath());
                        }
                    }

                }
            }


            itemsMeta.addAll(AppData.get().getAllFavoriteFiles(false));
            itemsMeta.addAll(AppData.get().getAllFavoriteFolders());

            LibraryMerge.Result merge = LibraryMerge.merge(itemsMeta, existingByPath,
                    changedCalibrePaths, eagerCompleted);
            List<FileMeta> merged = merge.books;
            List<FileMeta> toProcess = merge.extract;
            List<FileMeta> toMarkRemoved = merge.removed;

            itemsMeta = merged;

            AppDB.get().saveAll(merged);
            if (!toMarkRemoved.isEmpty()) {
                AppDB.get().updateAll(toMarkRemoved);
            }

            handler.removeCallbacks(timer);

            sendLibraryUpdated();

            handler.post(refreshTimer);

            LOG.d("SearchAllBooksWorker", "incremental toProcess=", toProcess.size(),
                    "of total=", merged.size(), "removed=", toMarkRemoved.size());

            // Metadata extraction in parallel. Each per-book task is independent aside from a
            // ReentrantLock inside CacheZipUtils that serializes actual .zip/.okular unpacks;
            // PDF/EPUB/MOBI/FB2/DJVU extractors don't hit that lock. Kept small so SAF
            // extractions don't hammer the remote provider.
            int threads = Math.max(1, Tunables.METADATA_EXTRACTION_PARALLELISM);
            LOG.d("Metadata extraction parallelism", threads);
            try {
                boolean finished = BoundedTasks.run(toProcess, threads, this::isStopped, meta -> {
                    try {
                        extractMetaForBook(meta);
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                    return meta;
                }, meta -> {
                    AppDB.get().update(meta);
                    sendMetadataUpdated(Collections.singletonList(meta.getPath()));
                });
                if (!finished) return false;
            } catch (ExecutionException e) {
                throw new IOException("Metadata extraction failed", e.getCause());
            }

            long progressStarted = SystemClock.elapsedRealtime();
            SharedBooks.updateProgress(merged, true, -1);
            Log.i("SafScan", "Reading progress ms=" + (SystemClock.elapsedRealtime() - progressStarted));


            itemsMeta.clear();

            handler.removeCallbacks(refreshTimer);
            CacheZipUtils.CacheDir.ZipService.removeCacheContent();

            if (isStopped()) {
                return false;
            }

            Clouds.get().syncronizeGet();

            if (isStopped()) {
                return false;
            }

            //TagData.restoreTags();
            Tags2.updateTagsDB();


            List<FileMeta> allNone = AppDB.get().getAllByState(FileMetaCore.STATE_NONE);
            for (FileMeta m : allNone) {
                if (isStopped()) {
                    return false;
                }
                LOG.d("BooksService-createMetaIfNeedSafe-service", m.getTitle(), m.getPath(), m.getTitle());
                FileMetaCore.createMetaIfNeedSafe(m.getPath(), false);
            }

            if (isStopped()) {
                return false;
            }
            updateBookAnnotations();
            for (CalibreLibraryIndex index : calibreIndices) index.save();
            SafOpfRegistry.save(getApplicationContext());
            CoverWarmupWorker.run(getApplicationContext());
        } finally {
            if (eagerMetadata != null) eagerMetadata.shutdownNow();
            Prefs.get().remove(errorID, 0);
            handler.removeCallbacksAndMessages(null);
        }
        return true;


    }

    private void publishNewSafBook(FileMeta meta) {
        if (!knownPaths.add(meta.getPath())
                || excludedAtStart.contains(SimpleMeta.SyncSimpleMeta(meta.getPath()))
                || syncTitlesAtStart.contains(meta.getTitle())) return;
        try {
            eagerMetadata.execute(() -> {
                if (isStopped()) return;
                meta.setIsSearchBook(true);
                meta.setState(FileMetaCore.STATE_BASIC);
                AppDB.get().save(meta);
                discoveriesPublished.set(true);
                // Start the cover independently: an OPF cover avoids downloading the book.
                handler.post(() -> {
                    if (!isStopped() && AppState.get().isShowImages) {
                        IMG.getCoverPageWithEffect(getApplicationContext(), meta, null)
                                .priority(Priority.NORMAL).preload();
                    }
                });
                try {
                    extractMetaForBook(meta);
                    if (Integer.valueOf(FileMetaCore.STATE_FULL).equals(meta.getState())) {
                        eagerCompleted.add(meta.getPath());
                    }
                } catch (Exception e) {
                    LOG.e(e);
                }
                if (!isStopped()) {
                    AppDB.get().update(meta);
                    sendMetadataUpdated(Collections.singletonList(meta.getPath()));
                }
            });
        } catch (RejectedExecutionException full) {
            // Leave overflow books to the final extraction pass; discovery never blocks.
        }
    }

    private void extractMetaForBook(FileMeta meta) {
        if (ExtUtils.isExteralSD(meta.getPath())) {
            // Basic size/date/pathTxt already came from the SAF listing at discovery time.
            extractSAFMeta(meta);
        } else {
            File file = new File(meta.getPath());
            FileMetaCore.get().upadteBasicMeta(meta, file);
            EbookMeta ebookMeta = FileMetaCore.get()
                    .getEbookMeta(meta.getPath(), CacheZipUtils.CacheDir.ZipService, true);
            FileMetaCore.get().udpateFullMeta(meta, ebookMeta);
        }
    }

    private void extractSAFMeta(FileMeta fileMeta) {
        String displayName = TxtUtils.isNotEmpty(fileMeta.getPathTxt()) ? fileMeta.getPathTxt() : fileMeta.getTitle();
        if (TxtUtils.isEmpty(displayName)) return;

        if (AppState.get().isUseCalibreOpf) {
            SafOpfRegistry.Entry entry = SafOpfRegistry.get(fileMeta.getPath());
            if (entry != null && extractCalibreOpfMeta(fileMeta, entry, displayName)) {
                return;
            }
        }

        try (SafFileLink link = new SafFileLink(
                getApplicationContext(), Uri.parse(fileMeta.getPath()), displayName)) {
            EbookMeta ebookMeta = FileMetaCore.get().getEbookMeta(
                    link.file.getPath(), CacheZipUtils.CacheDir.ZipService, true);
            FileMetaCore.get().udpateFullMeta(fileMeta, ebookMeta);
            if (TxtUtils.isEmpty(fileMeta.getTitle())) fileMeta.setTitle(displayName);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private boolean extractCalibreOpfMeta(FileMeta fileMeta, SafOpfRegistry.Entry entry, String displayName) {
        ContentResolver cr = getApplicationContext().getContentResolver();
        try (InputStream in = cr.openInputStream(entry.opfUri)) {
            if (in == null) return false;
            // Publish the inexpensive XML metadata without waiting for a separate SAF cover read.
            EbookMeta ebookMeta = CalirbeExtractor.getBookMetaInformationFromStream(in, null);
            if (ebookMeta == null) return false;
            ebookMeta.setUnzipPath(fileMeta.getPath());
            FileMetaCore.get().udpateFullMeta(fileMeta, ebookMeta);
            if (TxtUtils.isEmpty(fileMeta.getTitle())) {
                fileMeta.setTitle(displayName);
            }
            LOG.d("SAF Calibre meta applied", displayName, "cover=" + (ebookMeta.coverImage != null));
            return true;
        } catch (Exception e) {
            LOG.e(e);
            return false;
        }
    }

    private void searchSAF(Context context, Uri rootUri, List<FileMeta> items) throws IOException, InterruptedException {
        // SAF folder listings are network round-trips. Fan out with fork-join so subdirectory
        // queries run in parallel (fork-join avoids the classic thread-starvation deadlock
        // that a fixed pool would hit when every worker is waiting on its children).
        CalibreLibraryIndex index = null;
        if (AppState.get().isUseCalibreDatabaseForScan) {
            try {
                index = CalibreLibraryIndex.open(context, rootUri, this::isStopped);
            } catch (IOException unavailable) {
                if (isStopped()) throw unavailable;
                context.getSharedPreferences("CalibreDiscovery", Context.MODE_PRIVATE).edit()
                        .remove(rootUri.toString()).apply();
                Log.w("SafScan", "Calibre database unavailable; scanning folders", unavailable);
                folderScanStatus = context.getString(com.foobnix.pdf.info.R.string.calibre_scan_fallback);
                sendTextMessage(folderScanStatus);
            }
        } else {
            context.getSharedPreferences("CalibreDiscovery", Context.MODE_PRIVATE).edit()
                    .remove(rootUri.toString()).apply();
        }
        if (index != null) {
            if (index.discoverCached(items, this::publishNewSafBook)) {
                Map<String, Uri> folders = index.foldersToCheck();
                if (!folders.isEmpty()) {
                    AtomicBoolean failed = new AtomicBoolean();
                    ForkJoinPool pool = new ForkJoinPool(Math.max(1, Tunables.SAF_DISCOVERY_PARALLELISM));
                    try {
                        List<SafSearchTask> tasks = new ArrayList<>();
                        for (Map.Entry<String, Uri> folder : folders.entrySet()) {
                            tasks.add(new SafSearchTask(context, folder.getValue(), items, failed,
                                    folder.getKey(), index));
                        }
                        pool.invoke(new RecursiveAction() {
                            @Override protected void compute() { invokeAll(tasks); }
                        });
                    } finally { pool.shutdown(); }
                    if (failed.get()) throw new IOException("Incomplete SAF discovery: " + rootUri);
                }
                changedCalibrePaths.addAll(index.changedPaths());
                calibreIndices.add(index);
                return;
            }
            calibreIndices.add(index);
        }
        // Books are published while discovery continues.
        int parallelism = Math.max(1, Tunables.SAF_DISCOVERY_PARALLELISM);
        LOG.d("SAF discovery parallelism", parallelism);
        long scanStarted = SystemClock.elapsedRealtime();
        int startingBooks = items.size();
        Log.i("SafScan", "start parallelism=" + parallelism);
        AtomicBoolean failed = new AtomicBoolean();
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.invoke(new SafSearchTask(context, rootUri, items, failed, "", index));
        } catch (Exception e) {
            failed.set(true);
            LOG.e(e);
        } finally {
            pool.shutdown();
            Log.i("SafScan", "finished ms="
                    + (SystemClock.elapsedRealtime() - scanStarted)
                    + " books=" + (items.size() - startingBooks) + " failed=" + failed.get());
        }
        if (failed.get()) throw new IOException("Incomplete SAF discovery: " + rootUri);
    }

    private final class SafSearchTask extends RecursiveAction {
        private final String relativeFolder;
        private final CalibreLibraryIndex index;
        private final Context context;
        private final Uri parentUri;
        private final List<FileMeta> found;
        private final AtomicBoolean failed;

        SafSearchTask(Context context, Uri parentUri, List<FileMeta> found, AtomicBoolean failed,
                      String relativeFolder, CalibreLibraryIndex index) {
            this.relativeFolder = relativeFolder;
            this.index = index;
            this.failed = failed;
            this.context = context;
            this.parentUri = parentUri;
            this.found = found;
        }

        @Override
        protected void compute() {
            if (isStopped()) return;
            try {
                if (index != null) index.folder(relativeFolder, parentUri);
                List<SafDocuments.Document> children = SafDocuments.list(context, parentUri,
                        SearchAllBooksWorker.this::isStopped);
                Map<String, Uri> siblingByLowerName = new HashMap<>();
                for (SafDocuments.Document child : children) {
                    if (!child.directory && child.name != null) {
                        siblingByLowerName.put(child.name.toLowerCase(Locale.US), child.uri);
                    }
                }

                List<SafSearchTask> subtasks = new ArrayList<>();
                for (SafDocuments.Document child : children) {
                    if (child.directory) {
                        subtasks.add(new SafSearchTask(context, child.uri, found, failed,
                                relativeFolder.isEmpty() ? child.name : relativeFolder + "/" + child.name, index));
                    } else if (SearchCore.endWith(child.name, ExtUtils.seachExts)) {
                        FileMeta meta = child.book();
                        String relativePath = relativeFolder.isEmpty() ? child.name : relativeFolder + "/" + child.name;
                        if (index != null) index.file(relativePath, meta);
                        Uri opfUri = findCalibreOpf(child.name, siblingByLowerName);
                        SafOpfRegistry.Entry previous = SafOpfRegistry.get(child.uri.toString());
                        if (opfUri != null) {
                            String revision = index == null ? "" : index.revision(relativePath);
                            if (revision.isEmpty()) revision = sidecarRevision(children);
                            if (previous == null || !revision.equals(previous.revision)
                                    || !opfUri.equals(previous.opfUri)) {
                                changedCalibrePaths.add(meta.getPath());
                            }
                            SafOpfRegistry.register(child.uri.toString(),
                                    new SafOpfRegistry.Entry(opfUri, siblingByLowerName, revision));
                        } else if (previous != null) {
                            SafOpfRegistry.unregister(child.uri.toString());
                            changedCalibrePaths.add(meta.getPath());
                        }
                        found.add(meta);
                        publishNewSafBook(meta);
                    }
                }
                if (!subtasks.isEmpty()) {
                    invokeAll(subtasks);
                }
            } catch (Exception e) {
                failed.set(true);
                LOG.e(e);
            }
        }
    }

    private static Uri findCalibreOpf(String bookName, Map<String, Uri> siblingByLowerName) {
        if (siblingByLowerName.isEmpty()) return null;
        Uri byBaseName = siblingByLowerName.get(
                ExtUtils.getFileNameWithoutExt(bookName).toLowerCase(Locale.US) + ".opf");
        if (byBaseName != null) return byBaseName;
        return siblingByLowerName.get("metadata.opf");
    }

    private static String sidecarRevision(List<SafDocuments.Document> children) {
        List<String> revisions = new ArrayList<>();
        for (SafDocuments.Document document : children) {
            if (document.directory || document.name == null) continue;
            String name = document.name.toLowerCase(Locale.US);
            if (name.endsWith(".opf") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png")) {
                revisions.add(SidecarRevision.file(name, document.size, document.modified));
            }
        }
        return SidecarRevision.combine(revisions);
    }


    public void updateBookAnnotations() {

        if (AppState.get().isDisplayAnnotation) {
            sendBuildingLibrary();
            LOG.d("updateBookAnnotations begin");
            List<FileMeta> itemsMeta = AppDB.get().getAll();
            for (FileMeta meta : itemsMeta) {
                if (TxtUtils.isEmpty(meta.getAnnotation())) {
                    String bookOverview = FileMetaCore.getBookOverview(meta.getPath());
                    meta.setAnnotation(bookOverview);
                }
            }
            AppDB.get().updateAll(itemsMeta);
            LOG.d("updateBookAnnotations end");
        }

    }

    Runnable timer = new Runnable() {

        @Override
        public void run() {
            if (isStopped()) return;
            LOG.d("timer 2");
            sendProggressMessage(itemsMeta);
            long now = SystemClock.elapsedRealtime();
            if (now - lastLibraryPublish >= 500 && discoveriesPublished.getAndSet(false)) {
                lastLibraryPublish = now;
                sendLibraryUpdated();
            }
            handler.postDelayed(timer, 250);
        }
    };


    Runnable refreshTimer = new Runnable() {

        @Override
        public void run() {
            if (isStopped()) return;
            LOG.d("timer2");
            sendBuildingLibrary();
            handler.postDelayed(refreshTimer, 500);
        }
    };


}
