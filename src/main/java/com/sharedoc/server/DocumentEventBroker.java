package com.sharedoc.server;

import io.javalin.http.sse.SseClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks SSE subscribers per document and broadcasts document events.
 */
public class DocumentEventBroker {
    private final Map<String, CopyOnWriteArrayList<SseClient>> subscribers = new ConcurrentHashMap<>();

    public void addSubscriber(String documentId, SseClient client) {
        subscribers.computeIfAbsent(documentId, key -> new CopyOnWriteArrayList<>()).add(client);
        client.onClose(() -> removeSubscriber(documentId, client));
    }

    public void removeSubscriber(String documentId, SseClient client) {
        CopyOnWriteArrayList<SseClient> clients = subscribers.get(documentId);
        if (clients == null) {
            return;
        }
        clients.remove(client);
        if (clients.isEmpty()) {
            subscribers.remove(documentId);
        }
    }

    public void broadcast(String documentId, String eventName, Object payload) {
        CopyOnWriteArrayList<SseClient> clients = subscribers.get(documentId);
        if (clients == null) {
            return;
        }

        List<SseClient> staleClients = new ArrayList<>();
        for (SseClient client : clients) {
            try {
                client.sendEvent(eventName, payload);
            } catch (Exception ex) {
                staleClients.add(client);
            }
        }
        for (SseClient staleClient : staleClients) {
            removeSubscriber(documentId, staleClient);
        }
    }
}
