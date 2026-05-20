package com.sharedoc.client;

import com.sharedoc.model.Request;
import com.sharedoc.model.RequestType;
import com.sharedoc.model.Response;
import com.sharedoc.server.ServerConfig;

import java.io.IOException;
import java.util.Scanner;

/**
 * Console client workflow skeleton.
 * Displays basic menus and reserves methods for login, document, edit, and version operations.
 */
public class ClientApp {
    private final Scanner scanner = new Scanner(System.in);
    private final ClientConnection connection = new ClientConnection();
    private String currentUsername;

    public void start() {
        try {
            connection.connect("localhost", ServerConfig.PORT);
            showMainMenu();
        } catch (IOException e) {
            System.err.println("Unable to connect to server: " + e.getMessage());
        } finally {
            connection.close();
        }
    }

    private void showMainMenu() {
        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine();
            switch (choice) {
                case "1" -> login();
                case "2" -> listDocuments();
                case "3" -> uploadDocument();
                case "4" -> downloadDocument();
                case "5" -> viewDocument();
                case "6" -> requestEdit();
                case "7" -> saveDocument();
                case "8" -> releaseEdit();
                case "9" -> listVersions();
                case "10" -> downloadVersion();
                case "11" -> rollbackVersion();
                case "0" -> running = false;
                default -> System.out.println("Unknown menu choice.");
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("==== Shared Document Client ====");
        System.out.println("1. Login");
        System.out.println("2. List documents");
        System.out.println("3. Upload document");
        System.out.println("4. Download document");
        System.out.println("5. View document");
        System.out.println("6. Request edit permission");
        System.out.println("7. Save document");
        System.out.println("8. Release edit permission");
        System.out.println("9. List versions");
        System.out.println("10. Download version");
        System.out.println("11. Rollback version");
        System.out.println("0. Exit");
        System.out.print("Choose: ");
    }

    private void login() {
        // TODO: Ask for username/password and send LOGIN request.
        System.out.print("Username: ");
        currentUsername = scanner.nextLine();
        sendAndPrint(new Request(RequestType.LOGIN, currentUsername, null, "123456"));
    }

    private void listDocuments() {
        // TODO: Render document metadata returned by server.
        sendAndPrint(new Request(RequestType.LIST_DOCUMENTS, currentUsername, null, null));
    }

    private void uploadDocument() {
        // TODO: Ask for local path, read file bytes, and send UPLOAD_DOCUMENT request.
        sendAndPrint(new Request(RequestType.UPLOAD_DOCUMENT, currentUsername, null, null));
    }

    private void downloadDocument() {
        // TODO: Ask for document ID and save returned bytes locally.
        String documentId = readDocumentId();
        sendAndPrint(new Request(RequestType.DOWNLOAD_DOCUMENT, currentUsername, documentId, null));
    }

    private void viewDocument() {
        // TODO: Ask for document ID and display read-only content preview.
        String documentId = readDocumentId();
        sendAndPrint(new Request(RequestType.VIEW_DOCUMENT, currentUsername, documentId, null));
    }

    private void requestEdit() {
        // TODO: Ask for document ID and request exclusive edit lock.
        String documentId = readDocumentId();
        sendAndPrint(new Request(RequestType.REQUEST_EDIT, currentUsername, documentId, null));
    }

    private void saveDocument() {
        // TODO: Collect edited content or file path and send SAVE_DOCUMENT request.
        String documentId = readDocumentId();
        sendAndPrint(new Request(RequestType.SAVE_DOCUMENT, currentUsername, documentId, null));
    }

    private void releaseEdit() {
        // TODO: Release edit lock for the selected document.
        String documentId = readDocumentId();
        sendAndPrint(new Request(RequestType.RELEASE_EDIT, currentUsername, documentId, null));
    }

    private void listVersions() {
        // TODO: Render historical versions for the selected document.
        String documentId = readDocumentId();
        sendAndPrint(new Request(RequestType.LIST_VERSIONS, currentUsername, documentId, null));
    }

    private void downloadVersion() {
        // TODO: Ask for version ID and save version bytes locally.
        System.out.print("Version ID: ");
        String versionId = scanner.nextLine();
        sendAndPrint(new Request(RequestType.DOWNLOAD_VERSION, currentUsername, null, versionId));
    }

    private void rollbackVersion() {
        // TODO: Ask for document ID and version ID, then send ROLLBACK_VERSION request.
        String documentId = readDocumentId();
        System.out.print("Version ID: ");
        String versionId = scanner.nextLine();
        sendAndPrint(new Request(RequestType.ROLLBACK_VERSION, currentUsername, documentId, versionId));
    }

    private String readDocumentId() {
        System.out.print("Document ID: ");
        return scanner.nextLine();
    }

    private void sendAndPrint(Request request) {
        try {
            connection.sendRequest(request);
            Response response = connection.receiveResponse();
            System.out.println(response.getMessage());
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Request failed: " + e.getMessage());
        }
    }
}
