package com.sharedoc.server;

import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.storage.FileStorage;

/**
 * Server application entry point.
 * Starts the HTTP API used by the current HTML frontend.
 */
public class ServerMain {
    public static void main(String[] args) {
        FileStorage fileStorage = new FileStorage();
        fileStorage.createDirectoryIfNotExists(ServerConfig.DOCUMENT_STORAGE_PATH);
        fileStorage.createDirectoryIfNotExists(ServerConfig.VERSION_STORAGE_PATH);

        DocumentService documentService = new DocumentService();
        UserService userService = new UserService(documentService);
        VersionService versionService = new VersionService();

        HttpApiServer apiServer = new HttpApiServer(userService, documentService, versionService);
        apiServer.start(ServerConfig.HTTP_PORT);
    }
}
