package com.sharedoc.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Service layer response.
 * Carries the operation status, a machine-readable code (see {@link ErrorCodes}),
 * a user-facing message, and optional data.
 */
public class Response implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private boolean success;
    private String code;
    private String message;
    private Object data;

    public Response() {
    }

    public Response(boolean success, String message, Object data) {
        this(success, success ? ErrorCodes.OK : ErrorCodes.BAD_REQUEST, message, data);
    }

    public Response(boolean success, String code, String message, Object data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static Response ok(String message) {
        return new Response(true, message, null);
    }

    public static Response ok(String message, Object data) {
        return new Response(true, message, data);
    }

    public static Response fail(String message) {
        return new Response(false, message, null);
    }

    public static Response fail(String code, String message) {
        return new Response(false, code, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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
