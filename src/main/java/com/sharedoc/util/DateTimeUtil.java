package com.sharedoc.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Date and time utility.
 * Provides common formatting methods for document and version timestamps.
 */
public final class DateTimeUtil {
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimeUtil() {
        // TODO: Add more date helpers if the UI needs them.
    }

    public static String format(LocalDateTime dateTime) {
        // TODO: Handle null values according to final UI display rules.
        return dateTime == null ? "" : dateTime.format(DEFAULT_FORMATTER);
    }

    public static LocalDateTime now() {
        // TODO: Centralize clock access for easier testing later.
        return LocalDateTime.now();
    }
}
