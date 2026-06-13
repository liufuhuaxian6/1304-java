package com.sharedoc.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.LockService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.storage.FileStorage;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpApiServer apiServer;
    private HttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        TestStateHelper.resetState();

        int port = findFreePort();
        baseUrl = "http://127.0.0.1:" + port + "/api/v1";
        httpClient = HttpClient.newHttpClient();

        VersionService versionService = new VersionService();
        DocumentService documentService = new DocumentService(new FileStorage(), new LockService(), versionService);
        UserService userService = new UserService(documentService);

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
        assertEquals(1, contentData.get("revision"));

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
        assertEquals(2, contentData.get("revision"));
    }

    @Test
    void documentResponsesDoNotLeakInternalStoragePath() throws Exception {
        String token = login("admin", "123456");
        String documentId = uploadTextDocument(token, "leak.md", "x");

        HttpResponse<String> list = sendJson("GET", "/documents", null, token);
        assertFalse(list.body().contains("currentPath"),
                "Document list must not expose the internal storage path");

        HttpResponse<String> content = sendJson("GET", "/documents/" + documentId + "/content", null, token);
        assertFalse(content.body().contains("currentPath"),
                "Document content must not expose the internal storage path");
    }

    @Test
    void deleteAndRenameEndpointsWork() throws Exception {
        String token = login("admin", "123456");
        String documentId = uploadTextDocument(token, "manage.md", "content");

        HttpResponse<String> rename = sendJson("PATCH", "/documents/" + documentId, """
                {"fileName":"renamed.md"}
                """, token);
        assertEquals(200, rename.statusCode());
        assertEquals("renamed.md", map(map(jsonResponse(rename).get("data")).get("document")).get("fileName"));

        HttpResponse<String> delete = sendJson("DELETE", "/documents/" + documentId, null, token);
        assertEquals(200, delete.statusCode());

        HttpResponse<String> afterDelete = sendJson("GET", "/documents/" + documentId + "/content", null, token);
        assertEquals(404, afterDelete.statusCode());
    }

    @Test
    void changePasswordEndpointUpdatesCredentials() throws Exception {
        registerAndLoginUser("dave", "oldpass1");
        String token = login("dave", "oldpass1");

        HttpResponse<String> change = sendJson("POST", "/auth/password", """
                {"currentPassword":"oldpass1","newPassword":"newpass1"}
                """, token);
        assertEquals(200, change.statusCode());

        HttpResponse<String> oldLogin = sendJson("POST", "/auth/login", """
                {"username":"dave","password":"oldpass1"}
                """, null);
        assertEquals(401, oldLogin.statusCode());

        HttpResponse<String> newLogin = sendJson("POST", "/auth/login", """
                {"username":"dave","password":"newpass1"}
                """, null);
        assertEquals(200, newLogin.statusCode());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        HttpResponse<String> health = sendJson("GET", "/health", null, null);
        assertEquals(200, health.statusCode());
        assertEquals("UP", map(jsonResponse(health).get("data")).get("status"));
    }

    @Test
    void unauthorizedRequestsAreRejectedWithoutLeakingData() throws Exception {
        String adminToken = login("admin", "123456");
        String documentId = uploadTextDocument(adminToken, "secret.md", "TOP-SECRET-CONTENT");

        HttpResponse<String> listResponse = sendJson("GET", "/documents", null, null);
        assertEquals(401, listResponse.statusCode());
        assertEquals("AUTH_REQUIRED", jsonResponse(listResponse).get("code"));
        assertFalse(listResponse.body().contains("secret.md"));

        HttpResponse<String> contentResponse = sendJson("GET", "/documents/" + documentId + "/content", null, null);
        assertEquals(401, contentResponse.statusCode());
        assertFalse(contentResponse.body().contains("TOP-SECRET-CONTENT"),
                "Unauthorized content request must not leak document text");

        HttpResponse<byte[]> downloadResponse = sendDownload("/documents/" + documentId + "/download", null);
        assertEquals(401, downloadResponse.statusCode());
        assertFalse(new String(downloadResponse.body(), StandardCharsets.UTF_8).contains("TOP-SECRET-CONTENT"),
                "Unauthorized download must not leak file bytes");

        HttpResponse<String> staleTokenResponse = sendJson("GET", "/documents", null, "token-invalid");
        assertEquals(401, staleTokenResponse.statusCode());

        HttpResponse<String> versionsResponse = sendJson("GET", "/documents/" + documentId + "/versions", null, null);
        assertEquals(401, versionsResponse.statusCode());
        assertFalse(versionsResponse.body().contains("secret.md"));
    }

    @Test
    void corsPreflightRequestsBypassAuthentication() throws Exception {
        // Browsers send an OPTIONS preflight without credentials before any
        // cross-origin request that carries an Authorization header. The auth
        // filter must let it through, otherwise the frontend cannot call any
        // protected endpoint at all.
        HttpRequest preflight = HttpRequest.newBuilder(URI.create(baseUrl + "/documents"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:8003")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .build();
        HttpResponse<String> response = httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:8003",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        HttpRequest nestedPreflight = HttpRequest.newBuilder(URI.create(baseUrl + "/documents/D-1/lock"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:8003")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .build();
        assertEquals(200, httpClient.send(nestedPreflight, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void registrationCannotEscalateToAdminRole() throws Exception {
        HttpResponse<String> registerResponse = sendJson("POST", "/auth/register", """
                {"username":"mallory","password":"pass123","role":"ADMIN"}
                """, null);
        assertEquals(201, registerResponse.statusCode());
        Map<String, Object> registerData = map(jsonResponse(registerResponse).get("data"));
        assertEquals("USER", registerData.get("role"));

        String malloryToken = login("mallory", "pass123");
        HttpResponse<String> meResponse = sendJson("GET", "/auth/me", null, malloryToken);
        Map<String, Object> meData = map(jsonResponse(meResponse).get("data"));
        assertEquals("USER", meData.get("role"));
        assertFalse(meResponse.body().contains("password"), "User payload must never contain the password field");
    }

    @Test
    void rollbackByNonOwnerIsForbiddenButAdminCanRollback() throws Exception {
        String adminToken = login("admin", "123456");
        registerAndLoginUser("alice", "pass123");
        String aliceToken = login("alice", "pass123");

        String documentId = uploadTextDocument(aliceToken, "alice-doc.md", "alice content");

        HttpResponse<String> adminRollback = sendJson("POST",
                "/documents/" + documentId + "/versions/V-1/rollback", "", adminToken);
        assertEquals(200, adminRollback.statusCode());

        registerAndLoginUser("bob", "pass123");
        String bobToken = login("bob", "pass123");
        HttpResponse<String> bobRollback = sendJson("POST",
                "/documents/" + documentId + "/versions/V-1/rollback", "", bobToken);
        Map<String, Object> bobJson = jsonResponse(bobRollback);
        assertEquals(403, bobRollback.statusCode());
        assertEquals("FORBIDDEN", bobJson.get("code"));
    }

    @Test
    void loginResponseNeverExposesPasswordOrLogsToken() throws Exception {
        HttpResponse<String> loginResponse = sendJson("POST", "/auth/login", """
                {"username":"admin","password":"123456"}
                """, null);
        assertEquals(200, loginResponse.statusCode());
        assertFalse(loginResponse.body().contains("\"password\""),
                "Login payload must not contain a password field");
        Map<String, Object> user = map(map(jsonResponse(loginResponse).get("data")).get("user"));
        assertEquals("ADMIN", user.get("role"));
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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

    private Map<String, Object> jsonResponse(HttpResponse<String> response) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
            Map<String, Object> result = new HashMap<>(parsed);
            result.put("_status", response.statusCode());
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse JSON response: " + response.body(), e);
        }
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
