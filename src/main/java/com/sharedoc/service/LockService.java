package com.sharedoc.service;

import com.sharedoc.model.LockReleaseResult;
import com.sharedoc.model.RangeLock;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.util.IdGenerator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Document range lock service.
 * Allows concurrent edits on non-overlapping ranges in the same document.
 * Overlapping requests are queued FIFO and promoted when conflicts clear.
 * Active locks expire after {@code lockTtl} so a vanished client (closed
 * browser, lost connection) cannot block a range forever; queued requests
 * get a fresh TTL when they are promoted to active.
 */
public class LockService {
    private final Map<String, List<RangeLock>> documentLocks = new HashMap<>();
    private final Map<String, List<RangeLock>> documentLockQueues = new HashMap<>();
    private final Duration lockTtl;

    public LockService() {
        this(ServerConfig.LOCK_TTL);
    }

    public LockService(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public synchronized RangeLock tryLockRange(String documentId, String username, long revision, int start, int end) {
        if (documentId == null || username == null || username.isBlank() || start < 0 || end < start) {
            return null;
        }
        expireStaleLocks(documentId);

        List<RangeLock> locks = documentLocks.computeIfAbsent(documentId, key -> new ArrayList<>());
        for (RangeLock lock : locks) {
            if (username.equals(lock.getOwner())) {
                return null;
            }
            if (overlaps(start, end, lock.getCurrentStart(), lock.getCurrentEnd())) {
                return enqueueLock(documentId, username, revision, start, end);
            }
        }

        RangeLock lock = new RangeLock(
                IdGenerator.nextLockId(),
                documentId,
                username,
                revision,
                start,
                end,
                start,
                end,
                LocalDateTime.now()
        );
        locks.add(lock);
        return copyLock(lock);
    }

    public synchronized List<RangeLock> getQueuedLocks(String documentId) {
        expireStaleLocks(documentId);
        List<RangeLock> queuedLocks = documentLockQueues.get(documentId);
        if (queuedLocks == null) {
            return new ArrayList<>();
        }
        return copyLocksWithQueuePositions(queuedLocks);
    }

    public synchronized RangeLock getLockByOwner(String documentId, String username) {
        expireStaleLocks(documentId);
        List<RangeLock> locks = documentLocks.get(documentId);
        if (locks == null) {
            return null;
        }
        for (RangeLock lock : locks) {
            if (username.equals(lock.getOwner())) {
                return copyLock(lock);
            }
        }
        return null;
    }

    public synchronized RangeLock getLockById(String documentId, String lockId) {
        expireStaleLocks(documentId);
        List<RangeLock> locks = documentLocks.get(documentId);
        if (locks == null) {
            return null;
        }
        for (RangeLock lock : locks) {
            if (lockId.equals(lock.getLockId())) {
                return copyLock(lock);
            }
        }
        return null;
    }

    public synchronized boolean hasAnyLock(String documentId) {
        expireStaleLocks(documentId);
        List<RangeLock> locks = documentLocks.get(documentId);
        List<RangeLock> queuedLocks = documentLockQueues.get(documentId);
        return (locks != null && !locks.isEmpty()) || (queuedLocks != null && !queuedLocks.isEmpty());
    }

    public synchronized List<RangeLock> getActiveLocks(String documentId) {
        expireStaleLocks(documentId);
        List<RangeLock> locks = documentLocks.get(documentId);
        if (locks == null) {
            return new ArrayList<>();
        }
        return copyLocks(locks);
    }

    public synchronized List<RangeLock> getVisibleLocks(String documentId) {
        List<RangeLock> visibleLocks = getActiveLocks(documentId);
        visibleLocks.addAll(getQueuedLocks(documentId));
        return visibleLocks;
    }

    public synchronized LockReleaseResult releaseLockByOwner(String documentId, String username) {
        expireStaleLocks(documentId);
        List<RangeLock> released = new ArrayList<>();
        List<RangeLock> locks = documentLocks.get(documentId);

        if (locks != null) {
            Iterator<RangeLock> iterator = locks.iterator();
            while (iterator.hasNext()) {
                RangeLock lock = iterator.next();
                if (username.equals(lock.getOwner())) {
                    released.add(copyLock(lock));
                    iterator.remove();
                }
            }
            if (locks.isEmpty()) {
                documentLocks.remove(documentId);
            }
        }
        List<RangeLock> queuedLocks = documentLockQueues.get(documentId);
        if (queuedLocks != null) {
            Iterator<RangeLock> queueIterator = queuedLocks.iterator();
            while (queueIterator.hasNext()) {
                RangeLock lock = queueIterator.next();
                if (username.equals(lock.getOwner())) {
                    released.add(copyLock(lock));
                    queueIterator.remove();
                }
            }
            if (queuedLocks.isEmpty()) {
                documentLockQueues.remove(documentId);
            } else {
                updateQueuePositions(queuedLocks);
            }
        }
        List<RangeLock> promoted = promoteQueuedLocks(documentId);
        return new LockReleaseResult(released, getVisibleLocks(documentId), promoted);
    }

    public synchronized List<RangeLock> shiftLocksAfterEdit(String documentId, String savedLockId, int editStart, int editEnd, int delta) {
        List<RangeLock> locks = documentLocks.get(documentId);
        List<RangeLock> queuedLocks = documentLockQueues.get(documentId);
        if (delta == 0) {
            return getVisibleLocks(documentId);
        }

        if (locks != null) {
            for (RangeLock lock : locks) {
                if (savedLockId.equals(lock.getLockId())) {
                    continue;
                }
                shiftLock(lock, editStart, editEnd, delta);
            }
        }
        if (queuedLocks != null) {
            for (RangeLock lock : queuedLocks) {
                shiftLock(lock, editStart, editEnd, delta);
            }
        }
        return getVisibleLocks(documentId);
    }

    /**
     * Removes active locks older than the TTL and promotes queued requests
     * into the freed ranges. Queued entries are not expired here: they get
     * a fresh {@code acquiredAt} when promoted, so an abandoned queue entry
     * is eventually promoted and then expired by the same mechanism.
     */
    private void expireStaleLocks(String documentId) {
        List<RangeLock> locks = documentLocks.get(documentId);
        if (locks == null || locks.isEmpty()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minus(lockTtl);
        boolean removedAny = false;
        Iterator<RangeLock> iterator = locks.iterator();
        while (iterator.hasNext()) {
            RangeLock lock = iterator.next();
            if (lock.getAcquiredAt() != null && lock.getAcquiredAt().isBefore(cutoff)) {
                iterator.remove();
                removedAny = true;
            }
        }
        if (!removedAny) {
            return;
        }
        if (locks.isEmpty()) {
            documentLocks.remove(documentId);
        }
        promoteQueuedLocks(documentId);
    }

    private RangeLock enqueueLock(String documentId, String username, long revision, int start, int end) {
        List<RangeLock> queuedLocks = documentLockQueues.computeIfAbsent(documentId, key -> new ArrayList<>());
        for (RangeLock queuedLock : queuedLocks) {
            if (username.equals(queuedLock.getOwner())) {
                return copyLock(queuedLock);
            }
        }

        RangeLock lock = new RangeLock(
                IdGenerator.nextLockId(),
                documentId,
                username,
                revision,
                start,
                end,
                start,
                end,
                LocalDateTime.now()
        );
        lock.setQueued(true);
        queuedLocks.add(lock);
        lock.setQueuePosition(queuedLocks.size());
        return copyLock(lock);
    }

    private List<RangeLock> promoteQueuedLocks(String documentId) {
        List<RangeLock> promoted = new ArrayList<>();
        List<RangeLock> queuedLocks = documentLockQueues.get(documentId);
        if (queuedLocks == null || queuedLocks.isEmpty()) {
            return promoted;
        }

        List<RangeLock> activeLocks = documentLocks.computeIfAbsent(documentId, key -> new ArrayList<>());
        Iterator<RangeLock> iterator = queuedLocks.iterator();
        while (iterator.hasNext()) {
            RangeLock queuedLock = iterator.next();
            if (conflictsWithActive(activeLocks, queuedLock)) {
                break;
            }
            queuedLock.setQueued(false);
            queuedLock.setQueuePosition(0);
            queuedLock.setAcquiredAt(LocalDateTime.now());
            activeLocks.add(queuedLock);
            promoted.add(copyLock(queuedLock));
            iterator.remove();
        }

        if (queuedLocks.isEmpty()) {
            documentLockQueues.remove(documentId);
        } else {
            updateQueuePositions(queuedLocks);
        }
        if (activeLocks.isEmpty()) {
            documentLocks.remove(documentId);
        }
        return promoted;
    }

    private boolean conflictsWithActive(List<RangeLock> activeLocks, RangeLock queuedLock) {
        for (RangeLock activeLock : activeLocks) {
            if (queuedLock.getOwner().equals(activeLock.getOwner())) {
                return true;
            }
            if (overlaps(
                    queuedLock.getCurrentStart(),
                    queuedLock.getCurrentEnd(),
                    activeLock.getCurrentStart(),
                    activeLock.getCurrentEnd())) {
                return true;
            }
        }
        return false;
    }

    private void shiftLock(RangeLock lock, int editStart, int editEnd, int delta) {
        lock.setCurrentStart(transformPosition(lock.getCurrentStart(), editStart, editEnd, delta));
        lock.setCurrentEnd(transformPosition(lock.getCurrentEnd(), editStart, editEnd, delta));
    }

    private void updateQueuePositions(List<RangeLock> queuedLocks) {
        for (int index = 0; index < queuedLocks.size(); index += 1) {
            queuedLocks.get(index).setQueuePosition(index + 1);
        }
    }

    private boolean overlaps(int startA, int endA, int startB, int endB) {
        if (startA == endA && startB == endB) {
            return startA == startB;
        }
        if (startA == endA) {
            return startA > startB && startA < endB;
        }
        if (startB == endB) {
            return startB > startA && startB < endA;
        }
        return startA < endB && startB < endA;
    }

    private int transformPosition(int position, int start, int end, int delta) {
        if (position <= start) {
            return position;
        }
        if (position >= end) {
            return position + delta;
        }
        int mapped = start + Math.min(position - start, Math.max(0, end - start + delta));
        return Math.max(start, mapped);
    }

    private List<RangeLock> copyLocks(List<RangeLock> locks) {
        List<RangeLock> result = new ArrayList<>();
        for (RangeLock lock : locks) {
            result.add(copyLock(lock));
        }
        return result;
    }

    private List<RangeLock> copyLocksWithQueuePositions(List<RangeLock> locks) {
        List<RangeLock> result = new ArrayList<>();
        for (int index = 0; index < locks.size(); index += 1) {
            RangeLock copy = copyLock(locks.get(index));
            copy.setQueued(true);
            copy.setQueuePosition(index + 1);
            result.add(copy);
        }
        return result;
    }

    private RangeLock copyLock(RangeLock lock) {
        RangeLock copy = new RangeLock(
                lock.getLockId(),
                lock.getDocumentId(),
                lock.getOwner(),
                lock.getBaseRevision(),
                lock.getBaseStart(),
                lock.getBaseEnd(),
                lock.getCurrentStart(),
                lock.getCurrentEnd(),
                lock.getAcquiredAt()
        );
        copy.setQueued(lock.isQueued());
        copy.setQueuePosition(lock.getQueuePosition());
        return copy;
    }
}
