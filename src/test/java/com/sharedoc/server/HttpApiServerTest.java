package com.sharedoc.server;

import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpApiServerTest {
    private HttpApiServer apiServer;
    private HttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        TestStateHelper.resetState();

        int port = findFreePort();
        baseUrl = "http://127.0.0.1:" + port + "/api/v1";
        httpClient = HttpClient.newHttpClient();

        DocumentService documentService = new DocumentService();
        UserService userService = new UserService(documentService);
        VersionService versionService = new VersionService();

        apiServer = new HttpApiServer(userService, documentService, versionService);
        apiServer.start(port);
    }

    @AfterEach
    void tearDown() {
        if (apiServer != null) {
            apiServer.stop();
        }
        TestStateHelper.resetState();
    }

    @Test
    void httpSmokeFlowCoversRangeLockEndpoints() throws Exception {
        Map<String, Object> loginResponse = jsonResponse(sendJson("POST", "/auth/login", """
                {"username":"admin","password":"123456"}
                """, null));
        assertEquals(200, (int) loginResponse.get("_status"));
        Map<String, Object> loginData = map(loginResponse.get("data"));
        String token = String.valueOf(loginData.get("token"));

        String boundary = "----CodexBoundary" + UUID.randomUUID();
        HttpResponse<String> uploadResponse = sendMultipart("/documents", token, boundary, "smoke.md", "# hello");
        Map<String, Object> uploadJson = jsonResponse(uploadResponse);
        assertEquals(200, uploadResponse.statusCode());
        Map<String, Object> uploadData = map(uploadJson.get("data"));
        Map<String, Object> uploadedDocument = map(uploadData.get("document"));
        String documentId = String.valueOf(uploadedDocument.get("documentId"));

        HttpResponse<String> contentResponse = sendJson("GET", "/documents/" + documentId + "/content", null, token);
        Map<String, Object> contentJson = jsonResponse(contentResponse);
        Map<String, Object> contentData = map(contentJson.get("data"));
        assertEquals("# hello", contentData.get("contentText"));
        assertEquals(1.0, contentData.get("revision"));

        HttpResponse<String> lockResponse = sendJson("POST", "/documents/" + documentId + "/lock", """
                {"start":6,"end":6,"revision":1}
                """, token);
        assertEquals(200, lockResponse.statusCode());
        Map<String, Object> lockJson = jsonResponse(lockResponse);
        Map<String, Object> lockData = map(lockJson.get("data"));
        String lockId = String.valueOf(lockData.get("lockId"));

        HttpResponse<String> saveResponse = sendJson("PATCH", "/documents/" + documentId + "/content", """
                {"lockId":"%s","clientRevision":1,"replacementText":"updated","comment":"区间编辑保存"}
                """.formatted(lockId), token);
        assertEquals(200, saveResponse.statusCode());

        HttpResponse<String> versionsResponse = sendJson("GET", "/documents/" + documentId + "/versions", null, token);
        Map<String, Object> versionsJson = jsonResponse(versionsResponse);
        assertEquals(200, versionsResponse.statusCode());
        assertEquals(2, list(versionsJson.get("data")).size());

        HttpResponse<byte[]> downloadResponse = sendDownload("/documents/" + documentId + "/download", token);
        assertEquals(200, downloadResponse.statusCode());
        assertArrayEquals("# hellupdatedo".getBytes(StandardCharsets.UTF_8), downloadResponse.body());

        HttpResponse<String> secondReleaseResponse = sendJson("DELETE", "/documents/" + documentId + "/lock", null, token);
        Map<String, Object> releaseJson = jsonResponse(secondReleaseResponse);
        assertEquals(403, secondReleaseResponse.statusCode());
        assertFalse((Boolean) releaseJson.get("success"));
    }

    @Test
    void rollbackIsRejectedWhenAnyActiveLockExists() throws Exception {
        Map<String, Object> loginResponse = jsonResponse(sendJson("POST", "/auth/login", """
                {"username":"admin","password":"123456"}
                """, null));
        String token = String.valueOf(map(loginResponse.get("data")).get("token"));

        String boundary = "----CodexBoundary" + UUID.randomUUID();
        HttpResponse<String> uploadResponse = sendMultipart("/documents", token, boundary, "rollback.md", "abc");
        String documentId = String.valueOf(map(map(jsonResponse(uploadResponse).get("data")).get("document")).get("documentId"));

        sendJson("POST", "/documents/" + documentId + "/lock", """
                {"start":0,"end":1,"revision":1}
                """, token);

        HttpResponse<String> rollbackResponse = sendJson("POST", "/documents/" + documentId + "/versions/V-1/rollback", "", token);
        Map<String, Object> rollbackJson = jsonResponse(rollbackResponse);

        assertEquals(409, rollbackResponse.statusCode());
        assertEquals("ACTIVE_LOCKS_PRESENT", rollbackJson.get("code"));
    }

    @Test
    void uploadsKeepRealOwnerPerLoggedInUser() throws Exception {
        sendJson("POST", "/auth/register", """
                {"username":"alice","password":"pass123","role":"USER"}
                """, null);

        Map<String, Object> adminLogin = jsonResponse(sendJson("POST", "/auth/login", """
                {"username":"admin","password":"123456"}
                """, null));
        String adminToken = String.valueOf(map(adminLogin.get("data")).get("token"));

        Map<String, Object> aliceLogin = jsonResponse(sendJson("POST", "/auth/login", """
                {"username":"alice","password":"pass123"}
                """, null));
        String aliceToken = String.valueOf(map(aliceLogin.get("data")).get("token"));

        String adminBoundary = "----CodexBoundary" + UUID.randomUUID();
        HttpResponse<String> adminUpload = sendMultipart("/documents", adminToken, adminBoundary, "admin.md", "admin");
        assertEquals(200, adminUpload.statusCode());
        Map<String, Object> adminDocument = map(map(jsonResponse(adminUpload).get("data")).get("document"));
        assertEquals("admin", adminDocument.get("owner"));

        String aliceBoundary = "----CodexBoundary" + UUID.randomUUID();
        HttpResponse<String> aliceUpload = sendMultipart("/documents", aliceToken, aliceBoundary, "alice.md", "alice");
        assertEquals(200, aliceUpload.statusCode());
        Map<String, Object> aliceDocument = map(map(jsonResponse(aliceUpload).get("data")).get("document"));
        assertEquals("alice", aliceDocument.get("owner"));

        Map<String, Object> listJson = jsonResponse(sendJson("GET", "/documents", null, adminToken));
        List<Object> documents = list(listJson.get("data"));

        boolean foundAdminOwner = documents.stream()
                .map(this::map)
                .anyMatch(doc -> "admin.md".equals(doc.get("fileName")) && "admin".equals(doc.get("owner")));
        boolean foundAliceOwner = documents.stream()
                .map(this::map)
                .anyMatch(doc -> "alice.md".equals(doc.get("fileName")) && "alice".equals(doc.get("owner")));

        assertTrue(foundAdminOwner);
        assertTrue(foundAliceOwner);
    }

    @Test
    void nonOverlappingHttpSavesPreserveBothUsersChanges() throws Exception {
        String adminToken = login("admin", "123456");
        registerAndLoginUser("alice", "pass123");
        String aliceToken = login("alice", "pass123");

        String documentId = uploadTextDocument(adminToken, "parallel.md", "0123456789");

        HttpResponse<String> adminLockResponse = sendJson("POST", "/documents/" + documentId + "/lock", """
                {"start":0,"end":2,"revision":1}
                """, adminToken);
        HttpResponse<String> aliceLockResponse = sendJson("POST", "/documents/" + documentId + "/lock", """
                {"start":8,"end":10,"revision":1}
                """, aliceToken);
        assertEquals(200, adminLockResponse.statusCode());
        assertEquals(200, aliceLockResponse.statusCode());

        String adminLockId = String.valueOf(map(jsonResponse(adminLockResponse).get("data")).get("lockId"));
        String aliceLockId = String.valueOf(map(jsonResponse(aliceLockResponse).get("data")).get("lockId"));

        HttpResponse<String> adminSaveResponse = sendJson("PATCH", "/documents/" + documentId + "/content", """
                {"lockId":"%s","clientRevision":1,"replacementText":"AB","comment":"admin save"}
                """.formatted(adminLockId), adminToken);
        HttpResponse<String> aliceSaveResponse = sendJson("PATCH", "/documents/" + documentId + "/content", """
                {"lockId":"%s","clientRevision":1,"replacementText":"YZ","comment":"alice save"}
                """.formatted(aliceLockId), aliceToken);

        assertEquals(200, adminSaveResponse.statusCode());
        assertEquals(200, aliceSaveResponse.statusCode());

        HttpResponse<byte[]> downloadResponse = sendDownload("/documents/" + documentId + "/download", adminToken);
        assertEquals(200, downloadResponse.statusCode());
        assertArrayEquals("AB234567YZ".getBytes(StandardCharsets.UTF_8), downloadResponse.body());
    }

    @Test
    void staleRevisionLockRequestReturns409AndLatestContentRemainsFetchable() throws Exception {
        String adminToken = login("admin", "123456");
        registerAndLoginUser("alice", "pass123");
        String aliceToken = login("alice", "pass123");

        String documentId = uploadTextDocument(adminToken, "stale-lock.md", "abcdef");

        HttpResponse<String> adminLockResponse = sendJson("POST", "/documents/" + documentId + "/lock", """
                {"start":2,"end":4,"revision":1}
                """, adminToken);
        assertEquals(200, adminLockResponse.statusCode());
        String adminLockId = String.valueOf(map(jsonResponse(adminLockResponse).get("data")).get("lockId"));

        HttpResponse<String> adminSaveResponse = sendJson("PATCH", "/documents/" + documentId + "/content", """
                {"lockId":"%s","clientRevision":1,"replacementText":"ZZ","comment":"admin save"}
                """.formatted(adminLockId), adminToken);
        assertEquals(200, adminSaveResponse.statusCode());

        HttpResponse<String> staleLockResponse = sendJson("POST", "/documents/" + documentId + "/lock", """
                {"start":2,"end":4,"revision":1}
                """, aliceToken);
        Map<String, Object> staleLockJson = jsonResponse(staleLockResponse);
        assertEquals(409, staleLockResponse.statusCode());
        assertEquals("REVISION_STALE", staleLockJson.get("code"));

        HttpResponse<String> contentResponse = sendJson("GET", "/documents/" + documentId + "/content", null, aliceToken);
        Map<String, Object> contentJson = jsonResponse(contentResponse);
        Map<String, Object> contentData = map(contentJson.get("data"));
        assertEquals(200, contentResponse.statusCode());
        assertEquals("abZZef", contentData.get("contentText"));
        assertEquals(2.0, contentData.get("revision"));
    }

    private HttpResponse<String> sendJson(String method, String path, String body, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        return httpClient.send(builder.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendMultipart(String path, String token, String boundary, String fileName, String content)
            throws IOException, InterruptedException {
        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n"
                + content + "\r\n"
                + "--" + boundary + "--\r\n";

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipartBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> sendDownload(String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private void registerAndLoginUser(String username, String password) throws IOException, InterruptedException {
        sendJson("POST", "/auth/register", """
                {"username":"%s","password":"%s","role":"USER"}
                """.formatted(username, password), null);
        login(username, password);
    }

    private String login(String username, String password) throws IOException, InterruptedException {
        Map<String, Object> loginJson = jsonResponse(sendJson("POST", "/auth/login", """
                {"username":"%s","password":"%s"}
                """.formatted(username, password), null));
        return String.valueOf(map(loginJson.get("data")).get("token"));
    }

    private String uploadTextDocument(String token, String fileName, String content) throws IOException, InterruptedException {
        String boundary = "----CodexBoundary" + UUID.randomUUID();
        HttpResponse<String> uploadResponse = sendMultipart("/documents", token, boundary, fileName, content);
        Map<String, Object> uploadJson = jsonResponse(uploadResponse);
        return String.valueOf(map(map(uploadJson.get("data")).get("document")).get("documentId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonResponse(HttpResponse<String> response) {
        Map<String, Object> result = new HashMap<>(new com.google.gson.Gson().fromJson(response.body(), Map.class));
        result.put("_status", response.statusCode());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
