package com.sharedoc.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of applying a partial content update.
 */
public class ContentUpdateResult {
    private final Document document;
    private final DocumentVersion editVersion;
    private final RangeLock releasedLock;
    private final int start;
    private final int end;
    private final String replacementText;
    private final int delta;
    private final long revisionBefore;
    private final long revisionAfter;
    private final List<RangeLock> activeLocks;

    public ContentUpdateResult(Document document, DocumentVersion editVersion, RangeLock releasedLock,
                               int start, int end, String replacementText, int delta,
                               long revisionBefore, long revisionAfter, List<RangeLock> activeLocks) {
        this.document = document;
        this.editVersion = editVersion;
        this.releasedLock = releasedLock;
        this.start = start;
        this.end = end;
        this.replacementText = replacementText;
        this.delta = delta;
        this.revisionBefore = revisionBefore;
        this.revisionAfter = revisionAfter;
        this.activeLocks = activeLocks == null ? new ArrayList<>() : new ArrayList<>(activeLocks);
    }

    public Document getDocument() {
        return document;
    }

    public DocumentVersion getEditVersion() {
        return editVersion;
    }

    public RangeLock getReleasedLock() {
        return releasedLock;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public String getReplacementText() {
        return replacementText;
    }

    public int getDelta() {
        return delta;
    }

    public long getRevisionBefore() {
        return revisionBefore;
    }

    public long getRevisionAfter() {
        return revisionAfter;
    }

    public List<RangeLock> getActiveLocks() {
        return new ArrayList<>(activeLocks);
    }
}
