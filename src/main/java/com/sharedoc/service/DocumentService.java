package com.sharedoc.service;

import com.sharedoc.model.ContentUpdateResult;
import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.LockReleaseResult;
import com.sharedoc.model.RangeLock;
import com.sharedoc.model.Response;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.util.IdGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document service.
 * Owns document metadata, range locking, and partial save workflows.
 */
public class DocumentService {
    static final Map<String, Document> DOCUMENTS = new ConcurrentHashMap<>();
    static final Map<String, Object> DOCUMENT_MONITORS = new ConcurrentHashMap<>();
    private static final LockService LOCK_SERVICE = new LockService();
    private static final VersionService VERSION_SERVICE = new VersionService();

    private final FileStorage fileStorage;

    public DocumentService() {
        this(new FileStorage());
    }

    DocumentService(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    public Response listDocuments() {
        List<Document> documents = new ArrayList<>(DOCUMENTS.values());
        for (Document doc : documents) {
            synchronized (documentMonitor(doc.getDocumentId())) {
                syncDocumentEditingState(doc);
            }
        }
        return new Response(true, "文档列表获取成功", documents);
    }

    public Response uploadDocument(String username, String fileName, byte[] fileContent) {
        if (username == null || username.isBlank()) {
            return Response.fail("请先登录");
        }
        if (fileName == null || fileName.isBlank()) {
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

            Response versionResponse = VERSION_SERVICE.createInitialVersion(
                    documentId,
                    fileName,
                    username,
                    storagePath
            );
            if (!versionResponse.isSuccess()) {
                DOCUMENTS.remove(documentId);
                fileStorage.deleteFile(storagePath);
                return Response.fail("文档上传失败: " + versionResponse.getMessage());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            result.put("initialVersion", versionResponse.getData());
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

        synchronized (documentMonitor(documentId)) {
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
    }

    public Response viewDocument(String documentId) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            try {
                byte[] fileContent = fileStorage.readFile(document.getCurrentPath());
                String preview;
                String fileName = document.getFileName().toLowerCase();
                boolean isTextFile = fileName.endsWith(".txt") || fileName.endsWith(".md")
                        || fileName.endsWith(".csv") || fileName.endsWith(".xml")
                        || fileName.endsWith(".json") || fileName.endsWith(".html")
                        || fileName.endsWith(".css") || fileName.endsWith(".js")
                        || fileName.endsWith(".java") || fileName.endsWith(".yml")
                        || fileName.endsWith(".yaml") || fileName.endsWith(".sql");

                if (isTextFile) {
                    preview = new String(fileContent, StandardCharsets.UTF_8);
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
    }

    public Response getDocumentContent(String documentId) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            try {
                String content = new String(fileStorage.readFile(document.getCurrentPath()), StandardCharsets.UTF_8);
                syncDocumentEditingState(document);

                Map<String, Object> result = new HashMap<>();
                result.put("document", document);
                result.put("contentText", content);
                result.put("revision", document.getRevision());
                result.put("activeLocks", LOCK_SERVICE.getVisibleLocks(documentId));
                return new Response(true, "文档内容获取成功", result);
            } catch (Exception e) {
                return Response.fail("文档内容获取失败: " + e.getMessage());
            }
        }
    }

    public Response requestEdit(String documentId, String username, long revision, int start, int end) {
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

        synchronized (documentMonitor(documentId)) {
            if (revision != document.getRevision()) {
                return Response.fail("文档版本已更新，请刷新后重试");
            }
            int maxLength = getDocumentLength(document);
            if (start < 0 || end < start || end > maxLength) {
                return Response.fail("锁定区间超出文档范围");
            }

            RangeLock lock = LOCK_SERVICE.tryLockRange(documentId, username, revision, start, end);
            if (lock == null) {
                RangeLock ownLock = LOCK_SERVICE.getLockByOwner(documentId, username);
                if (ownLock != null) {
                    return Response.fail("您已在该文档持有一个编辑区间");
                }

                return Response.fail("区间锁申请失败");
            }

            syncDocumentEditingState(document);
            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            result.put("lock", lock);
            result.put("activeLocks", LOCK_SERVICE.getVisibleLocks(documentId));
            return new Response(true, lock.isQueued() ? "编辑区间已进入等待队列" : "编辑区间申请成功", result);
        }
    }

    public Response saveRange(String username, String documentId, String lockId, long clientRevision,
                              String replacementText, String comment) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }
        if (replacementText == null) {
            return Response.fail("替换内容不能为空");
        }
        synchronized (documentMonitor(documentId)) {
            RangeLock lock = LOCK_SERVICE.getLockById(documentId, lockId);
            if (lock == null || !username.equals(lock.getOwner())) {
                return Response.fail("您没有该区间的编辑权限");
            }
            if (clientRevision != document.getRevision() && clientRevision != lock.getBaseRevision()) {
                return Response.fail("文档版本已更新，请刷新后重试");
            }

            try {
                String content = new String(fileStorage.readFile(document.getCurrentPath()), StandardCharsets.UTF_8);
                int start = lock.getCurrentStart();
                int end = lock.getCurrentEnd();
                if (start < 0 || end < start || end > content.length()) {
                    return Response.fail("锁定区间已失效，请刷新后重试");
                }

                long revisionBefore = document.getRevision();
                int delta = replacementText.length() - (end - start);
                String newContent = content.substring(0, start) + replacementText + content.substring(end);
                fileStorage.saveFile(document.getCurrentPath(), newContent.getBytes(StandardCharsets.UTF_8));

                Response versionResponse = VERSION_SERVICE.createEditVersion(
                        documentId,
                        document.getFileName(),
                        username,
                        document.getCurrentPath(),
                        comment
                );
                if (!versionResponse.isSuccess()) {
                    return Response.fail("文档保存失败: " + versionResponse.getMessage());
                }

                document.setRevision(revisionBefore + 1);
                document.setLastModifiedTime(LocalDateTime.now());

                LOCK_SERVICE.shiftLocksAfterEdit(documentId, lockId, start, end, delta);
                LockReleaseResult releaseResult = LOCK_SERVICE.releaseLockByOwner(documentId, username);
                RangeLock releasedLock = releaseResult.getReleasedLocks().isEmpty() ? null : releaseResult.getReleasedLocks().get(0);

                syncDocumentEditingState(document);

                DocumentVersion editVersion = (DocumentVersion) versionResponse.getData();
                ContentUpdateResult updateResult = new ContentUpdateResult(
                        document,
                        editVersion,
                        releasedLock,
                        start,
                        end,
                        replacementText,
                        delta,
                        revisionBefore,
                        document.getRevision(),
                        releaseResult.getActiveLocks()
                );

                Map<String, Object> result = new HashMap<>();
                result.put("document", document);
                result.put("editVersion", editVersion);
                result.put("contentUpdate", updateResult);
                result.put("activeLocks", releaseResult.getActiveLocks());
                result.put("promotedLocks", releaseResult.getPromotedLocks());
                return new Response(true, "文档保存成功", result);
            } catch (Exception e) {
                return Response.fail("文档保存失败: " + e.getMessage());
            }
        }
    }

    public Response releaseEdit(String documentId, String username) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            LockReleaseResult releaseResult = LOCK_SERVICE.releaseLockByOwner(documentId, username);
            if (releaseResult.getReleasedLocks().isEmpty()) {
                return Response.fail("您不是该文档的编辑用户，无法释放编辑权限");
            }

            syncDocumentEditingState(document);
            Map<String, Object> result = new HashMap<>();
            result.put("releasedLocks", releaseResult.getReleasedLocks());
            result.put("activeLocks", releaseResult.getActiveLocks());
            result.put("promotedLocks", releaseResult.getPromotedLocks());
            return new Response(true, "编辑权限已释放", result);
        }
    }

    public Response rollbackDocumentToVersion(String username, String documentId, String versionId) {
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            if (LOCK_SERVICE.hasAnyLock(documentId)) {
                return Response.fail("当前文档存在活动编辑区间，无法回滚");
            }

            Response rollbackResponse = VERSION_SERVICE.rollbackToVersion(document, versionId, username);
            if (rollbackResponse.isSuccess()) {
                document.setLastModifiedTime(LocalDateTime.now());
                document.setRevision(document.getRevision() + 1);
            }
            return rollbackResponse;
        }
    }

    public int releaseLocksHeldBy(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }

        int released = 0;
        for (Document document : new ArrayList<>(DOCUMENTS.values())) {
            synchronized (documentMonitor(document.getDocumentId())) {
                LockReleaseResult result = LOCK_SERVICE.releaseLockByOwner(document.getDocumentId(), username);
                if (result.getReleasedLocks().isEmpty()) {
                    continue;
                }
                released += result.getReleasedLocks().size();
                syncDocumentEditingState(document);
            }
        }
        return released;
    }

    public List<RangeLock> getActiveLocks(String documentId) {
        synchronized (documentMonitor(documentId)) {
            return LOCK_SERVICE.getActiveLocks(documentId);
        }
    }

    public boolean hasAnyLock(String documentId) {
        synchronized (documentMonitor(documentId)) {
            return LOCK_SERVICE.hasAnyLock(documentId);
        }
    }

    public void releaseAllEditsByUser(String username) {
        releaseLocksHeldBy(username);
    }

    private void syncDocumentEditingState(Document document) {
        List<RangeLock> activeLocks = LOCK_SERVICE.getActiveLocks(document.getDocumentId());
        if (activeLocks.isEmpty()) {
            document.setEditingUser(null);
            document.setEditingStartTime(null);
            document.setActiveLockCount(0);
            return;
        }

        RangeLock firstLock = activeLocks.get(0);
        document.setEditingUser(firstLock.getOwner());
        document.setEditingStartTime(firstLock.getAcquiredAt());
        document.setActiveLockCount(activeLocks.size());
    }

    private int getDocumentLength(Document document) {
        try {
            return new String(fileStorage.readFile(document.getCurrentPath()), StandardCharsets.UTF_8).length();
        } catch (Exception e) {
            throw new IllegalStateException("读取文档长度失败", e);
        }
    }

    private Object documentMonitor(String documentId) {
        return DOCUMENT_MONITORS.computeIfAbsent(documentId, key -> new Object());
    }

    private boolean rangesOverlap(int startA, int endA, int startB, int endB) {
        if (startA == endA && startB == endB) {
            return startA == startB;
        }
        if (startA == endA) {
            return startA > startB && startA < endB;
        }
        if (startB == endB) {
            return startB > startA && startB < endA;
        }
        return startA < endB && startB < endA;
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
