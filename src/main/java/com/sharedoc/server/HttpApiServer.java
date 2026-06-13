package com.sharedoc.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sharedoc.model.ContentUpdateResult;
import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.ErrorCodes;
import com.sharedoc.model.RangeLock;
import com.sharedoc.model.Response;
import com.sharedoc.model.User;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import com.sharedoc.util.DateTimeUtil;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP API server.
 *
 * Authentication: opaque bearer tokens with a sliding TTL
 * ({@link ServerConfig#SESSION_TTL}). The before-handler stops request
 * processing for unauthenticated requests via skipRemainingHandlers, so
 * protected handlers never run without a valid session.
 *
 * Error responses use machine-readable codes (see {@link ErrorCodes});
 * HTTP status codes are derived from those codes, never from message text.
 */
public class HttpApiServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpApiServer.class);

    private final UserService userService;
    private final DocumentService documentService;
    private final VersionService versionService;
    private final ObjectMapper bodyMapper = new ObjectMapper();
    private final DocumentEventBroker eventBroker = new DocumentEventBroker();

    private final Map<String, Session> tokenStore = new ConcurrentHashMap<>();
    private Javalin app;

    private static final class Session {
        private final String username;
        private final String role;
        private volatile long expiresAtMillis;

        private Session(String username, String role, long expiresAtMillis) {
            this.username = username;
            this.role = role;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

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

        boolean serveFrontend = Files.isDirectory(Path.of(ServerConfig.FRONTEND_DIR));
        app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(jacksonMapper));
            config.http.maxRequestSize = ServerConfig.MAX_UPLOAD_BYTES + 1_048_576L;
            config.plugins.enableCors(cors -> cors.add(it -> {
                if (ServerConfig.CORS_ORIGINS.contains("*")) {
                    it.anyHost();
                } else {
                    List<String> origins = ServerConfig.CORS_ORIGINS;
                    it.allowHost(origins.get(0), origins.subList(1, origins.size()).toArray(new String[0]));
                }
            }));
            if (serveFrontend) {
                // Host the static frontend from the same origin as the API so
                // opening http://<host>:<port>/ needs no separate web server
                // and triggers no CORS at all.
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = ServerConfig.FRONTEND_DIR;
                    staticFiles.location = Location.EXTERNAL;
                });
            }
        });

        registerRoutes(app);
        app.exception(UnauthorizedResponse.class, (e, ctx) ->
                ctx.status(401).json(error(ErrorCodes.AUTH_REQUIRED, e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            LOGGER.error("Unhandled exception, path={}", ctx.path(), e);
            ctx.status(500).json(error(ErrorCodes.INTERNAL_ERROR, "服务器内部错误"));
        });
        app.start(port);
        LOGGER.info("HTTP API Server started on port {}", port);
        if (serveFrontend) {
            LOGGER.info("Frontend hosted at http://localhost:{}/ (dir: {})", port, ServerConfig.FRONTEND_DIR);
        }
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
        app.before("/api/v1/auth/logout", this::requireAuth);
        app.before("/api/v1/auth/password", this::requireAuth);

        app.get("/api/v1/health", ctx -> {
            Map<String, Object> data = new HashMap<>();
            data.put("status", "UP");
            ctx.json(success("OK", data));
        });

        app.post("/api/v1/auth/login", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            String username = stringValue(body.get("username"));
            String password = stringValue(body.get("password"));
            LOGGER.info("Login attempt username={}, ip={}", safe(username), ctx.ip());

            Response res = userService.login(username, password);
            if (!res.isSuccess()) {
                LOGGER.warn("Login failed username={}", safe(username));
                respondError(ctx, res);
                return;
            }

            User user = (User) res.getData();
            String token = "token-" + UUID.randomUUID();
            tokenStore.put(token, new Session(username, user.getRole(), newExpiry()));

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("user", user);
            LOGGER.info("Login success username={}", safe(username));
            ctx.json(success("登录成功", data));
        });

        app.post("/api/v1/auth/logout", ctx -> {
            String username = ctx.attribute("username");
            String token = ctx.attribute("token");
            Response response = userService.logout(username);
            if (token != null) {
                tokenStore.remove(token);
            }
            ctx.json(success(response.getMessage(), null));
        });

        app.get("/api/v1/auth/me", ctx -> {
            String username = ctx.attribute("username");
            User user = userService.findByUsername(username);
            if (user == null) {
                ctx.status(401).json(error(ErrorCodes.AUTH_REQUIRED, "用户不存在或会话已失效"));
                return;
            }
            ctx.json(success("获取当前用户成功", user));
        });

        app.post("/api/v1/auth/password", ctx -> {
            String username = ctx.attribute("username");
            Map<String, Object> body = parseBody(ctx);
            String currentPassword = stringValue(body.get("currentPassword"));
            String newPassword = stringValue(body.get("newPassword"));

            Response res = userService.changePassword(username, currentPassword, newPassword);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }
            ctx.json(success("密码修改成功", null));
        });

        app.post("/api/v1/auth/register", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            String username = stringValue(body.get("username"));
            String password = stringValue(body.get("password"));

            // Self-registration always produces a USER account; any client
            // supplied role field is deliberately ignored.
            Response res = userService.register(username, password);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }

            User user = (User) res.getData();
            Map<String, Object> data = new HashMap<>();
            data.put("username", user.getUsername());
            data.put("role", user.getRole());
            ctx.status(201).json(success("注册成功", data));
        });

        app.get("/api/v1/documents", ctx -> {
            Response res = documentService.listDocuments();
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }
            ctx.json(success("文档列表获取成功", res.getData()));
        });

        app.post("/api/v1/documents", ctx -> {
            String username = ctx.attribute("username");
            UploadedFile file;
            try {
                file = ctx.uploadedFile("file");
            } catch (Exception e) {
                // Jetty rejects multipart bodies whose headers are not valid
                // UTF-8 (for example a filename sent in a legacy encoding).
                LOGGER.warn("Malformed multipart upload, ip={}: {}", ctx.ip(), e.getMessage());
                ctx.status(400).json(error(ErrorCodes.BAD_REQUEST, "上传请求格式错误，请使用 UTF-8 编码的文件名"));
                return;
            }
            if (file == null) {
                ctx.status(400).json(error(ErrorCodes.BAD_REQUEST, "No file uploaded"));
                return;
            }

            try (InputStream inputStream = file.content()) {
                byte[] content = inputStream.readAllBytes();
                Response res = documentService.uploadDocument(username, file.filename(), content);
                if (!res.isSuccess()) {
                    LOGGER.warn("Upload failed username={}, filename={}, reason={}",
                            safe(username), safe(file.filename()), res.getMessage());
                    respondError(ctx, res);
                    return;
                }
                LOGGER.info("Upload success username={}, filename={}", safe(username), safe(file.filename()));
                ctx.json(success("文档上传成功", res.getData()));
            }
        });

        app.get("/api/v1/documents/{documentId}/preview", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = documentService.viewDocument(docId);
            if (!res.isSuccess()) {
                respondError(ctx, res);
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
                respondError(ctx, res);
                return;
            }
            ctx.json(success("文档内容获取成功", res.getData()));
        });

        app.get("/api/v1/documents/{documentId}/download", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = documentService.downloadDocument(docId);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) res.getData();
            Document doc = (Document) result.get("document");
            byte[] content = (byte[]) result.get("fileContent");
            ctx.header("Content-Disposition", contentDisposition(doc.getFileName()));
            ctx.header("X-Document-Id", doc.getDocumentId());
            ctx.contentType("application/octet-stream");
            ctx.result(content);
        });

        app.delete("/api/v1/documents/{documentId}", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            boolean isAdmin = "ADMIN".equals(ctx.attribute("role"));

            Response res = documentService.deleteDocument(username, docId, isAdmin);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }
            ctx.json(success("文档已删除", res.getData()));

            Map<String, Object> event = new HashMap<>();
            event.put("documentId", docId);
            event.put("editor", username);
            eventBroker.broadcast(docId, "document-deleted", event);
        });

        app.patch("/api/v1/documents/{documentId}", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            boolean isAdmin = "ADMIN".equals(ctx.attribute("role"));
            Map<String, Object> body = parseBody(ctx);
            String newFileName = stringValue(body.get("fileName"));

            Response res = documentService.renameDocument(username, docId, newFileName, isAdmin);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }
            ctx.json(success("文档已重命名", res.getData()));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.getData();
            Document document = (Document) data.get("document");
            Map<String, Object> event = new HashMap<>();
            event.put("document", document);
            event.put("editor", username);
            eventBroker.broadcast(docId, "document-renamed", event);
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
                respondError(ctx, res);
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
                respondError(ctx, res);
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
                respondError(ctx, res);
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
            ctx.status(405).json(error(ErrorCodes.METHOD_NOT_ALLOWED, "请使用 PATCH 进行局部保存"));
        });

        app.get("/api/v1/documents/{documentId}/versions", ctx -> {
            String docId = ctx.pathParam("documentId");
            Response res = versionService.listVersions(docId);
            if (!res.isSuccess()) {
                respondError(ctx, res);
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
                item.put("storageType", v.getStorageType());
                item.put("patchCount", v.getPatchCount());
                data.add(item);
            }
            ctx.json(success("版本列表获取成功", data));
        });

        app.get("/api/v1/documents/{documentId}/versions/{versionId}/download", ctx -> {
            String docId = ctx.pathParam("documentId");
            String versionId = ctx.pathParam("versionId");
            Response res = versionService.downloadVersion(docId, versionId);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) res.getData();
            DocumentVersion version = (DocumentVersion) result.get("version");
            byte[] content = (byte[]) result.get("fileContent");
            ctx.header("Content-Disposition", contentDisposition(version.getFileName()));
            ctx.header("X-Version-Id", version.getVersionId());
            ctx.contentType("application/octet-stream");
            ctx.result(content);
        });

        app.get("/api/v1/documents/{documentId}/versions/{versionId}/diff", ctx -> {
            String docId = ctx.pathParam("documentId");
            String versionId = ctx.pathParam("versionId");
            Response res = versionService.diffWithPreviousVersion(docId, versionId);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }
            ctx.json(success("版本差异获取成功", res.getData()));
        });

        app.post("/api/v1/documents/{documentId}/versions/{versionId}/rollback", ctx -> {
            String docId = ctx.pathParam("documentId");
            String versionId = ctx.pathParam("versionId");
            String username = ctx.attribute("username");
            boolean isAdmin = "ADMIN".equals(ctx.attribute("role"));

            Response res = documentService.rollbackDocumentToVersion(username, docId, versionId, isAdmin);
            if (!res.isSuccess()) {
                respondError(ctx, res);
                return;
            }
            ctx.json(success("版本回滚成功", res.getData()));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.getData();
            Document document = (Document) data.get("document");
            Map<String, Object> event = new HashMap<>();
            event.put("document", document);
            event.put("versionId", versionId);
            event.put("editor", username);
            event.put("revision", document.getRevision());
            eventBroker.broadcast(docId, "document-rolled-back", event);
        });

        // The before-handler has already authenticated this request
        // (EventSource cannot send headers, so the token arrives as a
        // query parameter, which extractToken supports).
        app.sse("/api/v1/documents/{documentId}/events", client -> {
            Context ctx = client.ctx();
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            if (!documentService.documentExists(docId)) {
                ctx.status(404);
                client.close();
                return;
            }

            client.keepAlive();
            eventBroker.addSubscriber(docId, username, client);
        });
    }

    /**
     * Authenticates the request. Throwing UnauthorizedResponse aborts the
     * handler chain, so a protected endpoint can never run without a valid
     * session (setting a status in a before-handler alone would NOT stop
     * Javalin from invoking the endpoint handler).
     */
    private void requireAuth(Context ctx) {
        // CORS preflight requests never carry credentials (per spec); they are
        // answered by the CORS plugin and must not be rejected here, otherwise
        // every cross-origin call from the frontend fails before it starts.
        if (ctx.method() == HandlerType.OPTIONS) {
            return;
        }
        Session session = activeSession(ctx);
        if (session == null) {
            LOGGER.warn("Auth failed path={}, ip={}", ctx.path(), ctx.ip());
            throw new UnauthorizedResponse("未登录或会话已过期");
        }
        ctx.attribute("username", session.username);
        ctx.attribute("role", session.role);
        ctx.attribute("token", extractToken(ctx));
    }

    /**
     * Resolves the session for the request token, enforcing the sliding TTL:
     * expired sessions are evicted, live sessions get their expiry extended.
     */
    private Session activeSession(Context ctx) {
        String token = extractToken(ctx);
        if (token == null) {
            return null;
        }
        Session session = tokenStore.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAtMillis < System.currentTimeMillis()) {
            tokenStore.remove(token);
            return null;
        }
        session.expiresAtMillis = newExpiry();
        return session;
    }

    private long newExpiry() {
        return System.currentTimeMillis() + ServerConfig.SESSION_TTL.toMillis();
    }

    private Map<String, Object> parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = bodyMapper.readValue(body, new TypeReference<Map<String, Object>>() { });
            return parsed == null ? new HashMap<>() : parsed;
        } catch (IOException e) {
            return new HashMap<>();
        }
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
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : (int) Math.round(Double.parseDouble(String.valueOf(value)));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Math.round(Double.parseDouble(String.valueOf(value)));
    }

    private void respondError(Context ctx, Response res) {
        String code = res.getCode() == null ? ErrorCodes.BAD_REQUEST : res.getCode();
        ctx.status(statusFor(code)).json(error(code, res.getMessage()));
    }

    private int statusFor(String code) {
        return switch (code) {
            case ErrorCodes.AUTH_REQUIRED, ErrorCodes.INVALID_CREDENTIALS -> 401;
            case ErrorCodes.FORBIDDEN, ErrorCodes.NO_EDIT_PERMISSION -> 403;
            case ErrorCodes.DOCUMENT_NOT_FOUND, ErrorCodes.VERSION_NOT_FOUND -> 404;
            case ErrorCodes.METHOD_NOT_ALLOWED -> 405;
            case ErrorCodes.REVISION_STALE, ErrorCodes.USER_ALREADY_HAS_LOCK, ErrorCodes.LOCK_FAILED,
                    ErrorCodes.INVALID_LOCK_RANGE, ErrorCodes.ACTIVE_LOCKS_PRESENT -> 409;
            case ErrorCodes.FILE_TOO_LARGE -> 413;
            case ErrorCodes.INTERNAL_ERROR -> 500;
            default -> 400;
        };
    }

    /**
     * Builds an RFC 6266/5987 Content-Disposition header so non-ASCII file
     * names (for example Chinese) download correctly, with an ASCII fallback.
     */
    private String contentDisposition(String fileName) {
        String safeName = fileName == null ? "document" : fileName;
        String fallback = safeName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_").replace("\\", "_");
        String encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
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
        map.put("code", ErrorCodes.OK);
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

    private String safe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
