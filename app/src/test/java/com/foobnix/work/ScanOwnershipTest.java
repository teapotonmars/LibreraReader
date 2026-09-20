package com.foobnix.work;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ScanOwnershipTest {
    @Test public void replacedWorkerCannotWrite() {
        long old = ScanOwnership.claim();
        AtomicInteger writes = new AtomicInteger();
        assertTrue(ScanOwnership.write(old, () -> false, writes::incrementAndGet));
        long current = ScanOwnership.claim();
        assertFalse(ScanOwnership.write(old, () -> false, writes::incrementAndGet));
        assertTrue(ScanOwnership.write(current, () -> false, writes::incrementAndGet));
        assertEquals(2, writes.get());
    }
}
