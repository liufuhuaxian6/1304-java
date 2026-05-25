package com.sharedoc.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File storage helper.
 * Encapsulates current document file operations for upload, download, view, and delete workflows.
 */
public class FileStorage {
    public String saveFile(String targetPath, byte[] content) {
        createDirectoryIfNotExists(Path.of(targetPath).getParent().toString());
        try {
            Files.write(Path.of(targetPath), content);
            return targetPath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save file: " + targetPath, e);
        }
    }

    public byte[] readFile(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                throw new IllegalStateException("File not found: " + filePath);
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file: " + filePath, e);
        }
    }

    public void copyFile(String sourcePath, String targetPath) {
        try {
            Path source = Path.of(sourcePath);
            if (!Files.exists(source)) {
                throw new IllegalStateException("Source file not found: " + sourcePath);
            }
            createDirectoryIfNotExists(Path.of(targetPath).getParent().toString());
            Files.copy(source, Path.of(targetPath), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy file from " + sourcePath + " to " + targetPath, e);
        }
    }

    public void deleteFile(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete file: " + filePath, e);
        }
    }

    public void createDirectoryIfNotExists(String directoryPath) {
        try {
            Files.createDirectories(Path.of(directoryPath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directory: " + directoryPath, e);
        }
    }
}
