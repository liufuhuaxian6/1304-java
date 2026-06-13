package com.sharedoc.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * One JSON file used as a small metadata store.
 *
 * Writes are atomic (temp file + atomic move) so a crash mid-write cannot
 * leave a half-written file. {@code write} is synchronized so concurrent
 * callers persisting the same file are serialized.
 */
public class JsonStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;

    public JsonStore(Path file) {
        this.file = file;
    }

    public synchronized void write(Object value) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, MAPPER.writeValueAsBytes(value));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist metadata file: " + file, e);
        }
    }

    public <T> T read(TypeReference<T> type) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load metadata file: " + file, e);
        }
    }
}
