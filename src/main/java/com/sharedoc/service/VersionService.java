package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.OperationType;
import com.sharedoc.model.Response;
import com.sharedoc.storage.VersionStorage;
import com.sharedoc.util.IdGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version service skeleton.
 * Records version metadata and delegates version file path handling to VersionStorage.
 */
public class VersionService {
    private static final Map<String, List<DocumentVersion>> VERSION_MAP = new ConcurrentHashMap<>();
    private static final Object VERSION_LOCK = new Object();

    private final VersionStorage versionStorage = new VersionStorage();

    public Response createInitialVersion(String documentId, String fileName, String username, String sourcePath) {
        return createVersion(documentId, fileName, username, sourcePath, OperationType.UPLOAD, "初始上传版本");
    }

    public Response createEditVersion(String documentId, String fileName, String username, String sourcePath, String comment) {
        String versionComment = normalizeComment(comment, "编辑保存版本");
        return createVersion(documentId, fileName, username, sourcePath, OperationType.EDIT, versionComment);
    }

    public Response listVersions(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }

        synchronized (VERSION_LOCK) {
            List<DocumentVersion> versions = VERSION_MAP.getOrDefault(documentId, new ArrayList<>());
            return new Response(true, "历史版本列表获取成功", new ArrayList<>(versions));
        }
    }

    public Response downloadVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        DocumentVersion version = findVersionById(versionId);
        if (version == null) {
            return Response.fail("历史版本不存在");
        }

        try {
            byte[] fileContent = versionStorage.readVersionFile(version.getVersionPath());
            Map<String, Object> result = new HashMap<>();
            result.put("version", version);
            result.put("fileContent", fileContent);
            return new Response(true, "历史版本下载成功", result);
        } catch (Exception e) {
            return Response.fail("历史版本下载失败: " + e.getMessage());
        }
    }

    public Response rollbackToVersion(String documentId, String versionId) {
        return Response.fail("版本回滚需要文档当前路径，请通过文档服务发起回滚");
    }

    public Response rollbackToVersion(Document document, String versionId, String username) {
        if (document == null) {
            return Response.fail("文档不存在");
        }
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        DocumentVersion sourceVersion = findVersionById(versionId);
        if (sourceVersion == null || !document.getDocumentId().equals(sourceVersion.getDocumentId())) {
            return Response.fail("历史版本不存在或不属于当前文档");
        }

        try {
            versionStorage.restoreVersionFile(sourceVersion.getVersionPath(), document.getCurrentPath());
            Response rollbackResponse = createVersion(
                    document.getDocumentId(),
                    document.getFileName(),
                    username,
                    document.getCurrentPath(),
                    OperationType.ROLLBACK,
                    "回滚到版本 " + versionId
            );
            if (!rollbackResponse.isSuccess()) {
                return rollbackResponse;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("document", document);
            result.put("rolledBackFrom", sourceVersion);
            result.put("rollbackVersion", rollbackResponse.getData());
            return new Response(true, "版本回滚成功", result);
        } catch (Exception e) {
            return Response.fail("版本回滚失败: " + e.getMessage());
        }
    }

    private Response createVersion(String documentId, String fileName, String username, String sourcePath,
                                   OperationType operationType, String comment) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            return Response.fail("文件名不能为空");
        }
        if (username == null || username.isBlank()) {
            return Response.fail("操作用户不能为空");
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            return Response.fail("源文件路径不能为空");
        }

        synchronized (VERSION_LOCK) {
            try {
                String versionId = IdGenerator.nextVersionId();
                String versionPath = versionStorage.saveVersionFile(sourcePath, documentId, versionId, fileName);
                DocumentVersion version = new DocumentVersion(
                        versionId,
                        documentId,
                        fileName,
                        username,
                        operationType,
                        versionPath,
                        normalizeComment(comment, "无")
                );
                VERSION_MAP.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
                return new Response(true, "版本记录创建成功", version);
            } catch (Exception e) {
                return Response.fail("版本记录创建失败: " + e.getMessage());
            }
        }
    }

    private DocumentVersion findVersionById(String versionId) {
        synchronized (VERSION_LOCK) {
            for (List<DocumentVersion> versions : VERSION_MAP.values()) {
                for (DocumentVersion version : versions) {
                    if (versionId.equals(version.getVersionId())) {
                        return version;
                    }
                }
            }
            return null;
        }
    }

    private String normalizeComment(String comment, String defaultComment) {
        return comment == null || comment.isBlank() ? defaultComment : comment;
    }
}
