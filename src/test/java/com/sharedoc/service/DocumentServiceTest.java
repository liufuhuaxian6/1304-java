package com.sharedoc.service;

import com.sharedoc.model.ContentUpdateResult;
import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.RangeLock;
import com.sharedoc.model.Response;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentServiceTest {
    private LockService lockService;
    private VersionService versionService;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
        lockService = new LockService();
        versionService = new VersionService();
        documentService = new DocumentService(new FileStorage(), lockService, versionService);
    }

    @AfterEach
    void tearDown() {
        TestStateHelper.resetState();
    }

    @Test
    void uploadAndListDocumentsReturnDocumentEntities() {
        Response uploadResponse = documentService.uploadDocument("admin", "README-test.md", "# hello".getBytes());

        assertTrue(uploadResponse.isSuccess());

        Response listResponse = documentService.listDocuments();
        assertTrue(listResponse.isSuccess());

        List<?> documents = assertInstanceOf(List.class, listResponse.getData());
        assertEquals(1, documents.size());

        Document document = assertInstanceOf(Document.class, documents.get(0));
        assertEquals("D-1", document.getDocumentId());
        assertEquals("README-test.md", document.getFileName());
        assertEquals("admin", document.getOwner());
        assertNotNull(document.getCurrentPath());
        assertTrue(document.getCurrentPath().contains("data"));
        assertEquals(1L, document.getRevision());
        assertNull(document.getEditingUser());
    }

    @Test
    void requestEditForMissingDocumentFails() {
        Response response = documentService.requestEdit("D-404", "admin", 1L, 0, 0);

        assertFalse(response.isSuccess());
        assertNotNull(response.getMessage());
    }

    @Test
    void releaseEditRejectsNonOwner() {
        Document document = uploadDocument("admin", "locked.md", "v1");
        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 1);
        assertTrue(lockResponse.isSuccess());

        Response releaseResponse = documentService.releaseEdit(document.getDocumentId(), "user");

        assertFalse(releaseResponse.isSuccess());
        assertEquals("您不是该文档的编辑用户，无法释放编辑权限", releaseResponse.getMessage());
    }

    @Test
    void downloadAndViewDocumentReturnUploadedTextContent() {
        String content = "line1\nline2\nline3";
        Document document = uploadDocument("admin", "preview.md", content);

        Response downloadResponse = documentService.downloadDocument(document.getDocumentId());
        Response viewResponse = documentService.viewDocument(document.getDocumentId());

        Map<String, Object> downloadData = responseData(downloadResponse);
        assertArrayEquals(content.getBytes(), assertInstanceOf(byte[].class, downloadData.get("fileContent")));
        assertEquals(document.getDocumentId(), assertInstanceOf(Document.class, downloadData.get("document")).getDocumentId());

        Map<String, Object> viewData = responseData(viewResponse);
        assertEquals(content, viewData.get("preview"));
        assertEquals(Boolean.TRUE, viewData.get("isTextFile"));
    }

    @Test
    void partialSaveUpdatesStoredContentAndReleasesCurrentLock() {
        Document document = uploadDocument("admin", "partial-save.md", "hello world");
        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 6, 11);
        assertTrue(lockResponse.isSuccess());

        String lockId = lockId(lockResponse);
        Response saveResponse = documentService.saveRange("admin", document.getDocumentId(), lockId, 1L, "sharedoc", "区间编辑保存");
        assertTrue(saveResponse.isSuccess());

        Response downloadResponse = documentService.downloadDocument(document.getDocumentId());
        Map<String, Object> downloadData = responseData(downloadResponse);
        assertArrayEquals("hello sharedoc".getBytes(), assertInstanceOf(byte[].class, downloadData.get("fileContent")));

        Map<String, Object> saveData = responseData(saveResponse);
        ContentUpdateResult update = assertInstanceOf(ContentUpdateResult.class, saveData.get("contentUpdate"));
        assertEquals(1L, update.getRevisionBefore());
        assertEquals(2L, update.getRevisionAfter());
        assertEquals(6, update.getStart());
        assertEquals(11, update.getEnd());
        assertEquals("sharedoc", update.getReplacementText());
        assertTrue(documentService.getActiveLocks(document.getDocumentId()).isEmpty());
    }

    @Test
    void rapidEditsAreMergedIntoOnePatchVersion() throws Exception {
        Document document = uploadDocument("admin", "patch-merge.md", "abcdef");
        Response firstLock = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 1);
        assertTrue(firstLock.isSuccess());
        Response firstSave = documentService.saveRange("admin", document.getDocumentId(), lockId(firstLock), 1L, "A", "same comment");
        assertTrue(firstSave.isSuccess());

        Response secondLock = documentService.requestEdit(document.getDocumentId(), "admin", 2L, 5, 6);
        assertTrue(secondLock.isSuccess());
        Response secondSave = documentService.saveRange("admin", document.getDocumentId(), lockId(secondLock), 2L, "F", "same comment");
        assertTrue(secondSave.isSuccess());

        Response versionsResponse = versionService.listVersions(document.getDocumentId());
        List<?> versions = assertInstanceOf(List.class, versionsResponse.getData());
        assertEquals(2, versions.size());

        DocumentVersion editVersion = assertInstanceOf(DocumentVersion.class, versions.get(1));
        assertEquals("PATCH", editVersion.getStorageType());
        assertEquals(2, editVersion.getPatchCount());
        assertEquals("same comment", editVersion.getComment());

        String storedPatch = Files.readString(Path.of(editVersion.getVersionPath()), StandardCharsets.UTF_8);
        assertTrue(storedPatch.contains("\"replacementText\":\"A\""));
        assertTrue(storedPatch.contains("\"replacementText\":\"F\""));
        assertFalse(storedPatch.equals("AbcdeF"));

        Response downloadVersionResponse = versionService.downloadVersion(document.getDocumentId(), editVersion.getVersionId());
        Map<String, Object> downloadData = responseData(downloadVersionResponse);
        assertEquals("AbcdeF", new String(assertInstanceOf(byte[].class, downloadData.get("fileContent")), StandardCharsets.UTF_8));

        Response diffResponse = versionService.diffWithPreviousVersion(document.getDocumentId(), editVersion.getVersionId());
        Map<String, Object> diffData = responseData(diffResponse);
        List<?> changes = assertInstanceOf(List.class, diffData.get("changes"));
        assertEquals(2, changes.size());
        Map<?, ?> firstChange = assertInstanceOf(Map.class, changes.get(0));
        Map<?, ?> secondChange = assertInstanceOf(Map.class, changes.get(1));
        assertEquals("REPLACE", firstChange.get("type"));
        assertEquals("a", firstChange.get("removedText"));
        assertEquals("A", firstChange.get("addedText"));
        assertEquals("REPLACE", secondChange.get("type"));
        assertEquals("f", secondChange.get("removedText"));
        assertEquals("F", secondChange.get("addedText"));
    }

    @Test
    void versionNumbersAreScopedPerDocument() {
        Document firstDocument = uploadDocument("admin", "first.md", "first");
        Document secondDocument = uploadDocument("admin", "second.md", "second");

        List<?> firstVersions = assertInstanceOf(List.class, versionService.listVersions(firstDocument.getDocumentId()).getData());
        List<?> secondVersions = assertInstanceOf(List.class, versionService.listVersions(secondDocument.getDocumentId()).getData());

        DocumentVersion firstInitialVersion = assertInstanceOf(DocumentVersion.class, firstVersions.get(0));
        DocumentVersion secondInitialVersion = assertInstanceOf(DocumentVersion.class, secondVersions.get(0));
        assertEquals("V-1", firstInitialVersion.getVersionId());
        assertEquals("V-1", secondInitialVersion.getVersionId());

        Response downloadSecondVersion = versionService.downloadVersion(secondDocument.getDocumentId(), "V-1");
        Map<String, Object> downloadData = responseData(downloadSecondVersion);
        assertEquals("second", new String(assertInstanceOf(byte[].class, downloadData.get("fileContent")), StandardCharsets.UTF_8));
    }

    @Test
    void nonOverlappingLocksCanCoexistAndShiftAfterSave() {
        Document document = uploadDocument("admin", "ranges.md", "0123456789abcdefghij");

        Response firstLock = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 2);
        Response secondLock = documentService.requestEdit(document.getDocumentId(), "user", 1L, 10, 12);
        assertTrue(firstLock.isSuccess());
        assertTrue(secondLock.isSuccess());

        String firstLockId = lockId(firstLock);
        Response saveResponse = documentService.saveRange("admin", document.getDocumentId(), firstLockId, 1L, "ABCD", "expand prefix");
        assertTrue(saveResponse.isSuccess());

        List<RangeLock> activeLocks = documentService.getActiveLocks(document.getDocumentId());
        assertEquals(1, activeLocks.size());
        RangeLock shifted = activeLocks.get(0);
        assertEquals("user", shifted.getOwner());
        assertEquals(12, shifted.getCurrentStart());
        assertEquals(14, shifted.getCurrentEnd());
    }

    @Test
    void overlappingLocksAreQueued() {
        Document document = uploadDocument("admin", "overlap.md", "0123456789");
        assertTrue(documentService.requestEdit(document.getDocumentId(), "admin", 1L, 2, 5).isSuccess());

        Response secondLockResponse = documentService.requestEdit(document.getDocumentId(), "user", 1L, 4, 7);

        assertTrue(secondLockResponse.isSuccess());
        RangeLock queuedLock = lock(secondLockResponse);
        assertTrue(queuedLock.isQueued());
        assertEquals(1, queuedLock.getQueuePosition());
    }

    @Test
    void queuedOverlappingLockIsPromotedAndShiftedAfterSave() {
        Document document = uploadDocument("admin", "queue-offset.md", "0123456789");

        Response firstLock = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 10);
        Response queuedLock = documentService.requestEdit(document.getDocumentId(), "user", 1L, 0, 10);
        assertTrue(firstLock.isSuccess());
        assertTrue(queuedLock.isSuccess());
        assertTrue(lock(queuedLock).isQueued());

        Response saveResponse = documentService.saveRange("admin", document.getDocumentId(), lockId(firstLock), 1L, "01ABCDE56789", "expand middle");
        assertTrue(saveResponse.isSuccess());

        List<RangeLock> activeLocks = documentService.getActiveLocks(document.getDocumentId());
        assertEquals(1, activeLocks.size());
        RangeLock promoted = activeLocks.get(0);
        assertEquals("user", promoted.getOwner());
        assertFalse(promoted.isQueued());
        assertEquals(0, promoted.getCurrentStart());
        assertEquals(12, promoted.getCurrentEnd());
    }

    @Test
    void adjacentRangesAndBoundaryCursorLocksCanCoexist() {
        Document document = uploadDocument("admin", "adjacent.md", "0123456789");

        Response firstLock = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 2);
        Response adjacentLock = documentService.requestEdit(document.getDocumentId(), "user", 1L, 2, 4);
        Response boundaryCursorLock = documentService.requestEdit(document.getDocumentId(), "guest", 1L, 4, 4);

        assertTrue(firstLock.isSuccess());
        assertTrue(adjacentLock.isSuccess());
        assertTrue(boundaryCursorLock.isSuccess());
        assertEquals(3, documentService.getActiveLocks(document.getDocumentId()).size());
    }

    @Test
    void staleRevisionSaveFails() {
        Document document = uploadDocument("admin", "stale.md", "abcdef");
        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 2, 4);
        assertTrue(lockResponse.isSuccess());

        String lockId = lockId(lockResponse);
        Response saveResponse = documentService.saveRange("admin", document.getDocumentId(), lockId, 2L, "XX", "stale");

        assertFalse(saveResponse.isSuccess());
        assertEquals("文档版本已更新，请刷新后重试", saveResponse.getMessage());
    }

    @Test
    void saveStillSucceedsForShiftedNonOverlappingLockAfterAnotherUserUpdatedDocument() {
        Document document = uploadDocument("admin", "stale-after-lock.md", "0123456789");

        Response firstLock = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 2);
        Response secondLock = documentService.requestEdit(document.getDocumentId(), "user", 1L, 8, 10);
        assertTrue(firstLock.isSuccess());
        assertTrue(secondLock.isSuccess());

        Response firstSave = documentService.saveRange("admin", document.getDocumentId(), lockId(firstLock), 1L, "AB", "first");
        assertTrue(firstSave.isSuccess());

        Response shiftedSave = documentService.saveRange("user", document.getDocumentId(), lockId(secondLock), 1L, "YZ", "second");
        assertTrue(shiftedSave.isSuccess());

        Response downloadResponse = documentService.downloadDocument(document.getDocumentId());
        Map<String, Object> downloadData = responseData(downloadResponse);
        assertEquals("AB234567YZ", new String(assertInstanceOf(byte[].class, downloadData.get("fileContent")), StandardCharsets.UTF_8));
    }

    @Test
    void concurrentNonOverlappingSavesAreSerializedPerDocument() throws InterruptedException {
        CoordinatedFileStorage storage = new CoordinatedFileStorage();
        DocumentService concurrentService = new DocumentService(storage, new LockService(), new VersionService());
        Document document = uploadDocument(concurrentService, "admin", "concurrent.md", "0123456789");

        Response leftLock = concurrentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 2);
        Response rightLock = concurrentService.requestEdit(document.getDocumentId(), "user", 1L, 8, 10);
        assertTrue(leftLock.isSuccess());
        assertTrue(rightLock.isSuccess());
        storage.armConcurrentReadWindow();

        AtomicReference<Response> leftSave = new AtomicReference<>();
        AtomicReference<Response> rightSave = new AtomicReference<>();

        Thread leftThread = new Thread(() -> leftSave.set(
                concurrentService.saveRange("admin", document.getDocumentId(), lockId(leftLock), 1L, "AB", "left patch")
        ));
        Thread rightThread = new Thread(() -> rightSave.set(
                concurrentService.saveRange("user", document.getDocumentId(), lockId(rightLock), 1L, "YZ", "right patch")
        ));

        leftThread.start();
        rightThread.start();
        leftThread.join();
        rightThread.join();

        assertTrue(leftSave.get().isSuccess());
        assertTrue(rightSave.get().isSuccess());
        assertEquals(1, storage.getMaxConcurrentReads());

        Response downloadResponse = concurrentService.downloadDocument(document.getDocumentId());
        Map<String, Object> downloadData = responseData(downloadResponse);
        assertEquals("AB234567YZ", new String(assertInstanceOf(byte[].class, downloadData.get("fileContent")), StandardCharsets.UTF_8));
    }

    @Test
    void listDocumentsReflectsCurrentEditingUserAndRelease() {
        Document document = uploadDocument("admin", "editing-state.md", "v1");

        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 1);
        assertTrue(lockResponse.isSuccess());

        Document listedWhileLocked = firstListedDocument();
        assertEquals("admin", listedWhileLocked.getEditingUser());
        assertNotNull(listedWhileLocked.getEditingStartTime());
        assertEquals(1, listedWhileLocked.getActiveLockCount());

        Response releaseResponse = documentService.releaseEdit(document.getDocumentId(), "admin");
        assertTrue(releaseResponse.isSuccess());

        Document listedAfterRelease = firstListedDocument();
        assertNull(listedAfterRelease.getEditingUser());
        assertNull(listedAfterRelease.getEditingStartTime());
        assertEquals(0, listedAfterRelease.getActiveLockCount());
    }

    @Test
    void rollbackFailsWhileAnyRangeLockExists() {
        Document document = uploadDocument("admin", "rollback.md", "v1");
        assertTrue(documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 1).isSuccess());

        Response rollbackResponse = documentService.rollbackDocumentToVersion("admin", document.getDocumentId(), "V-1", false);

        assertFalse(rollbackResponse.isSuccess());
        assertNotNull(rollbackResponse.getMessage());
    }

    @Test
    void rollbackByNonOwnerWithoutAdminRoleIsForbidden() {
        Document document = uploadDocument("admin", "owned.md", "v1");

        Response forbidden = documentService.rollbackDocumentToVersion("user", document.getDocumentId(), "V-1", false);
        assertFalse(forbidden.isSuccess());
        assertEquals("FORBIDDEN", forbidden.getCode());

        Response asAdmin = documentService.rollbackDocumentToVersion("user", document.getDocumentId(), "V-1", true);
        assertTrue(asAdmin.isSuccess());
    }

    @Test
    void deleteDocumentRemovesMetadataVersionsAndFile() {
        Document document = uploadDocument("admin", "to-delete.md", "bye");
        String docId = document.getDocumentId();
        Path storedFile = Path.of(document.getCurrentPath());
        assertTrue(Files.exists(storedFile));

        Response delete = documentService.deleteDocument("admin", docId, false);
        assertTrue(delete.isSuccess());

        assertFalse(documentService.documentExists(docId));
        assertFalse(Files.exists(storedFile), "Stored document file should be deleted");
        List<?> versions = assertInstanceOf(List.class, versionService.listVersions(docId).getData());
        assertTrue(versions.isEmpty(), "Version records should be removed");

        Response listResponse = documentService.listDocuments();
        assertTrue(assertInstanceOf(List.class, listResponse.getData()).isEmpty());
    }

    @Test
    void deleteDocumentByNonOwnerIsForbiddenButAdminCan() {
        Document document = uploadDocument("user", "user-doc.md", "v1");
        String docId = document.getDocumentId();

        Response forbidden = documentService.deleteDocument("admin-but-not", docId, false);
        assertFalse(forbidden.isSuccess());
        assertEquals("FORBIDDEN", forbidden.getCode());
        assertTrue(documentService.documentExists(docId));

        Response asAdmin = documentService.deleteDocument("someone", docId, true);
        assertTrue(asAdmin.isSuccess());
        assertFalse(documentService.documentExists(docId));
    }

    @Test
    void deleteDocumentIsRejectedWhileLockHeld() {
        Document document = uploadDocument("admin", "locked-delete.md", "0123456789");
        assertTrue(documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 2).isSuccess());

        Response delete = documentService.deleteDocument("admin", document.getDocumentId(), false);
        assertFalse(delete.isSuccess());
        assertEquals("ACTIVE_LOCKS_PRESENT", delete.getCode());
        assertTrue(documentService.documentExists(document.getDocumentId()));
    }

    @Test
    void renameDocumentChangesDisplayNameOnly() {
        Document document = uploadDocument("admin", "old-name.md", "content");
        String originalPath = document.getCurrentPath();

        Response rename = documentService.renameDocument("admin", document.getDocumentId(), "new-name.md", false);
        assertTrue(rename.isSuccess());
        Map<String, Object> data = responseData(rename);
        Document renamed = assertInstanceOf(Document.class, data.get("document"));
        assertEquals("new-name.md", renamed.getFileName());
        // Physical file is untouched; only the display name changed.
        assertEquals(originalPath, renamed.getCurrentPath());

        Response forbidden = documentService.renameDocument("user", document.getDocumentId(), "hijack.md", false);
        assertFalse(forbidden.isSuccess());
        assertEquals("FORBIDDEN", forbidden.getCode());

        Response badType = documentService.renameDocument("admin", document.getDocumentId(), "evil.exe", false);
        assertFalse(badType.isSuccess());
        assertEquals("UNSUPPORTED_FILE_TYPE", badType.getCode());
    }

    @Test
    void uploadSanitizesPathTraversalFileName() {
        Response uploadResponse = documentService.uploadDocument("admin", "..\\..\\evil.md", "# attack".getBytes());

        assertTrue(uploadResponse.isSuccess());
        Map<String, Object> data = responseData(uploadResponse);
        Document document = assertInstanceOf(Document.class, data.get("document"));
        assertEquals("evil.md", document.getFileName());

        Path storedPath = Path.of(document.getCurrentPath()).normalize().toAbsolutePath();
        Path storageRoot = Path.of("data", "documents").normalize().toAbsolutePath();
        assertTrue(storedPath.startsWith(storageRoot),
                "Stored file must stay inside the document storage directory: " + storedPath);
    }

    @Test
    void uploadRejectsOversizedFile() {
        byte[] oversized = new byte[com.sharedoc.server.ServerConfig.MAX_UPLOAD_BYTES + 1];
        oversized[0] = '#';

        Response uploadResponse = documentService.uploadDocument("admin", "big.md", oversized);

        assertFalse(uploadResponse.isSuccess());
        assertEquals("FILE_TOO_LARGE", uploadResponse.getCode());
    }

    private Document uploadDocument(String username, String fileName, String content) {
        Response uploadResponse = documentService.uploadDocument(username, fileName, content.getBytes());
        Map<String, Object> data = responseData(uploadResponse);
        return assertInstanceOf(Document.class, data.get("document"));
    }

    private Document uploadDocument(DocumentService service, String username, String fileName, String content) {
        Response uploadResponse = service.uploadDocument(username, fileName, content.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> data = responseData(uploadResponse);
        return assertInstanceOf(Document.class, data.get("document"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseData(Response response) {
        return assertInstanceOf(Map.class, response.getData());
    }

    @SuppressWarnings("unchecked")
    private String lockId(Response response) {
        return lock(response).getLockId();
    }

    @SuppressWarnings("unchecked")
    private RangeLock lock(Response response) {
        Map<String, Object> data = responseData(response);
        return assertInstanceOf(RangeLock.class, data.get("lock"));
    }

    private Document firstListedDocument() {
        Response listResponse = documentService.listDocuments();
        List<?> documents = assertInstanceOf(List.class, listResponse.getData());
        return assertInstanceOf(Document.class, documents.get(0));
    }

    private static final class CoordinatedFileStorage extends FileStorage {
        private final AtomicInteger activeReads = new AtomicInteger();
        private final AtomicInteger maxConcurrentReads = new AtomicInteger();
        private volatile boolean concurrentReadWindowArmed;

        @Override
        public byte[] readFile(String filePath) {
            byte[] content = super.readFile(filePath);
            if (concurrentReadWindowArmed && filePath.contains("concurrent.md")) {
                int currentReads = activeReads.incrementAndGet();
                maxConcurrentReads.accumulateAndGet(currentReads, Math::max);
                try {
                    await(150);
                } finally {
                    activeReads.decrementAndGet();
                }
            }
            return content;
        }

        private void armConcurrentReadWindow() {
            concurrentReadWindowArmed = true;
        }

        private int getMaxConcurrentReads() {
            return maxConcurrentReads.get();
        }

        private void await(long millis) {
            try {
                TimeUnit.MILLISECONDS.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待并发测试信号失败", e);
            }
        }
    }
}
