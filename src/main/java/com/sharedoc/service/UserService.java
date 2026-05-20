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

    static {
        USERS.put("admin", new User("U-ADMIN", "admin", "123456", "ADMIN"));
        USERS.put("user", new User("U-DEMO", "user", "123456", "USER"));
    }

    public Response login(String username, String password) {
        // TODO: Validate username/password, session state, and role permissions.
        User user = USERS.get(username);
        if (user == null) {
            return Response.fail("Login placeholder: user not found.");
        }
        ONLINE_USERS.put(username, user);
        return Response.ok("Login placeholder: success.");
    }

    public Response logout(String username) {
        // TODO: Release edit locks held by this user during logout.
        ONLINE_USERS.remove(username);
        return Response.ok("Logout placeholder: success.");
    }

    public Response register(String username, String password, String role) {
        // TODO: Add duplicate checks and persistence when user module is implemented.
        User user = new User(IdGenerator.nextUserId(), username, password, role);
        USERS.put(username, user);
        return Response.ok("Register placeholder: success.");
    }
}
