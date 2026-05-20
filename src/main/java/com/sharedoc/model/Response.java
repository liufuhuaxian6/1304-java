package com.sharedoc.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Server response message.
 * Returns operation status, display message, and optional data to the client.
 */
public class Response implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;
    private Object data;

    public Response() {
        // TODO: Keep default constructor for serialization and future protocol expansion.
    }

    public Response(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static Response ok(String message) {
        // TODO: Attach operation data when concrete business logic is implemented.
        return new Response(true, message, null);
    }

    public static Response fail(String message) {
        // TODO: Attach error codes when protocol design is expanded.
        return new Response(false, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
