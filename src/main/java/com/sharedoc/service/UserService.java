package com.sharedoc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sharedoc.model.ErrorCodes;
import com.sharedoc.model.Response;
import com.sharedoc.model.User;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.storage.JsonStore;
import com.sharedoc.storage.StoredUser;
import com.sharedoc.util.IdGenerator;
import com.sharedoc.util.PasswordHasher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User service.
 * Manages login, logout, and registration. Accounts are persisted to
 * {@code users.json} (password hashes included); online status stays in
 * memory only. User objects returned to callers never carry the hash.
 */
public class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();
    private final DocumentService documentService;
    private final JsonStore userStore;

    public UserService() {
        this(new DocumentService());
    }

    public UserService(DocumentService documentService) {
        this(documentService, new JsonStore(Path.of(ServerConfig.METADATA_STORAGE_PATH, "users.json")));
    }

    public UserService(DocumentService documentService, JsonStore userStore) {
        this.documentService = documentService;
        this.userStore = userStore;
        loadOrSeedUsers();
    }

    private void loadOrSeedUsers() {
        List<StoredUser> stored = userStore.read(new TypeReference<List<StoredUser>>() { });
        if (stored == null || stored.isEmpty()) {
            users.put("admin", new User("U-ADMIN", "admin", PasswordHasher.hash("123456"), "ADMIN"));
            users.put("user", new User("U-DEMO", "user", PasswordHasher.hash("123456"), "USER"));
            persist();
            return;
        }

        long maxId = 0;
        for (StoredUser record : stored) {
            users.put(record.getUsername(),
                    new User(record.getUserId(), record.getUsername(), record.getPassword(), record.getRole()));
            maxId = Math.max(maxId, IdGenerator.numericSuffix(record.getUserId()));
        }
        IdGenerator.ensureUserSequenceAtLeast(maxId + 1);
    }

    private void persist() {
        List<StoredUser> records = new ArrayList<>();
        for (User user : users.values()) {
            records.add(new StoredUser(user.getUserId(), user.getUsername(), user.getPassword(), user.getRole()));
        }
        userStore.write(records);
    }

    public Response login(String username, String password) {
        if (username == null || username.isBlank()) {
            return Response.fail(ErrorCodes.INVALID_CREDENTIALS, "用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            return Response.fail(ErrorCodes.INVALID_CREDENTIALS, "密码不能为空");
        }

        User user = users.get(username);
        if (user == null || !PasswordHasher.verify(password, user.getPassword())) {
            // Same message for unknown user and wrong password to avoid
            // leaking which usernames exist.
            return Response.fail(ErrorCodes.INVALID_CREDENTIALS, "用户名或密码错误");
        }

        onlineUsers.put(username, user);
        return Response.ok("登录成功", sanitized(user));
    }

    public Response logout(String username) {
        if (username == null || username.isBlank()) {
            return Response.fail("未指定登出用户");
        }
        onlineUsers.remove(username);
        int released = documentService.releaseLocksHeldBy(username);
        String message = released > 0
                ? "登出成功，已释放 " + released + " 个文档的编辑锁"
                : "登出成功";
        return Response.ok(message);
    }

    /**
     * Registers a new account. The role is always USER: privileged roles can
     * never be obtained through self-registration.
     */
    public Response register(String username, String password) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return Response.fail(usernameError);
        }
        String passwordError = validatePassword(password);
        if (passwordError != null) {
            return Response.fail(passwordError);
        }
        if (users.containsKey(username)) {
            return Response.fail(ErrorCodes.USERNAME_TAKEN, "用户名已存在");
        }

        User user = new User(IdGenerator.nextUserId(), username, PasswordHasher.hash(password), "USER");
        users.put(username, user);
        persist();
        return Response.ok("注册成功", sanitized(user));
    }

    /** Changes a user's password after verifying the current one. */
    public Response changePassword(String username, String currentPassword, String newPassword) {
        if (username == null || username.isBlank()) {
            return Response.fail(ErrorCodes.AUTH_REQUIRED, "请先登录");
        }
        User user = users.get(username);
        if (user == null || !PasswordHasher.verify(currentPassword, user.getPassword())) {
            return Response.fail(ErrorCodes.INVALID_CREDENTIALS, "当前密码不正确");
        }
        String passwordError = validatePassword(newPassword);
        if (passwordError != null) {
            return Response.fail(passwordError);
        }
        user.setPassword(PasswordHasher.hash(newPassword));
        persist();
        return Response.ok("密码修改成功");
    }

    private String validateUsername(String username) {
        if (username == null || username.isBlank()) {
            return "用户名不能为空";
        }
        // Letters, digits, underscore, hyphen and CJK characters, 2-32 long.
        if (!username.matches("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{2,32}$")) {
            return "用户名只能包含字母、数字、下划线、连字符或中文，长度 2-32";
        }
        return null;
    }

    private String validatePassword(String password) {
        if (password == null || password.isBlank()) {
            return "密码不能为空";
        }
        if (password.length() < 6 || password.length() > 64) {
            return "密码长度需为 6-64 位";
        }
        return null;
    }

    /** Returns the user without the password hash, or null when unknown. */
    public User findByUsername(String username) {
        if (username == null) {
            return null;
        }
        User user = users.get(username);
        return user == null ? null : sanitized(user);
    }

    private User sanitized(User user) {
        return new User(user.getUserId(), user.getUsername(), null, user.getRole());
    }
}
