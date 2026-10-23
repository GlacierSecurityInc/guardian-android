package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.Config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A secondary encrypted credential store that is architecturally independent
 * of {@link EncryptedPreferencesHelper}.
 *
 * <p>Each credential is encrypted with a dedicated AES-256-GCM key stored in
 * the Android Keystore under the alias {@code glacier_cred_<uuid>}. The
 * resulting ciphertext is persisted in plain {@link SharedPreferences} as a
 * Base64-encoded blob, independently of the Tink keyset used by
 * EncryptedSharedPreferences.
 *
 * <p>This store acts as a fallback: if EncryptedSharedPreferences is corrupted
 * (e.g., by a single-byte flip), credentials can still be recovered here,
 * allowing the app to surface a re-authentication prompt rather than silently
 * locking out the user.
 *
 * <p>Storage format (Base64-encoded bytes):
 * <pre>
 *   [ 12 bytes GCM IV ][ N bytes AES-GCM ciphertext ]
 * </pre>
 */
public final class KeystoreCredentialStore {

    private static final String KEYSTORE_PROVIDER  = "AndroidKeyStore";
    private static final String PREFS_FILE          = "glacier_keystore_creds";
    private static final String KEY_ALIAS_PREFIX    = "glacier_cred_";
    private static final String DATABASE_KEY_PREFIX = "database_";
    private static final String CIPHER_ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH_BYTES = 12;
    private static final int    GCM_TAG_LENGTH_BITS = 128;
    private static final int    KEY_SIZE_BITS        = 256;

    private KeystoreCredentialStore() { /* utility class */ }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Encrypts {@code plaintext} and stores it under {@code credentialId}.
     * Generates a new Keystore key if one does not already exist for this id.
     *
     * <p>The write is durable before this returns, so a {@code true} result means the ciphertext is
     * on disk and it is safe to encrypt data that only this credential can recover.
     *
     * @return {@code true} once stored, {@code false} if encryption or the write failed (logged).
     */
    public static boolean store(
            @NonNull Context context,
            @NonNull String credentialId,
            @NonNull String plaintext) {
        try {
            final SecretKey key = getOrCreateKey(credentialId);
            final byte[] blob = encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8));
            final String encoded = Base64.encodeToString(blob, Base64.NO_WRAP);
            // commit(), not apply(). apply() returns immediately and flushes to disk on a
            // background thread, so a true return would only mean "queued": if the process dies
            // between here and the flush, the credential is gone while whatever was encrypted with
            // it survives. For a database passphrase that is unrecoverable -- the next launch finds
            // no stored credential, mints a different key, and the existing database can never be
            // opened again, without even taking the key-loss path, which keys off the stored
            // ciphertext being present. Callers read this boolean as "it is safe to encrypt with
            // this now", so it has to mean persisted, not scheduled. These are small values written
            // at login or first use, never on a hot path.
            return prefsFor(context).edit().putString(prefKey(credentialId), encoded).commit();
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "KeystoreCredentialStore: failed to store credential '"
                    + credentialId + "'", e);
            return false;
        }
    }

    /**
     * Decrypts and returns the credential stored under {@code credentialId},
     * or {@code null} if not found or decryption fails.
     */
    @Nullable
    public static String retrieve(
            @NonNull Context context,
            @NonNull String credentialId) {
        try {
            final String encoded = prefsFor(context).getString(prefKey(credentialId), null);
            if (encoded == null) return null;

            final SecretKey key = getExistingKey(credentialId);
            if (key == null) {
                Log.w(Config.LOGTAG, "KeystoreCredentialStore: no key for '" + credentialId + "'");
                return null;
            }
            final byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            return new String(decrypt(key, blob), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "KeystoreCredentialStore: failed to retrieve credential '"
                    + credentialId + "'", e);
            return null;
        }
    }

    /**
     * Removes the stored ciphertext and the associated Keystore key for
     * {@code credentialId}. A no-op if the credential does not exist.
     */
    public static void remove(@NonNull Context context, @NonNull String credentialId) {
        try {
            // commit() for the same reason as store(): the recovery paths that call this delete the
            // database alongside the credential, and a removal that had not reached disk would
            // leave a stale credential pointing at a database that no longer exists.
            prefsFor(context).edit().remove(prefKey(credentialId)).commit();
            deleteKey(credentialId);
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "KeystoreCredentialStore: failed to remove credential '"
                    + credentialId + "'", e);
        }
    }

    /**
     * Removes all credentials managed by this store. Should only be called
     * during explicit account deletion or a user-initiated logout.
     */
    public static void removeAll(@NonNull Context context) {
        try {
            final SharedPreferences prefs = prefsFor(context);
            final SharedPreferences.Editor editor = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                final String credId = credentialIdFromPrefKey(key);
                // Database keys outlive a logout: the files they unlock stay on disk.
                if (credId != null && credId.startsWith(DATABASE_KEY_PREFIX)) continue;
                if (credId != null) deleteKey(credId);
                editor.remove(key);
            }
            editor.commit();
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "KeystoreCredentialStore: failed to removeAll", e);
        }
    }

    /**
     * Encrypts {@code plaintext} with a per-{@code credentialId} Keystore key
     * and returns the ciphertext Base64-encoded. Unlike {@link #store}, the
     * result is not persisted here — callers own where it's stored (e.g. a
     * SQLite column). Generates the Keystore key if one does not already
     * exist for this id.
     *
     * @return the encoded ciphertext, or {@code null} if encryption failed.
     */
    @Nullable
    public static String encryptString(@NonNull String credentialId, @NonNull String plaintext) {
        try {
            final SecretKey key = getOrCreateKey(credentialId);
            final byte[] blob = encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(blob, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "KeystoreCredentialStore: failed to encrypt value for '"
                    + credentialId + "'", e);
            return null;
        }
    }

    /**
     * Decrypts a value previously produced by {@link #encryptString} for the
     * same {@code credentialId}.
     *
     * @return the decrypted plaintext, or {@code null} if no Keystore key
     * exists for {@code credentialId} (e.g. the value is not Keystore-wrapped)
     * or decryption otherwise fails.
     */
    @Nullable
    public static String decryptString(@NonNull String credentialId, @NonNull String encoded) {
        try {
            final SecretKey key = getExistingKey(credentialId);
            if (key == null) return null;
            final byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            return new String(decrypt(key, blob), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "KeystoreCredentialStore: failed to decrypt value for '"
                    + credentialId + "'", e);
            return null;
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────

    private static byte[] encrypt(SecretKey key, byte[] plaintext) throws Exception {
        final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        final byte[] iv         = cipher.getIV();   // Keystore generates IV
        final byte[] ciphertext = cipher.doFinal(plaintext);

        // Pack: [ iv (12) | ciphertext+tag ]
        final ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
        buf.put(iv);
        buf.put(ciphertext);
        return buf.array();
    }

    private static byte[] decrypt(SecretKey key, byte[] blob) throws Exception {
        if (blob.length < GCM_IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Blob too short to contain IV");
        }
        final ByteBuffer buf = ByteBuffer.wrap(blob);
        final byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        buf.get(iv);
        final byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    // ── Keystore key management ───────────────────────────────────────

    private static SecretKey getOrCreateKey(String credentialId) throws Exception {
        final SecretKey existing = getExistingKey(credentialId);
        if (existing != null) return existing;
        return generateKey(credentialId);
    }

    @Nullable
    private static SecretKey getExistingKey(String credentialId) throws Exception {
        final KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        final KeyStore.Entry entry = ks.getEntry(keyAlias(credentialId), null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        return null;
    }

    private static SecretKey generateKey(String credentialId) throws Exception {
        final KeyGenerator keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        keyGen.init(new KeyGenParameterSpec.Builder(
                keyAlias(credentialId),
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)  // Keystore supplies the IV
                .build());
        return keyGen.generateKey();
    }

    private static void deleteKey(String credentialId) {
        try {
            final KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(keyAlias(credentialId))) {
                ks.deleteEntry(keyAlias(credentialId));
            }
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "KeystoreCredentialStore: failed to delete key for '"
                    + credentialId + "'", e);
        }
    }

    /**
     * Reports whether ciphertext is on disk for {@code credentialId}, without attempting to decrypt
     * it.
     *
     * <p>{@link #retrieve} deliberately returns {@code null} both when nothing was ever stored and
     * when something was stored but cannot be decrypted, because most callers treat the two the same
     * -- fall back to re-authentication. Callers that encrypt data under the stored value cannot:
     * minting a replacement key for a credential whose old key is merely unreadable silently
     * abandons everything encrypted with it. Those callers use this to tell the two apart and refuse
     * the destructive case.
     */
    public static boolean hasStoredCredential(
            @NonNull Context context, @NonNull String credentialId) {
        return prefsFor(context).contains(prefKey(credentialId));
    }

    // ── SharedPreferences helpers ─────────────────────────────────────

    private static SharedPreferences prefsFor(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    private static String keyAlias(String credentialId) {
        return KEY_ALIAS_PREFIX + credentialId;
    }

    private static String prefKey(String credentialId) {
        return KEY_ALIAS_PREFIX + credentialId;
    }

    @Nullable
    private static String credentialIdFromPrefKey(String prefKey) {
        if (prefKey != null && prefKey.startsWith(KEY_ALIAS_PREFIX)) {
            return prefKey.substring(KEY_ALIAS_PREFIX.length());
        }
        return null;
    }
}