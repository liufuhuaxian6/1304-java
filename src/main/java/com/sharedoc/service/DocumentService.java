package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.Request;
import com.sharedoc.model.Response;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.util.IdGenerator;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document service.
 * Owns document metadata operations and delegates edit permission checks to LockService.
 */
public class DocumentService {
    private static final Map<String, Document> DOCUMENTS = new ConcurrentHashMap<>();
    private static final LockService LOCK_SERVICE = new LockService();

    private final FileStorage fileStorage = new FileStorage();

    public Response listDocuments() {
        List<Document> documents = new ArrayList<>(DOCUMENTS.values());
        for (Document doc : documents) {
            syncDocumentEditingState(doc);
        }
        return new Response(true, "文档列表获取成功", documents);
    }

    public Response uploadDocument(Request request) {
        String username = request.getUsername();
        if (username == null || username.isEmpty()) {
            return Response.fail("请先登录");
        }

        Object payload = request.getPayload();
        if (!(payload instanceof Map)) {
            return Response.fail("上传请求格式错误");
        }

        Map<?, ?> uploadData = (Map<?, ?>) payload;
        String fileName = (String) uploadData.get("fileName");
        byte[] fileContent = (byte[]) uploadData.get("fileContent");

        if (fileName == null || fileName.isEmpty()) {
            return Response.fail("文件名不能为空");
        }
        if (fileContent == null || fileContent.length == 0) {
            return Response.fail("文件内容不能为空");
        }

        if (!isAllowedFileType(fileName)) {
            return Response.fail("不支持的文件类型，请上传代码、网页样式、配置数据、脚本命令、文档标记或工程文件");
        }

        String documentId = IdGenerator.nextDocumentId();
        String storagePath = Path.of(ServerConfig.DOCUMENT_STORAGE_PATH, documentId + "_" + fileName).toString();

        try {
            fileStorage.saveFile(storagePath, fileContent);
            Document document = new Document(documentId, fileName, username, storagePath);
            DOCUMENTS.put(documentId, document);

            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            return new Response(true, "文档上传成功", result);
        } catch (Exception e) {
            return Response.fail("文档上传失败: " + e.getMessage());
        }
    }

    public Response downloadDocument(String documentId) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        try {
            byte[] fileContent = fileStorage.readFile(document.getCurrentPath());
            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            result.put("fileContent", fileContent);
            return new Response(true, "文档下载成功", result);
        } catch (Exception e) {
            return Response.fail("文档下载失败: " + e.getMessage());
        }
    }

    public Response viewDocument(String documentId) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        try {
            byte[] fileContent = fileStorage.readFile(document.getCurrentPath());
            String preview = "";
            String fileName = document.getFileName().toLowerCase();
            boolean isTextFile = fileName.endsWith(".txt") || fileName.endsWith(".md") || 
                                 fileName.endsWith(".csv") || fileName.endsWith(".xml") ||
                                 fileName.endsWith(".json") || fileName.endsWith(".html") ||
                                 fileName.endsWith(".css") || fileName.endsWith(".js");

            if (isTextFile) {
                preview = new String(fileContent);
                if (preview.length() > 1000) {
                    preview = preview.substring(0, 1000) + "\n... (预览截断)";
                }
            } else {
                preview = "非文本文件，无法预览";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            result.put("preview", preview);
            result.put("isTextFile", isTextFile);
            return new Response(true, "文档查看成功", result);
        } catch (Exception e) {
            return Response.fail("文档查看失败: " + e.getMessage());
        }
    }

    public Response requestEdit(String documentId, String username) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档 ID 不能为空");
        }
        if (username == null || username.isBlank()) {
            return Response.fail("请先登录");
        }

        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        boolean locked = LOCK_SERVICE.tryLockDocument(documentId, username);
        if (!locked) {
            return Response.fail("文档正在被其他用户编辑");
        }

        document.setEditingUser(username);
        document.setEditingStartTime(LocalDateTime.now());
        return Response.ok("编辑权限申请成功");
    }

    public Response saveDocument(Request request) {
        String username = request.getUsername();
        String documentId = request.getDocumentId();

        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        String lockOwner = LOCK_SERVICE.getLockOwner(documentId);
        if (lockOwner == null || !lockOwner.equals(username)) {
            return Response.fail("您没有该文档的编辑权限");
        }

        Object payload = request.getPayload();
        if (!(payload instanceof byte[])) {
            return Response.fail("保存请求格式错误");
        }

        byte[] fileContent = (byte[]) payload;

        try {
            fileStorage.saveFile(document.getCurrentPath(), fileContent);
            document.setLastModifiedTime(LocalDateTime.now());
            return new Response(true, "文档保存成功", document);
        } catch (Exception e) {
            return Response.fail("文档保存失败: " + e.getMessage());
        }
    }

    public Response releaseEdit(String documentId, String username) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        String lockOwner = LOCK_SERVICE.getLockOwner(documentId);
        if (lockOwner == null || username == null || !username.equals(lockOwner)) {
            return Response.fail("当前用户未持有该文档的编辑权限");
        }

        LOCK_SERVICE.unlockDocument(documentId, username);
        document.setEditingUser(null);
        document.setEditingStartTime(null);
        return Response.ok("编辑权限已释放");
    }

    public boolean isEditing(String documentId) {
        // TODO: Add document existence validation.
        return LOCK_SERVICE.isLocked(documentId);
    }

    public String getEditingUser(String documentId) {
        // TODO: Return null or a user display name according to later UI requirements.
        return LOCK_SERVICE.getLockOwner(documentId);
    }

    public void releaseAllEditsByUser(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        for (Document document : DOCUMENTS.values()) {
            if (username.equals(LOCK_SERVICE.getLockOwner(document.getDocumentId()))) {
                LOCK_SERVICE.unlockDocument(document.getDocumentId(), username);
                document.setEditingUser(null);
                document.setEditingStartTime(null);
            }
        }
    }

    private void syncDocumentEditingState(Document document) {
        String lockOwner = LOCK_SERVICE.getLockOwner(document.getDocumentId());
        if (lockOwner == null) {
            document.setEditingUser(null);
            document.setEditingStartTime(null);
            return;
        }

        if (!lockOwner.equals(document.getEditingUser())) {
            document.setEditingUser(lockOwner);
            document.setEditingStartTime(null);
        }
    }

    private boolean isAllowedFileType(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();

        if (lowerFileName.equals("dockerfile") || lowerFileName.equals("makefile") || lowerFileName.equals(".gitignore")) {
            return true;
        }

        int lastDot = fileName.lastIndexOf(".");
        if (lastDot == -1) {
            return false;
        }

        String extension = lowerFileName.substring(lastDot + 1);

        String[] allowedExtensions = {
            "c", "cpp", "h", "hpp", "java", "py", "js", "ts", "jsx", "tsx", "go", "php", "rb", "rs", "swift", "vue",
            "html", "htm", "css", "scss", "less", "svg",
            "txt", "json", "yml", "yaml", "ini", "conf", "cfg", "xml", "csv", "log", "env",
            "sh", "bat", "cmd", "ps1", "bash",
            "md", "sql", "graphql"
        };

        for (String ext : allowedExtensions) {
            if (ext.equals(extension)) {
                return true;
            }
        }

        return false;
    }
}
