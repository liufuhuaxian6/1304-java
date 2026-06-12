package com.sharedoc.server;

import com.sharedoc.service.DocumentService;
import com.sharedoc.service.LockService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.storage.FileStorage;

/**
 * Server application entry point.
 * Wires all service instances explicitly: the same VersionService instance
 * is shared by DocumentService and the HTTP layer.
 */
public class ServerMain {
    public static void main(String[] args) {
        FileStorage fileStorage = new FileStorage();
        fileStorage.createDirectoryIfNotExists(ServerConfig.DOCUMENT_STORAGE_PATH);
        fileStorage.createDirectoryIfNotExists(ServerConfig.VERSION_STORAGE_PATH);

        VersionService versionService = new VersionService();
        LockService lockService = new LockService();
        DocumentService documentService = new DocumentService(fileStorage, lockService, versionService);
        UserService userService = new UserService(documentService);

        HttpApiServer apiServer = new HttpApiServer(userService, documentService, versionService);
        apiServer.start(ServerConfig.HTTP_PORT);
    }
}
