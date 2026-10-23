package com.glaciersecurity.glaciermessenger.utils;

import java.util.regex.Pattern;

public class PasswordValidator {

    public static final int MIN_LENGTH = 12;

    // Kept in sync with the requirements string shown to the user.
    public static final String SPECIAL_CHARS = "$^*.[]{}()?\"!@#%&/,><':;|_~`";

    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    public static boolean isValid(final String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return false;
        }
        return UPPERCASE.matcher(password).find()
                && LOWERCASE.matcher(password).find()
                && DIGIT.matcher(password).find()
                && containsSpecialChar(password);
    }

    private static boolean containsSpecialChar(final String password) {
        for (int i = 0; i < password.length(); i++) {
            if (SPECIAL_CHARS.indexOf(password.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private PasswordValidator() {}
}
