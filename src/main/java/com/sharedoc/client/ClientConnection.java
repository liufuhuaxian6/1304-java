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
        // TODO: Add retry strategy and connection status callbacks.
        socket = new Socket(host, port);
        output = new ObjectOutputStream(socket.getOutputStream());
        input = new ObjectInputStream(socket.getInputStream());
    }

    public void sendRequest(Request request) throws IOException {
        // TODO: Validate request and add client-side request logging.
        output.writeObject(request);
        output.flush();
    }

    public Response receiveResponse() throws IOException, ClassNotFoundException {
        // TODO: Add timeout handling and protocol error conversion.
        Object object = input.readObject();
        if (object instanceof Response response) {
            return response;
        }
        return Response.fail("Invalid response from server.");
    }

    @Override
    public void close() {
        // TODO: Send logout request before closing when user session support is completed.
        try {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Failed to close client connection: " + e.getMessage());
        }
    }
}
