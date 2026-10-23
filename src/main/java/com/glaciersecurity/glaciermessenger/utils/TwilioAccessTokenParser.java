package com.glaciersecurity.glaciermessenger.utils;

import androidx.annotation.Nullable;

import org.json.JSONObject;

public final class TwilioAccessTokenParser {

    private TwilioAccessTokenParser() {
    }

    @Nullable
    public static String parse(@Nullable String rawResponse) {
        if (rawResponse == null) {
            return null;
        }
        final String trimmed = rawResponse.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // A JSON envelope has to be unwrapped before the JWT shape is considered: an envelope
        // wrapping a three-segment JWT itself splits into three dot-separated segments, so
        // testing for a bare JWT first would hand the whole envelope back as the token.
        if (trimmed.startsWith("{")) {
            try {
                final JSONObject body = new JSONObject(trimmed);
                final String[] keys = {"token", "accessToken", "AccessToken"};
                for (String key : keys) {
                    final String value = body.optString(key, null);
                    if (value != null) {
                        final String parsed = parse(value);
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Return null below when the token cannot be extracted.
            }
            return null;
        }

        if (looksLikeJwt(trimmed)) {
            return trimmed;
        }
        return null;
    }

    private static boolean looksLikeJwt(String value) {
        final int segments = value.split("\\.").length;
        return segments == 3 || segments == 5;
    }
}
