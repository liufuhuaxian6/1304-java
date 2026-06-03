package com.sharedoc.server;

import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.storage.FileStorage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server application entry point.
 * Listens for client sockets and creates one ClientHandler thread for each connection.
 */
public class ServerMain {
    public static void main(String[] args) {
        FileStorage fileStorage = new FileStorage();
        fileStorage.createDirectoryIfNotExists(ServerConfig.DOCUMENT_STORAGE_PATH);
        fileStorage.createDirectoryIfNotExists(ServerConfig.VERSION_STORAGE_PATH);

        DocumentService documentService = new DocumentService();
        UserService userService = new UserService(documentService);
        VersionService versionService = new VersionService();
        
        // Start HTTP API Server
        HttpApiServer apiServer = new HttpApiServer(userService, documentService, versionService);
        apiServer.start(8082); // Changed port to 8082 to avoid conflict

        ExecutorService executorService = createClientExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownExecutor(executorService), "server-shutdown"));

        try (ServerSocket serverSocket = new ServerSocket(ServerConfig.PORT, ServerConfig.BACKLOG)) {
            System.out.println("Shared document server started on port " + ServerConfig.PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket, userService, documentService, versionService);
                try {
                    executorService.execute(handler);
                } catch (RejectedExecutionException e) {
                    System.err.println("Client connection rejected: " + e.getMessage());
                    closeClientSocket(clientSocket);
                }
            }
        } catch (IOException e) {
            System.err.println("Server failed to start or accept client: " + e.getMessage());
        } finally {
            shutdownExecutor(executorService);
        }
    }

    private static ExecutorService createClientExecutor() {
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        AtomicInteger threadCounter = new AtomicInteger(1);
        return Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("client-handler-" + threadCounter.getAndIncrement());
            return thread;
        });
    }

    private static void shutdownExecutor(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void closeClientSocket(Socket clientSocket) {
        try {
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("Failed to close rejected client socket: " + e.getMessage());
        }
    }
}
