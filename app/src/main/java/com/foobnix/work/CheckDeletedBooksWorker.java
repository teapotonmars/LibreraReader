package com.foobnix.work;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.SimpleMeta;
import com.foobnix.model.TagData;
import com.foobnix.model.Tags2;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.Prefs;
import com.foobnix.pdf.info.io.SearchCore;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.foobnix.pdf.info.AppsConfig.SEARCH_FRAGMENT_WORKER_NAME;
import static com.foobnix.pdf.info.AppsConfig.WORKER_POLICY;

public class CheckDeletedBooksWorker extends MessageWorker {

    public CheckDeletedBooksWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void run(Context context) {
        long generation = ScanOwnership.claim();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CheckDeletedBooksWorker.class)
                .setInputData(new Data.Builder().putLong(ScanOwnership.GENERATION, generation).build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                SEARCH_FRAGMENT_WORKER_NAME, WORKER_POLICY, request);
    }

    private long scanGeneration;

    @Override protected boolean publishCompletion(Runnable action) {
        return ScanOwnership.write(scanGeneration, this::isStopped, action);
    }

    @Override protected boolean reportsOwnCompletion() { return true; }

    @Override protected boolean publishFailure(Runnable action) {
        return ScanOwnership.write(scanGeneration, () -> false, action);
    }

    @Override public boolean doWorkInner() throws IOException, InterruptedException {
        long generation = ScanOwnership.adopt(
                getInputData().getLong(ScanOwnership.GENERATION, 0));
        final long owner = generation;
        scanGeneration = generation;
        if (!SearchAllBooksWorker.reconcileSelection(generation, this::isStopped)) return false;
        if (!ScanOwnership.write(owner, this::isStopped,
                AppDB.get()::migrateAllSafRows)) return false;
        Map<String, FileMeta> before = new HashMap<>();
        for (FileMeta row : AppDB.get().scanSnapshot()) before.put(row.getPath(), row);
        List<FileMeta> found = new ArrayList<>();
        Set<String> completeRoots = new HashSet<>();
        Map<String, Set<String>> safMembership = new HashMap<>();
        for (String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (path == null || path.trim().isEmpty()) continue;
            if (ExtUtils.isExteralSD(path)) {
                List<FileMeta> fromRoot = new ArrayList<>();
                SafDiscovery.collect(getApplicationContext(), Uri.parse(path), fromRoot, this::isStopped);
                found.addAll(fromRoot);
                Set<String> paths = new HashSet<>();
                for (FileMeta row : fromRoot) paths.add(row.getPath());
                safMembership.put(path, paths);
                completeRoots.add(path);
            } else {
                File root = new File(path);
                List<FileMeta> fromRoot = new ArrayList<>();
                LocalDiscovery.collect(root, ExtUtils.seachExts, fromRoot, this::isStopped);
                found.addAll(fromRoot);
                Set<String> paths = new HashSet<>();
                for (FileMeta row : fromRoot) paths.add(row.getPath());
                safMembership.put(root.getPath(), paths);
                completeRoots.add(root.getPath());
            }
        }
        Map<String, FileMeta> unique = new java.util.LinkedHashMap<>();
        for (FileMeta row : found) unique.putIfAbsent(row.getPath(), row);
        found.clear(); found.addAll(unique.values());
        if (!reconcileFound(owner, this::isStopped, found, completeRoots, safMembership,
                AppData.get().getAllExcluded(), AppData.get().getAllSyncBooks())) return false;
        for (FileMeta row : found) {
            if (isStopped()) return false;
            if (before.containsKey(row.getPath())) continue;
            if (ExtUtils.isExteralSD(row.getPath())) continue;
            FileMeta basic = new FileMeta(row.getPath());
            FileMetaCore.get().upadteBasicMeta(basic, new File(row.getPath()));
            FileMeta start = new FileMeta(row.getPath());
            start.setTitle(row.getTitle());
            if (!ScanOwnership.write(owner, this::isStopped,
                    () -> AppDB.get().updateScannedMetadata(basic, start))) return false;
        }
        if (isStopped()) return false;
        Clouds.get().syncronizeGet();
        if (isStopped()) return false;
        Tags2.updateTagsDB();
        return ScanOwnership.write(owner, this::isStopped,
                () -> CoverWarmupWorker.run(getApplicationContext()));
    }

    static boolean reconcileFound(long owner, java.util.function.BooleanSupplier stopped,
                                  List<FileMeta> found, Set<String> completeRoots,
                                  Map<String, Set<String>> rootMembership,
                                  List<SimpleMeta> excluded, List<FileMeta> synced) {
        if (!ScanMembership.apply(found, excluded, synced, stopped)) return false;
        return ScanOwnership.write(owner, stopped,
                () -> AppDB.get().reconcileCompletedScan(found, completeRoots, rootMembership));
    }
}
