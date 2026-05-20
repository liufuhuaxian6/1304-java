package com.sharedoc.server;

/**
 * Server configuration constants.
 * Centralizes port and storage paths for later configuration-file replacement.
 */
public final class ServerConfig {
    public static final int PORT = 8888;
    public static final String DOCUMENT_STORAGE_PATH = "data/documents";
    public static final String VERSION_STORAGE_PATH = "data/versions";
    public static final int BACKLOG = 50;

    private ServerConfig() {
        // TODO: Replace constants with properties file loading if needed.
    }
}
