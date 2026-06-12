package com.sharedoc.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Document version entity.
 * Records one historical operation and the corresponding version file location.
 */
public class DocumentVersion implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String versionId;
    private String documentId;
    private String fileName;
    private String editor;
    private LocalDateTime editTime;
    private OperationType operationType;
    private String versionPath;
    private String comment;
    private String storageType;
    private int patchCount;

    public DocumentVersion() {
        // TODO: Keep default constructor for serialization and future data binding.
    }

    public DocumentVersion(String versionId, String documentId, String fileName, String editor,
                           OperationType operationType, String versionPath, String comment) {
        this.versionId = versionId;
        this.documentId = documentId;
        this.fileName = fileName;
        this.editor = editor;
        this.editTime = LocalDateTime.now();
        this.operationType = operationType;
        this.versionPath = versionPath;
        this.comment = comment;
        this.storageType = "FULL";
        this.patchCount = 0;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
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

    public String getEditor() {
        return editor;
    }

    public void setEditor(String editor) {
        this.editor = editor;
    }

    public LocalDateTime getEditTime() {
        return editTime;
    }

    public void setEditTime(LocalDateTime editTime) {
        this.editTime = editTime;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public String getVersionPath() {
        return versionPath;
    }

    public void setVersionPath(String versionPath) {
        this.versionPath = versionPath;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public int getPatchCount() {
        return patchCount;
    }

    public void setPatchCount(int patchCount) {
        this.patchCount = patchCount;
    }
}
