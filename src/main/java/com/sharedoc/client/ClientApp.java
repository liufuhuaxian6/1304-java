package com.sharedoc.client;

import com.sharedoc.model.Document;
import com.sharedoc.model.DocumentVersion;
import com.sharedoc.model.Request;
import com.sharedoc.model.RequestType;
import com.sharedoc.model.Response;
import com.sharedoc.server.ServerConfig;
import com.sharedoc.util.DateTimeUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Console client workflow.
 * Displays basic menus and implements login, document, edit, and version operations.
 */
public class ClientApp {
    private static final String BACK_COMMAND = "/q";
    private static final String RETRY_COMMAND = "r";

    private final Scanner scanner = new Scanner(System.in);
    private final ClientConnection connection = new ClientConnection();
    private String currentUsername;

    public void start() {
        try {
            if (!connectToServer(true)) {
                return;
            }
            showMainMenu();
        } finally {
            logoutOnExit();
            connection.close();
        }
    }

    private void showMainMenu() {
        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1" -> login();
                case "2" -> logout();
                case "3" -> listDocuments();
                case "4" -> uploadDocument();
                case "5" -> downloadDocument();
                case "6" -> viewDocument();
                case "7" -> requestEdit();
                case "8" -> saveDocument();
                case "9" -> releaseEdit();
                case "10" -> listVersions();
                case "11" -> downloadVersion();
                case "12" -> rollbackVersion();
                case "0" -> running = false;
                default -> System.out.println("无效菜单编号，请重新输入。");
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("==== 共享文档客户端 ====");
        System.out.println("当前用户：" + (isLoggedIn() ? currentUsername : "未登录"));
        System.out.println("1. 登录");
        System.out.println("2. 登出");
        System.out.println("3. 查看文档列表");
        System.out.println("4. 上传文档");
        System.out.println("5. 下载文档");
        System.out.println("6. 只读查看文档");
        System.out.println("7. 申请编辑权限");
        System.out.println("8. 保存文档");
        System.out.println("9. 释放编辑权限");
        System.out.println("10. 查看历史版本");
        System.out.println("11. 下载历史版本");
        System.out.println("12. 回滚版本");
        System.out.println("0. 退出");
        System.out.print("请输入菜单编号：");
    }

    private void login() {
        if (isLoggedIn()) {
            System.out.println("当前已登录用户：" + currentUsername + "。如需切换账号，请先登出。");
            return;
        }

        while (true) {
            String username = promptRequired("用户名：");
            if (username == null) {
                return;
            }

            String password = promptRequired("密码：");
            if (password == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.LOGIN, username, null, password), true);
            if (response == null) {
                if (!promptRetry("登录")) {
                    return;
                }
                continue;
            }

            printResponse(response);
            if (response.isSuccess()) {
                currentUsername = username;
                return;
            }

            if (!promptRetry("登录")) {
                return;
            }
        }
    }

    private void listDocuments() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            Response response = sendRequest(new Request(RequestType.LIST_DOCUMENTS, currentUsername, null, null), true);
            if (response == null) {
                if (!promptRetry("查看文档列表")) {
                    return;
                }
                continue;
            }

            printResponse(response);
            if (response.isSuccess() || !promptRetry("查看文档列表")) {
                return;
            }
        }
    }

    private void uploadDocument() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String filePath = promptRequired("本地文件路径：");
            if (filePath == null) {
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                System.out.println("[失败] 文件不存在：" + filePath);
                if (!promptRetry("上传文档")) {
                    return;
                }
                continue;
            }

            try {
                byte[] fileContent = Files.readAllBytes(path);
                String fileName = path.getFileName().toString();

                Map<String, Object> payload = new HashMap<>();
                payload.put("fileName", fileName);
                payload.put("fileContent", fileContent);

                Response response = sendRequest(new Request(RequestType.UPLOAD_DOCUMENT, currentUsername, null, payload), true);
                if (response == null) {
                    if (!promptRetry("上传文档")) {
                        return;
                    }
                    continue;
                }

                printResponse(response);
                if (response.isSuccess() || !promptRetry("上传文档")) {
                    return;
                }
            } catch (IOException e) {
                System.out.println("[失败] 读取文件失败：" + e.getMessage());
                if (!promptRetry("上传文档")) {
                    return;
                }
            }
        }
    }

    private void downloadDocument() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.DOWNLOAD_DOCUMENT, currentUsername, documentId, null), true);
            if (response == null) {
                if (!promptRetry("下载文档")) {
                    return;
                }
                continue;
            }

            if (response.isSuccess() && response.getData() instanceof Map<?, ?>) {
                Map<?, ?> data = (Map<?, ?>) response.getData();
                Document document = (Document) data.get("document");
                byte[] fileContent = (byte[]) data.get("fileContent");

                while (true) {
                    String savePathInput = promptRequired("保存路径（目录或完整路径）：");
                    if (savePathInput == null) {
                        return;
                    }

                    try {
                        Path savePath = resolveDownloadSavePath(savePathInput, document.getFileName());
                        Files.write(savePath, fileContent);
                        System.out.println("[成功] 文件已保存到：" + savePath);
                        return;
                    } catch (IOException | IllegalArgumentException e) {
                        System.out.println("[失败] 保存文件失败：" + e.getMessage());
                        if (!promptRetry("保存下载文件")) {
                            return;
                        }
                    }
                }
            }

            printResponse(response);
            if (!promptRetry("下载文档")) {
                return;
            }
        }
    }

    private void viewDocument() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.VIEW_DOCUMENT, currentUsername, documentId, null), true);
            if (response == null) {
                if (!promptRetry("查看文档")) {
                    return;
                }
                continue;
            }

            if (response.isSuccess() && response.getData() instanceof Map<?, ?>) {
                Map<?, ?> data = (Map<?, ?>) response.getData();
                Document document = (Document) data.get("document");
                String preview = (String) data.get("preview");
                Boolean isTextFile = (Boolean) data.get("isTextFile");

                System.out.println("[成功] 文档查看成功");
                System.out.println("文件名：" + document.getFileName());
                System.out.println("所有者：" + document.getOwner());
                System.out.println("上传时间：" + formatTime(document.getUploadTime()));
                System.out.println("最后修改：" + formatTime(document.getLastModifiedTime()));
                System.out.println();

                if (Boolean.TRUE.equals(isTextFile)) {
                    System.out.println("==== 文本预览 ====");
                    System.out.println(preview);
                } else {
                    System.out.println("提示：" + preview);
                }
                return;
            }

            printResponse(response);
            if (!promptRetry("查看文档")) {
                return;
            }
        }
    }

    private void requestEdit() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.REQUEST_EDIT, currentUsername, documentId, null), true);
            if (response == null) {
                if (!promptRetry("申请编辑权限")) {
                    return;
                }
                continue;
            }

            printResponse(response);
            if (response.isSuccess() || !promptRetry("申请编辑权限")) {
                return;
            }
        }
    }

    private void saveDocument() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            String filePath = promptRequired("本地文件路径：");
            if (filePath == null) {
                return;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                System.out.println("[失败] 文件不存在：" + filePath);
                if (!promptRetry("保存文档")) {
                    return;
                }
                continue;
            }

            try {
                byte[] fileContent = Files.readAllBytes(path);
                Response response = sendRequest(new Request(RequestType.SAVE_DOCUMENT, currentUsername, documentId, fileContent), true);
                if (response == null) {
                    if (!promptRetry("保存文档")) {
                        return;
                    }
                    continue;
                }

                printResponse(response);
                if (response.isSuccess() || !promptRetry("保存文档")) {
                    return;
                }
            } catch (IOException e) {
                System.out.println("[失败] 读取文件失败：" + e.getMessage());
                if (!promptRetry("保存文档")) {
                    return;
                }
            }
        }
    }

    private void releaseEdit() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.RELEASE_EDIT, currentUsername, documentId, null), true);
            if (response == null) {
                if (!promptRetry("释放编辑权限")) {
                    return;
                }
                continue;
            }

            printResponse(response);
            if (response.isSuccess() || !promptRetry("释放编辑权限")) {
                return;
            }
        }
    }

    private void listVersions() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.LIST_VERSIONS, currentUsername, documentId, null), true);
            if (response == null) {
                if (!promptRetry("查看历史版本")) {
                    return;
                }
                continue;
            }

            printResponse(response);
            if (response.isSuccess() || !promptRetry("查看历史版本")) {
                return;
            }
        }
    }

    private void downloadVersion() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String versionId = promptRequired("版本 ID：");
            if (versionId == null) {
                return;
            }

            Response response = sendRequest(new Request(RequestType.DOWNLOAD_VERSION, currentUsername, null, versionId), true);
            if (response == null) {
                if (!promptRetry("下载历史版本")) {
                    return;
                }
                continue;
            }

            if (response.isSuccess() && response.getData() instanceof Map) {
                Map<?, ?> data = (Map<?, ?>) response.getData();
                DocumentVersion version = (DocumentVersion) data.get("version");
                byte[] fileContent = (byte[]) data.get("fileContent");

                String savePathInput = promptRequired("保存路径（目录或完整路径）：");
                if (savePathInput == null) {
                    System.out.println("[失败] 未输入保存路径");
                    return;
                }

                try {
                    Path inputPath = Paths.get(savePathInput);
                    Path savePath;

                    if (Files.isDirectory(inputPath) || !savePathInput.contains(".")) {
                        Files.createDirectories(inputPath);
                        savePath = inputPath.resolve(version.getVersionId() + "-" + version.getFileName());
                    } else {
                        Files.createDirectories(inputPath.getParent());
                        savePath = inputPath;
                    }

                    Files.write(savePath, fileContent);
                    System.out.println("[成功] 历史版本已保存到：" + savePath);
                } catch (IOException e) {
                    System.out.println("[失败] 保存文件失败：" + e.getMessage());
                }
                return;
            }

            printResponse(response);
            if (!promptRetry("下载历史版本")) {
                return;
            }
        }
    }

    private void rollbackVersion() {
        if (!requireLogin()) {
            return;
        }

        while (true) {
            String documentId = readDocumentId();
            if (documentId == null) {
                return;
            }

            String versionId = promptRequired("版本 ID：");
            if (versionId == null) {
                return;
            }

            System.out.println("提示：回滚前请确认已申请该文档的编辑权限。");
            Response response = sendRequest(new Request(RequestType.ROLLBACK_VERSION, currentUsername, documentId, versionId), true);
            if (response == null) {
                if (!promptRetry("回滚版本")) {
                    return;
                }
                continue;
            }

            if (response.isSuccess() && response.getData() instanceof Map) {
                Map<?, ?> data = (Map<?, ?>) response.getData();
                System.out.println("[成功] " + response.getMessage());
                if (data.get("document") instanceof Document document) {
                    System.out.println("=== 当前文档 ===");
                    printDocument(document);
                }
                if (data.get("rolledBackFrom") instanceof DocumentVersion source) {
                    System.out.println("=== 回滚来源版本 ===");
                    printVersion(source);
                }
                if (data.get("rollbackVersion") instanceof DocumentVersion rollback) {
                    System.out.println("=== 新生成的回滚版本 ===");
                    printVersion(rollback);
                }
                return;
            }

            printResponse(response);
            if (!promptRetry("回滚版本")) {
                return;
            }
        }
    }

    private String readDocumentId() {
        return promptRequired("文档 ID：");
    }

    private Response sendRequest(Request request, boolean allowReconnect) {
        if (!ensureServerConnection(allowReconnect)) {
            return null;
        }

        try {
            connection.sendRequest(request);
            return connection.receiveResponse();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("请求失败：" + e.getMessage());
            connection.close();
            return null;
        }
    }

    private void printResponse(Response response) {
        if (response == null) {
            return;
        }

        String prefix = response.isSuccess() ? "[成功] " : "[失败] ";
        System.out.println(prefix + response.getMessage());

        Object data = response.getData();
        if (data == null) {
            return;
        }

        if (data instanceof Document document) {
            printDocument(document);
            return;
        }

        if (data instanceof DocumentVersion version) {
            printVersion(version);
            return;
        }

        if (data instanceof List<?> list) {
            printListData(list);
            return;
        }

        System.out.println("返回数据：" + data);
    }

    private void printListData(List<?> list) {
        if (list.isEmpty()) {
            System.out.println("暂无数据。");
            return;
        }

        Object first = list.get(0);
        if (first instanceof Document) {
            System.out.println("文档列表：");
            for (Object item : list) {
                if (item instanceof Document document) {
                    printDocument(document);
                }
            }
            return;
        }

        if (first instanceof Map) {
            System.out.println("文档列表：");
            for (Object item : list) {
                if (item instanceof Map<?, ?> docInfo) {
                    printDocumentInfo(docInfo);
                }
            }
            return;
        }

        if (first instanceof DocumentVersion) {
            System.out.println("版本列表：");
            for (Object item : list) {
                if (item instanceof DocumentVersion version) {
                    printVersion(version);
                }
            }
            return;
        }

        for (Object item : list) {
            System.out.println("- " + item);
        }
    }

    private void printDocumentInfo(Map<?, ?> docInfo) {
        System.out.println("文档ID: " + mapValue(docInfo.get("documentId")));
        System.out.println("文件名: " + mapValue(docInfo.get("fileName")));
        System.out.println("所有者: " + mapValue(docInfo.get("owner")));
        System.out.println("上传时间: " + mapTime(docInfo.get("uploadTime")));
        System.out.println("最后修改: " + mapTime(docInfo.get("lastModifiedTime")));
        boolean editing = Boolean.TRUE.equals(docInfo.get("isEditing"));
        if (editing) {
            System.out.println("编辑状态: 编辑中（" + mapValue(docInfo.get("editingUser")) + "）");
        } else {
            System.out.println("编辑状态: 空闲");
        }
        System.out.println();
    }

    private String mapValue(Object value) {
        return value == null ? "未提供" : String.valueOf(value);
    }

    private String mapTime(Object value) {
        if (value instanceof java.time.LocalDateTime dateTime) {
            return formatTime(dateTime);
        }
        return value == null ? "无" : String.valueOf(value);
    }

    private void printDocument(Document document) {
        System.out.println("文档ID: " + safeValue(document.getDocumentId()));
        System.out.println("文件名: " + safeValue(document.getFileName()));
        System.out.println("所有者: " + safeValue(document.getOwner()));
        System.out.println("当前路径: " + safeValue(document.getCurrentPath()));
        System.out.println("上传时间: " + formatTime(document.getUploadTime()));
        System.out.println("最后修改: " + formatTime(document.getLastModifiedTime()));
        System.out.println("编辑用户: " + safeValue(document.getEditingUser(), "无"));
        System.out.println("开始编辑: " + safeValue(formatTime(document.getEditingStartTime()), "无"));
        System.out.println();
    }

    private void printVersion(DocumentVersion version) {
        System.out.println("版本ID: " + safeValue(version.getVersionId()));
        System.out.println("文档ID: " + safeValue(version.getDocumentId()));
        System.out.println("文件名: " + safeValue(version.getFileName()));
        System.out.println("编辑者: " + safeValue(version.getEditor()));
        System.out.println("操作时间: " + formatTime(version.getEditTime()));
        System.out.println("操作类型: " + (version.getOperationType() == null ? "未知" : version.getOperationType().name()));
        System.out.println("版本路径: " + safeValue(version.getVersionPath()));
        System.out.println("备注: " + safeValue(version.getComment(), "无"));
        System.out.println();
    }

    private boolean requireLogin() {
        if (isLoggedIn()) {
            return true;
        }
        System.out.println("请先登录后再执行该操作。");
        return false;
    }

    private boolean isLoggedIn() {
        return currentUsername != null && !currentUsername.isBlank();
    }

    private void logout() {
        if (!isLoggedIn()) {
            System.out.println("当前未登录，无需登出。");
            return;
        }

        while (true) {
            Response response = sendRequest(new Request(RequestType.LOGOUT, currentUsername, null, null), true);
            if (response == null) {
                if (!promptRetry("登出")) {
                    return;
                }
                continue;
            }

            printResponse(response);
            if (response.isSuccess()) {
                currentUsername = null;
            }
            return;
        }
    }

    private void logoutOnExit() {
        if (!isLoggedIn() || !connection.isConnected()) {
            return;
        }

        Response response = sendRequest(new Request(RequestType.LOGOUT, currentUsername, null, null), false);
        if (response != null) {
            System.out.println("[退出前登出] " + response.getMessage());
        }
        currentUsername = null;
    }

    private String promptRequired(String prompt) {
        while (true) {
            System.out.print(prompt + "（输入 " + BACK_COMMAND + " 返回菜单）：");
            String input = scanner.nextLine().trim();
            if (BACK_COMMAND.equalsIgnoreCase(input)) {
                return null;
            }
            if (input.isEmpty()) {
                System.out.println("输入不能为空，请重新输入。");
                continue;
            }
            return input;
        }
    }

    private boolean connectToServer(boolean allowRetry) {
        while (true) {
            if (connection.isConnected()) {
                return true;
            }

            try {
                connection.connect("localhost", ServerConfig.PORT);
                System.out.println("已连接到服务器 localhost:" + ServerConfig.PORT);
                return true;
            } catch (IOException e) {
                System.err.println("无法连接服务器：" + e.getMessage());
                connection.close();
                if (!allowRetry || !promptRetry("连接服务器")) {
                    return false;
                }
            }
        }
    }

    private boolean ensureServerConnection(boolean allowReconnect) {
        if (connection.isConnected()) {
            return true;
        }

        System.out.println("当前未连接服务器。");
        return allowReconnect && connectToServer(true);
    }

    private boolean promptRetry(String action) {
        System.out.print(action + "失败。输入 R 重试，其他任意键返回菜单：");
        String input = scanner.nextLine().trim();
        return RETRY_COMMAND.equalsIgnoreCase(input);
    }

    private Path resolveDownloadSavePath(String savePathInput, String fileName) throws IOException {
        Path inputPath = Paths.get(savePathInput);
        if (Files.isDirectory(inputPath) || !savePathInput.contains(".")) {
            Files.createDirectories(inputPath);
            return inputPath.resolve(fileName);
        }

        Path parent = inputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return inputPath;
    }

    private String formatTime(java.time.LocalDateTime dateTime) {
        return safeValue(DateTimeUtil.format(dateTime), "无");
    }

    private String safeValue(String value) {
        return safeValue(value, "未提供");
    }

    private String safeValue(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
