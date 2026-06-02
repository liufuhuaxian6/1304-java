package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.Request;
import com.sharedoc.model.Response;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentServiceTest {
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
        documentService = new DocumentService();
    }

    @AfterEach
    void tearDown() {
        TestStateHelper.resetState();
    }

    @Test
    void uploadAndListDocumentsReturnDocumentEntities() {
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "README-test.md", "# hello"));

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
        assertNull(document.getEditingUser());
    }

    @Test
    void saveDocumentWithoutLockFailsGracefully() {
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "save-without-lock.md", "v1"));
        Document document = uploadedDocument(uploadResponse);

        Response saveResponse = documentService.saveDocument(
                new Request(com.sharedoc.model.RequestType.SAVE_DOCUMENT, "admin", document.getDocumentId(), "v2".getBytes()));

        assertTrue(!saveResponse.isSuccess());
        assertEquals("您没有该文档的编辑权限", saveResponse.getMessage());
    }

    @Test
    void requestEditForMissingDocumentFails() {
        Response response = documentService.requestEdit("D-404", "admin");

        assertTrue(!response.isSuccess());
        assertEquals("文档不存在", response.getMessage());
    }

    @Test
    void releaseEditRejectsNonOwner() {
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "locked.md", "v1"));
        Document document = uploadedDocument(uploadResponse);
        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin");

        assertTrue(lockResponse.isSuccess());

        Response releaseResponse = documentService.releaseEdit(document.getDocumentId(), "user");

        assertTrue(!releaseResponse.isSuccess());
        assertEquals("当前用户未持有该文档的编辑权限", releaseResponse.getMessage());
    }

    @Test
    void downloadAndViewDocumentReturnUploadedTextContent() {
        String content = "line1\nline2\nline3";
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "preview.md", content));
        Document document = uploadedDocument(uploadResponse);

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
    void saveDocumentWithLockUpdatesStoredContent() {
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "save-with-lock.md", "v1"));
        Document uploaded = uploadedDocument(uploadResponse);

        Response lockResponse = documentService.requestEdit(uploaded.getDocumentId(), "admin");
        assertTrue(lockResponse.isSuccess());

        Response saveResponse = documentService.saveDocument(
                new Request(com.sharedoc.model.RequestType.SAVE_DOCUMENT, "admin", uploaded.getDocumentId(), "v2".getBytes()));
        assertTrue(saveResponse.isSuccess());

        Response downloadResponse = documentService.downloadDocument(uploaded.getDocumentId());
        Map<String, Object> downloadData = responseData(downloadResponse);
        assertArrayEquals("v2".getBytes(), assertInstanceOf(byte[].class, downloadData.get("fileContent")));
    }

    @Test
    void listDocumentsReflectsCurrentEditingUserAndRelease() {
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "editing-state.md", "v1"));
        Document document = uploadedDocument(uploadResponse);

        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin");
        assertTrue(lockResponse.isSuccess());

        Document listedWhileLocked = firstListedDocument();
        assertEquals("admin", listedWhileLocked.getEditingUser());
        assertNotNull(listedWhileLocked.getEditingStartTime());

        Response releaseResponse = documentService.releaseEdit(document.getDocumentId(), "admin");
        assertTrue(releaseResponse.isSuccess());

        Document listedAfterRelease = firstListedDocument();
        assertNull(listedAfterRelease.getEditingUser());
        assertNull(listedAfterRelease.getEditingStartTime());
    }

    @Test
    void secondUserCannotAcquireLockWhileDocumentIsEditing() {
        Response uploadResponse = documentService.uploadDocument(uploadRequest("admin", "contention.md", "v1"));
        Document document = uploadedDocument(uploadResponse);

        assertTrue(documentService.requestEdit(document.getDocumentId(), "admin").isSuccess());

        Response secondLockResponse = documentService.requestEdit(document.getDocumentId(), "user");

        assertFalse(secondLockResponse.isSuccess());
        assertEquals("文档正在被其他用户编辑", secondLockResponse.getMessage());
    }

    private Request uploadRequest(String username, String fileName, String content) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileContent", content.getBytes());
        return new Request(com.sharedoc.model.RequestType.UPLOAD_DOCUMENT, username, null, payload);
    }

    @SuppressWarnings("unchecked")
    private Document uploadedDocument(Response uploadResponse) {
        Map<String, Object> data = responseData(uploadResponse);
        return assertInstanceOf(Document.class, data.get("document"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseData(Response response) {
        return assertInstanceOf(Map.class, response.getData());
    }

    private Document firstListedDocument() {
        Response listResponse = documentService.listDocuments();
        List<?> documents = assertInstanceOf(List.class, listResponse.getData());
        return assertInstanceOf(Document.class, documents.get(0));
    }
}
