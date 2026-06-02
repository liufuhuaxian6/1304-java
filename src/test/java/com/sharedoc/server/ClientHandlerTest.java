package com.sharedoc.server;

import com.sharedoc.model.Document;
import com.sharedoc.model.Request;
import com.sharedoc.model.RequestType;
import com.sharedoc.model.Response;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientHandlerTest {
    private DocumentService documentService;
    private UserService userService;
    private VersionService versionService;

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
        documentService = new DocumentService();
        userService = new UserService(documentService);
        versionService = new VersionService();
    }

    @AfterEach
    void tearDown() {
        TestStateHelper.resetState();
    }

    @Test
    void unsupportedObjectDoesNotBreakConnection() throws Exception {
        try (HandlerHarness harness = startHandlerHarness()) {
            harness.sendObject("not-a-request");
            Response invalidResponse = harness.readResponse();
            assertTrue(!invalidResponse.isSuccess());
            assertEquals("Unsupported request object.", invalidResponse.getMessage());

            harness.sendObject(new Request(RequestType.LOGIN, "admin", null, "123456"));
            Response loginResponse = harness.readResponse();
            assertTrue(loginResponse.isSuccess());
            assertEquals("登录成功", loginResponse.getMessage());
        }
    }

    @Test
    void disconnectReleasesLocksForNextConnection() throws Exception {
        String documentId;

        HandlerHarness firstHarness = startHandlerHarness();
        try {
            firstHarness.sendObject(new Request(RequestType.LOGIN, "admin", null, "123456"));
            assertTrue(firstHarness.readResponse().isSuccess());

            firstHarness.sendObject(uploadRequest("admin", "disconnect-release.md", "v1"));
            Response uploadResponse = firstHarness.readResponse();
            Document document = uploadedDocument(uploadResponse);
            documentId = document.getDocumentId();

            firstHarness.sendObject(new Request(RequestType.REQUEST_EDIT, "admin", documentId, null));
            Response lockResponse = firstHarness.readResponse();
            assertTrue(lockResponse.isSuccess());
        } finally {
            firstHarness.closeClientOnly();
            firstHarness.awaitHandlerExit();
        }

        try (HandlerHarness secondHarness = startHandlerHarness()) {
            secondHarness.sendObject(new Request(RequestType.LOGIN, "user", null, "123456"));
            assertTrue(secondHarness.readResponse().isSuccess());

            secondHarness.sendObject(new Request(RequestType.REQUEST_EDIT, "user", documentId, null));
            Response secondLockResponse = secondHarness.readResponse();
            assertTrue(secondLockResponse.isSuccess());
            assertEquals("编辑权限申请成功", secondLockResponse.getMessage());
        }
    }

    @Test
    void requestWithoutTypeReturnsFailureAndConnectionStaysUsable() throws Exception {
        try (HandlerHarness harness = startHandlerHarness()) {
            harness.sendObject(new Request(null, "admin", null, null));
            Response invalidResponse = harness.readResponse();
            assertTrue(!invalidResponse.isSuccess());
            assertEquals("Request type is required.", invalidResponse.getMessage());

            harness.sendObject(new Request(RequestType.LOGIN, "admin", null, "123456"));
            Response loginResponse = harness.readResponse();
            assertTrue(loginResponse.isSuccess());
            assertEquals("登录成功", loginResponse.getMessage());
        }
    }

    @Test
    void loggedInConnectionCanReuseStoredUsername() throws Exception {
        try (HandlerHarness harness = startHandlerHarness()) {
            harness.sendObject(new Request(RequestType.LOGIN, "admin", null, "123456"));
            assertTrue(harness.readResponse().isSuccess());

            harness.sendObject(uploadRequest("admin", "username-fallback.md", "v1"));
            Document document = uploadedDocument(harness.readResponse());

            harness.sendObject(new Request(RequestType.REQUEST_EDIT, null, document.getDocumentId(), null));
            Response lockResponse = harness.readResponse();

            assertTrue(lockResponse.isSuccess());
            assertEquals("编辑权限申请成功", lockResponse.getMessage());
        }
    }

    private HandlerHarness startHandlerHarness() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        Thread handlerThread = new Thread(() -> {
            try (ServerSocket ignored = serverSocket) {
                Socket serverSideSocket = serverSocket.accept();
                new ClientHandler(serverSideSocket, userService, documentService, versionService).run();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to run test handler.", e);
            }
        }, "client-handler-test");
        handlerThread.start();

        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        ObjectOutputStream output = new ObjectOutputStream(clientSocket.getOutputStream());
        ObjectInputStream input = new ObjectInputStream(clientSocket.getInputStream());
        return new HandlerHarness(clientSocket, output, input, handlerThread);
    }

    private Request uploadRequest(String username, String fileName, String content) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileContent", content.getBytes());
        return new Request(RequestType.UPLOAD_DOCUMENT, username, null, payload);
    }

    @SuppressWarnings("unchecked")
    private Document uploadedDocument(Response uploadResponse) {
        Map<String, Object> data = assertInstanceOf(Map.class, uploadResponse.getData());
        return assertInstanceOf(Document.class, data.get("document"));
    }

    private static final class HandlerHarness implements AutoCloseable {
        private final Socket clientSocket;
        private final ObjectOutputStream output;
        private final ObjectInputStream input;
        private final Thread handlerThread;

        private HandlerHarness(Socket clientSocket, ObjectOutputStream output,
                               ObjectInputStream input, Thread handlerThread) {
            this.clientSocket = clientSocket;
            this.output = output;
            this.input = input;
            this.handlerThread = handlerThread;
        }

        private void sendObject(Object value) throws IOException {
            output.writeObject(value);
            output.flush();
        }

        private Response readResponse() throws IOException, ClassNotFoundException {
            return assertInstanceOf(Response.class, input.readObject());
        }

        private void closeClientOnly() throws IOException {
            clientSocket.close();
        }

        private void awaitHandlerExit() throws InterruptedException {
            handlerThread.join(3000);
            assertTrue(!handlerThread.isAlive(), "Handler thread should exit after client disconnects.");
        }

        @Override
        public void close() throws IOException, InterruptedException {
            try {
                clientSocket.close();
            } finally {
                awaitHandlerExit();
            }
        }
    }
}
