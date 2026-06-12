package com.sharedoc.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serial;
import java.io.Serializable;

/**
 * User entity.
 * Stores basic account information used by the server during login and access checks.
 * The password field holds a PBKDF2 hash and is never serialized to clients.
 */
public class User implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private String username;
    private String password;
    private String role;

    public User() {
        // TODO: Keep default constructor for serialization and future data binding.
    }

    public User(String userId, String username, String password, String role) {
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

    @JsonIgnore
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

    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", role='" + role + '\'' +
                '}';
    }
}
