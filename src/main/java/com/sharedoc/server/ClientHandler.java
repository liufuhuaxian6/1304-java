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
    private String currentUsername;

    public ClientHandler(Socket socket, UserService userService,
                         DocumentService documentService, VersionService versionService) {
        this.socket = socket;
        this.userService = userService;
        this.documentService = documentService;
        this.versionService = versionService;
    }

    @Override
    public void run() {
        try (ObjectOutputStream output = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream input = new ObjectInputStream(socket.getInputStream())) {
            while (!socket.isClosed()) {
                Object object;
                try {
                    object = input.readObject();
                } catch (ClassNotFoundException e) {
                    writeResponse(output, Response.fail("Unsupported request payload class."));
                    continue;
                }

                Response response = handleIncomingObject(object);
                writeResponse(output, response);

                if (object instanceof Request request
                        && request.getType() == com.sharedoc.model.RequestType.LOGOUT
                        && response.isSuccess()) {
                    currentUsername = null;
                }
            }
        } catch (EOFException e) {
            System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("Client handler error: " + e.getMessage());
        } finally {
            cleanupLoggedInUser();
            closeSocket();
        }
    }

    private Response handleIncomingObject(Object object) {
        if (!(object instanceof Request request)) {
            return Response.fail("Unsupported request object.");
        }
        return handleRequest(request);
    }

    private Response handleRequest(Request request) {
        if (request == null || request.getType() == null) {
            return Response.fail("Request type is required.");
        }

        normalizeRequestUsername(request);

        try {
            Response response = switch (request.getType()) {
                case LOGIN -> userService.login(request.getUsername(), extractStringPayload(request.getPayload()));
                case LOGOUT -> userService.logout(request.getUsername());
                case LIST_DOCUMENTS -> documentService.listDocuments();
                case UPLOAD_DOCUMENT -> documentService.uploadDocument(request);
                case DOWNLOAD_DOCUMENT -> documentService.downloadDocument(request.getDocumentId());
                case VIEW_DOCUMENT -> documentService.viewDocument(request.getDocumentId());
                case REQUEST_EDIT -> documentService.requestEdit(request.getDocumentId(), request.getUsername());
                case SAVE_DOCUMENT -> documentService.saveDocument(request);
                case RELEASE_EDIT -> documentService.releaseEdit(request.getDocumentId(), request.getUsername());
                case LIST_VERSIONS -> versionService.listVersions(request.getDocumentId());
                case DOWNLOAD_VERSION -> versionService.downloadVersion(extractStringPayload(request.getPayload()));
                case ROLLBACK_VERSION -> documentService.rollbackDocumentToVersion(request);
            };

            if (request.getType() == com.sharedoc.model.RequestType.LOGIN && response.isSuccess()) {
                currentUsername = request.getUsername();
            }
            return response;
        } catch (RuntimeException e) {
            return Response.fail("服务器处理请求时发生异常: " + e.getMessage());
        }
    }

    private void normalizeRequestUsername(Request request) {
        if (request.getType() != com.sharedoc.model.RequestType.LOGIN
                && isBlank(request.getUsername())
                && !isBlank(currentUsername)) {
            request.setUsername(currentUsername);
        }
    }

    private String extractStringPayload(Object payload) {
        if (payload == null) {
            return null;
        }
        return String.valueOf(payload);
    }

    private void writeResponse(ObjectOutputStream output, Response response) throws IOException {
        output.writeObject(response);
        output.flush();
        output.reset();
    }

    private void cleanupLoggedInUser() {
        if (isBlank(currentUsername)) {
            return;
        }
        try {
            userService.logout(currentUsername);
        } catch (RuntimeException e) {
            System.err.println("Failed to clean up disconnected user session: " + e.getMessage());
        } finally {
            currentUsername = null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
