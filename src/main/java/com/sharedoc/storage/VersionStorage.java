package com.sharedoc.storage;

import com.sharedoc.server.ServerConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Version file storage helper skeleton.
 * Builds and manages file paths for historical document versions.
 */
public class VersionStorage {
    private final FileStorage fileStorage = new FileStorage();

    public String saveVersionFile(String sourcePath, String documentId, String versionId, String fileName) {
        String versionPath = buildVersionFilePath(documentId, versionId, fileName);
        fileStorage.copyFile(sourcePath, versionPath);
        return versionPath;
    }

    public byte[] readVersionFile(String versionPath) {
        return fileStorage.readFile(versionPath);
    }

    public String buildVersionFilePath(String documentId, String versionId, String fileName) {
        return Path.of(ServerConfig.VERSION_STORAGE_PATH, documentId, versionId + "-" + sanitizeFileName(fileName)).toString();
    }

    public String savePatchFile(String documentId, String versionId, String fileName, String patchContent) {
        String versionPath = buildPatchFilePath(documentId, versionId, fileName);
        fileStorage.saveFile(versionPath, patchContent.getBytes(StandardCharsets.UTF_8));
        return versionPath;
    }

    public void overwriteVersionText(String versionPath, String content) {
        fileStorage.saveFile(versionPath, content.getBytes(StandardCharsets.UTF_8));
    }

    public String readVersionText(String versionPath) {
        return new String(readVersionFile(versionPath), StandardCharsets.UTF_8);
    }

    public String buildPatchFilePath(String documentId, String versionId, String fileName) {
        return Path.of(ServerConfig.VERSION_STORAGE_PATH, documentId, versionId + "-" + sanitizeFileName(fileName) + ".patch.json").toString();
    }

    public void restoreVersionFile(String versionPath, String targetPath) {
        fileStorage.copyFile(versionPath, targetPath);
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }

        String normalized = fileName.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        String sanitized = baseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (sanitized.isEmpty()) {
            return "document";
        }

        try {
            return Path.of(sanitized).getFileName().toString();
        } catch (InvalidPathException e) {
            return "document";
        }
    }
}
