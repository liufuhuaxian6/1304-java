package com.sharedoc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sharedoc.model.ContentUpdateResult;
import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.ErrorCodes;
import com.sharedoc.model.LockReleaseResult;
import com.sharedoc.model.RangeLock;
import com.sharedoc.model.Response;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.storage.JsonStore;
import com.sharedoc.storage.StoredDocument;
import com.sharedoc.util.FileNames;
import com.sharedoc.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * All operations on one document are serialized through a per-document monitor.
 */
public class DocumentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentService.class);

    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private final Map<String, Object> documentMonitors = new ConcurrentHashMap<>();
    private final FileStorage fileStorage;
    private final LockService lockService;
    private final VersionService versionService;
    private final JsonStore documentStore;

    public DocumentService() {
        this(new FileStorage(), new LockService(), new VersionService());
    }

    public DocumentService(FileStorage fileStorage, LockService lockService, VersionService versionService) {
        this(fileStorage, lockService, versionService,
                new JsonStore(Path.of(ServerConfig.METADATA_STORAGE_PATH, "documents.json")));
    }

    public DocumentService(FileStorage fileStorage, LockService lockService, VersionService versionService,
                           JsonStore documentStore) {
        this.fileStorage = fileStorage;
        this.lockService = lockService;
        this.versionService = versionService;
        this.documentStore = documentStore;
        loadDocuments();
    }

    private void loadDocuments() {
        List<StoredDocument> stored = documentStore.read(new TypeReference<List<StoredDocument>>() { });
        if (stored == null) {
            return;
        }
        long maxId = 0;
        for (StoredDocument record : stored) {
            Document document = new Document(
                    record.getDocumentId(), record.getFileName(), record.getOwner(), record.getCurrentPath());
            document.setUploadTime(record.getUploadTime());
            document.setLastModifiedTime(record.getLastModifiedTime());
            document.setRevision(record.getRevision());
            // Locks never survive a restart, so the derived editing state stays
            // at its default and is recomputed from live locks on the next read.
            documents.put(document.getDocumentId(), document);
            maxId = Math.max(maxId, IdGenerator.numericSuffix(document.getDocumentId()));
        }
        IdGenerator.ensureDocumentSequenceAtLeast(maxId + 1);
    }

    private void persistDocuments() {
        List<StoredDocument> records = new ArrayList<>();
        for (Document document : documents.values()) {
            StoredDocument record = new StoredDocument();
            record.setDocumentId(document.getDocumentId());
            record.setFileName(document.getFileName());
            record.setOwner(document.getOwner());
            record.setCurrentPath(document.getCurrentPath());
            record.setUploadTime(document.getUploadTime());
            record.setLastModifiedTime(document.getLastModifiedTime());
            record.setRevision(document.getRevision());
            records.add(record);
        }
        documentStore.write(records);
    }

    public boolean documentExists(String documentId) {
        return documentId != null && documents.containsKey(documentId);
    }

    public Response listDocuments() {
        List<Document> snapshot = new ArrayList<>();
        for (Document doc : documents.values()) {
            synchronized (documentMonitor(doc.getDocumentId())) {
                syncDocumentEditingState(doc);
                snapshot.add(copyOf(doc));
            }
        }
        return new Response(true, "文档列表获取成功", snapshot);
    }

    public Response uploadDocument(String username, String fileName, byte[] fileContent) {
        if (username == null || username.isBlank()) {
            return Response.fail(ErrorCodes.AUTH_REQUIRED, "请先登录");
        }
        if (fileName == null || fileName.isBlank()) {
            return Response.fail("文件名不能为空");
        }
        if (fileContent == null || fileContent.length == 0) {
            return Response.fail("文件内容不能为空");
        }
        if (fileContent.length > ServerConfig.MAX_UPLOAD_BYTES) {
            return Response.fail(ErrorCodes.FILE_TOO_LARGE,
                    "文件大小超过限制（最大 " + (ServerConfig.MAX_UPLOAD_BYTES / 1024 / 1024) + " MB）");
        }

        String safeFileName = FileNames.sanitize(fileName);
        if (!isAllowedFileType(safeFileName)) {
            return Response.fail(ErrorCodes.UNSUPPORTED_FILE_TYPE,
                    "不支持的文件类型，请上传代码、网页样式、配置数据、脚本命令、文档标记或工程文件");
        }

        String documentId = IdGenerator.nextDocumentId();
        String storagePath = Path.of(ServerConfig.DOCUMENT_STORAGE_PATH, documentId + "_" + safeFileName).toString();

        try {
            fileStorage.saveFile(storagePath, fileContent);
            Document document = new Document(documentId, safeFileName, username, storagePath);
            documents.put(documentId, document);

            Response versionResponse = versionService.createInitialVersion(
                    documentId,
                    safeFileName,
                    username,
                    storagePath
            );
            if (!versionResponse.isSuccess()) {
                documents.remove(documentId);
                fileStorage.deleteFile(storagePath);
                return Response.fail(versionResponse.getCode(), "文档上传失败: " + versionResponse.getMessage());
            }

            persistDocuments();
            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            result.put("initialVersion", versionResponse.getData());
            return new Response(true, "文档上传成功", result);
        } catch (Exception e) {
            LOGGER.error("Upload failed, fileName={}", safeFileName, e);
            return Response.fail(ErrorCodes.INTERNAL_ERROR, "文档上传失败");
        }
    }

    public Response downloadDocument(String documentId) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            try {
                byte[] fileContent = fileStorage.readFile(document.getCurrentPath());
                Map<String, Object> result = new HashMap<>();
                result.put("document", document);
                result.put("fileContent", fileContent);
                return new Response(true, "文档下载成功", result);
            } catch (Exception e) {
                LOGGER.error("Download failed, documentId={}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "文档下载失败");
            }
        }
    }

    public Response viewDocument(String documentId) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
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
                LOGGER.error("View failed, documentId={}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "文档查看失败");
            }
        }
    }

    public Response getDocumentContent(String documentId) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            try {
                String content = new String(fileStorage.readFile(document.getCurrentPath()), StandardCharsets.UTF_8);
                syncDocumentEditingState(document);

                Map<String, Object> result = new HashMap<>();
                result.put("document", document);
                result.put("contentText", content);
                result.put("revision", document.getRevision());
                result.put("activeLocks", lockService.getVisibleLocks(documentId));
                return new Response(true, "文档内容获取成功", result);
            } catch (Exception e) {
                LOGGER.error("Get content failed, documentId={}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "文档内容获取失败");
            }
        }
    }

    public Response requestEdit(String documentId, String username, long revision, int start, int end) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档 ID 不能为空");
        }
        if (username == null || username.isBlank()) {
            return Response.fail(ErrorCodes.AUTH_REQUIRED, "请先登录");
        }

        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            try {
                if (revision != document.getRevision()) {
                    return Response.fail(ErrorCodes.REVISION_STALE, "文档版本已更新，请刷新后重试");
                }
                int maxLength = getDocumentLength(document);
                if (start < 0 || end < start || end > maxLength) {
                    return Response.fail("锁定区间超出文档范围");
                }

                RangeLock lock = lockService.tryLockRange(documentId, username, revision, start, end);
                if (lock == null) {
                    RangeLock ownLock = lockService.getLockByOwner(documentId, username);
                    if (ownLock != null) {
                        return Response.fail(ErrorCodes.USER_ALREADY_HAS_LOCK, "您已在该文档持有一个编辑区间");
                    }
                    return Response.fail(ErrorCodes.LOCK_FAILED, "区间锁申请失败");
                }

                syncDocumentEditingState(document);
                Map<String, Object> result = new HashMap<>();
                result.put("document", document);
                result.put("lock", lock);
                result.put("activeLocks", lockService.getVisibleLocks(documentId));
                return new Response(true, lock.isQueued() ? "编辑区间已进入等待队列" : "编辑区间申请成功", result);
            } catch (Exception e) {
                LOGGER.error("Request edit failed, documentId={}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "编辑区间申请失败");
            }
        }
    }

    public Response saveRange(String username, String documentId, String lockId, long clientRevision,
                              String replacementText, String comment) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        if (replacementText == null) {
            return Response.fail("替换内容不能为空");
        }
        synchronized (documentMonitor(documentId)) {
            RangeLock lock = lockService.getLockById(documentId, lockId);
            if (lock == null || !username.equals(lock.getOwner())) {
                return Response.fail(ErrorCodes.NO_EDIT_PERMISSION, "您没有该区间的编辑权限");
            }
            if (clientRevision != document.getRevision() && clientRevision != lock.getBaseRevision()) {
                return Response.fail(ErrorCodes.REVISION_STALE, "文档版本已更新，请刷新后重试");
            }

            try {
                String content = new String(fileStorage.readFile(document.getCurrentPath()), StandardCharsets.UTF_8);
                int start = lock.getCurrentStart();
                int end = lock.getCurrentEnd();
                if (start < 0 || end < start || end > content.length()) {
                    return Response.fail(ErrorCodes.INVALID_LOCK_RANGE, "锁定区间已失效，请刷新后重试");
                }

                long revisionBefore = document.getRevision();
                String originalText = content.substring(start, end);
                int delta = replacementText.length() - (end - start);
                String newContent = content.substring(0, start) + replacementText + content.substring(end);
                fileStorage.saveFile(document.getCurrentPath(), newContent.getBytes(StandardCharsets.UTF_8));

                Response versionResponse = versionService.createEditVersion(
                        documentId,
                        document.getFileName(),
                        username,
                        start,
                        end,
                        originalText,
                        replacementText,
                        revisionBefore,
                        revisionBefore + 1,
                        comment,
                        newContent
                );
                if (!versionResponse.isSuccess()) {
                    // Version record failed: restore the previous content so the
                    // stored document never diverges from the version history.
                    fileStorage.saveFile(document.getCurrentPath(), content.getBytes(StandardCharsets.UTF_8));
                    return Response.fail(versionResponse.getCode(), "文档保存失败: " + versionResponse.getMessage());
                }

                document.setRevision(revisionBefore + 1);
                document.setLastModifiedTime(LocalDateTime.now());
                persistDocuments();

                lockService.shiftLocksAfterEdit(documentId, lockId, start, end, delta);
                LockReleaseResult releaseResult = lockService.releaseLockByOwner(documentId, username);
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
                LOGGER.error("Save range failed, documentId={}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "文档保存失败");
            }
        }
    }

    public Response releaseEdit(String documentId, String username) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }

        synchronized (documentMonitor(documentId)) {
            LockReleaseResult releaseResult = lockService.releaseLockByOwner(documentId, username);
            if (releaseResult.getReleasedLocks().isEmpty()) {
                return Response.fail(ErrorCodes.NO_EDIT_PERMISSION, "您不是该文档的编辑用户，无法释放编辑权限");
            }

            syncDocumentEditingState(document);
            Map<String, Object> result = new HashMap<>();
            result.put("releasedLocks", releaseResult.getReleasedLocks());
            result.put("activeLocks", releaseResult.getActiveLocks());
            result.put("promotedLocks", releaseResult.getPromotedLocks());
            return new Response(true, "编辑权限已释放", result);
        }
    }

    /**
     * Rolls the document content back to a historical version.
     * Only the document owner or an ADMIN may roll back.
     */
    public Response rollbackDocumentToVersion(String username, String documentId, String versionId, boolean isAdmin) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        if (!isAdmin && (username == null || !username.equals(document.getOwner()))) {
            return Response.fail(ErrorCodes.FORBIDDEN, "只有文档所有者或管理员可以回滚该文档");
        }

        synchronized (documentMonitor(documentId)) {
            if (lockService.hasAnyLock(documentId)) {
                return Response.fail(ErrorCodes.ACTIVE_LOCKS_PRESENT, "当前文档存在活动编辑区间，无法回滚");
            }

            Response rollbackResponse = versionService.rollbackToVersion(document, versionId, username);
            if (rollbackResponse.isSuccess()) {
                document.setLastModifiedTime(LocalDateTime.now());
                document.setRevision(document.getRevision() + 1);
                persistDocuments();
            }
            return rollbackResponse;
        }
    }

    /**
     * Deletes a document, its current file and all of its version files.
     * Only the owner or an ADMIN may delete, and not while any lock is held.
     */
    public Response deleteDocument(String username, String documentId, boolean isAdmin) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        if (!isAdmin && (username == null || !username.equals(document.getOwner()))) {
            return Response.fail(ErrorCodes.FORBIDDEN, "只有文档所有者或管理员可以删除该文档");
        }

        synchronized (documentMonitor(documentId)) {
            if (lockService.hasAnyLock(documentId)) {
                return Response.fail(ErrorCodes.ACTIVE_LOCKS_PRESENT, "当前文档存在活动编辑区间，无法删除");
            }

            documents.remove(documentId);
            versionService.deleteDocumentVersions(documentId);
            try {
                fileStorage.deleteFile(document.getCurrentPath());
            } catch (Exception e) {
                LOGGER.warn("Failed to delete document file {}", document.getCurrentPath(), e);
            }
            persistDocuments();

            Map<String, Object> result = new HashMap<>();
            result.put("documentId", documentId);
            return new Response(true, "文档已删除", result);
        }
    }

    /**
     * Renames a document's display name. The physical file and historical
     * versions keep their original names; only the owner or an ADMIN may rename.
     */
    public Response renameDocument(String username, String documentId, String newFileName, boolean isAdmin) {
        Document document = documents.get(documentId);
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        if (!isAdmin && (username == null || !username.equals(document.getOwner()))) {
            return Response.fail(ErrorCodes.FORBIDDEN, "只有文档所有者或管理员可以重命名该文档");
        }
        if (newFileName == null || newFileName.isBlank()) {
            return Response.fail("文件名不能为空");
        }
        String safeFileName = FileNames.sanitize(newFileName);
        if (!isAllowedFileType(safeFileName)) {
            return Response.fail(ErrorCodes.UNSUPPORTED_FILE_TYPE, "不支持的文件类型");
        }

        synchronized (documentMonitor(documentId)) {
            document.setFileName(safeFileName);
            document.setLastModifiedTime(LocalDateTime.now());
            persistDocuments();

            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            return new Response(true, "文档已重命名", result);
        }
    }

    public int releaseLocksHeldBy(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }

        int released = 0;
        for (Document document : new ArrayList<>(documents.values())) {
            synchronized (documentMonitor(document.getDocumentId())) {
                LockReleaseResult result = lockService.releaseLockByOwner(document.getDocumentId(), username);
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
            return lockService.getActiveLocks(documentId);
        }
    }

    public boolean hasAnyLock(String documentId) {
        synchronized (documentMonitor(documentId)) {
            return lockService.hasAnyLock(documentId);
        }
    }

    private void syncDocumentEditingState(Document document) {
        List<RangeLock> activeLocks = lockService.getActiveLocks(document.getDocumentId());
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

    private Document copyOf(Document source) {
        Document copy = new Document();
        copy.setDocumentId(source.getDocumentId());
        copy.setFileName(source.getFileName());
        copy.setOwner(source.getOwner());
        copy.setCurrentPath(source.getCurrentPath());
        copy.setUploadTime(source.getUploadTime());
        copy.setLastModifiedTime(source.getLastModifiedTime());
        copy.setEditingUser(source.getEditingUser());
        copy.setEditingStartTime(source.getEditingStartTime());
        copy.setRevision(source.getRevision());
        copy.setActiveLockCount(source.getActiveLockCount());
        return copy;
    }

    private int getDocumentLength(Document document) {
        return new String(fileStorage.readFile(document.getCurrentPath()), StandardCharsets.UTF_8).length();
    }

    private Object documentMonitor(String documentId) {
        return documentMonitors.computeIfAbsent(documentId, key -> new Object());
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
