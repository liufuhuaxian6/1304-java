package com.sharedoc.model;

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
}
