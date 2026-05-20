package com.sharedoc.service;

import com.sharedoc.model.Document;
import com.sharedoc.model.Request;
import com.sharedoc.model.Response;
import com.sharedoc.storage.FileStorage;
import com.sharedoc.util.IdGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document service skeleton.
 * Owns document metadata operations and delegates edit permission checks to LockService.
 */
public class DocumentService {
    private static final Map<String, Document> DOCUMENTS = new ConcurrentHashMap<>();
    private static final LockService LOCK_SERVICE = new LockService();

    private final FileStorage fileStorage = new FileStorage();

    public Response listDocuments() {
        // TODO: Add pagination, permission filtering, and metadata formatting.
        List<Document> documents = new ArrayList<>(DOCUMENTS.values());
        return new Response(true, "List documents placeholder.", documents);
    }

    public Response uploadDocument(Request request) {
        // TODO: Save uploaded file content and create initial version.
        String documentId = IdGenerator.nextDocumentId();
        Document document = new Document(documentId, "placeholder.txt", request.getUsername(), "data/documents/placeholder.txt");
        DOCUMENTS.put(documentId, document);
        return new Response(true, "Upload document placeholder.", document);
    }

    public Response downloadDocument(String documentId) {
        // TODO: Read current document bytes from FileStorage and return to client.
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("Download placeholder: document not found.");
        }
        return new Response(true, "Download document placeholder.", document);
    }

    public Response viewDocument(String documentId) {
        // TODO: Read document text preview without acquiring edit lock.
        Document document = DOCUMENTS.get(documentId);
        if (document == null) {
            return Response.fail("View placeholder: document not found.");
        }
        return new Response(true, "View document placeholder.", document);
    }

    public Response requestEdit(String documentId, String username) {
        // TODO: Update Document.editingUser and editingStartTime after lock acquisition.
        boolean locked = LOCK_SERVICE.tryLockDocument(documentId, username);
        if (!locked) {
            return Response.fail("Request edit placeholder: document is locked.");
        }
        Document document = DOCUMENTS.get(documentId);
        if (document != null) {
            document.setEditingUser(username);
            document.setEditingStartTime(LocalDateTime.now());
        }
        return Response.ok("Request edit placeholder: lock acquired.");
    }

    public Response saveDocument(Request request) {
        // TODO: Verify edit lock owner, save content, update metadata, and create edit version.
        Document document = DOCUMENTS.get(request.getDocumentId());
        if (document == null) {
            return Response.fail("Save placeholder: document not found.");
        }
        document.setLastModifiedTime(LocalDateTime.now());
        return Response.ok("Save document placeholder.");
    }

    public Response releaseEdit(String documentId, String username) {
        // TODO: Clear document editing metadata and notify waiting clients if needed.
        LOCK_SERVICE.unlockDocument(documentId, username);
        Document document = DOCUMENTS.get(documentId);
        if (document != null && username != null && username.equals(document.getEditingUser())) {
            document.setEditingUser(null);
            document.setEditingStartTime(null);
        }
        return Response.ok("Release edit placeholder.");
    }

    public boolean isEditing(String documentId) {
        // TODO: Add document existence validation.
        return LOCK_SERVICE.isLocked(documentId);
    }

    public String getEditingUser(String documentId) {
        // TODO: Return null or a user display name according to later UI requirements.
        return LOCK_SERVICE.getLockOwner(documentId);
    }
}
