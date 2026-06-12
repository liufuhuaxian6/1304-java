package com.sharedoc.testutil;

import com.sharedoc.model.Document;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.LockService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.util.IdGenerator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only helpers for resetting the in-memory application state.
 */
public final class TestStateHelper {
    private TestStateHelper() {
    }

    public static void resetState() {
        deleteDataDirectories();
        clearStaticMap(DocumentService.class, "DOCUMENTS");
        clearStaticMap(DocumentService.class, "DOCUMENT_MONITORS");
        clearStaticMap(LockService.class, "DOCUMENT_LOCKS");
        clearStaticMap(LockService.class, "DOCUMENT_LOCK_QUEUES");
        clearStaticMap(UserService.class, "ONLINE_USERS");
        clearStaticMap(VersionService.class, "VERSION_MAP");
        resetSequence("USER_SEQUENCE");
        resetSequence("DOCUMENT_SEQUENCE");
        resetSequence("VERSION_SEQUENCE");
        resetSequence("LOCK_SEQUENCE");
        createDataDirectories();
    }

    @SuppressWarnings("unchecked")
    private static void clearStaticMap(Class<?> type, String fieldName) {
        try {
            Field field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            Map<Object, Object> map = (Map<Object, Object>) field.get(null);
            map.clear();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to clear static map " + fieldName + ".", e);
        }
    }

    private static void resetSequence(String fieldName) {
        try {
            Field field = IdGenerator.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            AtomicLong sequence = (AtomicLong) field.get(null);
            sequence.set(1L);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to reset ID sequence " + fieldName + ".", e);
        }
    }

    private static void deleteDataDirectories() {
        deleteDirectory(Path.of(ServerConfig.DOCUMENT_STORAGE_PATH));
        deleteDirectory(Path.of(ServerConfig.VERSION_STORAGE_PATH));
    }

    private static void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to delete test file: " + file, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clean test directory: " + path, e);
        }
    }

    private static void createDataDirectories() {
        try {
            Files.createDirectories(Path.of(ServerConfig.DOCUMENT_STORAGE_PATH));
            Files.createDirectories(Path.of(ServerConfig.VERSION_STORAGE_PATH));
            ensureGitkeep(Path.of(ServerConfig.DOCUMENT_STORAGE_PATH, ".gitkeep"));
            ensureGitkeep(Path.of(ServerConfig.VERSION_STORAGE_PATH, ".gitkeep"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to recreate data directories for tests.", e);
        }
    }

    private static void ensureGitkeep(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
    }
}
