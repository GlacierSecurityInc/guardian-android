package com.glaciersecurity.glaciermessenger.crypto;

import java.util.Locale;

/**
 * Recognizes message bodies that are nothing but a known "could not decrypt"
 * placeholder inserted by an OMEMO client (including Glacier's iOS client)
 * rather than actual user content, so they can be suppressed from the
 * conversation view instead of shown as a ghost message.
 */
public final class FallbackShell {

    private static final String[] KNOWN_OMEMO_FALLBACK_BODIES = {
            "message cannot be decrypted",
            "unable to decrypt message",
    };

    private FallbackShell() {
    }

    public static boolean isKnownOmemoFallbackBody(final String body) {
        if (body == null) {
            return false;
        }
        String normalized = body.trim();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return false;
        }
        final String lowerCase = normalized.toLowerCase(Locale.US);
        for (final String known : KNOWN_OMEMO_FALLBACK_BODIES) {
            if (lowerCase.equals(known)) {
                return true;
            }
        }
        return false;
    }
}
