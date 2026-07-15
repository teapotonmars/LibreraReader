package com.foobnix.work;

import static com.foobnix.pdf.info.AppsConfig.SEARCH_FRAGMENT_WORKER_NAME;
import static com.foobnix.pdf.info.AppsConfig.WORKER_POLICY;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicLong;


public class SearchAllBooksWorker extends MessageWorker {

    private static final AtomicLong safLinkCounter = new AtomicLong();

    Handler handler;
    List<FileMeta> itemsMeta;

    public SearchAllBooksWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        handler = new Handler(Looper.getMainLooper());

    }

    public static void run(Context context) {


        OneTimeWorkRequest workRequest = new OneTimeWorkRequest
                .Builder(SearchAllBooksWorker.class).build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(SEARCH_FRAGMENT_WORKER_NAME, WORKER_POLICY, workRequest);
    }


    public boolean doWorkInner() {
        LOG.d("worker-starts", "SearchAllBooksWorker");
        String errorID = AppProfile.getCurrent();
        Prefs.get().put(errorID, 0);
        ExecutorService executor = null;
        try {
            Tags2.migration();
            itemsMeta = new LinkedList<FileMeta>();

            AppProfile.init(getApplicationContext());

            ImageExtractor.clearErrors();
            // Incremental: keep IMG disc/memory cache — covers of unchanged books stay valid.

            SafOpfRegistry.clear();

            // Snapshot existing DB rows so we can diff against discovery and reuse rows
            // (preserving user fields like stars, tags, annotations).
            Map<String, FileMeta> existingByPath = new HashMap<>();
            for (FileMeta m : AppDB.get().getAll()) {
                existingByPath.put(m.getPath(), m);
            }

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

            // Merge discoveries with existing DB rows. Reused rows preserve user state;
            // for SAF, size/date/pathTxt are refreshed from the current SAF listing.
            Set<String> discoveredPaths = new HashSet<>();
            List<FileMeta> merged = new ArrayList<>(itemsMeta.size());
            List<FileMeta> toProcess = new ArrayList<>();

            for (FileMeta discovered : itemsMeta) {
                discoveredPaths.add(discovered.getPath());
                FileMeta existing = existingByPath.get(discovered.getPath());
                FileMeta row;
                if (existing != null) {
                    existing.setIsSearchBook(discovered.getIsSearchBook());
                    if (ExtUtils.isExteralSD(discovered.getPath())) {
                        if (discovered.getSize() != null) existing.setSize(discovered.getSize());
                        if (discovered.getDate() != null) existing.setDate(discovered.getDate());
                        if (TxtUtils.isNotEmpty(discovered.getPathTxt())) existing.setPathTxt(discovered.getPathTxt());
                    }
                    row = existing;
                } else {
                    row = discovered;
                }
                merged.add(row);
                if (needsFullUpdate(row, existing)) {
                    toProcess.add(row);
                }
            }

            // Books previously in the search set that no longer exist: soft-delete (keep row for
            // user state, drop the search-visible flag).
            List<FileMeta> toMarkRemoved = new ArrayList<>();
            for (Map.Entry<String, FileMeta> e : existingByPath.entrySet()) {
                if (!discoveredPaths.contains(e.getKey())
                        && Boolean.TRUE.equals(e.getValue().getIsSearchBook())) {
                    e.getValue().setIsSearchBook(false);
                    toMarkRemoved.add(e.getValue());
                }
            }

            itemsMeta = merged;

            AppDB.get().saveAll(merged);
            if (!toMarkRemoved.isEmpty()) {
                AppDB.get().updateAll(toMarkRemoved);
            }

            handler.removeCallbacks(timer);

            sendFinishMessage();

            handler.post(refreshTimer);

            LOG.d("SearchAllBooksWorker", "incremental toProcess=", toProcess.size(),
                    "of total=", merged.size(), "removed=", toMarkRemoved.size());

            // Metadata extraction in parallel. Each per-book task is independent aside from a
            // ReentrantLock inside CacheZipUtils that serializes actual .zip/.okular unpacks;
            // PDF/EPUB/MOBI/FB2/DJVU extractors don't hit that lock. Kept small so SAF
            // extractions don't hammer the remote provider.
            int threads = Math.max(1, Tunables.METADATA_EXTRACTION_PARALLELISM);
            LOG.d("Metadata extraction parallelism", threads);
            executor = Executors.newFixedThreadPool(threads);

            List<Future<?>> futures = new ArrayList<>(toProcess.size());
            for (final FileMeta meta : toProcess) {
                futures.add(executor.submit(new Runnable() {
                    @Override public void run() {
                        if (isStopped()) return;
                        try {
                            extractMetaForBook(meta);
                        } catch (Throwable t) {
                            LOG.e(t);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                if (isStopped()) {
                    executor.shutdownNow();
                    return false;
                }
                try { f.get(); } catch (Exception e) { LOG.e(e); }
            }

            SharedBooks.updateProgress(toProcess, true, -1);
            if (!toProcess.isEmpty()) {
                AppDB.get().updateAll(toProcess);
            }


            itemsMeta.clear();

            handler.removeCallbacks(refreshTimer);
            sendFinishMessage();
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
        } finally {
            if (executor != null) executor.shutdownNow();
            Prefs.get().remove(errorID, 0);
            handler.removeCallbacksAndMessages(null);
        }
        return true;


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

    private boolean needsFullUpdate(FileMeta row, FileMeta existing) {
        if (existing == null) return true;
        Integer state = existing.getState();
        if (state == null || state != FileMetaCore.STATE_FULL) return true;
        Long storedSize = existing.getSize();
        Long storedDate = existing.getDate();
        if (storedSize == null || storedDate == null) return true;
        if (ExtUtils.isExteralSD(row.getPath())) {
            Long discSize = row.getSize();
            Long discDate = row.getDate();
            if (discSize != null && !discSize.equals(storedSize)) return true;
            if (discDate != null && !discDate.equals(storedDate)) return true;
            return false;
        }
        File f = new File(row.getPath());
        return f.lastModified() != storedDate || f.length() != storedSize;
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

        ParcelFileDescriptor pfd = null;
        File linkFile = null;
        try {
            Uri uri = Uri.parse(fileMeta.getPath());
            pfd = getApplicationContext().getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return;

            long uniq = safLinkCounter.incrementAndGet();
            linkFile = new File(getApplicationContext().getCacheDir(), "saf_meta_" + uniq + "_" + displayName);
            linkFile.delete();
            Os.symlink("/proc/self/fd/" + pfd.getFd(), linkFile.getAbsolutePath());

            EbookMeta ebookMeta = FileMetaCore.get().getEbookMeta(linkFile.getPath(), CacheZipUtils.CacheDir.ZipService, true);
            FileMetaCore.get().udpateFullMeta(fileMeta, ebookMeta);

            if (TxtUtils.isEmpty(fileMeta.getTitle())) {
                fileMeta.setTitle(displayName);
            }
        } catch (Exception e) {
            LOG.e(e);
        } finally {
            if (linkFile != null) linkFile.delete();
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean extractCalibreOpfMeta(FileMeta fileMeta, SafOpfRegistry.Entry entry, String displayName) {
        ContentResolver cr = getApplicationContext().getContentResolver();
        try (InputStream in = cr.openInputStream(entry.opfUri)) {
            if (in == null) return false;
            EbookMeta ebookMeta = CalirbeExtractor.getBookMetaInformationFromStream(
                    in, SafOpfRegistry.coverResolver(cr, entry.siblingByLowerName));
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

    private static class SafChild {
        final String name;
        final Uri uri;
        final String mimeType;
        final String sizeStr;
        final String modifiedStr;

        SafChild(String name, Uri uri, String mimeType, String sizeStr, String modifiedStr) {
            this.name = name;
            this.uri = uri;
            this.mimeType = mimeType;
            this.sizeStr = sizeStr;
            this.modifiedStr = modifiedStr;
        }
    }

    private void searchSAF(Context context, Uri rootUri, List<FileMeta> items) {
        // SAF folder listings are network round-trips. Fan out with fork-join so subdirectory
        // queries run in parallel (fork-join avoids the classic thread-starvation deadlock
        // that a fixed pool would hit when every worker is waiting on its children).
        ConcurrentLinkedQueue<FileMeta> found = new ConcurrentLinkedQueue<>();
        int parallelism = Math.max(1, Tunables.SAF_DISCOVERY_PARALLELISM);
        LOG.d("SAF discovery parallelism", parallelism);
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            pool.invoke(new SafSearchTask(context, rootUri, found));
        } catch (Exception e) {
            LOG.e(e);
        } finally {
            pool.shutdown();
        }
        items.addAll(found);
    }

    private final class SafSearchTask extends RecursiveAction {
        private final Context context;
        private final Uri parentUri;
        private final ConcurrentLinkedQueue<FileMeta> found;

        SafSearchTask(Context context, Uri parentUri, ConcurrentLinkedQueue<FileMeta> found) {
            this.context = context;
            this.parentUri = parentUri;
            this.found = found;
        }

        @Override
        protected void compute() {
            if (isStopped()) return;
            try {
                ContentResolver cr = context.getContentResolver();
                Uri childrenUri = ExtUtils.getChildUri(context, parentUri);
                if (childrenUri == null) return;

                List<SafChild> children = new ArrayList<>();
                Map<String, Uri> siblingByLowerName = new HashMap<>();

                Cursor cursor = cr.query(childrenUri, new String[]{
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                }, null, null, null);
                try {
                    while (cursor != null && cursor.moveToNext()) {
                        String name = cursor.getString(0);
                        String docId = cursor.getString(1);
                        String mimeType = cursor.getString(2);
                        String sizeStr = cursor.getString(3);
                        String modifiedStr = cursor.getString(4);

                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, docId);
                        children.add(new SafChild(name, docUri, mimeType, sizeStr, modifiedStr));
                        if (name != null && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                            siblingByLowerName.put(name.toLowerCase(Locale.US), docUri);
                        }
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }

                List<SafSearchTask> subtasks = new ArrayList<>();
                for (SafChild child : children) {
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(child.mimeType)) {
                        subtasks.add(new SafSearchTask(context, child.uri, found));
                    } else if (SearchCore.endWith(child.name, ExtUtils.seachExts)) {
                        FileMeta meta = new FileMeta(child.uri.toString());
                        meta.setTitle(child.name);
                        meta.setPathTxt(child.name);
                        meta.setExt(ExtUtils.getFileExtension(child.name));
                        try {
                            if (child.sizeStr != null) meta.setSize(Long.parseLong(child.sizeStr));
                            if (child.modifiedStr != null) meta.setDate(Long.parseLong(child.modifiedStr));
                        } catch (NumberFormatException ignored) {}
                        found.add(meta);

                        Uri opfUri = findCalibreOpf(child.name, siblingByLowerName);
                        if (opfUri != null) {
                            SafOpfRegistry.register(child.uri.toString(),
                                    new SafOpfRegistry.Entry(opfUri, siblingByLowerName));
                        }
                    }
                }
                if (!subtasks.isEmpty()) {
                    invokeAll(subtasks);
                }
            } catch (Exception e) {
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
            sendFinishMessage();
            LOG.d("updateBookAnnotations end");
        }

    }

    Runnable timer = new Runnable() {

        @Override
        public void run() {
            LOG.d("timer 2");
            sendProggressMessage(itemsMeta);
            handler.postDelayed(timer, 250);
        }
    };


    Runnable refreshTimer = new Runnable() {

        @Override
        public void run() {
            LOG.d("timer2");
            sendBuildingLibrary();
            handler.postDelayed(refreshTimer, 500);
        }
    };


}
