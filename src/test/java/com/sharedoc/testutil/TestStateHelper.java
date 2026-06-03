package com.sharedoc.testutil;

import com.sharedoc.model.Document;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.LockService;
import com.sharedoc.service.UserService;
import com.sharedoc.util.IdGenerator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only helpers for resetting the in-memory application state.
 */
public final class TestStateHelper {
    private TestStateHelper() {
    }

    public static void resetState() {
        deleteTrackedDocumentFiles();
        clearStaticMap(DocumentService.class, "DOCUMENTS");
        clearStaticMap(LockService.class, "DOCUMENT_LOCKS");
        clearStaticMap(UserService.class, "ONLINE_USERS");
        resetSequence("USER_SEQUENCE");
        resetSequence("DOCUMENT_SEQUENCE");
        resetSequence("VERSION_SEQUENCE");
        createDataDirectories();
    }

    @SuppressWarnings("unchecked")
    private static void deleteTrackedDocumentFiles() {
        try {
            Field documentsField = DocumentService.class.getDeclaredField("DOCUMENTS");
            documentsField.setAccessible(true);
            Map<String, Document> documents = (Map<String, Document>) documentsField.get(null);
            for (Document document : documents.values()) {
                if (document != null && document.getCurrentPath() != null) {
                    Files.deleteIfExists(Path.of(document.getCurrentPath()));
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException | IOException e) {
            throw new IllegalStateException("Failed to delete tracked document files for tests.", e);
        }
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

    private static void createDataDirectories() {
        try {
            Files.createDirectories(Path.of(ServerConfig.DOCUMENT_STORAGE_PATH));
            Files.createDirectories(Path.of(ServerConfig.VERSION_STORAGE_PATH));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to recreate data directories for tests.", e);
        }
    }
}
