package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.Response;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that business state survives a "restart", simulated by building a
 * fresh set of service instances that read the same metadata files from disk.
 */
class PersistenceTest {

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
    }

    @AfterEach
    void tearDown() {
        TestStateHelper.resetState();
    }

    @Test
    void documentsAndVersionsSurviveServiceRestart() {
        VersionService versions = new VersionService();
        DocumentService docs = new DocumentService(new FileStorage(), new LockService(), versions);

        Document document = upload(docs, "admin", "persist.md", "hello world");
        Response lock = docs.requestEdit(document.getDocumentId(), "admin", 1L, 6, 11);
        assertTrue(lock.isSuccess());
        String lockId = lockId(lock);
        assertTrue(docs.saveRange("admin", document.getDocumentId(), lockId, 1L, "sharedoc", "edit").isSuccess());

        // Rebuild every service from disk, mimicking a process restart.
        VersionService reloadedVersions = new VersionService();
        DocumentService reloadedDocs = new DocumentService(new FileStorage(), new LockService(), reloadedVersions);

        Response listResponse = reloadedDocs.listDocuments();
        List<?> listed = assertInstanceOf(List.class, listResponse.getData());
        assertEquals(1, listed.size());
        Document restored = assertInstanceOf(Document.class, listed.get(0));
        assertEquals("persist.md", restored.getFileName());
        assertEquals("admin", restored.getOwner());
        assertEquals(2L, restored.getRevision());

        Response content = reloadedDocs.getDocumentContent(document.getDocumentId());
        Map<String, Object> contentData = assertInstanceOf(Map.class, content.getData());
        assertEquals("hello sharedoc", contentData.get("contentText"));

        List<?> versionList = assertInstanceOf(List.class,
                reloadedVersions.listVersions(document.getDocumentId()).getData());
        assertEquals(2, versionList.size());
    }

    @Test
    void newDocumentIdsDoNotCollideWithRestoredOnes() {
        DocumentService docs = new DocumentService(new FileStorage(), new LockService(), new VersionService());
        Document first = upload(docs, "admin", "first.md", "a");
        assertEquals("D-1", first.getDocumentId());

        DocumentService reloaded = new DocumentService(new FileStorage(), new LockService(), new VersionService());
        Document second = upload(reloaded, "admin", "second.md", "b");
        assertEquals("D-2", second.getDocumentId());
    }

    @Test
    void registeredUsersSurviveServiceRestart() {
        UserService users = new UserService(new DocumentService());
        assertTrue(users.register("carol", "pass123").isSuccess());

        UserService reloaded = new UserService(new DocumentService());
        Response login = reloaded.login("carol", "pass123");
        assertTrue(login.isSuccess());

        // Default accounts must not be re-seeded over the persisted set.
        Response badLogin = reloaded.login("carol", "wrong");
        assertFalse(badLogin.isSuccess());
    }

    private Document upload(DocumentService service, String username, String fileName, String content) {
        Response response = service.uploadDocument(username, fileName, content.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> data = assertInstanceOf(Map.class, response.getData());
        return assertInstanceOf(Document.class, data.get("document"));
    }

    @SuppressWarnings("unchecked")
    private String lockId(Response response) {
        Map<String, Object> data = (Map<String, Object>) response.getData();
        return ((com.sharedoc.model.RangeLock) data.get("lock")).getLockId();
    }
}
