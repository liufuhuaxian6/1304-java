package com.sharedoc.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Document entity.
 * Describes the current document metadata and the current edit holder, if any.
 */
public class Document implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String documentId;
    private String fileName;
    private String owner;
    private String currentPath;
    private LocalDateTime uploadTime;
    private LocalDateTime lastModifiedTime;
    private String editingUser;
    private LocalDateTime editingStartTime;
    private long revision;
    private int activeLockCount;

    public Document() {
        // TODO: Keep default constructor for serialization and future data binding.
    }

    public Document(String documentId, String fileName, String owner, String currentPath) {
        this.documentId = documentId;
        this.fileName = fileName;
        this.owner = owner;
        this.currentPath = currentPath;
        this.uploadTime = LocalDateTime.now();
        this.lastModifiedTime = this.uploadTime;
        this.revision = 1L;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    @JsonIgnore
    public String getCurrentPath() {
        return currentPath;
    }

    public void setCurrentPath(String currentPath) {
        this.currentPath = currentPath;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }

    public LocalDateTime getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(LocalDateTime lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public String getEditingUser() {
        return editingUser;
    }

    public void setEditingUser(String editingUser) {
        this.editingUser = editingUser;
    }

    public LocalDateTime getEditingStartTime() {
        return editingStartTime;
    }

    public void setEditingStartTime(LocalDateTime editingStartTime) {
        this.editingStartTime = editingStartTime;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public int getActiveLockCount() {
        return activeLockCount;
    }

    public void setActiveLockCount(int activeLockCount) {
        this.activeLockCount = activeLockCount;
    }
}
