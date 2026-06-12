package com.sharedoc.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Active range lock for one user in one document.
 */
public class RangeLock implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String lockId;
    private String documentId;
    private String owner;
    private long baseRevision;
    private int baseStart;
    private int baseEnd;
    private int currentStart;
    private int currentEnd;
    private LocalDateTime acquiredAt;
    private boolean queued;
    private int queuePosition;

    public RangeLock() {
    }

    public RangeLock(String lockId, String documentId, String owner, long baseRevision,
                     int baseStart, int baseEnd, int currentStart, int currentEnd, LocalDateTime acquiredAt) {
        this.lockId = lockId;
        this.documentId = documentId;
        this.owner = owner;
        this.baseRevision = baseRevision;
        this.baseStart = baseStart;
        this.baseEnd = baseEnd;
        this.currentStart = currentStart;
        this.currentEnd = currentEnd;
        this.acquiredAt = acquiredAt;
        this.queued = false;
        this.queuePosition = 0;
    }

    public String getLockId() {
        return lockId;
    }

    public void setLockId(String lockId) {
        this.lockId = lockId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public long getBaseRevision() {
        return baseRevision;
    }

    public void setBaseRevision(long baseRevision) {
        this.baseRevision = baseRevision;
    }

    public int getBaseStart() {
        return baseStart;
    }

    public void setBaseStart(int baseStart) {
        this.baseStart = baseStart;
    }

    public int getBaseEnd() {
        return baseEnd;
    }

    public void setBaseEnd(int baseEnd) {
        this.baseEnd = baseEnd;
    }

    public int getCurrentStart() {
        return currentStart;
    }

    public void setCurrentStart(int currentStart) {
        this.currentStart = currentStart;
    }

    public int getCurrentEnd() {
        return currentEnd;
    }

    public void setCurrentEnd(int currentEnd) {
        this.currentEnd = currentEnd;
    }

    public LocalDateTime getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(LocalDateTime acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public boolean isQueued() {
        return queued;
    }

    public void setQueued(boolean queued) {
        this.queued = queued;
    }

    public int getQueuePosition() {
        return queuePosition;
    }

    public void setQueuePosition(int queuePosition) {
        this.queuePosition = queuePosition;
    }
}
