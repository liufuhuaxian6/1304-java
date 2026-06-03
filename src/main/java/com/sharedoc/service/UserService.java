package com.sharedoc.service;

import com.sharedoc.model.Response;
import com.sharedoc.model.User;
import com.sharedoc.util.IdGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User service skeleton.
 * Manages login, logout, and optional registration with in-memory user data.
 */
public class UserService {
    private static final Map<String, User> USERS = new ConcurrentHashMap<>();
    private static final Map<String, User> ONLINE_USERS = new ConcurrentHashMap<>();
    private final DocumentService documentService;

    static {
        USERS.put("admin", new User("U-ADMIN", "admin", "123456", "ADMIN"));
        USERS.put("user", new User("U-DEMO", "user", "123456", "USER"));
    }

    public UserService() {
        this(new DocumentService());
    }

    public UserService(DocumentService documentService) {
        this.documentService = documentService;
    }

    public Response login(String username, String password) {
        if (username == null || username.isBlank()) {
            return Response.fail("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            return Response.fail("密码不能为空");
        }

        User user = USERS.get(username);
        if (user == null) {
            return Response.fail("用户不存在");
        }
        if (!password.equals(user.getPassword())) {
            return Response.fail("密码错误");
        }

        ONLINE_USERS.put(username, user);
        return Response.ok("登录成功");
    }

    public Response logout(String username) {
        if (username == null || username.isBlank()) {
            return Response.fail("未指定登出用户");
        }
        ONLINE_USERS.remove(username);
        int released = documentService.releaseLocksHeldBy(username);
        String message = released > 0
                ? "登出成功，已释放 " + released + " 个文档的编辑锁"
                : "登出成功";
        return Response.ok(message);
    }

    public Response register(String username, String password, String role) {
        if (username == null || username.isBlank()) {
            return Response.fail("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            return Response.fail("密码不能为空");
        }
        if (USERS.containsKey(username)) {
            return Response.fail("用户名已存在");
        }

        String userRole = (role == null || role.isBlank()) ? "USER" : role;
        User user = new User(IdGenerator.nextUserId(), username, password, userRole);
        USERS.put(username, user);
        return Response.ok("注册成功");
    }
}
