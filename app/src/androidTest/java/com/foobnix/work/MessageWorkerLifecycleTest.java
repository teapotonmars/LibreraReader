package com.foobnix.work;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.ExistingWorkPolicy;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;
import com.foobnix.ui2.BooksService;
import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlin.coroutines.EmptyCoroutineContext;
import static org.junit.Assert.*;

/** Exercises the real worker's terminal handling without scanning a user's library. */
public class MessageWorkerLifecycleTest {
    @Test public void canceledScansCannotPublishDelayedSearchingOrExtractingMessages() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SearchAllBooksWorker worker = new SearchAllBooksWorker(context, parameters());
        worker.itemsMeta = Collections.synchronizedList(new java.util.ArrayList<>());
        worker.stop(androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP);
        AtomicInteger messages = new AtomicInteger();
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(context);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) { messages.incrementAndGet(); }
        };
        broadcasts.registerReceiver(receiver, new IntentFilter(BooksService.INTENT_NAME));
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                worker.timer.run(); worker.refreshTimer.run();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Canceled scan overwrote the new scan's UI", 0, messages.get());
        } finally {
            worker.handler.removeCallbacksAndMessages(null);
            broadcasts.unregisterReceiver(receiver);
        }
    }
    @Test public void canceledOlderScanCannotClearReplacementScansBusyState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch oldStarted = new CountDownLatch(1), newStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1), releaseNew = new CountDownLatch(1);
        CountDownLatch oldFinished = new CountDownLatch(1), newFinished = new CountDownLatch(1);
        AtomicInteger generations = new AtomicInteger();
        WorkerFactory factory = new WorkerFactory() {
            @Override public ListenableWorker createWorker(Context ctx, String name, WorkerParameters params) {
                int generation = generations.incrementAndGet();
                return new MessageWorker(ctx, params) {
                    @Override boolean doWorkInner() throws InterruptedException {
                        if (generation == 1) {
                            oldStarted.countDown();
                            // Simulate provider I/O that does not immediately honor interruption.
                            while (releaseOld.getCount() > 0) {
                                try { releaseOld.await(); } catch (InterruptedException ignored) { }
                            }
                            return false;
                        }
                        newStarted.countDown(); releaseNew.await(); return true;
                    }
                    @Override public Result doWork() {
                        try { return super.doWork(); }
                        finally { (generation == 1 ? oldFinished : newFinished).countDown(); }
                    }
                };
            }
        };
        try (WorkManagerFixture fixture = new WorkManagerFixture(context, factory)) {
            String name = "scan-overlap-" + UUID.randomUUID();
            fixture.manager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(SearchAllBooksWorker.class).build())
                    .getResult().get(5, TimeUnit.SECONDS);
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS));
            fixture.manager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(SearchAllBooksWorker.class).build())
                    .getResult().get(5, TimeUnit.SECONDS);
            assertTrue(newStarted.await(5, TimeUnit.SECONDS));
            releaseOld.countDown(); assertTrue(oldFinished.await(5, TimeUnit.SECONDS));
            assertTrue("Canceled scan cleared a running replacement", BooksService.isRunning);
            releaseNew.countDown(); assertTrue(newFinished.await(5, TimeUnit.SECONDS));
            assertFalse(BooksService.isRunning);
        } finally { releaseOld.countDown(); releaseNew.countDown(); }
    }
    private WorkerParameters parameters() {
        return new WorkerParameters(UUID.randomUUID(), Data.EMPTY,
                Collections.emptyList(), new WorkerParameters.RuntimeExtras(), 0, 0,
                Runnable::run, EmptyCoroutineContext.INSTANCE,
                new WorkManagerTaskExecutor(Runnable::run), new WorkerFactory() {
                    @Override public ListenableWorker createWorker(Context ctx, String name, WorkerParameters params) {
                        throw new AssertionError("Unexpected worker creation");
                    }
                },
                (ctx, id, data) -> { throw new AssertionError("Unexpected progress API"); },
                (ctx, id, info) -> { throw new AssertionError("Unexpected foreground API"); });
    }
    private MessageWorker worker(Context context, boolean throwFailure) {
        return new MessageWorker(context, parameters()) {
            @Override boolean doWorkInner() throws IOException {
                assertTrue(BooksService.isRunning);
                if (throwFailure) throw new IOException("Fixture listing failed");
                return false;
            }
        };
    }

    @Test public void failedListingReleasesBusyStateAndNotifiesLibrary() {
        verifyTerminal(true);
    }

    @Test public void incompleteWorkReportsFailureAndNotifiesLibrary() {
        verifyTerminal(false);
    }

    private void verifyTerminal(boolean throwFailure) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(context);
        AtomicInteger finishes = new AtomicInteger();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (BooksService.RESULT_SEARCH_FINISH.equals(intent.getStringExtra(Intent.EXTRA_TEXT))) {
                    assertFalse(BooksService.isRunning);
                    finishes.incrementAndGet();
                }
            }
        };
        broadcasts.registerReceiver(receiver, new IntentFilter(BooksService.INTENT_NAME));
        try {
            assertEquals(ListenableWorker.Result.failure(), worker(context, throwFailure).doWork());
            assertFalse(BooksService.isRunning);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, finishes.get());
        } finally {
            broadcasts.unregisterReceiver(receiver);
        }
    }
}
