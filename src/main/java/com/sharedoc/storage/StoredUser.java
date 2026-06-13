package com.sharedoc.storage;

/**
 * Persistence representation of a user account.
 * Unlike the API {@code User} model, this type intentionally carries the
 * password hash so it can be written to and read from {@code users.json}.
 */
public class StoredUser {
    private String userId;
    private String username;
    private String password;
    private String role;

    public StoredUser() {
    }

    public StoredUser(String userId, String username, String password, String role) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
