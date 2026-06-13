package com.sharedoc.testutil;

import com.sharedoc.server.ServerConfig;
import com.sharedoc.util.IdGenerator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only helpers for resetting shared state between tests.
 * Business state lives in service instances now, so tests get isolation by
 * creating fresh instances; only the global ID sequences and the on-disk
 * data directories still need an explicit reset.
 */
public final class TestStateHelper {
    private TestStateHelper() {
    }

    public static void resetState() {
        deleteDataDirectories();
        resetSequence("USER_SEQUENCE");
        resetSequence("DOCUMENT_SEQUENCE");
        resetSequence("LOCK_SEQUENCE");
        createDataDirectories();
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
        deleteDirectory(Path.of(ServerConfig.METADATA_STORAGE_PATH));
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
