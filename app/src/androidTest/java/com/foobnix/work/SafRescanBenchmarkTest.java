package com.foobnix.work;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.foobnix.android.utils.JsonDB;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.model.BookCSS;

import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in benchmark of the real worker, using the device's existing granted library. */
public class SafRescanBenchmarkTest {
    @Test public void indexThenRescanWithoutLosingHistory() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String root = InstrumentationRegistry.getArguments().getString("rootUri");
        org.junit.Assume.assumeTrue("Opt-in benchmark: pass rootUri", root != null);
        AppProfile.init(context);
        assertTrue("Only benchmark an already configured root", JsonDB.get(BookCSS.get().searchPathsJson).contains(root));
        int recents = AppData.get().getAllRecentSimple().size();
        WorkManager manager = WorkManager.getInstance(context);
        boolean fast = AppState.get().isUseCalibreDatabaseForScan;
        boolean enableFast = "true".equals(InstrumentationRegistry.getArguments().getString("enableFastScan"));
        try {
            if (enableFast) AppState.get().isUseCalibreDatabaseForScan = true;
            for (int scan = 1; scan <= 2; scan++) {
                var request = new OneTimeWorkRequest.Builder(SearchAllBooksWorker.class).build();
                long started = SystemClock.elapsedRealtime();
                manager.enqueueUniqueWork(AppsConfig.SEARCH_FRAGMENT_WORKER_NAME, ExistingWorkPolicy.REPLACE, request).getResult().get();
                WorkInfo info;
                do {
                    Thread.sleep(250);
                    info = manager.getWorkInfoById(request.getId()).get();
                    assertTrue("Rescan exceeded three minutes", SystemClock.elapsedRealtime() - started < 180_000);
                } while (info == null || !info.getState().isFinished());
                assertEquals("Scan failed", WorkInfo.State.SUCCEEDED, info.getState());
                Log.i("SafProbe", "Rescan " + scan + " total ms=" + (SystemClock.elapsedRealtime() - started));
                boolean indexed = context.getSharedPreferences("CalibreDiscovery", 0).contains(root);
                Log.i("SafProbe", "Rescan " + scan + " used/saved Calibre index=" + indexed);
                if ("true".equals(InstrumentationRegistry.getArguments().getString("requireFastIndex"))) {
                    assertTrue("A complete Calibre index was not saved", indexed);
                }
                assertEquals("Recent history changed during rescan", recents, AppData.get().getAllRecentSimple().size());
            }
        } finally {
            AppState.get().isUseCalibreDatabaseForScan = fast;
        }
    }
}
