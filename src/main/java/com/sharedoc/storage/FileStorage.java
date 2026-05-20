package com.sharedoc.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File storage helper skeleton.
 * Encapsulates current document file operations for upload, download, view, and delete workflows.
 */
public class FileStorage {
    public String saveFile(String targetPath, byte[] content) {
        // TODO: Write uploaded bytes to targetPath and return the final storage path.
        return targetPath;
    }

    public byte[] readFile(String filePath) {
        // TODO: Read bytes from filePath and return them to service layer.
        return new byte[0];
    }

    public void copyFile(String sourcePath, String targetPath) {
        // TODO: Copy current document file to another path, usually for download or version creation.
    }

    public void deleteFile(String filePath) {
        // TODO: Delete file after permission and existence checks.
    }

    public void createDirectoryIfNotExists(String directoryPath) {
        // TODO: Add better exception handling and logging.
        try {
            Files.createDirectories(Path.of(directoryPath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directory: " + directoryPath, e);
        }
    }
}
