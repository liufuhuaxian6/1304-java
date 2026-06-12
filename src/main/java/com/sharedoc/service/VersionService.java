package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.OperationType;
import com.sharedoc.model.Response;
import com.sharedoc.model.VersionPatch;
import com.sharedoc.storage.VersionStorage;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private static final String STORAGE_FULL = "FULL";
    private static final String STORAGE_PATCH = "PATCH";
    private static final Duration EDIT_MERGE_WINDOW = Duration.ofSeconds(60);
    private static final Type PATCH_LIST_TYPE = new TypeToken<List<VersionPatch>>() { }.getType();
    private static final Map<String, List<DocumentVersion>> VERSION_MAP = new ConcurrentHashMap<>();
    private static final Object VERSION_LOCK = new Object();

    private final VersionStorage versionStorage = new VersionStorage();
    private final Gson gson = new Gson();

    public Response createInitialVersion(String documentId, String fileName, String username, String sourcePath) {
        return createVersion(documentId, fileName, username, sourcePath, OperationType.UPLOAD, "初始上传版本");
    }

    public Response createEditVersion(String documentId, String fileName, String username, String sourcePath, String comment) {
        String versionComment = normalizeComment(comment, "编辑保存版本");
        return createVersion(documentId, fileName, username, sourcePath, OperationType.EDIT, versionComment);
    }

    public Response createEditVersion(String documentId, String fileName, String username, int start, int end,
                                      String originalText, String replacementText, long revisionBefore,
                                      long revisionAfter, String comment) {
        if (replacementText == null) {
            return Response.fail("替换内容不能为空");
        }
        if (start < 0 || end < start) {
            return Response.fail("补丁区间非法");
        }

        String versionComment = normalizeComment(comment, "编辑保存版本");
        synchronized (VERSION_LOCK) {
            try {
                VersionPatch patch = new VersionPatch(
                        username,
                        start,
                        end,
                        originalText == null ? "" : originalText,
                        replacementText,
                        revisionBefore,
                        revisionAfter,
                        versionComment
                );
                DocumentVersion version = findMergeTarget(documentId, username, OperationType.EDIT);
                if (version == null) {
                    version = createPatchVersion(documentId, fileName, username, versionComment, patch);
                    VERSION_MAP.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
                } else {
                    appendPatch(version, patch, versionComment);
                }
                return new Response(true, "版本记录创建成功", version);
            } catch (Exception e) {
                return Response.fail("版本记录创建失败: " + e.getMessage());
            }
        }
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

    public Response downloadVersion(String documentId, String versionId) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        DocumentVersion version = findVersionById(documentId, versionId);
        if (version == null) {
            return Response.fail("历史版本不存在或不属于当前文档");
        }

        try {
            byte[] fileContent = reconstructVersionContent(version).getBytes(StandardCharsets.UTF_8);
            Map<String, Object> result = new HashMap<>();
            result.put("version", version);
            result.put("fileContent", fileContent);
            return new Response(true, "历史版本下载成功", result);
        } catch (Exception e) {
            return Response.fail("历史版本下载失败: " + e.getMessage());
        }
    }

    public Response diffWithPreviousVersion(String documentId, String versionId) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        synchronized (VERSION_LOCK) {
            List<DocumentVersion> versions = VERSION_MAP.getOrDefault(documentId, new ArrayList<>());
            for (int index = 0; index < versions.size(); index += 1) {
                DocumentVersion version = versions.get(index);
                if (!versionId.equals(version.getVersionId())) {
                    continue;
                }

                try {
                    String previousContent = index == 0 ? "" : reconstructVersionContent(versions.get(index - 1));
                    String currentContent = reconstructVersionContent(version);
                    Map<String, Object> result = new HashMap<>();
                    result.put("version", version);
                    result.put("previousVersion", index == 0 ? null : versions.get(index - 1));
                    result.put("changes", buildVersionChanges(version, previousContent, currentContent));
                    return new Response(true, "版本差异获取成功", result);
                } catch (Exception e) {
                    return Response.fail("版本差异获取失败: " + e.getMessage());
                }
            }
        }
        return Response.fail("历史版本不存在或不属于当前文档");
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

        DocumentVersion sourceVersion = findVersionById(document.getDocumentId(), versionId);
        if (sourceVersion == null) {
            return Response.fail("历史版本不存在或不属于当前文档");
        }

        try {
            String targetContent = reconstructVersionContent(sourceVersion);
            versionStorage.overwriteVersionText(document.getCurrentPath(), targetContent);
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
                String versionId = nextVersionId(documentId);
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
                version.setStorageType(STORAGE_FULL);
                version.setPatchCount(0);
                VERSION_MAP.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
                return new Response(true, "版本记录创建成功", version);
            } catch (Exception e) {
                return Response.fail("版本记录创建失败: " + e.getMessage());
            }
        }
    }

    private DocumentVersion findVersionById(String documentId, String versionId) {
        synchronized (VERSION_LOCK) {
            List<DocumentVersion> versions = VERSION_MAP.getOrDefault(documentId, new ArrayList<>());
            for (DocumentVersion version : versions) {
                if (versionId.equals(version.getVersionId())) {
                    return version;
                }
            }
            return null;
        }
    }

    private String nextVersionId(String documentId) {
        List<DocumentVersion> versions = VERSION_MAP.getOrDefault(documentId, new ArrayList<>());
        return "V-" + (versions.size() + 1);
    }

    private DocumentVersion findMergeTarget(String documentId, String username, OperationType operationType) {
        List<DocumentVersion> versions = VERSION_MAP.getOrDefault(documentId, new ArrayList<>());
        if (versions.isEmpty()) {
            return null;
        }

        DocumentVersion latest = versions.get(versions.size() - 1);
        if (!username.equals(latest.getEditor()) || latest.getOperationType() != operationType) {
            return null;
        }
        if (!STORAGE_PATCH.equals(latest.getStorageType())) {
            return null;
        }
        LocalDateTime editTime = latest.getEditTime();
        if (editTime == null || Duration.between(editTime, LocalDateTime.now()).compareTo(EDIT_MERGE_WINDOW) > 0) {
            return null;
        }
        return latest;
    }

    private DocumentVersion createPatchVersion(String documentId, String fileName, String username,
                                               String comment, VersionPatch patch) {
        String versionId = nextVersionId(documentId);
        String versionPath = versionStorage.savePatchFile(
                documentId,
                versionId,
                fileName,
                serializePatches(List.of(patch))
        );
        DocumentVersion version = new DocumentVersion(
                versionId,
                documentId,
                fileName,
                username,
                OperationType.EDIT,
                versionPath,
                comment
        );
        version.setStorageType(STORAGE_PATCH);
        version.setPatchCount(1);
        return version;
    }

    private void appendPatch(DocumentVersion version, VersionPatch patch, String comment) {
        List<VersionPatch> patches = readPatches(version);
        patches.add(patch);
        versionStorage.overwriteVersionText(version.getVersionPath(), serializePatches(patches));
        version.setPatchCount(patches.size());
        version.setEditTime(LocalDateTime.now());
        version.setComment(mergeComments(version.getComment(), comment));
    }

    private String reconstructVersionContent(DocumentVersion targetVersion) {
        List<DocumentVersion> versions = VERSION_MAP.getOrDefault(targetVersion.getDocumentId(), new ArrayList<>());
        String content = "";
        for (DocumentVersion version : versions) {
            if (STORAGE_PATCH.equals(version.getStorageType())) {
                content = applyPatches(content, readPatches(version));
            } else {
                content = versionStorage.readVersionText(version.getVersionPath());
            }
            if (version.getVersionId().equals(targetVersion.getVersionId())) {
                return content;
            }
        }
        throw new IllegalStateException("Version not found in document history: " + targetVersion.getVersionId());
    }

    private String applyPatches(String content, List<VersionPatch> patches) {
        String current = content == null ? "" : content;
        for (VersionPatch patch : patches) {
            int start = Math.max(0, Math.min(patch.getStart(), current.length()));
            int end = Math.max(start, Math.min(patch.getEnd(), current.length()));
            current = current.substring(0, start) + patch.getReplacementText() + current.substring(end);
        }
        return current;
    }

    private List<Map<String, Object>> buildDiffChanges(String previousContent, String currentContent) {
        String previous = previousContent == null ? "" : previousContent;
        String current = currentContent == null ? "" : currentContent;
        if (previous.equals(current)) {
            return new ArrayList<>();
        }

        int prefixLength = commonPrefixLength(previous, current);
        int previousSuffix = previous.length() - 1;
        int currentSuffix = current.length() - 1;
        while (previousSuffix >= prefixLength
                && currentSuffix >= prefixLength
                && previous.charAt(previousSuffix) == current.charAt(currentSuffix)) {
            previousSuffix -= 1;
            currentSuffix -= 1;
        }

        String removed = previous.substring(prefixLength, previousSuffix + 1);
        String added = current.substring(prefixLength, currentSuffix + 1);
        Map<String, Object> change = new HashMap<>();
        change.put("start", prefixLength);
        change.put("end", previousSuffix + 1);
        change.put("type", diffType(removed, added));
        change.put("removedText", removed);
        change.put("addedText", added);
        change.put("removedLine", lineNumberAt(previous, prefixLength));
        change.put("addedLine", lineNumberAt(current, prefixLength));
        return List.of(change);
    }

    private List<Map<String, Object>> buildVersionChanges(DocumentVersion version, String previousContent, String currentContent) {
        if (!STORAGE_PATCH.equals(version.getStorageType())) {
            return buildDiffChanges(previousContent, currentContent);
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        for (VersionPatch patch : readPatches(version)) {
            Map<String, Object> change = new HashMap<>();
            change.put("start", patch.getStart());
            change.put("end", patch.getEnd());
            change.put("type", diffType(patch.getOriginalText(), patch.getReplacementText()));
            change.put("removedText", patch.getOriginalText());
            change.put("addedText", patch.getReplacementText());
            change.put("removedLine", lineNumberAt(previousContent, patch.getStart()));
            change.put("addedLine", lineNumberAt(currentContent, patch.getStart()));
            changes.add(change);
        }
        return changes;
    }

    private int commonPrefixLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        int index = 0;
        while (index < max && left.charAt(index) == right.charAt(index)) {
            index += 1;
        }
        return index;
    }

    private String diffType(String removed, String added) {
        if (removed == null || removed.isEmpty()) {
            return "ADD";
        }
        if (added == null || added.isEmpty()) {
            return "DELETE";
        }
        return "REPLACE";
    }

    private int lineNumberAt(String content, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, content.length()));
        int line = 1;
        for (int index = 0; index < safeOffset; index += 1) {
            if (content.charAt(index) == '\n') {
                line += 1;
            }
        }
        return line;
    }

    private List<VersionPatch> readPatches(DocumentVersion version) {
        List<VersionPatch> patches = gson.fromJson(versionStorage.readVersionText(version.getVersionPath()), PATCH_LIST_TYPE);
        return patches == null ? new ArrayList<>() : new ArrayList<>(patches);
    }

    private String serializePatches(List<VersionPatch> patches) {
        return gson.toJson(patches);
    }

    private String normalizeComment(String comment, String defaultComment) {
        return comment == null || comment.isBlank() ? defaultComment : comment;
    }

    private String mergeComments(String existingComment, String newComment) {
        List<String> uniqueComments = new ArrayList<>();
        String combined = normalizeComment(existingComment, "") + "；" + normalizeComment(newComment, "");
        for (String item : combined.split("[；;]")) {
            String comment = item.trim();
            if (!comment.isEmpty() && !uniqueComments.contains(comment)) {
                uniqueComments.add(comment);
            }
        }
        return String.join("；", uniqueComments);
    }
}
