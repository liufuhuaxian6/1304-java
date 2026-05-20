package com.sharedoc.model;

/**
 * Types of client requests supported by the server skeleton.
 */
public enum RequestType {
    LOGIN,
    LIST_DOCUMENTS,
    UPLOAD_DOCUMENT,
    DOWNLOAD_DOCUMENT,
    VIEW_DOCUMENT,
    REQUEST_EDIT,
    SAVE_DOCUMENT,
    RELEASE_EDIT,
    LIST_VERSIONS,
    DOWNLOAD_VERSION,
    ROLLBACK_VERSION,
    LOGOUT
}
