package com.sharedoc.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One text range mutation stored inside an incremental version record.
 */
public class VersionPatch implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String editor;
    private String editTime;
    private int start;
    private int end;
    private String originalText;
    private String replacementText;
    private long revisionBefore;
    private long revisionAfter;
    private String comment;

    public VersionPatch() {
    }

    public VersionPatch(String editor, int start, int end, String originalText, String replacementText,
                        long revisionBefore, long revisionAfter, String comment) {
        this.editor = editor;
        this.editTime = LocalDateTime.now().toString();
        this.start = start;
        this.end = end;
        this.originalText = originalText;
        this.replacementText = replacementText;
        this.revisionBefore = revisionBefore;
        this.revisionAfter = revisionAfter;
        this.comment = comment;
    }

    public String getEditor() {
        return editor;
    }

    public void setEditor(String editor) {
        this.editor = editor;
    }

    public String getEditTime() {
        return editTime;
    }

    public void setEditTime(String editTime) {
        this.editTime = editTime;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getReplacementText() {
        return replacementText;
    }

    public void setReplacementText(String replacementText) {
        this.replacementText = replacementText;
    }

    public long getRevisionBefore() {
        return revisionBefore;
    }

    public void setRevisionBefore(long revisionBefore) {
        this.revisionBefore = revisionBefore;
    }

    public long getRevisionAfter() {
        return revisionAfter;
    }

    public void setRevisionAfter(long revisionAfter) {
        this.revisionAfter = revisionAfter;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
