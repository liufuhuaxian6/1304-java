package com.sharedoc.model;

/**
 * Machine-readable business error codes shared between the service layer
 * and the HTTP layer. The HTTP layer maps these codes to status codes,
 * so user-facing messages can change freely without breaking clients.
 */
public final class ErrorCodes {
    public static final String OK = "OK";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NO_EDIT_PERMISSION = "NO_EDIT_PERMISSION";
    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";
    public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
    public static final String REVISION_STALE = "REVISION_STALE";
    public static final String USER_ALREADY_HAS_LOCK = "USER_ALREADY_HAS_LOCK";
    public static final String LOCK_FAILED = "LOCK_FAILED";
    public static final String INVALID_LOCK_RANGE = "INVALID_LOCK_RANGE";
    public static final String ACTIVE_LOCKS_PRESENT = "ACTIVE_LOCKS_PRESENT";
    public static final String UNSUPPORTED_FILE_TYPE = "UNSUPPORTED_FILE_TYPE";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
