package com.sharedoc.client;

/**
 * Client application entry point.
 * Starts the console client and delegates menu handling to ClientApp.
 */
public class ClientMain {
    public static void main(String[] args) {
        ClientApp app = new ClientApp();
        app.start();
    }
}
