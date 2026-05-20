package com.sharedoc.service;

import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.OperationType;
import com.sharedoc.model.Response;
import com.sharedoc.storage.VersionStorage;
import com.sharedoc.util.IdGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version service skeleton.
 * Records version metadata and delegates version file path handling to VersionStorage.
 */
public class VersionService {
    private static final Map<String, List<DocumentVersion>> VERSION_MAP = new ConcurrentHashMap<>();

    private final VersionStorage versionStorage = new VersionStorage();

    public Response createInitialVersion(String documentId, String fileName, String username, String sourcePath) {
        // TODO: Copy uploaded file as version 1 and persist version metadata.
        DocumentVersion version = buildVersion(documentId, fileName, username, OperationType.UPLOAD, "Initial upload.");
        VERSION_MAP.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
        return new Response(true, "Create initial version placeholder.", version);
    }

    public Response createEditVersion(String documentId, String fileName, String username, String sourcePath, String comment) {
        // TODO: Copy current saved file into version storage and append version metadata.
        DocumentVersion version = buildVersion(documentId, fileName, username, OperationType.EDIT, comment);
        VERSION_MAP.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
        return new Response(true, "Create edit version placeholder.", version);
    }

    public Response listVersions(String documentId) {
        // TODO: Sort by version number or edit time when metadata is finalized.
        List<DocumentVersion> versions = VERSION_MAP.getOrDefault(documentId, new ArrayList<>());
        return new Response(true, "List versions placeholder.", versions);
    }

    public Response downloadVersion(String versionId) {
        // TODO: Locate version by ID and read the version file content.
        return Response.ok("Download version placeholder for " + versionId + ".");
    }

    public Response rollbackToVersion(String documentId, String versionId) {
        // TODO: Copy historical file back to current document path and create rollback version.
        return Response.ok("Rollback placeholder for document " + documentId + ", version " + versionId + ".");
    }

    private DocumentVersion buildVersion(String documentId, String fileName, String username,
                                         OperationType operationType, String comment) {
        String versionId = IdGenerator.nextVersionId();
        String versionPath = versionStorage.buildVersionFilePath(documentId, versionId, fileName);
        return new DocumentVersion(versionId, documentId, fileName, username, operationType, versionPath, comment);
    }
}
