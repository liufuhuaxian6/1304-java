package com.sharedoc.storage;

import com.sharedoc.server.ServerConfig;

import java.nio.file.Path;

/**
 * Version file storage helper skeleton.
 * Builds and manages file paths for historical document versions.
 */
public class VersionStorage {
    public String saveVersionFile(String sourcePath, String documentId, String versionId, String fileName) {
        // TODO: Copy sourcePath into the generated version path.
        return buildVersionFilePath(documentId, versionId, fileName);
    }

    public byte[] readVersionFile(String versionPath) {
        // TODO: Read historical version file bytes from versionPath.
        return new byte[0];
    }

    public String buildVersionFilePath(String documentId, String versionId, String fileName) {
        // TODO: Sanitize fileName before using it in filesystem paths.
        return Path.of(ServerConfig.VERSION_STORAGE_PATH, documentId, versionId + "-" + fileName).toString();
    }
}
