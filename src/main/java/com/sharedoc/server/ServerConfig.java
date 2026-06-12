package com.sharedoc.server;

/**
 * Server configuration constants.
 * Centralizes HTTP and storage settings for later configuration-file replacement.
 */
public final class ServerConfig {
    public static final int HTTP_PORT = 8082;
    public static final String DOCUMENT_STORAGE_PATH = "data/documents";
    public static final String VERSION_STORAGE_PATH = "data/versions";

    private ServerConfig() {
    }
}
