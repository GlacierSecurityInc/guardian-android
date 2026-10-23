package com.glaciersecurity.glaciermessenger.persistance;

import android.content.Context;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Supplies the SQLCipher passphrase for an encrypted database, sealed with an Android Keystore key.
 *
 * <p>The passphrase is 32 bytes of {@link SecureRandom} output, handed to SQLCipher in <b>raw
 * key form</b> -- the literal string {@code x'<64 hex digits>'}. Exactly 64 hex digits tells SQLCipher
 * "this is the key material itself, derive the salt from the file header", which skips PBKDF2
 * entirely. With 256 bits of entropy the KDF buys nothing, and skipping it matters: at SQLCipher 4's
 * default of 256,000 iterations it costs roughly 100ms on every cold database open, and this app opens
 * databases from short-lived headless processes -- {@code BootCompletedReceiver} and the
 * push-triggered {@code IncomingCallNotificationService} -- not just from a warm UI process.
 *
 * <p>The key material itself is stored through {@link KeystoreCredentialStore}, so it inherits that
 * class's AES-256-GCM Keystore-backed sealing and lands in the {@code glacier_keystore_creds}
 * preferences file, which {@code res/xml/data_extraction_rules.xml} already excludes from both cloud
 * backup and device transfer. No new crypto is introduced here.
 *
 * <p><b>Generation fails closed.</b> A newly generated passphrase is written, read back, and compared
 * before it is ever used to encrypt anything. This is {@code
 * DatabaseBackend#storeOwnIdentityKeyPair(Account, IdentityKeyPair)} applied to a much higher-stakes
 * secret: encrypting data under a key you have not proven you can retrieve turns a Keystore hiccup
 * into unrecoverable data loss.
 *
 * <p>The write has to be <em>durable</em> for that read-back to mean anything, which is why {@link
 * KeystoreCredentialStore#store} commits rather than queuing. A read-back that only confirmed an
 * in-memory value would still let the process die between encrypting the database and the key
 * reaching disk -- and that case is worse than a lost key, because the next launch finds no stored
 * credential at all, takes the "never stored" branch below, and mints a <em>different</em>
 * passphrase over a database it can now never open. It would not even reach the key-loss path, which
 * keys off the stored ciphertext being present.
 *
 * <p><b>Two hardening options are deliberately not taken.</b> {@code setUserAuthenticationRequired}
 * and {@code setUnlockedDeviceRequired} would both make the database unreadable while the screen is
 * locked, which would break background message receipt -- the app must decrypt to process an incoming
 * message whether or not anyone is looking at the device. StrongBox is left for later; it changes the
 * failure modes ({@code StrongBoxUnavailableException}, per-device behaviour) for little gain here.
 */
public final class DatabasePassphrase {

    /** Credential id for the UnifiedPush distributor database. */
    public static final String UNIFIED_PUSH_KEY_ID = "database_unifiedpush_key";

    /** Credential id for the {@code history} database. */
    public static final String HISTORY_KEY_ID = "database_history_key";

    private static final int KEY_SIZE_BYTES = 32;

    private DatabasePassphrase() { /* utility class */ }

    /** Why a passphrase could not be produced. Each reason wants different handling by the caller. */
    public enum Unavailable {
        /**
         * The device has not been unlocked since boot, so Keystore is not usable yet. Transient, and
         * the correct response is to defer -- never to generate a key, which would succeed and leave
         * a confusing half-state.
         */
        DEVICE_LOCKED,
        /**
         * A passphrase was stored but cannot be decrypted -- Keystore cleared, credentials reset,
         * device restored from another device, or an OS-level Keystore fault. The database is
         * unreadable and no amount of retrying will change that.
         */
        KEY_LOST,
        /** Keystore refused to store or to read back a freshly generated key. */
        GENERATION_FAILED
    }

    /** Thrown instead of returning a passphrase, so no caller can mistake absence for "no encryption". */
    public static class UnavailableException extends IllegalStateException {
        public final Unavailable reason;

        UnavailableException(final Unavailable reason, final String message) {
            super(message);
            this.reason = reason;
        }
    }

    /**
     * Returns the SQLCipher passphrase for {@code credentialId}, generating and sealing one on first
     * use.
     *
     * @throws UnavailableException if no passphrase can be produced. Callers must not fall back to
     *     opening the database unencrypted; that is the failure mode database encryption exists to remove.
     */
    @NonNull
    public static synchronized String require(
            @NonNull final Context context, @NonNull final String credentialId) {
        final Context appContext = context.getApplicationContext();

        // Mirrors GlacierGuardian.ensureAmplifyConfigured: a Keystore-backed store is not usable
        // before the first unlock, and the right move is to defer rather than to fail or to
        // improvise. Nothing in this app is directBootAware, so this window is narrow to the point
        // of being unreachable -- the check costs nothing and documents the assumption.
        final UserManager userManager = appContext.getSystemService(UserManager.class);
        if (userManager != null && !userManager.isUserUnlocked()) {
            throw new UnavailableException(
                    Unavailable.DEVICE_LOCKED,
                    "device not unlocked since boot; deferring database passphrase for '"
                            + credentialId
                            + "'");
        }

        final String existing = KeystoreCredentialStore.retrieve(appContext, credentialId);
        if (existing != null) {
            return asRawKeySpec(existing);
        }

        // retrieve() returns null both for "never stored" and for "stored but undecryptable", and
        // those are opposite situations: one wants a fresh key, the other must never get one,
        // because generating a new key over a database encrypted with the old one silently
        // abandons its contents. Distinguish them by asking whether ciphertext is on disk.
        if (KeystoreCredentialStore.hasStoredCredential(appContext, credentialId)) {
            throw new UnavailableException(
                    Unavailable.KEY_LOST,
                    "a passphrase is stored for '"
                            + credentialId
                            + "' but could not be decrypted; refusing to mint a replacement over an"
                            + " existing encrypted database");
        }

        return asRawKeySpec(generateAndVerify(appContext, credentialId));
    }

    /**
     * Whether a passphrase has ever been minted for {@code credentialId}, without trying to decrypt
     * it and without creating one.
     *
     * <p>This is how an encrypted database is told apart from a corrupt one. Their file headers are
     * identical -- neither carries the SQLite magic -- so the header alone cannot distinguish "we
     * encrypted this" from "these are random bytes". The credential can: it is written before the
     * database is ever encrypted, it lives in ordinary app storage rather than in the database, and
     * it survives a downgrade, which is exactly the case that matters.
     */
    public static boolean hasKey(
            @NonNull final Context context, @NonNull final String credentialId) {
        return KeystoreCredentialStore.hasStoredCredential(
                context.getApplicationContext(), credentialId);
    }

    /**
     * Discards the passphrase for {@code credentialId}.
     *
     * <p>Only safe for a database whose contents are disposable and self-rebuilding. Calling this for
     * a database holding user data makes that data permanently unreadable.
     */
    public static synchronized void forget(
            @NonNull final Context context, @NonNull final String credentialId) {
        KeystoreCredentialStore.remove(context.getApplicationContext(), credentialId);
    }

    @NonNull
    private static String generateAndVerify(
            @NonNull final Context context, @NonNull final String credentialId) {
        final byte[] material = new byte[KEY_SIZE_BYTES];
        new SecureRandom().nextBytes(material);
        final String hex = toHex(material);

        if (!KeystoreCredentialStore.store(context, credentialId, hex)) {
            throw new UnavailableException(
                    Unavailable.GENERATION_FAILED,
                    "Keystore refused to store a passphrase for '" + credentialId + "'");
        }

        // Fail closed. Encrypt nothing under a key we have not just proven we can read back.
        final String readBack = KeystoreCredentialStore.retrieve(context, credentialId);
        if (!hex.equals(readBack)) {
            KeystoreCredentialStore.remove(context, credentialId);
            throw new UnavailableException(
                    Unavailable.GENERATION_FAILED,
                    "passphrase for '"
                            + credentialId
                            + "' did not survive a store/retrieve round trip; refusing to encrypt"
                            + " with a key that cannot be recovered");
        }

        return hex;
    }

    /**
     * Wraps 64 hex digits in SQLCipher's raw-key literal. Kept separate, and free of any SQLCipher
     * import, so it stays reachable from the JVM unit-test tier -- see the net.zetetic exclusion in
     * build.gradle.
     */
    @VisibleForTesting
    @NonNull
    static String asRawKeySpec(@NonNull final String hex) {
        if (hex.length() != KEY_SIZE_BYTES * 2 || !isHex(hex)) {
            throw new IllegalArgumentException(
                    "database passphrase must be " + (KEY_SIZE_BYTES * 2) + " hex digits");
        }
        return "x'" + hex + "'";
    }

    @VisibleForTesting
    @NonNull
    static String toHex(@NonNull final byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            builder.append(String.format(Locale.US, "%02x", b));
        }
        return builder.toString();
    }

    private static boolean isHex(@Nullable final String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); ++i) {
            final char c = value.charAt(i);
            final boolean digit = c >= '0' && c <= '9';
            final boolean lower = c >= 'a' && c <= 'f';
            if (!digit && !lower) {
                return false;
            }
        }
        return true;
    }
}
