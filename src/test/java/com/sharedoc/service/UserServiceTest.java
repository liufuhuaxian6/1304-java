package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.Request;
import com.sharedoc.model.RequestType;
import com.sharedoc.model.Response;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {
    private DocumentService documentService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        TestStateHelper.resetState();
        documentService = new DocumentService();
        userService = new UserService(documentService);
    }

    @AfterEach
    void tearDown() {
        TestStateHelper.resetState();
    }

    @Test
    void loginRequiresCorrectPassword() {
        Response success = userService.login("admin", "123456");
        Response wrongPassword = userService.login("admin", "wrong-password");

        assertTrue(success.isSuccess());
        assertEquals("登录成功", success.getMessage());
        assertTrue(!wrongPassword.isSuccess());
        assertEquals("密码错误", wrongPassword.getMessage());
    }

    @Test
    void logoutReleasesHeldEditLocks() {
        Document document = uploadDocument("admin", "logout-release.md", "v1");
        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin");
        assertTrue(lockResponse.isSuccess());

        Response logoutResponse = userService.logout("admin");
        Response otherUserLockResponse = documentService.requestEdit(document.getDocumentId(), "user");

        assertTrue(logoutResponse.isSuccess());
        assertEquals("登出成功", logoutResponse.getMessage());
        assertTrue(otherUserLockResponse.isSuccess());
    }

    private Document uploadDocument(String username, String fileName, String content) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileContent", content.getBytes());
        Response uploadResponse = documentService.uploadDocument(
                new Request(RequestType.UPLOAD_DOCUMENT, username, null, payload));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = assertInstanceOf(Map.class, uploadResponse.getData());
        return assertInstanceOf(Document.class, data.get("document"));
    }
}
