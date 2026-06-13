package com.sharedoc.storage;

import com.sharedoc.server.ServerConfig;
import com.sharedoc.util.FileNames;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Version file storage helper.
 * Builds and manages file paths for historical document versions.
 */
public class VersionStorage {
    private final FileStorage fileStorage = new FileStorage();

    public String saveVersionFile(String sourcePath, String documentId, String versionId, String fileName) {
        String versionPath = buildVersionFilePath(documentId, versionId, fileName);
        fileStorage.copyFile(sourcePath, versionPath);
        return versionPath;
    }

    public String saveVersionText(String documentId, String versionId, String fileName, String content) {
        String versionPath = buildVersionFilePath(documentId, versionId, fileName);
        fileStorage.saveFile(versionPath, content.getBytes(StandardCharsets.UTF_8));
        return versionPath;
    }

    public String buildVersionFilePath(String documentId, String versionId, String fileName) {
        return Path.of(ServerConfig.VERSION_STORAGE_PATH, documentId,
                versionId + "-" + FileNames.sanitize(fileName)).toString();
    }

    public String savePatchFile(String documentId, String versionId, String fileName, String patchContent) {
        String versionPath = buildPatchFilePath(documentId, versionId, fileName);
        fileStorage.saveFile(versionPath, patchContent.getBytes(StandardCharsets.UTF_8));
        return versionPath;
    }

    public void overwriteVersionText(String versionPath, String content) {
        fileStorage.saveFile(versionPath, content.getBytes(StandardCharsets.UTF_8));
    }

    public void deleteVersionFile(String versionPath) {
        fileStorage.deleteFile(versionPath);
    }

    public String readVersionText(String versionPath) {
        return new String(fileStorage.readFile(versionPath), StandardCharsets.UTF_8);
    }

    public String buildPatchFilePath(String documentId, String versionId, String fileName) {
        return Path.of(ServerConfig.VERSION_STORAGE_PATH, documentId,
                versionId + "-" + FileNames.sanitize(fileName) + ".patch.json").toString();
    }
}
