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
        List<Map<String, Object>> documentInfoList = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> docInfo = new HashMap<>();
            docInfo.put("documentId", doc.getDocumentId());
            docInfo.put("fileName", doc.getFileName());
            docInfo.put("owner", doc.getOwner());
            docInfo.put("uploadTime", doc.getUploadTime());
            docInfo.put("lastModifiedTime", doc.getLastModifiedTime());
            docInfo.put("isEditing", LOCK_SERVICE.isLocked(doc.getDocumentId()));
            docInfo.put("editingUser", LOCK_SERVICE.getLockOwner(doc.getDocumentId()));
            documentInfoList.add(docInfo);
        }
        return new Response(true, "文档列表获取成功", documentInfoList);
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
        // TODO: Update Document.editingUser and editingStartTime after lock acquisition.
        boolean locked = LOCK_SERVICE.tryLockDocument(documentId, username);
        if (!locked) {
            return Response.fail("Request edit placeholder: document is locked.");
        }
        Document document = DOCUMENTS.get(documentId);
        if (document != null) {
            document.setEditingUser(username);
            document.setEditingStartTime(LocalDateTime.now());
        }
        return Response.ok("Request edit placeholder: lock acquired.");
    }

    public Response saveDocument(Request request) {
        String username = request.getUsername();
        String documentId = request.getDocumentId();

        if (!LOCK_SERVICE.getLockOwner(documentId).equals(username)) {
            return Response.fail("您没有该文档的编辑权限");
        }

        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
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
        // TODO: Clear document editing metadata and notify waiting clients if needed.
        LOCK_SERVICE.unlockDocument(documentId, username);
        Document document = DOCUMENTS.get(documentId);
        if (document != null && username != null && username.equals(document.getEditingUser())) {
            document.setEditingUser(null);
            document.setEditingStartTime(null);
        }
        return Response.ok("Release edit placeholder.");
    }

    public boolean isEditing(String documentId) {
        // TODO: Add document existence validation.
        return LOCK_SERVICE.isLocked(documentId);
    }

    public String getEditingUser(String documentId) {
        // TODO: Return null or a user display name according to later UI requirements.
        return LOCK_SERVICE.getLockOwner(documentId);
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
