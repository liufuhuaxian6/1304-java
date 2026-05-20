package com.sharedoc.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * Client request message.
 * Sent from console client to server through the reserved object serialization protocol.
 */
public class Request implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private RequestType type;
    private String username;
    private String documentId;
    private Object payload;

    public Request() {
        // TODO: Keep default constructor for serialization and future protocol expansion.
    }

    public Request(RequestType type, String username, String documentId, Object payload) {
        this.type = type;
        this.username = username;
        this.documentId = documentId;
        this.payload = payload;
    }

    public RequestType getType() {
        return type;
    }

    public void setType(RequestType type) {
        this.type = type;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }
}
