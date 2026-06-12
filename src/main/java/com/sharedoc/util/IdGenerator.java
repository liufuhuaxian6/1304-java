package com.sharedoc.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ID generation utility.
 * Generates readable IDs for users, documents, and versions during the skeleton stage.
 */
public final class IdGenerator {
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(1);
    private static final AtomicLong DOCUMENT_SEQUENCE = new AtomicLong(1);
    private static final AtomicLong VERSION_SEQUENCE = new AtomicLong(1);
    private static final AtomicLong LOCK_SEQUENCE = new AtomicLong(1);

    private IdGenerator() {
        // TODO: Replace with persistent ID strategy if file metadata persistence is added.
    }

    public static String nextUserId() {
        // TODO: Ensure uniqueness across restarts when persistent storage is implemented.
        return "U-" + USER_SEQUENCE.getAndIncrement();
    }

    public static String nextDocumentId() {
        // TODO: Consider UUID or persisted sequence for production-like implementation.
        return "D-" + DOCUMENT_SEQUENCE.getAndIncrement();
    }

    public static String nextVersionId() {
        // TODO: Consider per-document version numbers in addition to global IDs.
        return "V-" + VERSION_SEQUENCE.getAndIncrement();
    }

    public static String nextLockId() {
        return "L-" + LOCK_SEQUENCE.getAndIncrement();
    }

    public static String randomId() {
        // TODO: Use only when business-readable IDs are not required.
        return UUID.randomUUID().toString();
    }
}
