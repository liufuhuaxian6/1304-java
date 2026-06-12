package com.sharedoc.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.Gson;
import com.sharedoc.model.ContentUpdateResult;
import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.LockReleaseResult;
import com.sharedoc.model.RangeLock;
import com.sharedoc.model.Response;
import com.sharedoc.model.User;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.util.DateTimeUtil;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.json.JavalinJackson;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpApiServer {
    private static final Logger LOGGER = Logger.getLogger(HttpApiServer.class.getName());
    private final UserService userService;
    private final DocumentService documentService;
    private final VersionService versionService;
    private final Gson gson = new Gson();
    private final DocumentEventBroker eventBroker = new DocumentEventBroker();

    private final Map<String, String> tokenStore = new ConcurrentHashMap<>();
    private Javalin app;

    public HttpApiServer(UserService userService, DocumentService documentService, VersionService versionService) {
        this.userService = userService;
        this.documentService = documentService;
        this.versionService = versionService;
    }

    public synchronized void start(int port) {
        if (app != null) {
            return;
        }

        ObjectMapper jacksonMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(jacksonMapper));
            config.plugins.enableCors(cors -> cors.add(it -> it.anyHost()));
        });

        registerRoutes(app);
        app.start(port);
        LOGGER.info(() -> "HTTP API Server started on port " + port);
        System.out.println("HTTP API Server started on port " + port);
    }

    public synchronized void stop() {
        if (app == null) {
            return;
        }
        app.stop();
        app = null;
        tokenStore.clear();
    }

    private void registerRoutes(Javalin app) {
        app.before("/api/v1/documents/*", this::requireAuth);
        app.before("/api/v1/documents", this::requireAuth);
        app.before("/api/v1/auth/me", this::requireAuth);
        app.before("/api/v1/auth/logout", this::requireLogoutAuth);

        app.post("/api/v1/auth/login", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            String username = stringValue(body.get("username"));
            String password = stringValue(body.get("password"));
            LOGGER.info(() -> "Login attempt username=" + safe(username) + ", ip=" + ctx.ip());

            Response res = userService.login(username, password);
            if (!res.isSuccess()) {
                LOGGER.warning(() -> "Login failed username=" + safe(username) + ", reason=" + res.getMessage());
                ctx.status(401).json(error("INVALID_CREDENTIALS", res.getMessage()));
                return;
            }

            String token = "token-" + UUID.randomUUID();
            tokenStore.put(token, username);

            User user = new User();
            user.setUsername(username);
            user.setUserId("U-" + username.toUpperCase());

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("user", user);
            LOGGER.info(() -> "Login success username=" + safe(username) + ", token=" + token);
            ctx.json(success("登录成功", data));
        });

        app.post("/api/v1/auth/logout", ctx -> {
            String username = ctx.attribute("username");
            String token = ctx.attribute("token");
            Response response = userService.logout(username);
            tokenStore.remove(token);
            ctx.json(success(response.getMessage(), null));
        });

        app.get("/api/v1/auth/me", ctx -> {
            String username = ctx.attribute("username");
            User user = new User();
            user.setUsername(username);
            user.setUserId("U-" + username.toUpperCase());
            LOGGER.fine(() -> "Auth me username=" + safe(username));
            ctx.json(success("获取当前用户成功", user));
        });

        app.post("/api/v1/auth/register", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            String username = stringValue(body.get("username"));
            String password = stringValue(body.get("password"));
            String role = stringValue(body.get("role"));

            Response res = userService.register(username, password, role);
            if (!res.isSuccess()) {
                ctx.status(400).json(error("REGISTER_FAILED", res.getMessage()));
                return;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("username", username);
            data.put("role", (role == null || role.isBlank()) ? "USER" : role);
            ctx.status(201).json(success("注册成功", data));
        });

        app.get("/api/v1/documents", ctx -> {
            Response res = documentService.listDocuments();
            if (res.isSuccess()) {
                List<?> documents = res.getData() instanceof List<?> list ? list : List.of();
                LOGGER.info(() -> "List documents username=" + safe(ctx.attribute("username"))
                        + ", count=" + documents.size());
                ctx.json(success("文档列表获取成功", res.getData()));
            } else {
                LOGGER.warning(() -> "List documents failed username=" + safe(ctx.attribute("username"))
                        + ", reason=" + res.getMessage());
                ctx.status(500).json(error("INTERNAL_ERROR", res.getMessage()));
            }
        });

        app.post("/api/v1/documents", ctx -> {
            String username = ctx.attribute("username");
            UploadedFile file = ctx.uploadedFile("file");
            LOGGER.info(() -> "Upload request username=" + safe(username)
                    + ", hasFile=" + (file != null)
                    + ", filename=" + safe(file == null ? null : file.filename()));
            if (file == null) {
                LOGGER.warning(() -> "Upload rejected username=" + safe(username) + ", reason=No file uploaded");
                ctx.status(400).json(error("BAD_REQUEST", "No file uploaded"));
                return;
            }

            try (InputStream inputStream = file.content()) {
                byte[] content = inputStream.readAllBytes();
                Response res = documentService.uploadDocument(username, file.filename(), content);
                if (res.isSuccess()) {
                    Map<String, Object> data = res.getData() instanceof Map<?, ?> map
                            ? castMap(map)
                            : Map.of();
                    Object document = data.get("document");
                    LOGGER.info(() -> "Upload success username=" + safe(username)
                            + ", filename=" + safe(file.filename())
                            + ", owner=" + extractDocumentField(document, "owner")
                            + ", documentId=" + extractDocumentField(document, "documentId"));
                    ctx.json(success("文档上传成功", res.getData()));
                } else {
                    LOGGER.warning(() -> "Upload failed username=" + safe(username)
                            + ", filename=" + safe(file.filename())
                            + ", reason=" + res.getMessage());
                    ctx.status(400).json(error("BAD_REQUEST", res.getMessage()));
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Upload exception username=" + safe(username)
                        + ", filename=" + safe(file.filename()), e);
                ctx.status(500).json(error("INTERNAL_ERROR", "Failed to upload file: " + e.getMessage()));
            }
        });

        app.get("/api/v1/documents/{documentId}/preview", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = documentService.viewDocument(docId);
            if (!res.isSuccess()) {
                ctx.status(404).json(error("DOCUMENT_NOT_FOUND", res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.getData();
            Map<String, Object> result = new HashMap<>(data);
            String preview = String.valueOf(data.get("preview"));
            result.put("contentText", preview);
            result.put("truncated", preview.contains("预览截断"));
            ctx.json(success("文档查看成功", result));
        });

        app.get("/api/v1/documents/{documentId}/content", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = documentService.getDocumentContent(docId);
            if (!res.isSuccess()) {
                ctx.status(404).json(error("DOCUMENT_NOT_FOUND", res.getMessage()));
                return;
            }
            ctx.json(success("文档内容获取成功", res.getData()));
        });

        app.get("/api/v1/documents/{documentId}/download", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = documentService.downloadDocument(docId);
            if (!res.isSuccess()) {
                ctx.status(404).json(error("DOCUMENT_NOT_FOUND", res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) res.getData();
            Document doc = (Document) result.get("document");
            byte[] content = (byte[]) result.get("fileContent");
            ctx.header("Content-Disposition", "attachment; filename=\"" + doc.getFileName() + "\"");
            ctx.header("X-Document-Id", doc.getDocumentId());
            ctx.contentType("application/octet-stream");
            ctx.result(content);
        });

        app.post("/api/v1/documents/{documentId}/lock", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            Map<String, Object> body = parseBody(ctx);
            long revision = longValue(body.get("revision"));
            int start = intValue(body.get("start"));
            int end = intValue(body.get("end"));

            Response res = documentService.requestEdit(docId, username, revision, start, end);
            if (!res.isSuccess()) {
                ctx.status(mapLockStatus(res.getMessage())).json(error(mapLockCode(res.getMessage()), res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.getData();
            RangeLock lock = (RangeLock) data.get("lock");
            List<RangeLock> activeLocks = castLocks(data.get("activeLocks"));

            Map<String, Object> payload = new HashMap<>();
            payload.put("lockId", lock.getLockId());
            payload.put("documentId", lock.getDocumentId());
            payload.put("start", lock.getCurrentStart());
            payload.put("end", lock.getCurrentEnd());
            payload.put("revision", lock.getBaseRevision());
            payload.put("owner", lock.getOwner());
            payload.put("queued", lock.isQueued());
            payload.put("queuePosition", lock.getQueuePosition());
            payload.put("activeLocks", activeLocks);
            ctx.json(success(lock.isQueued() ? "编辑区间已进入等待队列" : "编辑区间申请成功", payload));

            Map<String, Object> event = new HashMap<>();
            event.put("lock", lock);
            event.put("activeLocks", activeLocks);
            eventBroker.broadcast(docId, lock.isQueued() ? "lock-queued" : "lock-acquired", event);
        });

        app.delete("/api/v1/documents/{documentId}/lock", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            Response res = documentService.releaseEdit(docId, username);
            if (!res.isSuccess()) {
                ctx.status(403).json(error("NO_EDIT_PERMISSION", res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.getData();
            List<RangeLock> releasedLocks = castLocks(data.get("releasedLocks"));
            List<RangeLock> activeLocks = castLocks(data.get("activeLocks"));
            List<RangeLock> promotedLocks = castLocks(data.get("promotedLocks"));

            ctx.json(success("编辑权限已释放", data));

            if (!releasedLocks.isEmpty()) {
                Map<String, Object> event = new HashMap<>();
                event.put("lockId", releasedLocks.get(0).getLockId());
                event.put("activeLocks", activeLocks);
                eventBroker.broadcast(docId, "lock-released", event);
            }
            broadcastPromotedLocks(docId, promotedLocks, activeLocks);
        });

        app.patch("/api/v1/documents/{documentId}/content", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            Map<String, Object> body = parseBody(ctx);
            String lockId = stringValue(body.get("lockId"));
            long clientRevision = longValue(body.get("clientRevision"));
            String replacementText = stringValue(body.get("replacementText"));
            String comment = stringValue(body.get("comment"));

            Response res = documentService.saveRange(username, docId, lockId, clientRevision, replacementText, comment);
            if (!res.isSuccess()) {
                String code = "NO_EDIT_PERMISSION";
                int status = 403;
                if (res.getMessage().contains("版本已更新")) {
                    code = "REVISION_STALE";
                    status = 409;
                } else if (res.getMessage().contains("锁定区间已失效")) {
                    code = "INVALID_LOCK_RANGE";
                    status = 409;
                }
                ctx.status(status).json(error(code, res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.getData();
            ContentUpdateResult contentUpdate = (ContentUpdateResult) data.get("contentUpdate");
            List<RangeLock> promotedLocks = castLocks(data.get("promotedLocks"));
            ctx.json(success("文档保存成功", data));

            Map<String, Object> contentEvent = new HashMap<>();
            contentEvent.put("revisionBefore", contentUpdate.getRevisionBefore());
            contentEvent.put("revisionAfter", contentUpdate.getRevisionAfter());
            contentEvent.put("start", contentUpdate.getStart());
            contentEvent.put("end", contentUpdate.getEnd());
            contentEvent.put("replacementText", contentUpdate.getReplacementText());
            contentEvent.put("delta", contentUpdate.getDelta());
            contentEvent.put("editor", username);
            contentEvent.put("activeLocks", contentUpdate.getActiveLocks());
            eventBroker.broadcast(docId, "content-updated", contentEvent);

            if (contentUpdate.getReleasedLock() != null) {
                Map<String, Object> releaseEvent = new HashMap<>();
                releaseEvent.put("lockId", contentUpdate.getReleasedLock().getLockId());
                releaseEvent.put("activeLocks", contentUpdate.getActiveLocks());
                eventBroker.broadcast(docId, "lock-released", releaseEvent);
            }
            broadcastPromotedLocks(docId, promotedLocks, contentUpdate.getActiveLocks());
        });

        app.put("/api/v1/documents/{documentId}/content", ctx -> {
            ctx.status(405).json(error("METHOD_NOT_ALLOWED", "请使用 PATCH 进行局部保存"));
        });

        app.get("/api/v1/documents/{documentId}/versions", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = versionService.listVersions(docId);
            if (!res.isSuccess()) {
                ctx.status(400).json(error("BAD_REQUEST", res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            List<DocumentVersion> versions = (List<DocumentVersion>) res.getData();
            List<Map<String, Object>> data = new ArrayList<>();
            for (DocumentVersion v : versions) {
                Map<String, Object> item = new HashMap<>();
                item.put("versionId", v.getVersionId());
                item.put("documentId", v.getDocumentId());
                item.put("fileName", v.getFileName());
                item.put("editor", v.getEditor());
                item.put("operationType", v.getOperationType() == null ? null : v.getOperationType().name());
                item.put("editTime", DateTimeUtil.format(v.getEditTime()));
                item.put("comment", v.getComment());
                data.add(item);
            }
            ctx.json(success("版本列表获取成功", data));
        });

        app.get("/api/v1/documents/{documentId}/versions/{versionId}/download", ctx -> {
            String versionId = ctx.pathParam("versionId");
            Response res = versionService.downloadVersion(versionId);
            if (!res.isSuccess()) {
                ctx.status(404).json(error("VERSION_NOT_FOUND", res.getMessage()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) res.getData();
            DocumentVersion version = (DocumentVersion) result.get("version");
            byte[] content = (byte[]) result.get("fileContent");
            ctx.header("Content-Disposition", "attachment; filename=\"" + version.getFileName() + "\"");
            ctx.header("X-Version-Id", version.getVersionId());
            ctx.contentType("application/octet-stream");
            ctx.result(content);
        });

        app.post("/api/v1/documents/{documentId}/versions/{versionId}/rollback", ctx -> {
            String docId = ctx.pathParam("documentId");
            String versionId = ctx.pathParam("versionId");
            String username = ctx.attribute("username");

            Response res = documentService.rollbackDocumentToVersion(username, docId, versionId);
            if (!res.isSuccess()) {
                int status = res.getMessage().contains("活动编辑区间") ? 409 : 403;
                String code = res.getMessage().contains("活动编辑区间") ? "ACTIVE_LOCKS_PRESENT" : "ROLLBACK_FAILED";
                ctx.status(status).json(error(code, res.getMessage()));
                return;
            }
            ctx.json(success("版本回滚成功", res.getData()));
        });

        app.sse("/api/v1/documents/{documentId}/events", client -> {
            Context ctx = client.ctx();
            String token = extractToken(ctx);
            if (token == null || !tokenStore.containsKey(token)) {
                ctx.status(401);
                client.close();
                return;
            }

            String docId = ctx.pathParam("documentId");
            if (!documentService.getDocumentContent(docId).isSuccess()) {
                ctx.status(404);
                client.close();
                return;
            }

            client.keepAlive();
            eventBroker.addSubscriber(docId, client);
        });
    }

    private void requireAuth(Context ctx) {
        String token = extractToken(ctx);
        if (token == null || !tokenStore.containsKey(token)) {
            LOGGER.warning(() -> "Auth failed path=" + ctx.path() + ", token=" + safe(token) + ", ip=" + ctx.ip());
            ctx.status(401).json(error("AUTH_REQUIRED", "Unauthorized"));
            return;
        }
        String username = tokenStore.get(token);
        LOGGER.fine(() -> "Auth success path=" + ctx.path() + ", username=" + safe(username));
        ctx.attribute("username", username);
    }

    private void requireLogoutAuth(Context ctx) {
        String token = extractToken(ctx);
        if (token == null || !tokenStore.containsKey(token)) {
            ctx.status(401).json(error("AUTH_REQUIRED", "Unauthorized"));
            return;
        }
        ctx.attribute("username", tokenStore.get(token));
        ctx.attribute("token", token);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Context ctx) {
        Map<String, Object> body = gson.fromJson(ctx.body(), Map.class);
        return body == null ? new HashMap<>() : body;
    }

    private List<RangeLock> castLocks(Object value) {
        if (value instanceof List<?> list) {
            List<RangeLock> locks = new ArrayList<>();
            for (Object item : list) {
                locks.add((RangeLock) item);
            }
            return locks;
        }
        return new ArrayList<>();
    }

    private void broadcastPromotedLocks(String documentId, List<RangeLock> promotedLocks, List<RangeLock> activeLocks) {
        for (RangeLock promotedLock : promotedLocks) {
            Map<String, Object> event = new HashMap<>();
            event.put("lock", promotedLock);
            event.put("activeLocks", activeLocks);
            eventBroker.broadcast(documentId, "lock-acquired", event);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value) {
        return value == null ? 0 : (int) Math.round(Double.parseDouble(String.valueOf(value)));
    }

    private long longValue(Object value) {
        return value == null ? 0L : Math.round(Double.parseDouble(String.valueOf(value)));
    }

    private int mapLockStatus(String message) {
        if (message.contains("版本已更新")) {
            return 409;
        }
        if (message.contains("区间") || message.contains("持有")) {
            return 409;
        }
        return 400;
    }

    private String mapLockCode(String message) {
        if (message.contains("版本已更新")) {
            return "REVISION_STALE";
        }
        if (message.contains("目标区间正在被")) {
            return "RANGE_LOCKED";
        }
        if (message.contains("持有一个编辑区间")) {
            return "USER_ALREADY_HAS_LOCK";
        }
        return "BAD_REQUEST";
    }

    private String extractToken(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return ctx.queryParam("token");
    }

    private Map<String, Object> success(String message, Object data) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", true);
        map.put("code", "OK");
        map.put("message", message);
        map.put("data", data);
        return map;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", false);
        map.put("code", code);
        map.put("message", message);
        map.put("data", null);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private String extractDocumentField(Object document, String field) {
        if (document instanceof com.sharedoc.model.Document doc) {
            if ("owner".equals(field)) {
                return safe(doc.getOwner());
            }
            if ("documentId".equals(field)) {
                return safe(doc.getDocumentId());
            }
        }
        if (document instanceof Map<?, ?> map) {
            return safe(map.get(field));
        }
        return "null";
    }

    private String safe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
