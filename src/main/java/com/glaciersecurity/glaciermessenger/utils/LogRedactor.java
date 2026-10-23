package com.glaciersecurity.glaciermessenger.utils;

import androidx.annotation.Nullable;

/**
 * Helpers for keeping sensitive identifiers out of application logs while
 * preserving enough shape to correlate related events during debugging.
 */
public final class LogRedactor {

    private LogRedactor() {}

    public static String phone(@Nullable final CharSequence number) {
        if (number == null) {
            return "null";
        }
        final String value = number.toString();
        final String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return "[redacted-phone:length=" + value.length() + "]";
        }
        final String suffix = digits.length() <= 2 ? "**" : digits.substring(digits.length() - 2);
        return "[redacted-phone:length=" + digits.length() + ",last2=" + suffix + "]";
    }

    public static String id(@Nullable final CharSequence id) {
        if (id == null) {
            return "null";
        }
        final String value = id.toString();
        if (value.isEmpty()) {
            return "[redacted-id:length=0]";
        }
        final int suffixLength = Math.min(4, value.length());
        return "[redacted-id:length=" + value.length() + ",last" + suffixLength + "="
                + value.substring(value.length() - suffixLength) + "]";
    }
}
