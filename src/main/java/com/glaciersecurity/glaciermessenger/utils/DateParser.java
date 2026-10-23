package com.glaciersecurity.glaciermessenger.utils;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

/**
 * Utility class to parse timestamps that may come in different formats.
 */
public final class DateParser {
    private static final DateTimeFormatter ALT_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss z yyyy", Locale.US);
    private static final DateTimeFormatter LEGACY_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a", Locale.US);

    private DateParser() {
        throw new AssertionError("No instances");
    }

    /**
     * Parse a timestamp string into a {@link Date}. Supported formats include
     * ISO-8601 as well as strings like "Tue Jul 22 15:36:21 EDT 2025".
     *
     * @param input the date string
     * @return the parsed {@link Date}
     * @throws ParseException if the input can not be parsed
     */
    public static Date parse(String input) throws ParseException {
        if (input == null) {
            throw new IllegalArgumentException("timestamp should not be null");
        }
        try {
            String normalized = normalizeOffset(input);
            Instant instant = Instant.parse(normalized);
            return Date.from(instant);
        } catch (DateTimeParseException ignore) {
            // fall back to alternate format
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(input, ALT_FORMAT);
            return Date.from(zdt.toInstant());
        } catch (DateTimeParseException ex) {
            // fall through to legacy format
        }
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(input, LEGACY_FORMAT);
            return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            throw new ParseException("Unparseable date: \"" + input + "\"", 0);
        }
    }

    /**
     * Parse a timestamp string into milliseconds since the epoch.
     */
    public static long parseMillis(String input) throws ParseException {
        return parse(input).getTime();
    }

    private static String normalizeOffset(String input) {
        if (input.length() > 5) {
            char sign = input.charAt(input.length() - 5);
            if (sign == '+' || sign == '-') {
                String offset = input.substring(input.length() - 5);
                return input.substring(0, input.length() - 5)
                        + offset.substring(0, 3) + ":" + offset.substring(3);
            }
        }
        return input;
    }
}