package com.sharedoc.server;

import io.javalin.http.sse.SseClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks SSE subscribers per document and broadcasts document events.
 * Each subscriber is tagged with its username so the broker can publish the
 * set of users currently viewing a document (presence) whenever someone
 * connects or disconnects.
 */
public class DocumentEventBroker {
    private static final class Subscriber {
        private final SseClient client;
        private final String username;

        private Subscriber(SseClient client, String username) {
            this.client = client;
            this.username = username;
        }
    }

    private final Map<String, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public void addSubscriber(String documentId, String username, SseClient client) {
        subscribers.computeIfAbsent(documentId, key -> new CopyOnWriteArrayList<>())
                .add(new Subscriber(client, username));
        client.onClose(() -> removeSubscriber(documentId, client));
        broadcastPresence(documentId);
    }

    public void removeSubscriber(String documentId, SseClient client) {
        CopyOnWriteArrayList<Subscriber> clients = subscribers.get(documentId);
        if (clients == null) {
            return;
        }
        boolean removed = clients.removeIf(subscriber -> subscriber.client == client);
        if (clients.isEmpty()) {
            subscribers.remove(documentId);
        }
        if (removed) {
            broadcastPresence(documentId);
        }
    }

    public void broadcast(String documentId, String eventName, Object payload) {
        CopyOnWriteArrayList<Subscriber> clients = subscribers.get(documentId);
        if (clients == null) {
            return;
        }

        List<SseClient> staleClients = new ArrayList<>();
        for (Subscriber subscriber : clients) {
            try {
                subscriber.client.sendEvent(eventName, payload);
            } catch (Exception ex) {
                staleClients.add(subscriber.client);
            }
        }
        for (SseClient staleClient : staleClients) {
            removeSubscriber(documentId, staleClient);
        }
    }

    /** Distinct usernames currently subscribed to a document. */
    public List<String> onlineUsers(String documentId) {
        CopyOnWriteArrayList<Subscriber> clients = subscribers.get(documentId);
        List<String> users = new ArrayList<>();
        if (clients == null) {
            return users;
        }
        for (Subscriber subscriber : clients) {
            if (subscriber.username != null && !users.contains(subscriber.username)) {
                users.add(subscriber.username);
            }
        }
        return users;
    }

    private void broadcastPresence(String documentId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("onlineUsers", onlineUsers(documentId));
        broadcast(documentId, "presence-changed", payload);
    }
}
