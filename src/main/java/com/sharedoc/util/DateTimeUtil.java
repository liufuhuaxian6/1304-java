package com.sharedoc.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Date and time utility.
 * Provides common formatting for document and version timestamps.
 */
public final class DateTimeUtil {
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimeUtil() {
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? "" : dateTime.format(DEFAULT_FORMATTER);
    }
}
