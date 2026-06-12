package com.sharedoc.service;

import com.sharedoc.model.RangeLock;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockServiceTest {
    private static final String DOC_ID = "D-1";

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
    }

    @Test
    void expiredActiveLockIsReleasedSoOthersCanLockTheRange() throws InterruptedException {
        LockService lockService = new LockService(Duration.ofMillis(50));

        RangeLock first = lockService.tryLockRange(DOC_ID, "alice", 1L, 0, 5);
        assertNotNull(first);
        assertFalse(first.isQueued());

        Thread.sleep(150);

        RangeLock second = lockService.tryLockRange(DOC_ID, "bob", 1L, 0, 5);
        assertNotNull(second);
        assertFalse(second.isQueued(), "Expired lock must not force the new request into the queue");

        List<RangeLock> activeLocks = lockService.getActiveLocks(DOC_ID);
        assertEquals(1, activeLocks.size());
        assertEquals("bob", activeLocks.get(0).getOwner());
    }

    @Test
    void queuedLockIsPromotedWhenActiveLockExpires() throws InterruptedException {
        LockService lockService = new LockService(Duration.ofMillis(50));

        RangeLock active = lockService.tryLockRange(DOC_ID, "alice", 1L, 0, 5);
        assertNotNull(active);
        RangeLock queued = lockService.tryLockRange(DOC_ID, "bob", 1L, 0, 5);
        assertNotNull(queued);
        assertTrue(queued.isQueued());

        Thread.sleep(150);

        List<RangeLock> activeLocks = lockService.getActiveLocks(DOC_ID);
        assertEquals(1, activeLocks.size());
        assertEquals("bob", activeLocks.get(0).getOwner());
        assertFalse(activeLocks.get(0).isQueued());
        assertTrue(lockService.getQueuedLocks(DOC_ID).isEmpty());
    }

    @Test
    void freshLocksAreNotExpired() {
        LockService lockService = new LockService(Duration.ofMinutes(10));

        assertNotNull(lockService.tryLockRange(DOC_ID, "alice", 1L, 0, 5));

        List<RangeLock> activeLocks = lockService.getActiveLocks(DOC_ID);
        assertEquals(1, activeLocks.size());
        assertEquals("alice", activeLocks.get(0).getOwner());
    }
}
