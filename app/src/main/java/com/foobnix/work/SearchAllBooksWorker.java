package com.foobnix.work;

import static com.foobnix.pdf.info.AppsConfig.SEARCH_FRAGMENT_WORKER_NAME;
import static com.foobnix.pdf.info.AppsConfig.WORKER_POLICY;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.IO;
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
import com.foobnix.pdf.info.SafFileLink;
import com.foobnix.pdf.info.SafDocumentIdentity;
import com.foobnix.pdf.info.SafOpfRegistry;
import com.foobnix.pdf.info.io.SearchCore;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import org.ebookdroid.common.settings.books.SharedBooks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class SearchAllBooksWorker extends MessageWorker {

    Handler handler;
    List<FileMeta> itemsMeta;
    private long scanGeneration;

    public SearchAllBooksWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        handler = new Handler(Looper.getMainLooper());

    }

    public static void run(Context context) {


        long generation = ScanOwnership.claim();
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(SearchAllBooksWorker.class)
                .setInputData(new Data.Builder().putLong(ScanOwnership.GENERATION, generation).build())
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(SEARCH_FRAGMENT_WORKER_NAME, WORKER_POLICY, workRequest);
    }

    static Set<String> selectedRoots() {
        Set<String> roots = new HashSet<>();
        for (String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (path == null || path.trim().isEmpty()) continue;
            roots.add(ExtUtils.isExteralSD(path) ? path : new File(path).getPath());
        }
        return roots;
    }

    /** Explicit removal also clears membership from older local scans that never recorded it. */
    public static void deselectRoot(Context context, String removedRoot) {
        IO.writeObjSync(AppProfile.syncCSS, BookCSS.get());
        long owner = ScanOwnership.claim();
        String root = ExtUtils.isExteralSD(removedRoot)
                ? removedRoot : new File(removedRoot).getPath();
        ScanOwnership.write(owner, () -> false, () -> AppDB.get().reconcileDeselectedRoots(
                selectedRoots(), java.util.Collections.singleton(root)));
        run(context);
    }

    static boolean reconcileSelection(long owner, java.util.function.BooleanSupplier stopped) {
        return ScanOwnership.write(owner, stopped, () -> AppDB.get().reconcileDeselectedRoots(
                selectedRoots(), java.util.Collections.emptySet()));
    }


    @Override protected boolean publishCompletion(Runnable action) {
        return ScanOwnership.write(scanGeneration, this::isStopped, action);
    }

    @Override protected boolean reportsOwnCompletion() { return true; }

    @Override protected boolean publishFailure(Runnable action) {
        return ScanOwnership.write(scanGeneration, () -> false, action);
    }

    public boolean doWorkInner() throws IOException, InterruptedException {
        scanGeneration = ScanOwnership.adopt(
                getInputData().getLong(ScanOwnership.GENERATION, 0));
        if (!reconcileSelection(scanGeneration, this::isStopped)) return false;
        String errorID = AppProfile.getCurrent();
        Prefs.get().put(errorID, 0);
        try {
            Tags2.migration();
            AppProfile.init(getApplicationContext());
            if (!ScanOwnership.write(scanGeneration, this::isStopped,
                    AppDB.get()::migrateAllSafRows)) return false;
            String metadataSettings = MetadataRefreshPolicy.settingsKey();
            SharedPreferences metadataPreferences = getApplicationContext()
                    .getSharedPreferences("ScanMetadataRevisions", Context.MODE_PRIVATE);
            String profileKey = AppProfile.getCurrent() + "|";
            Map<String, String> appliedRevisions = new HashMap<>();
            ImageExtractor.clearErrors();
            itemsMeta = java.util.Collections.synchronizedList(new LinkedList<>());
            SafOpfRegistry.restore(getApplicationContext());
            Map<String, SafOpfRegistry.Entry> sidecars = new HashMap<>();
            Map<String, FileMeta> before = new HashMap<>();
            for (FileMeta row : AppDB.get().scanSnapshot()) before.put(row.getPath(), row);
            Set<String> completedRoots = new HashSet<>();
            Map<String, Set<String>> safMembership = new HashMap<>();
            handler.post(timer);
            for (String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
                if (path == null || path.trim().isEmpty()) continue;
                if (ExtUtils.isExteralSD(path)) {
                    List<FileMeta> fromRoot = new ArrayList<>();
                    SafDiscovery.collect(getApplicationContext(), Uri.parse(path), fromRoot,
                            sidecars, this::isStopped);
                    itemsMeta.addAll(fromRoot);
                    Set<String> paths = new HashSet<>();
                    for (FileMeta row : fromRoot) paths.add(row.getPath());
                    safMembership.put(path, paths);
                    completedRoots.add(path);
                } else {
                    File root = new File(path);
                    List<FileMeta> fromRoot = new ArrayList<>();
                    LocalDiscovery.collect(root, ExtUtils.seachExts, fromRoot, this::isStopped);
                    itemsMeta.addAll(fromRoot);
                    Set<String> paths = new HashSet<>();
                    for (FileMeta row : fromRoot) paths.add(row.getPath());
                    safMembership.put(root.getPath(), paths);
                    completedRoots.add(root.getPath());
                }
            }
            if (isStopped()) return false;
            if (itemsMeta.isEmpty() && !selectedRoots().isEmpty()) {
                File downloadsDir = AppSP.get().getTempDownloadBooks(getApplicationContext());
                if (!downloadsDir.isDirectory() && !downloadsDir.mkdirs()) {
                    throw new IOException("Cannot create sample-book directory");
                }
                try {
                    String[] books = getApplicationContext().getAssets().list("books");
                    if (books != null) for (String book : books) {
                        File outFile = new File(downloadsDir, book);
                        try (FileOutputStream out = new FileOutputStream(outFile)) {
                            IOUtils.copyClose(getApplicationContext().getAssets().open("books/" + book), out);
                        }
                    }
                } catch (Exception failure) { LOG.e(failure); }
                LocalDiscovery.collect(downloadsDir, ExtUtils.seachExts, itemsMeta, this::isStopped);
            }
            Map<String, FileMeta> unique = new java.util.LinkedHashMap<>();
            for (FileMeta row : itemsMeta) unique.putIfAbsent(row.getPath(), row);
            itemsMeta.clear();
            itemsMeta.addAll(unique.values());
            List<SimpleMeta> excluded = AppData.get().getAllExcluded();
            List<FileMeta> synced = AppData.get().getAllSyncBooks();
            if (!ScanMembership.apply(itemsMeta, excluded, synced, this::isStopped)) return false;
            if (!ScanOwnership.write(scanGeneration, this::isStopped, () -> {
                AppDB.get().reconcileCompletedScan(itemsMeta, completedRoots, safMembership);
                for (FileMeta found : itemsMeta) {
                    if (!ExtUtils.isExteralSD(found.getPath())) continue;
                    SafOpfRegistry.Entry entry = sidecars.get(found.getPath());
                    if (entry == null) SafOpfRegistry.unregister(found.getPath());
                    else SafOpfRegistry.register(found.getPath(), entry);
                }
                SafOpfRegistry.save(getApplicationContext());
            })) return false;
            handler.removeCallbacks(timer);
            if (!ScanOwnership.isCurrent(scanGeneration, this::isStopped)) return false;
            handler.post(refreshTimer);
            for (FileMeta found : itemsMeta) {
                if (isStopped()) return false;
                FileMeta baseline = before.get(found.getPath());
                if (baseline == null) {
                    baseline = new FileMeta(found.getPath());
                    baseline.setTitle(found.getTitle());
                    if (ExtUtils.isExteralSD(found.getPath()))
                        baseline.setState(FileMetaCore.STATE_BASIC);
                    com.foobnix.model.AppBook progress = SharedBooks.load(found.getPath());
                    if (!ScanOwnership.write(scanGeneration, this::isStopped,
                            () -> AppDB.get().initializeReadingProgress(found.getPath(), progress.p, progress.t)))
                        return false;
                }
                if (ExtUtils.isExteralSD(found.getPath()) && baseline.getState() == null)
                    baseline.setState(FileMetaCore.STATE_BASIC);
                String revision = MetadataRefreshPolicy.revision(found,
                        sidecars.get(found.getPath()), metadataSettings);
                String revisionKey = profileKey + found.getPath();
                if (metadataSettings.equals(MetadataRefreshPolicy.settingsKey())
                        && !MetadataRefreshPolicy.needsExtraction(baseline,
                                metadataPreferences.getString(revisionKey, null), revision)) continue;
                boolean[] extracted = new boolean[1];
                if (ExtUtils.isExteralSD(found.getPath())) {
                    if (!publishSafMetadata(found, baseline, sidecars.get(found.getPath()),
                            scanGeneration, this::isStopped, extracted)) return false;
                } else if (!publishLocalMetadata(found, baseline,
                        scanGeneration, this::isStopped, extracted)) return false;
                if (revision != null && extracted[0])
                    appliedRevisions.put(revisionKey, revision);
            }
            if (!ScanOwnership.write(scanGeneration, this::isStopped, () -> {
                if (metadataSettings.equals(MetadataRefreshPolicy.settingsKey())) {
                    SharedPreferences.Editor editor = metadataPreferences.edit();
                    for (Map.Entry<String, String> revision : appliedRevisions.entrySet())
                        editor.putString(revision.getKey(), revision.getValue());
                    editor.commit();
                }
            })) return false;
            itemsMeta.clear();
            handler.removeCallbacks(refreshTimer);
            CacheZipUtils.CacheDir.ZipService.removeCacheContent();
            if (isStopped()) return false;
            Clouds.get().syncronizeGet();
            if (isStopped()) return false;
            Tags2.updateTagsDB();
            if (isStopped()) return false;
            updateBookAnnotations();
            return ScanOwnership.isCurrent(scanGeneration, this::isStopped);
        } finally {
            Prefs.get().remove(errorID, 0);
            handler.removeCallbacksAndMessages(null);
        }
    }

    boolean publishLocalMetadata(FileMeta found, FileMeta baseline, long owner,
                                 java.util.function.BooleanSupplier stopped) {
        return publishLocalMetadata(found, baseline, owner, stopped, new boolean[1]);
    }

    boolean publishLocalMetadata(FileMeta found, FileMeta baseline, long owner,
                                 java.util.function.BooleanSupplier stopped,
                                 boolean[] extractionSucceededResult) {
        FileMeta extracted = new FileMeta(found.getPath());
        File file = new File(found.getPath());
        FileMetaCore.get().upadteBasicMeta(extracted, file);
        boolean extractionSucceeded = false;
        try {
            EbookMeta metadata = readLocalMetadata(file);
            FileMetaCore.get().udpateFullMeta(extracted, metadata);
            extractionSucceeded = true;
        } catch (Exception failure) { LOG.e(failure); }
        boolean completed = extractionSucceeded;
        boolean published = ScanOwnership.write(owner, stopped,
                () -> AppDB.get().updateScannedMetadata(extracted, baseline, completed));
        extractionSucceededResult[0] = published && completed;
        return published;
    }

    boolean publishSafMetadata(FileMeta found, FileMeta baseline, long owner,
                               java.util.function.BooleanSupplier stopped) {
        return publishSafMetadata(found, baseline, null, owner, stopped);
    }

    boolean publishSafMetadata(FileMeta found, FileMeta baseline, SafOpfRegistry.Entry sidecar,
                               long owner, java.util.function.BooleanSupplier stopped) {
        return publishSafMetadata(found, baseline, sidecar, owner, stopped, new boolean[1]);
    }

    boolean publishSafMetadata(FileMeta found, FileMeta baseline, SafOpfRegistry.Entry sidecar,
                               long owner, java.util.function.BooleanSupplier stopped,
                               boolean[] extractionSucceededResult) {
        FileMeta extracted = new FileMeta(found.getPath());
        extracted.setTitle(found.getTitle());
        extracted.setPathTxt(found.getPathTxt());
        extracted.setSize(found.getSize());
        extracted.setDate(found.getDate());
        extracted.setExt(found.getExt());
        extracted.setState(FileMetaCore.STATE_BASIC);
        boolean extractionSucceeded = false;
        try {
            EbookMeta metadata = readSafMetadataForScan(found, sidecar);
            FileMetaCore.get().udpateFullMeta(extracted, metadata);
            if (TxtUtils.isEmpty(extracted.getTitle()) || extracted.getTitle().startsWith("saf_"))
                extracted.setTitle(found.getTitle());
            extractionSucceeded = true;
        } catch (Exception failure) { LOG.e(failure); }
        boolean completed = extractionSucceeded;
        boolean published = ScanOwnership.write(owner, stopped,
                () -> AppDB.get().updateScannedMetadata(extracted, baseline, completed));
        extractionSucceededResult[0] = published && completed;
        return published;
    }

    protected EbookMeta readSafMetadataForScan(FileMeta found) throws Exception {
        try (SafFileLink link = new SafFileLink(getApplicationContext(),
                Uri.parse(found.getPath()), found.getPathTxt())) {
            return readLocalMetadata(link.file);
        }
    }

    protected EbookMeta readSafMetadataForScan(FileMeta found, SafOpfRegistry.Entry sidecar)
            throws Exception {
        if (AppState.get().isUseCalibreOpf
                && !AppState.get().isShowOnlyOriginalFileNames && sidecar != null) {
            try (InputStream input = SafDocumentIdentity.openInputStream(
                    getApplicationContext(), sidecar.opfUri)) {
                EbookMeta metadata = CalirbeExtractor.getBookMetaInformationFromStream(input,
                        SafOpfRegistry.coverResolver(getApplicationContext(), sidecar.siblingByLowerName));
                if (metadata == null || metadata.isExtractionFailed()
                        || TxtUtils.isEmpty(metadata.getTitle()))
                    throw new IOException("Calibre sidecar metadata unavailable: " + sidecar.opfUri);
                metadata.setUnzipPath(found.getPath());
                return metadata;
            }
        }
        return readSafMetadataForScan(found);
    }

    /** A missing or unreadable discovery cannot certify an empty metadata result. */
    protected EbookMeta readLocalMetadata(File source) throws IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(source)) {
            if (input.read() == -1) throw new IOException("Empty book: " + source);
        }
        EbookMeta metadata = FileMetaCore.get().getEbookMetaForScan(
                source.getPath(), CacheZipUtils.CacheDir.ZipService);
        if (!source.isFile() || !source.canRead() || metadata == null
                || TxtUtils.isEmpty(metadata.getTitle())) {
            throw new IOException("Book metadata unavailable: " + source);
        }
        return metadata;
    }


    public void updateBookAnnotations() {
        if (!AppState.get().isDisplayAnnotation) return;
        for (FileMeta row : AppDB.get().scanSnapshot()) {
            if (isStopped()) return;
            if (TxtUtils.isEmpty(row.getAnnotation())) {
                String overview = FileMetaCore.getBookOverview(row.getPath());
                if (!ScanOwnership.write(scanGeneration, this::isStopped,
                        () -> AppDB.get().updateAnnotationIfMissing(row.getPath(), overview))) return;
            }
        }
    }

    Runnable timer = new Runnable() {

        @Override
        public void run() {
            if (!ScanOwnership.write(scanGeneration, SearchAllBooksWorker.this::isStopped,
                    () -> sendProggressMessage(itemsMeta))) return;
            handler.postDelayed(timer, 250);
        }
    };


    Runnable refreshTimer = new Runnable() {

        @Override
        public void run() {
            if (!ScanOwnership.write(scanGeneration, SearchAllBooksWorker.this::isStopped,
                    SearchAllBooksWorker.this::sendBuildingLibrary)) return;
            handler.postDelayed(refreshTimer, 500);
        }
    };


}
