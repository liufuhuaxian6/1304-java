package com.sharedoc.server;

import com.sharedoc.model.Request;
import com.sharedoc.model.Response;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Handles requests from one connected client.
 * This class is the main place for request dispatching in the server skeleton.
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final UserService userService;
    private final DocumentService documentService;
    private final VersionService versionService;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.userService = new UserService();
        this.documentService = new DocumentService();
        this.versionService = new VersionService();
    }

    @Override
    public void run() {
        try (ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream input = new ObjectInputStream(socket.getInputStream())) {
            while (!socket.isClosed()) {
                Object object = input.readObject();
                if (object instanceof Request request) {
                    Response response = handleRequest(request);
                    output.writeObject(response);
                    output.flush();
                } else {
                    output.writeObject(Response.fail("Unsupported request object."));
                    output.flush();
                }
            }
        } catch (EOFException e) {
            System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Client handler error: " + e.getMessage());
        } finally {
            closeSocket();
        }
    }

    private Response handleRequest(Request request) {
        if (request == null || request.getType() == null) {
            return Response.fail("Request type is required.");
        }

        return switch (request.getType()) {
            case LOGIN -> userService.login(request.getUsername(), String.valueOf(request.getPayload()));
            case LOGOUT -> userService.logout(request.getUsername());
            case LIST_DOCUMENTS -> documentService.listDocuments();
            case UPLOAD_DOCUMENT -> documentService.uploadDocument(request);
            case DOWNLOAD_DOCUMENT -> documentService.downloadDocument(request.getDocumentId());
            case VIEW_DOCUMENT -> documentService.viewDocument(request.getDocumentId());
            case REQUEST_EDIT -> documentService.requestEdit(request.getDocumentId(), request.getUsername());
            case SAVE_DOCUMENT -> documentService.saveDocument(request);
            case RELEASE_EDIT -> documentService.releaseEdit(request.getDocumentId(), request.getUsername());
            case LIST_VERSIONS -> versionService.listVersions(request.getDocumentId());
            case DOWNLOAD_VERSION -> versionService.downloadVersion(String.valueOf(request.getPayload()));
            case ROLLBACK_VERSION -> versionService.rollbackToVersion(request.getDocumentId(), String.valueOf(request.getPayload()));
        };
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            // TODO: Replace console logging with a simple project logger if needed.
            System.err.println("Failed to close client socket: " + e.getMessage());
        }
    }
}
