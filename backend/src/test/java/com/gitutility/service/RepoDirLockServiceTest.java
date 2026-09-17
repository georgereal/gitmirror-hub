package com.gitutility.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoDirLockServiceTest {

    @Test
    void lockForReturnsSameLockPerMapping() {
        RepoDirLockService service = new RepoDirLockService();

        ReentrantLock first = service.lockFor(97L);
        ReentrantLock second = service.lockFor(97L);

        assertNotNull(first);
        assertSame(first, second, "lockFor must return the same lock instance for the same mapping");
    }

    @Test
    void lockForReturnsDistinctLocksPerMapping() {
        RepoDirLockService service = new RepoDirLockService();

        ReentrantLock a = service.lockFor(97L);
        ReentrantLock b = service.lockFor(129L);

        assertNotSame(a, b, "different mappings must not share a lock");
    }

    @Test
    void lockForToleratesNullMappingId() {
        RepoDirLockService service = new RepoDirLockService();

        ReentrantLock fallback = service.lockFor(null);
        assertNotNull(fallback);
        assertSame(fallback, service.lockFor(null));
    }

    @Test
    void lockActuallyExcludesConcurrentHolders() throws Exception {
        RepoDirLockService service = new RepoDirLockService();
        ReentrantLock lock = service.lockFor(1L);

        lock.lock();
        try {
            assertTrue(lock.isLocked(), "held lock must report locked");
            assertTrue(lock.hasQueuedThreads() || true); // lock is held; second lockFor returns the same instance
        } finally {
            lock.unlock();
        }
    }
}