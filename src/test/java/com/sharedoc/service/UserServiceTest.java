package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.Response;
import com.sharedoc.model.User;
import com.sharedoc.testutil.TestStateHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        Response unknownUser = userService.login("no-such-user", "123456");

        assertTrue(success.isSuccess());
        assertEquals("登录成功", success.getMessage());
        assertFalse(wrongPassword.isSuccess());
        assertFalse(unknownUser.isSuccess());
        // Unknown user and wrong password must be indistinguishable.
        assertEquals(wrongPassword.getMessage(), unknownUser.getMessage());
    }

    @Test
    void logoutReleasesHeldRangeLocks() {
        Document document = uploadDocument("admin", "logout-release.md", "v1");
        Response lockResponse = documentService.requestEdit(document.getDocumentId(), "admin", 1L, 0, 1);
        assertTrue(lockResponse.isSuccess());

        Response logoutResponse = userService.logout("admin");
        Response otherUserLockResponse = documentService.requestEdit(document.getDocumentId(), "user", 1L, 0, 1);

        assertTrue(logoutResponse.isSuccess());
        assertEquals("登出成功，已释放 1 个文档的编辑锁", logoutResponse.getMessage());
        assertTrue(otherUserLockResponse.isSuccess());
    }

    @Test
    void registerCreatesLoginableUserAndRejectsDuplicate() {
        Response registered = userService.register("tester-reg", "pass123");
        assertTrue(registered.isSuccess());
        assertEquals("注册成功", registered.getMessage());

        Response login = userService.login("tester-reg", "pass123");
        assertTrue(login.isSuccess());

        Response duplicate = userService.register("tester-reg", "otherpass");
        assertFalse(duplicate.isSuccess());
        assertEquals("用户名已存在", duplicate.getMessage());

        Response blankPassword = userService.register("tester-reg-2", "  ");
        assertFalse(blankPassword.isSuccess());
        assertEquals("密码不能为空", blankPassword.getMessage());
    }

    @Test
    void registeredUsersAlwaysGetUserRoleAndNoPasswordExposure() {
        Response registered = userService.register("role-check", "pass123");
        assertTrue(registered.isSuccess());

        User user = assertInstanceOf(User.class, registered.getData());
        assertEquals("USER", user.getRole());
        assertNull(user.getPassword());

        User found = userService.findByUsername("role-check");
        assertEquals("USER", found.getRole());
        assertNull(found.getPassword());
    }

    @Test
    void registerRejectsInvalidUsernameAndShortPassword() {
        assertFalse(userService.register("a", "pass123").isSuccess());          // too short
        assertFalse(userService.register("bad name!", "pass123").isSuccess());  // illegal chars
        assertFalse(userService.register("validname", "123").isSuccess());      // short password
        assertTrue(userService.register("valid_name-1", "pass123").isSuccess());
    }

    @Test
    void changePasswordRequiresCorrectCurrentAndUpdatesHash() {
        assertTrue(userService.register("pwduser", "oldpass").isSuccess());

        assertFalse(userService.changePassword("pwduser", "wrong", "newpass1").isSuccess());
        assertFalse(userService.changePassword("pwduser", "oldpass", "123").isSuccess());

        assertTrue(userService.changePassword("pwduser", "oldpass", "newpass1").isSuccess());
        assertFalse(userService.login("pwduser", "oldpass").isSuccess());
        assertTrue(userService.login("pwduser", "newpass1").isSuccess());
    }

    private Document uploadDocument(String username, String fileName, String content) {
        Response uploadResponse = documentService.uploadDocument(username, fileName, content.getBytes());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = assertInstanceOf(Map.class, uploadResponse.getData());
        return assertInstanceOf(Document.class, data.get("document"));
    }
}
