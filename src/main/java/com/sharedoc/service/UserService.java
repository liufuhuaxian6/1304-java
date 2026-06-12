package com.sharedoc.service;

import com.sharedoc.model.ErrorCodes;
import com.sharedoc.model.Response;
import com.sharedoc.model.User;
import com.sharedoc.util.IdGenerator;
import com.sharedoc.util.PasswordHasher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User service.
 * Manages login, logout, and registration with in-memory user data.
 * Passwords are stored as PBKDF2 hashes; user objects returned to callers
 * never carry the password hash.
 */
public class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();
    private final DocumentService documentService;

    public UserService() {
        this(new DocumentService());
    }

    public UserService(DocumentService documentService) {
        this.documentService = documentService;
        users.put("admin", new User("U-ADMIN", "admin", PasswordHasher.hash("123456"), "ADMIN"));
        users.put("user", new User("U-DEMO", "user", PasswordHasher.hash("123456"), "USER"));
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
        if (username == null || username.isBlank()) {
            return Response.fail("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            return Response.fail("密码不能为空");
        }
        if (users.containsKey(username)) {
            return Response.fail(ErrorCodes.USERNAME_TAKEN, "用户名已存在");
        }

        User user = new User(IdGenerator.nextUserId(), username, PasswordHasher.hash(password), "USER");
        users.put(username, user);
        return Response.ok("注册成功", sanitized(user));
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
