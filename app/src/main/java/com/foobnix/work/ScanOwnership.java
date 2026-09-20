package com.foobnix.work;

import java.util.function.BooleanSupplier;

/** Serializes scan ownership changes with writes made on behalf of a scan. */
final class ScanOwnership {
    static final String GENERATION = "scan_generation";
    private static long generation;

    private ScanOwnership() {}

    static synchronized long claim() {
        return ++generation;
    }

    /** WorkManager can restart a persisted request after the process loses static state. */
    static synchronized long adopt(long requested) {
        if (requested <= 0) return claim();
        if (generation == 0) generation = requested;
        return requested;
    }

    static synchronized boolean isCurrent(long owner, BooleanSupplier stopped) {
        return owner == generation && !stopped.getAsBoolean();
    }

    static synchronized boolean write(long owner, BooleanSupplier stopped, Runnable operation) {
        if (!isCurrent(owner, stopped)) return false;
        operation.run();
        return true;
    }
}
