package com.sharedoc.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * File name sanitization shared by document upload and version storage.
 * Strips any directory components and replaces characters that are unsafe
 * in file names, preventing path traversal via client-supplied names.
 */
public final class FileNames {
    private FileNames() {
    }

    public static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }

        String normalized = removeUnsafeChars(fileName).replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        String sanitized = baseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (sanitized.startsWith(".") && sanitized.length() > 1 && sanitized.chars().allMatch(c -> c == '.')) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            return "document";
        }

        try {
            return Path.of(sanitized).getFileName().toString();
        } catch (InvalidPathException e) {
            return "document";
        }
    }

    /**
     * Drops control characters and unpaired surrogates. Clients with broken
     * multipart encodings can produce such characters, which would later
     * break JSON serialization or file system paths.
     */
    private static String removeUnsafeChars(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index += 1) {
            char ch = value.charAt(index);
            if (Character.isISOControl(ch)) {
                continue;
            }
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    builder.append(ch).append(value.charAt(index + 1));
                    index += 1;
                }
                continue;
            }
            if (Character.isLowSurrogate(ch)) {
                continue;
            }
            builder.append(ch);
        }
        return builder.toString();
    }
}
