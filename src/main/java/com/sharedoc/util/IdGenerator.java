package com.sharedoc.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ID generation utility.
 * Generates readable sequential IDs for users, documents, and locks.
 * IDs restart from 1 on every server restart because business state is
 * held in memory; persistent storage would need a persistent sequence.
 */
public final class IdGenerator {
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(1);
    private static final AtomicLong DOCUMENT_SEQUENCE = new AtomicLong(1);
    private static final AtomicLong LOCK_SEQUENCE = new AtomicLong(1);

    private IdGenerator() {
    }

    public static String nextUserId() {
        return "U-" + USER_SEQUENCE.getAndIncrement();
    }

    public static String nextDocumentId() {
        return "D-" + DOCUMENT_SEQUENCE.getAndIncrement();
    }

    public static String nextLockId() {
        return "L-" + LOCK_SEQUENCE.getAndIncrement();
    }
}
