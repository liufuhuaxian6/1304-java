package com.sharedoc.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document edit lock service.
 * Ensures one document can have only one editing user at the same time.
 */
public class LockService {
    private static final Map<String, String> DOCUMENT_LOCKS = new ConcurrentHashMap<>();

    public synchronized boolean tryLockDocument(String documentId, String username) {
        // TODO: Add timeout, reentrant policy, and lock metadata if needed.
        if (documentId == null || username == null || DOCUMENT_LOCKS.containsKey(documentId)) {
            return false;
        }
        DOCUMENT_LOCKS.put(documentId, username);
        return true;
    }

    public synchronized void unlockDocument(String documentId, String username) {
        // TODO: Verify permissions and record lock release events.
        if (username != null && username.equals(DOCUMENT_LOCKS.get(documentId))) {
            DOCUMENT_LOCKS.remove(documentId);
        }
    }

    public String getLockOwner(String documentId) {
        // TODO: Return richer lock information such as start time later.
        return DOCUMENT_LOCKS.get(documentId);
    }

    public boolean isLocked(String documentId) {
        // TODO: Consider expired locks in future implementation.
        return DOCUMENT_LOCKS.containsKey(documentId);
    }
}
