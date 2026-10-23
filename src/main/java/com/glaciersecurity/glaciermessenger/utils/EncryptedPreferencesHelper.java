package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.glaciersecurity.glaciermessenger.Config;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.AEADBadTagException;

public final class EncryptedPreferencesHelper {

    // ── Constants ──────────────────────────────────────────────────────
    private static final String DEFAULT_PREFS_FILE      = "secret_shared_prefs";
    private static final String KEYSTORE_PROVIDER       = "AndroidKeyStore";
    private static final String MASTER_KEY_ALIAS        = "_androidx_security_master_key_";
    private static final int    KEY_SIZE                = 256;
    private static final String ENCRYPTED_KEYSET_PREFS  = "__androidx_security_crypto_encrypted_prefs_keyset__";
    private static final String MASTER_KEY_KEYSET_PREFS = "__androidx_security_crypto_master_key_keyset__";

    private EncryptedPreferencesHelper() { /* utility class */ }

    // ── Typed exception ───────────────────────────────────────────────

    /**
     * Thrown when the encrypted preferences ciphertext or its Tink keyset
     * cannot be authenticated. The caller MUST surface this to the user and
     * obtain explicit consent before calling {@link #wipeCorruptedStore}.
     *
     * Never caught-and-ignored — it is a checked exception by design.
     */
    public static final class CredentialStoreCorruptedException extends Exception {
        CredentialStoreCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Opens the default encrypted preferences file.
     *
     * @throws CredentialStoreCorruptedException if the store has been tampered
     *         with or its master key is irrecoverably lost. The caller should
     *         surface this to the user and call {@link #wipeCorruptedStore}
     *         only after obtaining explicit consent.
     */
    public static SharedPreferences getEncryptedSharedPreferences(Context context)
            throws CredentialStoreCorruptedException {
        return getEncryptedSharedPreferences(context, DEFAULT_PREFS_FILE);
    }

    /**
     * Opens a named encrypted preferences file.
     *
     * @throws CredentialStoreCorruptedException if the store cannot be opened
     *         due to authentication failure or key loss.
     */
    public static SharedPreferences getEncryptedSharedPreferences(Context context, String fileName)
            throws CredentialStoreCorruptedException {
        return openEncryptedSharedPreferences(context.getApplicationContext(), fileName);
    }

    /**
     * Returns {@code true} if opening the default store would throw
     * {@link CredentialStoreCorruptedException}. Safe to call without try/catch;
     * useful for startup diagnostics.
     */
    public static boolean isCorrupted(Context context) {
        try {
            getEncryptedSharedPreferences(context);
            return false;
        } catch (CredentialStoreCorruptedException e) {
            return true;
        }
    }

    /**
     * Explicitly destroys the encrypted preferences store and its associated
     * keys. Must ONLY be called after the user has been shown a clear warning
     * and has provided affirmative consent (e.g., via a confirmation dialog).
     *
     * This method is intentionally NOT called automatically anywhere in the
     * codebase — it is a user-initiated destructive action only.
     */
    public static void wipeCorruptedStore(Context context) {
        wipeCorruptedStore(context, DEFAULT_PREFS_FILE);
    }

    public static void wipeCorruptedStore(Context context, String fileName) {
        Log.w(Config.LOGTAG,
                "EncryptedPreferencesHelper: wiping store='" + fileName
                        + "' at explicit user request");
        deleteMasterKey();
        deleteSharedPreferencesFile(context, fileName);
        deleteSharedPreferencesFile(context, ENCRYPTED_KEYSET_PREFS);
        deleteSharedPreferencesFile(context, MASTER_KEY_KEYSET_PREFS);
    }

    // ── Private helpers ───────────────────────────────────────────────

    private static SharedPreferences openEncryptedSharedPreferences(
            Context context, String fileName) throws CredentialStoreCorruptedException {
        try {
            return EncryptedSharedPreferences.create(
                    context,
                    fileName,
                    getMasterKey(context),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            if (isAuthenticationFailure(e)) {
                // Log the corruption but DO NOT wipe automatically.
                Log.e(Config.LOGTAG,
                        "EncryptedPreferencesHelper: authentication failure opening '"
                                + fileName + "' — store may be tampered with", e);
                throw new CredentialStoreCorruptedException(
                        "Encrypted preferences store '" + fileName
                                + "' failed authentication — user consent required to reset",
                        e);
            }
            // Non-authentication failures (e.g., GeneralSecurityException on key
            // generation) are still illegal state — propagate as-is.
            Log.e(Config.LOGTAG,
                    "EncryptedPreferencesHelper: unexpected error opening '" + fileName + "'", e);
            throw new IllegalStateException(
                    "Unable to access encrypted shared preferences: " + e.getMessage(), e);
        }
    }

    /**
     * Returns true for AEADBadTagException and any exception whose message
     * contains "VERIFICATION_FAILED" anywhere in its cause chain.
     * These indicate ciphertext tampering or master key loss — not transient errors.
     */
    private static boolean isAuthenticationFailure(Throwable t) {
        if (t == null) return false;
        if (t instanceof AEADBadTagException) return true;
        String msg = t.getMessage();
        if (msg != null && msg.contains("VERIFICATION_FAILED")) return true;
        return isAuthenticationFailure(t.getCause());
    }

    private static MasterKey getMasterKey(Context context)
            throws GeneralSecurityException, IOException {
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build();
        return new MasterKey.Builder(context)
                .setKeyGenParameterSpec(spec)
                .build();
    }

    private static void deleteMasterKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MASTER_KEY_ALIAS);
            }
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Unable to delete master key", e);
        }
    }

    private static void deleteSharedPreferencesFile(Context context, String name) {
        try {
            context.deleteSharedPreferences(name);
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Unable to delete shared preferences file: " + name, e);
        }
    }
}