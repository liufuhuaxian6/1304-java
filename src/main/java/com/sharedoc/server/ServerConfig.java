package com.sharedoc.server;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Server configuration.
 * Every value has a sensible default and can be overridden through an
 * environment variable, so deployments do not require code changes.
 */
public final class ServerConfig {
    /** HTTP listen port. Override: SHAREDOC_HTTP_PORT */
    public static final int HTTP_PORT = intFromEnv("SHAREDOC_HTTP_PORT", 8082);

    /** Current document storage directory. Override: SHAREDOC_DOCUMENT_DIR */
    public static final String DOCUMENT_STORAGE_PATH = fromEnv("SHAREDOC_DOCUMENT_DIR", "data/documents");

    /** Version storage directory. Override: SHAREDOC_VERSION_DIR */
    public static final String VERSION_STORAGE_PATH = fromEnv("SHAREDOC_VERSION_DIR", "data/versions");

    /** Metadata (users / documents / versions index) directory. Override: SHAREDOC_METADATA_DIR */
    public static final String METADATA_STORAGE_PATH = fromEnv("SHAREDOC_METADATA_DIR", "data/metadata");

    /** Frontend static files directory served by the backend. Override: SHAREDOC_FRONTEND_DIR */
    public static final String FRONTEND_DIR = fromEnv("SHAREDOC_FRONTEND_DIR", "frontend");

    /** Sliding session expiry. Override: SHAREDOC_SESSION_TTL_MINUTES */
    public static final Duration SESSION_TTL =
            Duration.ofMinutes(intFromEnv("SHAREDOC_SESSION_TTL_MINUTES", 30));

    /** Stale range-lock expiry. Override: SHAREDOC_LOCK_TTL_MINUTES */
    public static final Duration LOCK_TTL =
            Duration.ofMinutes(intFromEnv("SHAREDOC_LOCK_TTL_MINUTES", 10));

    /** Maximum upload size in bytes. Override: SHAREDOC_MAX_UPLOAD_BYTES */
    public static final int MAX_UPLOAD_BYTES = intFromEnv("SHAREDOC_MAX_UPLOAD_BYTES", 5 * 1024 * 1024);

    /**
     * Allowed CORS origins, comma separated. Use "*" to allow any origin
     * (development only). Override: SHAREDOC_CORS_ORIGINS
     */
    public static final List<String> CORS_ORIGINS = parseOrigins(
            fromEnv("SHAREDOC_CORS_ORIGINS", "http://localhost:8003,http://127.0.0.1:8003"));

    private ServerConfig() {
    }

    private static String fromEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intFromEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static List<String> parseOrigins(String raw) {
        return List.copyOf(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
    }
}
