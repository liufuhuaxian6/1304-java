package com.sharedoc.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

    public synchronized boolean unlockDocument(String documentId, String username) {
        if (username != null && username.equals(DOCUMENT_LOCKS.get(documentId))) {
            DOCUMENT_LOCKS.remove(documentId);
            return true;
        }
        return false;
    }

    public synchronized List<String> releaseAllLocksHeldBy(String username) {
        List<String> released = new ArrayList<>();
        if (username == null) {
            return released;
        }
        Iterator<Map.Entry<String, String>> iterator = DOCUMENT_LOCKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            if (username.equals(entry.getValue())) {
                released.add(entry.getKey());
                iterator.remove();
            }
        }
        return released;
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
