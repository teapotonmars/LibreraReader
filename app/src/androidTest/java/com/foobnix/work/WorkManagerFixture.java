package com.foobnix.work;

import android.content.Context;
import androidx.work.Configuration;
import androidx.work.WorkerFactory;
import androidx.work.impl.WorkDatabase;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.impl.WorkManagerImplExtKt;
import androidx.work.impl.Processor;
import androidx.work.impl.WorkLauncherImpl;
import androidx.work.impl.background.greedy.GreedyScheduler;
import androidx.work.impl.constraints.trackers.Trackers;
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import androidx.test.platform.app.InstrumentationRegistry;

/** Actual WorkManager execution, with an in-memory DB and no system-job scheduler. */
final class WorkManagerFixture implements AutoCloseable {
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    final WorkManagerImpl manager;

    WorkManagerFixture(Context context, WorkerFactory factory) {
        Configuration configuration = new Configuration.Builder().setExecutor(workers)
                .setTaskExecutor(tasks).setWorkerFactory(factory).build();
        WorkManagerTaskExecutor executor = new WorkManagerTaskExecutor(tasks);
        WorkDatabase database = WorkDatabase.create(context, tasks, configuration.getClock(), true);
        Trackers trackers = new Trackers(context, executor);
        Processor processor = new Processor(context, configuration, executor, database);
        GreedyScheduler scheduler = new GreedyScheduler(context, configuration, trackers, processor,
                new WorkLauncherImpl(processor, executor), executor);
        manager = new WorkManagerImpl(context, configuration, executor, database,
                Collections.singletonList(scheduler), processor, trackers);
    }

    @Override public void close() throws Exception {
        manager.cancelAllWork().getResult().get(5, TimeUnit.SECONDS);
        // Worker completion is delivered through main-thread and serial-executor callbacks.
        // A barrier on the underlying pool alone does not drain that serial queue.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int idlePasses = 0;
        while (idlePasses < 2) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            CompletableFuture<Void> barrier = new CompletableFuture<>();
            manager.getWorkTaskExecutor().getSerialTaskExecutor().execute(() -> barrier.complete(null));
            barrier.get(5, TimeUnit.SECONDS);
            if (!manager.getProcessor().hasWork()
                    && !manager.getWorkTaskExecutor().getSerialTaskExecutor().hasPendingTasks()) idlePasses++;
            else idlePasses = 0;
            if (System.nanoTime() > deadline) throw new AssertionError("WorkManager did not become idle");
        }
        WorkManagerImplExtKt.close(manager);
        workers.shutdown();
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Workers did not stop");
        tasks.shutdown();
        if (!tasks.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("Task callbacks did not stop");
    }
}
