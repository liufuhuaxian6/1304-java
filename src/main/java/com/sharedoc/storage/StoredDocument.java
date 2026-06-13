package com.sharedoc.storage;

import java.time.LocalDateTime;

/**
 * Persistence representation of a document's metadata.
 * Unlike the API {@code Document} model it deliberately carries
 * {@code currentPath} (the internal storage location), which is written to
 * and read from {@code documents.json} but never exposed to clients.
 * Derived editing state (current editor, lock count) is not persisted.
 */
public class StoredDocument {
    private String documentId;
    private String fileName;
    private String owner;
    private String currentPath;
    private LocalDateTime uploadTime;
    private LocalDateTime lastModifiedTime;
    private long revision;

    public StoredDocument() {
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

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
