package com.sharedoc.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result object for releasing one or more range locks.
 */
public class LockReleaseResult {
    private final List<RangeLock> releasedLocks;
    private final List<RangeLock> activeLocks;
    private final List<RangeLock> promotedLocks;

    public LockReleaseResult(List<RangeLock> releasedLocks, List<RangeLock> activeLocks) {
        this(releasedLocks, activeLocks, new ArrayList<>());
    }

    public LockReleaseResult(List<RangeLock> releasedLocks, List<RangeLock> activeLocks, List<RangeLock> promotedLocks) {
        this.releasedLocks = releasedLocks == null ? new ArrayList<>() : new ArrayList<>(releasedLocks);
        this.activeLocks = activeLocks == null ? new ArrayList<>() : new ArrayList<>(activeLocks);
        this.promotedLocks = promotedLocks == null ? new ArrayList<>() : new ArrayList<>(promotedLocks);
    }

    public List<RangeLock> getReleasedLocks() {
        return new ArrayList<>(releasedLocks);
    }

    public List<RangeLock> getActiveLocks() {
        return new ArrayList<>(activeLocks);
    }

    public List<RangeLock> getPromotedLocks() {
        return new ArrayList<>(promotedLocks);
    }
}
