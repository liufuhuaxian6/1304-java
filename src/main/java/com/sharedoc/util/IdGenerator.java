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

    /**
     * Raises the user ID sequence so the next generated ID will not collide
     * with an ID restored from persisted state.
     */
    public static void ensureUserSequenceAtLeast(long value) {
        USER_SEQUENCE.updateAndGet(current -> Math.max(current, value));
    }

    /** @see #ensureUserSequenceAtLeast(long) */
    public static void ensureDocumentSequenceAtLeast(long value) {
        DOCUMENT_SEQUENCE.updateAndGet(current -> Math.max(current, value));
    }

    /**
     * Extracts the trailing numeric component of an ID such as {@code "D-12"}.
     * Returns 0 for null, malformed, or non-numeric IDs (for example the
     * seeded {@code "U-ADMIN"}).
     */
    public static long numericSuffix(String id) {
        if (id == null) {
            return 0;
        }
        int dash = id.lastIndexOf('-');
        if (dash < 0 || dash == id.length() - 1) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
