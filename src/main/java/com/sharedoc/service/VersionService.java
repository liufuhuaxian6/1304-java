package com.sharedoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.ErrorCodes;
import com.sharedoc.model.OperationType;
import com.sharedoc.model.Response;
import com.sharedoc.model.VersionPatch;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.storage.JsonStore;
import com.sharedoc.storage.VersionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version service.
 * Stores version metadata in memory and version content on disk.
 *
 * Storage strategy:
 * - Upload and rollback versions are FULL snapshots of the document.
 * - Edit versions are PATCH records (range, original text, replacement text).
 * - Rapid consecutive edits by the same user are merged into one PATCH version.
 * - After {@link #SNAPSHOT_INTERVAL} consecutive PATCH versions, the next edit
 *   is stored as a FULL snapshot so reconstruction never replays an unbounded
 *   patch chain.
 */
public class VersionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionService.class);
    private static final String STORAGE_FULL = "FULL";
    private static final String STORAGE_PATCH = "PATCH";
    private static final Duration EDIT_MERGE_WINDOW = Duration.ofSeconds(60);
    private static final int SNAPSHOT_INTERVAL = 20;
    private static final ObjectMapper PATCH_MAPPER = new ObjectMapper();
    private static final TypeReference<List<VersionPatch>> PATCH_LIST_TYPE = new TypeReference<>() { };

    private final Map<String, List<DocumentVersion>> versionMap = new ConcurrentHashMap<>();
    private final Object versionLock = new Object();
    private final VersionStorage versionStorage = new VersionStorage();
    private final JsonStore versionStore;

    public VersionService() {
        this(new JsonStore(Path.of(ServerConfig.METADATA_STORAGE_PATH, "versions.json")));
    }

    public VersionService(JsonStore versionStore) {
        this.versionStore = versionStore;
        loadVersions();
    }

    private void loadVersions() {
        Map<String, List<DocumentVersion>> stored =
                versionStore.read(new TypeReference<Map<String, List<DocumentVersion>>>() { });
        if (stored != null) {
            versionMap.putAll(stored);
        }
    }

    /** Persists the version index. Must be called while holding {@code versionLock}. */
    private void persist() {
        versionStore.write(new HashMap<>(versionMap));
    }

    public Response createInitialVersion(String documentId, String fileName, String username, String sourcePath) {
        return createVersion(documentId, fileName, username, sourcePath, OperationType.UPLOAD, "初始上传版本");
    }

    /**
     * Records one range edit. {@code contentAfterEdit} is the full document text
     * after the edit; it is used when this version is stored as a FULL snapshot.
     */
    public Response createEditVersion(String documentId, String fileName, String username, int start, int end,
                                      String originalText, String replacementText, long revisionBefore,
                                      long revisionAfter, String comment, String contentAfterEdit) {
        if (replacementText == null) {
            return Response.fail("替换内容不能为空");
        }
        if (start < 0 || end < start) {
            return Response.fail("补丁区间非法");
        }

        String versionComment = normalizeComment(comment, "编辑保存版本");
        synchronized (versionLock) {
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
                if (version != null) {
                    appendPatch(version, patch, versionComment);
                    persist();
                    return new Response(true, "版本记录创建成功", version);
                }

                if (contentAfterEdit != null && patchVersionsSinceLastFull(documentId) >= SNAPSHOT_INTERVAL - 1) {
                    version = createSnapshotVersion(documentId, fileName, username, versionComment, contentAfterEdit);
                } else {
                    version = createPatchVersion(documentId, fileName, username, versionComment, patch);
                }
                versionMap.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
                persist();
                return new Response(true, "版本记录创建成功", version);
            } catch (Exception e) {
                LOGGER.error("Failed to create edit version, documentId={}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "版本记录创建失败");
            }
        }
    }

    public Response listVersions(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }

        synchronized (versionLock) {
            List<DocumentVersion> versions = versionMap.getOrDefault(documentId, new ArrayList<>());
            return new Response(true, "历史版本列表获取成功", new ArrayList<>(versions));
        }
    }

    /** Removes all version records and stored version files for one document. */
    public void deleteDocumentVersions(String documentId) {
        synchronized (versionLock) {
            List<DocumentVersion> versions = versionMap.remove(documentId);
            if (versions != null) {
                for (DocumentVersion version : versions) {
                    try {
                        versionStorage.deleteVersionFile(version.getVersionPath());
                    } catch (Exception e) {
                        LOGGER.warn("Failed to delete version file {}", version.getVersionPath(), e);
                    }
                }
            }
            persist();
        }
    }

    public Response downloadVersion(String documentId, String versionId) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        synchronized (versionLock) {
            DocumentVersion version = findVersionById(documentId, versionId);
            if (version == null) {
                return Response.fail(ErrorCodes.VERSION_NOT_FOUND, "历史版本不存在或不属于当前文档");
            }

            try {
                byte[] fileContent = reconstructVersionContent(version).getBytes(StandardCharsets.UTF_8);
                Map<String, Object> result = new HashMap<>();
                result.put("version", version);
                result.put("fileContent", fileContent);
                return new Response(true, "历史版本下载成功", result);
            } catch (Exception e) {
                LOGGER.error("Failed to download version {} of document {}", versionId, documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "历史版本下载失败");
            }
        }
    }

    public Response diffWithPreviousVersion(String documentId, String versionId) {
        if (documentId == null || documentId.isBlank()) {
            return Response.fail("文档ID不能为空");
        }
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        synchronized (versionLock) {
            List<DocumentVersion> versions = versionMap.getOrDefault(documentId, new ArrayList<>());
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
                    LOGGER.error("Failed to diff version {} of document {}", versionId, documentId, e);
                    return Response.fail(ErrorCodes.INTERNAL_ERROR, "版本差异获取失败");
                }
            }
        }
        return Response.fail(ErrorCodes.VERSION_NOT_FOUND, "历史版本不存在或不属于当前文档");
    }

    public Response rollbackToVersion(Document document, String versionId, String username) {
        if (document == null) {
            return Response.fail(ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在");
        }
        if (versionId == null || versionId.isBlank()) {
            return Response.fail("版本ID不能为空");
        }

        synchronized (versionLock) {
            DocumentVersion sourceVersion = findVersionById(document.getDocumentId(), versionId);
            if (sourceVersion == null) {
                return Response.fail(ErrorCodes.VERSION_NOT_FOUND, "历史版本不存在或不属于当前文档");
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
                LOGGER.error("Failed to rollback document {} to version {}", document.getDocumentId(), versionId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "版本回滚失败");
            }
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

        synchronized (versionLock) {
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
                versionMap.computeIfAbsent(documentId, key -> new ArrayList<>()).add(version);
                persist();
                return new Response(true, "版本记录创建成功", version);
            } catch (Exception e) {
                LOGGER.error("Failed to create version for document {}", documentId, e);
                return Response.fail(ErrorCodes.INTERNAL_ERROR, "版本记录创建失败");
            }
        }
    }

    private DocumentVersion findVersionById(String documentId, String versionId) {
        List<DocumentVersion> versions = versionMap.getOrDefault(documentId, new ArrayList<>());
        for (DocumentVersion version : versions) {
            if (versionId.equals(version.getVersionId())) {
                return version;
            }
        }
        return null;
    }

    private String nextVersionId(String documentId) {
        List<DocumentVersion> versions = versionMap.getOrDefault(documentId, new ArrayList<>());
        return "V-" + (versions.size() + 1);
    }

    private DocumentVersion findMergeTarget(String documentId, String username, OperationType operationType) {
        List<DocumentVersion> versions = versionMap.getOrDefault(documentId, new ArrayList<>());
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

    private int patchVersionsSinceLastFull(String documentId) {
        List<DocumentVersion> versions = versionMap.getOrDefault(documentId, new ArrayList<>());
        int count = 0;
        for (int index = versions.size() - 1; index >= 0; index -= 1) {
            if (STORAGE_PATCH.equals(versions.get(index).getStorageType())) {
                count += 1;
            } else {
                break;
            }
        }
        return count;
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

    private DocumentVersion createSnapshotVersion(String documentId, String fileName, String username,
                                                  String comment, String content) {
        String versionId = nextVersionId(documentId);
        String versionPath = versionStorage.saveVersionText(documentId, versionId, fileName, content);
        DocumentVersion version = new DocumentVersion(
                versionId,
                documentId,
                fileName,
                username,
                OperationType.EDIT,
                versionPath,
                comment
        );
        version.setStorageType(STORAGE_FULL);
        version.setPatchCount(0);
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

    /**
     * Rebuilds the document text as of {@code targetVersion}.
     * Replay starts from the latest FULL snapshot at or before the target,
     * so cost is bounded by the snapshot interval rather than total history.
     * Must be called while holding {@code versionLock}.
     */
    private String reconstructVersionContent(DocumentVersion targetVersion) {
        List<DocumentVersion> versions = versionMap.getOrDefault(targetVersion.getDocumentId(), new ArrayList<>());
        int targetIndex = -1;
        for (int index = 0; index < versions.size(); index += 1) {
            if (versions.get(index).getVersionId().equals(targetVersion.getVersionId())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            throw new IllegalStateException("Version not found in document history: " + targetVersion.getVersionId());
        }

        int startIndex = 0;
        for (int index = targetIndex; index >= 0; index -= 1) {
            if (!STORAGE_PATCH.equals(versions.get(index).getStorageType())) {
                startIndex = index;
                break;
            }
        }

        String content = "";
        for (int index = startIndex; index <= targetIndex; index += 1) {
            DocumentVersion version = versions.get(index);
            if (STORAGE_PATCH.equals(version.getStorageType())) {
                content = applyPatches(content, readPatches(version));
            } else {
                content = versionStorage.readVersionText(version.getVersionPath());
            }
        }
        return content;
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

    /**
     * Line-level diff between two full texts via a longest-common-subsequence
     * walk over lines. Produces one change per contiguous block of removed
     * and/or added lines, so multi-region edits are shown separately instead
     * of collapsed into a single coarse block.
     */
    private List<Map<String, Object>> buildDiffChanges(String previousContent, String currentContent) {
        String previous = previousContent == null ? "" : previousContent;
        String current = currentContent == null ? "" : currentContent;
        if (previous.equals(current)) {
            return new ArrayList<>();
        }

        List<String> before = splitLines(previous);
        List<String> after = splitLines(current);
        int n = before.size();
        int m = after.size();

        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i -= 1) {
            for (int j = m - 1; j >= 0; j -= 1) {
                lcs[i][j] = before.get(i).equals(after.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && before.get(i).equals(after.get(j))) {
                i += 1;
                j += 1;
                continue;
            }

            int removedStartLine = i + 1;
            int addedStartLine = j + 1;
            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            while (i < n || j < m) {
                if (i < n && j < m && before.get(i).equals(after.get(j))) {
                    break;
                }
                boolean takeFromBefore;
                if (i >= n) {
                    takeFromBefore = false;
                } else if (j >= m) {
                    takeFromBefore = true;
                } else {
                    takeFromBefore = lcs[i + 1][j] >= lcs[i][j + 1];
                }
                if (takeFromBefore) {
                    removed.add(before.get(i));
                    i += 1;
                } else {
                    added.add(after.get(j));
                    j += 1;
                }
            }

            String removedText = String.join("\n", removed);
            String addedText = String.join("\n", added);
            Map<String, Object> change = new HashMap<>();
            change.put("type", diffType(removedText, addedText));
            change.put("removedText", removedText);
            change.put("addedText", addedText);
            change.put("removedLine", removedStartLine);
            change.put("addedLine", addedStartLine);
            changes.add(change);
        }
        return changes;
    }

    private List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(content.split("\n", -1)));
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
        try {
            List<VersionPatch> patches = PATCH_MAPPER.readValue(
                    versionStorage.readVersionText(version.getVersionPath()), PATCH_LIST_TYPE);
            return patches == null ? new ArrayList<>() : new ArrayList<>(patches);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse patch file: " + version.getVersionPath(), e);
        }
    }

    private String serializePatches(List<VersionPatch> patches) {
        try {
            return PATCH_MAPPER.writeValueAsString(patches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize patches", e);
        }
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
