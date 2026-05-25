package com.sharedoc.client;

import com.sharedoc.model.Request;
import com.sharedoc.model.Response;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Client socket connection wrapper.
 * Provides request sending, response receiving, and connection closing methods.
 */
public class ClientConnection implements AutoCloseable {
    private Socket socket;
    private ObjectOutputStream output;
    private ObjectInputStream input;

    public void connect(String host, int port) throws IOException {
        if (isConnected()) {
            throw new IOException("Client is already connected to the server.");
        }
        socket = new Socket(host, port);
        output = new ObjectOutputStream(socket.getOutputStream());
        input = new ObjectInputStream(socket.getInputStream());
    }

    public void sendRequest(Request request) throws IOException {
        ensureConnected();
        if (request == null) {
            throw new IOException("Request must not be null.");
        }
        output.writeObject(request);
        output.flush();
    }

    public Response receiveResponse() throws IOException, ClassNotFoundException {
        ensureConnected();
        Object object = input.readObject();
        if (object instanceof Response response) {
            return response;
        }
        return Response.fail("Invalid response from server.");
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed()
                && output != null && input != null;
    }

    @Override
    public void close() {
        IOException closeError = null;

        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException e) {
            closeError = e;
        }

        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException e) {
            if (closeError == null) {
                closeError = e;
            }
        }

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            if (closeError == null) {
                closeError = e;
            }
        } finally {
            input = null;
            output = null;
            socket = null;
        }

        if (closeError != null) {
            System.err.println("Failed to close client connection: " + closeError.getMessage());
        }
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("Client is not connected to the server.");
        }
    }
}
