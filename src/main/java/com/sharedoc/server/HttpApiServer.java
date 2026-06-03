package com.sharedoc.server;

import com.google.gson.Gson;
import com.sharedoc.model.Document;
import com.sharedoc.model.User;
import com.sharedoc.service.DocumentService;
import com.sharedoc.service.UserService;
import com.sharedoc.service.VersionService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HttpApiServer {
    private final UserService userService;
    private final DocumentService documentService;
    private final VersionService versionService;
    private final Gson gson = new Gson();
    
    // Simple token storage: token -> username
    private final Map<String, String> tokenStore = new ConcurrentHashMap<>();

    public HttpApiServer(UserService userService, DocumentService documentService, VersionService versionService) {
        this.userService = userService;
        this.documentService = documentService;
        this.versionService = versionService;
    }

    public void start(int port) {
        Javalin app = Javalin.create(config -> {
            config.plugins.enableCors(cors -> {
                cors.add(it -> it.anyHost());
            });
        }).start(port);

        System.out.println("HTTP API Server started on port " + port);

        // Auth middleware
        app.before("/api/v1/documents/*", ctx -> {
            String token = extractToken(ctx);
            if (token == null || !tokenStore.containsKey(token)) {
                ctx.status(401).json(error("AUTH_REQUIRED", "Unauthorized"));
                return;
            }
            ctx.attribute("username", tokenStore.get(token));
        });
        app.before("/api/v1/documents", ctx -> {
            String token = extractToken(ctx);
            if (token == null || !tokenStore.containsKey(token)) {
                ctx.status(401).json(error("AUTH_REQUIRED", "Unauthorized"));
                return;
            }
            ctx.attribute("username", tokenStore.get(token));
        });
        app.before("/api/v1/auth/me", ctx -> {
            String token = extractToken(ctx);
            if (token == null || !tokenStore.containsKey(token)) {
                ctx.status(401).json(error("AUTH_REQUIRED", "Unauthorized"));
                return;
            }
            ctx.attribute("username", tokenStore.get(token));
        });
        app.before("/api/v1/auth/logout", ctx -> {
            String token = extractToken(ctx);
            if (token == null || !tokenStore.containsKey(token)) {
                ctx.status(401).json(error("AUTH_REQUIRED", "Unauthorized"));
                return;
            }
            ctx.attribute("username", tokenStore.get(token));
            ctx.attribute("token", token);
        });

        // 7.1 Login
        app.post("/api/v1/auth/login", ctx -> {
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String username = body.get("username");
            String password = body.get("password");
            
            com.sharedoc.model.Response res = userService.login(username, password);
            if (res.isSuccess()) {
                String token = "token-" + UUID.randomUUID().toString();
                tokenStore.put(token, username);
                
                User user = new User();
                user.setUsername(username);
                user.setUserId("U-" + username.toUpperCase());
                
                Map<String, Object> data = new HashMap<>();
                data.put("token", token);
                data.put("user", user);
                ctx.json(success("登录成功", data));
            } else {
                ctx.status(401).json(error("INVALID_CREDENTIALS", res.getMessage()));
            }
        });

        // 7.2 Logout
        app.post("/api/v1/auth/logout", ctx -> {
            String username = ctx.attribute("username");
            String token = ctx.attribute("token");
            userService.logout(username);
            tokenStore.remove(token);
            ctx.json(success("登出成功", null));
        });

        // 7.3 Get current user
        app.get("/api/v1/auth/me", ctx -> {
            String username = ctx.attribute("username");
            User user = new User();
            user.setUsername(username);
            user.setUserId("U-" + username.toUpperCase());
            ctx.json(success("获取当前用户成功", user));
        });

        // 7.4 List documents
        app.get("/api/v1/documents", ctx -> {
            com.sharedoc.model.Response res = documentService.listDocuments();
            if (res.isSuccess()) {
                ctx.json(success("文档列表获取成功", res.getData()));
            } else {
                ctx.status(500).json(error("INTERNAL_ERROR", res.getMessage()));
            }
        });

        // 7.5 Upload document
        app.post("/api/v1/documents", ctx -> {
            String username = ctx.attribute("username");
            UploadedFile file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(400).json(error("BAD_REQUEST", "No file uploaded"));
                return;
            }
            
            try {
                byte[] content = file.content().readAllBytes();
                com.sharedoc.model.Request req = new com.sharedoc.model.Request();
                req.setType(com.sharedoc.model.RequestType.UPLOAD_DOCUMENT);
                req.setUsername(username);
                Map<String, Object> payload = new HashMap<>();
                payload.put("fileName", file.filename());
                payload.put("fileContent", content);
                req.setPayload(payload);
                
                com.sharedoc.model.Response res = documentService.uploadDocument(req);
                if (res.isSuccess()) {
                    ctx.json(success("文档上传成功", res.getData()));
                } else {
                    ctx.status(400).json(error("BAD_REQUEST", res.getMessage()));
                }
            } catch (Exception e) {
                ctx.status(500).json(error("INTERNAL_ERROR", "Failed to upload file"));
            }
        });

        // 7.6 Preview document
        app.get("/api/v1/documents/{documentId}/preview", ctx -> {
            String docId = ctx.pathParam("documentId");
            com.sharedoc.model.Response res = documentService.viewDocument(docId);
            if (res.isSuccess()) {
                Map<String, Object> data = (Map<String, Object>) res.getData();
                Map<String, Object> result = new HashMap<>(data);
                result.put("contentText", data.get("preview")); // Map 'preview' to 'contentText' for frontend
                result.put("truncated", data.get("preview").toString().contains("预览截断"));
                ctx.json(success("文档查看成功", result));
            } else {
                ctx.status(404).json(error("DOCUMENT_NOT_FOUND", res.getMessage()));
            }
        });

        // 7.7 Download document
        app.get("/api/v1/documents/{documentId}/download", ctx -> {
            String docId = ctx.pathParam("documentId");
            com.sharedoc.model.Response res = documentService.downloadDocument(docId);
            if (res.isSuccess()) {
                Map<String, Object> result = (Map<String, Object>) res.getData();
                Document doc = (Document) result.get("document");
                byte[] content = (byte[]) result.get("fileContent");
                ctx.header("Content-Disposition", "attachment; filename=\"" + doc.getFileName() + "\"");
                ctx.header("X-Document-Id", doc.getDocumentId());
                ctx.contentType("application/octet-stream");
                ctx.result(content);
            } else {
                ctx.status(404).json(error("DOCUMENT_NOT_FOUND", res.getMessage()));
            }
        });

        // 7.8 Request edit lock
        app.post("/api/v1/documents/{documentId}/lock", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            com.sharedoc.model.Response res = documentService.requestEdit(docId, username);
            if (res.isSuccess()) {
                ctx.json(success("编辑权限申请成功", Map.of("documentId", docId, "editingUser", username)));
            } else {
                ctx.status(409).json(error("DOCUMENT_LOCKED", res.getMessage()));
            }
        });

        // 7.9 Release edit lock
        app.delete("/api/v1/documents/{documentId}/lock", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            com.sharedoc.model.Response res = documentService.releaseEdit(docId, username);
            if (res.isSuccess()) {
                ctx.json(success("编辑权限已释放", null));
            } else {
                ctx.status(403).json(error("NO_EDIT_PERMISSION", res.getMessage()));
            }
        });

        // 7.10 Save document content
        app.put("/api/v1/documents/{documentId}/content", ctx -> {
            String docId = ctx.pathParam("documentId");
            String username = ctx.attribute("username");
            Map<String, String> body = gson.fromJson(ctx.body(), Map.class);
            String contentText = body.get("contentText");
            
            if (contentText == null) {
                ctx.status(400).json(error("BAD_REQUEST", "Content cannot be empty"));
                return;
            }
            
            byte[] content = contentText.getBytes(StandardCharsets.UTF_8);
            com.sharedoc.model.Request req = new com.sharedoc.model.Request();
            req.setType(com.sharedoc.model.RequestType.SAVE_DOCUMENT);
            req.setUsername(username);
            req.setDocumentId(docId);
            req.setPayload(content);
            
            com.sharedoc.model.Response res = documentService.saveDocument(req);
            if (res.isSuccess()) {
                ctx.json(success("文档保存成功", res.getData()));
            } else {
                ctx.status(403).json(error("NO_EDIT_PERMISSION", res.getMessage()));
            }
        });
        
        // 7.11 List versions (mock)
        app.get("/api/v1/documents/{documentId}/versions", ctx -> {
            ctx.json(success("版本列表获取成功", List.of()));
        });
    }

    private String extractToken(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
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
}
