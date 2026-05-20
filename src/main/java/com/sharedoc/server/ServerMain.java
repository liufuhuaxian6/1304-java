package com.sharedoc.server;

import com.sharedoc.storage.FileStorage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Server application entry point.
 * Listens for client sockets and creates one ClientHandler thread for each connection.
 */
public class ServerMain {
    public static void main(String[] args) {
        FileStorage fileStorage = new FileStorage();
        fileStorage.createDirectoryIfNotExists(ServerConfig.DOCUMENT_STORAGE_PATH);
        fileStorage.createDirectoryIfNotExists(ServerConfig.VERSION_STORAGE_PATH);

        try (ServerSocket serverSocket = new ServerSocket(ServerConfig.PORT, ServerConfig.BACKLOG)) {
            System.out.println("Shared document server started on port " + ServerConfig.PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                new Thread(handler, "client-handler-" + clientSocket.getPort()).start();
            }
        } catch (IOException e) {
            System.err.println("Server failed to start or accept client: " + e.getMessage());
        }
    }
}
