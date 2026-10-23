package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Environment;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Helper methods for working with voicemail audio files downloaded from the
 * Glacier backend.  Recordings are cached inside the app-private cache
 * directory for offline playback.
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li>All downloads go through the authenticated Glacier endpoint (JWT Bearer
 *       always present).  There is no unauthenticated fallback.</li>
 *   <li>{@link OkHttpClientProvider#buildJwtFormRequestBuilder} throws
 *       {@link IOException} when the Cognito token is unavailable; this class
 *       catches that and returns {@code false} without retrying.</li>
 *
 *   <li><b>nonce filename + HMAC integrity guard.</b>
 *       The recording SID is present in the server-returned voicemail listing
 *       JSON, so a cache filename derived from the SID alone would be
 *       predictable. Two measures keep a pre-created file from being played:
 *       <ol>
 *         <li><b>Nonce filename</b> — a random 8-byte nonce is generated on
 *             first access per recording SID and appended to the filename:
 *             {@code {sid}_{nonce_hex}.mp3}.  The nonce is persisted in a
 *             private {@link SharedPreferences} file so the same name is used
 *             on subsequent lookups.  An attacker who does not know the nonce
 *             cannot pre-create the file at the correct path.</li>
 *         <li><b>HMAC-SHA256 integrity check</b> — after every successful
 *             download the file content is signed with an AES key stored in the
 *             Android Keystore (alias {@link #HMAC_KEY_ALIAS}).  Before
 *             returning {@code true} from {@code ensureFileForVoicemail()} for
 *             a pre-existing file, the stored MAC is verified.  A file that
 *             fails verification is deleted and re-downloaded.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
public final class VoicemailStorageUtils {

    private static final String DEFAULT_EXTENSION = ".mp3";

    // nonce + HMAC constants
    private static final String NONCE_PREFS_FILE  = "glacier_vm_nonces";
    private static final String MAC_PREFS_FILE    = "glacier_vm_macs";
    private static final String HMAC_KEY_ALIAS    = "glacier_voicemail_hmac";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String HMAC_ALGORITHM    = "HmacSHA256";
    private static final int    NONCE_BYTES        = 8;
    // ─────────────────────────────────────────────────────────────────────────

    // ---------------------------------------------------------------------------
    // Hostname allowlist
    // ---------------------------------------------------------------------------

    private static final Set<String> STATIC_TRUSTED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "api.twilio.com",
                    "recordings.twilio.com",
                    "media.twiliocdn.com"
            ))
    );

    @NonNull
    static Set<String> buildTrustedHostSet() {
        final Set<String> hosts = new HashSet<>(STATIC_TRUSTED_HOSTS);
        final String endpoint = Endpoints.base() + EndPointConfig.GET_VOICEMAILS_URL;
        if (hasText(endpoint)) {
            try {
                final String host = new URL(endpoint).getHost();
                if (hasText(host)) hosts.add(host.toLowerCase());
            } catch (MalformedURLException e) {
                Log.w(Config.LOGTAG,
                        "VoicemailStorageUtils: could not parse endpoint URL for host extraction", e);
            }
        }
        return Collections.unmodifiableSet(hosts);
    }

    static boolean isTrustedVoicemailHost(@Nullable String url) {
        if (!hasText(url)) return false;
        try {
            final URL parsed = new URL(url);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                Log.w(Config.LOGTAG, "VoicemailStorageUtils: rejected non-HTTPS voicemail URL");
                return false;
            }
            final String host = parsed.getHost();
            if (!hasText(host)) return false;
            final boolean trusted = buildTrustedHostSet().contains(host.toLowerCase());
            if (!trusted) {
                Log.w(Config.LOGTAG,
                        "VoicemailStorageUtils: rejected untrusted voicemail host: " + host);
            }
            return trusted;
        } catch (MalformedURLException e) {
            Log.w(Config.LOGTAG, "VoicemailStorageUtils: malformed voicemail URL rejected", e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------------

    private VoicemailStorageUtils() { /* utility class */ }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    @Nullable
    public static File getVoicemailDirectory(@NonNull Context context) {
        final File cacheRoot = context.getCacheDir();
        final File directory = cacheRoot != null
                ? new File(cacheRoot, Environment.DIRECTORY_DOWNLOADS)
                : new File(context.getFilesDir(), Environment.DIRECTORY_DOWNLOADS);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(Config.LOGTAG, "Failed to create voicemail download directory at "
                    + directory.getAbsolutePath());
            return null;
        }
        return directory;
    }

    /**
     * Returns the local {@link File} for a voicemail recording.
     *
     *
     * <p>The filename is {@code {id}_{nonce_hex}.ext} where the nonce is a
     * random 8-byte value generated once per recording SID and persisted in
     * private {@link SharedPreferences}.  Because the nonce is not derivable
     * from the recording SID alone, an attacker who can observe the network
     * listing response cannot pre-create the file at the correct cache path.
     */
    @Nullable
    public static File getVoicemailFile(@NonNull Context context,
                                        @NonNull VoicemailRecord record) {
        final File directory = getVoicemailDirectory(context);
        if (directory == null) return null;
        return new File(directory, buildFileName(context, record));
    }

    public static boolean ensureFileForVoicemail(@NonNull Context context,
                                                 @NonNull VoicemailRecord record) {
        return ensureFileForVoicemail(context, record, null);
    }

    /**
     * Ensures the local audio file for {@code record} exists and is
     * <em>authentic</em>, downloading it from the Glacier endpoint if necessary.
     *
     * <ol>
     *   <li>If the file already exists on disk its HMAC (stored at download time
     *       in private SharedPreferences) is verified before returning
     *       {@code true}.  A file whose MAC does not match is deleted and
     *       re-downloaded — this handles both on-disk corruption and an attacker
     *       who somehow managed to pre-seed the file despite the nonce.</li>
     *   <li>After a fresh download succeeds, the HMAC of the written bytes is
     *       computed and stored so it can be verified on the next access.</li>
     * </ol>
     */
    public static boolean ensureFileForVoicemail(@NonNull Context context,
                                                 @NonNull VoicemailRecord record,
                                                 @Nullable Account account) {
        final File file = getVoicemailFile(context, record);
        if (file == null) return false;

        synchronized (VoicemailStorageUtils.class) {

            if (file.exists()) {
                // verify HMAC before trusting the cached file
                if (verifyFileHmac(context, record, file)) {
                    return true;
                }
                // MAC mismatch: file is corrupt or was tampered with.
                Log.w(Config.LOGTAG,
                        "VoicemailStorageUtils: HMAC mismatch for "
                                + record.getRecordingSid() + " — deleting and re-downloading");
                if (!file.delete()) {
                    Log.e(Config.LOGTAG,
                            "VoicemailStorageUtils: failed to delete tampered cache file: "
                                    + file.getAbsolutePath());
                    return false;
                }
                // Clear the stale MAC entry so storeFileHmac writes a fresh one.
                clearStoredHmac(context, record);
            }

            if (account == null) {
                account = resolveAccountFromContext(context);
            }

            // Single authenticated code path — no unauthenticated fallback.
            final boolean downloaded =
                    downloadVoicemailFromEndpoint(context, record, file, account);

            if (downloaded && file.exists()) {
                // store HMAC immediately after a successful download
                storeFileHmac(context, record, file);
            }

            return downloaded;
        }
    }

    // ---------------------------------------------------------------------------
    // Nonce filename helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a nonce-qualified filename for the voicemail cache file.
     *
     * <p>Format: {@code {sanitized_id}_{nonce_hex}{.ext}}
     *
     * <p>The nonce is looked up in (or written to) the private
     * {@link #NONCE_PREFS_FILE} SharedPreferences.  Using a per-SID random
     * nonce makes the filename unguessable from network-observable data alone.
     */
    @NonNull
    private static String buildFileName(@NonNull Context context,
                                        @NonNull VoicemailRecord record) {
        final String id  = chooseIdentifier(record);
        final String ext = guessExtension(record.getUrl());

        // Look up or generate the nonce for this recording SID.
        final SharedPreferences noncePrefs =
                context.getApplicationContext()
                        .getSharedPreferences(NONCE_PREFS_FILE, Context.MODE_PRIVATE);

        String nonce = noncePrefs.getString(id, null);
        if (nonce == null) {
            // First access — generate and persist a fresh nonce.
            final byte[] nonceBytes = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonceBytes);
            nonce = bytesToHex(nonceBytes);
            noncePrefs.edit().putString(id, nonce).apply();
        }

        return id + "_" + nonce + ext;
    }

    // ---------------------------------------------------------------------------
    // HMAC helpers
    // ---------------------------------------------------------------------------

    /**
     * Computes HMAC-SHA256 of {@code file}'s content using the Keystore key and
     * stores the result in private {@link SharedPreferences}.
     */
    private static void storeFileHmac(@NonNull Context context,
                                      @NonNull VoicemailRecord record,
                                      @NonNull File file) {
        try {
            final byte[] mac = computeFileHmac(file);
            if (mac == null) return;
            final String encoded = Base64.encodeToString(mac, Base64.NO_WRAP);
            context.getApplicationContext()
                    .getSharedPreferences(MAC_PREFS_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(record.getRecordingSid(), encoded)
                    .apply();
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "VoicemailStorageUtils: failed to store HMAC", e);
        }
    }

    /**
     * Returns {@code true} if the stored HMAC matches the current content of
     * {@code file}.  Returns {@code false} if no stored MAC exists OR if
     * verification fails.
     */
    private static boolean verifyFileHmac(@NonNull Context context,
                                          @NonNull VoicemailRecord record,
                                          @NonNull File file) {
        try {
            final SharedPreferences macPrefs =
                    context.getApplicationContext()
                            .getSharedPreferences(MAC_PREFS_FILE, Context.MODE_PRIVATE);
            final String stored = macPrefs.getString(record.getRecordingSid(), null);
            if (stored == null) {
                // No stored MAC — treat as unverified so the caller re-downloads.
                return false;
            }
            final byte[] storedMac  = Base64.decode(stored, Base64.NO_WRAP);
            final byte[] currentMac = computeFileHmac(file);
            if (currentMac == null) return false;
            return constantTimeEquals(storedMac, currentMac);
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "VoicemailStorageUtils: HMAC verification error", e);
            return false;
        }
    }

    private static void clearStoredHmac(@NonNull Context context,
                                        @NonNull VoicemailRecord record) {
        context.getApplicationContext()
                .getSharedPreferences(MAC_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .remove(record.getRecordingSid())
                .apply();
    }

    /**
     * Computes HMAC-SHA256 of every byte in {@code file} using the Keystore key.
     * Returns {@code null} on any error.
     */
    @Nullable
    private static byte[] computeFileHmac(@NonNull File file) {
        try {
            final SecretKey key = getOrCreateHmacKey();
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);

            final byte[] buf = new byte[8192];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int read;
                while ((read = fis.read(buf)) != -1) {
                    mac.update(buf, 0, read);
                }
            }
            return mac.doFinal();
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "VoicemailStorageUtils: HMAC computation failed", e);
            return null;
        }
    }

    /**
     * Returns the Keystore-backed HMAC key, creating it if it does not exist.
     *
     * <p>The key is AES-256 (used as an HMAC key via {@link KeyProperties#KEY_ALGORITHM_HMAC_SHA256})
     * stored in the Android Keystore under {@link #HMAC_KEY_ALIAS}.
     */
    @NonNull
    private static SecretKey getOrCreateHmacKey() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(HMAC_KEY_ALIAS)) {
            final KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) keyStore.getEntry(HMAC_KEY_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
        }

        // Key does not exist — generate it.
        final KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER);
        kg.init(new KeyGenParameterSpec.Builder(
                HMAC_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .build());
        return kg.generateKey();
    }

    /** Constant-time byte-array comparison to prevent timing side-channels. */
    private static boolean constantTimeEquals(@NonNull byte[] a, @NonNull byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    // ---------------------------------------------------------------------------
    // Private download helpers (unchanged from pre-fix except for signature)
    // ---------------------------------------------------------------------------

    private static boolean downloadVoicemailFromEndpoint(
            @NonNull Context context,
            @NonNull VoicemailRecord record,
            @NonNull File file,
            @Nullable Account account) {

        final Account resolvedAccount =
                account != null ? account : resolveAccountFromContext(context);
        if (resolvedAccount == null) {
            return false;
        }

        final String recordingSid = record.getRecordingSid();
        if (!hasText(recordingSid)) {
            return false;
        }

        final String organization = resolvedAccount.getOrganization();
        final String username     = resolvedAccount.getUsername();
        if (!hasText(organization) || !hasText(username)) {
            return false;
        }


        final String endpoint = Endpoints.base() + EndPointConfig.GET_VOICEMAILS_URL;
        if (!hasText(endpoint)) {
            return false;
        }

        final Map<String, String> formFields = new HashMap<>();
        formFields.put("organization", organization);
        formFields.put("username",     username);
        formFields.put("recordingSid", recordingSid);

        final Request request;
        try {
            request = OkHttpClientProvider
                    .buildJwtFormRequestBuilder(endpoint, formFields)
                    .build();
        } catch (IOException e) {
            Log.w(Config.LOGTAG,
                    "VoicemailStorageUtils: Cognito token unavailable — aborting download: "
                            + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(Config.LOGTAG,
                    "VoicemailStorageUtils: failed to build authenticated request", e);
            return false;
        }

        try (Response response = OkHttpClientProvider.getOkHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(Config.LOGTAG,
                        "VoicemailStorageUtils: endpoint returned HTTP " + response.code()
                                + " for recording " + recordingSid);
                return false;
            }
            final ResponseBody body = response.body();
            if (body == null) {
                Log.w(Config.LOGTAG,
                        "VoicemailStorageUtils: empty response body for recording " + recordingSid);
                return false;
            }

            final File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.e(Config.LOGTAG,
                        "VoicemailStorageUtils: could not create cache directory: "
                                + parent.getAbsolutePath());
                return false;
            }

            try (InputStream is = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(file)) {
                final byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                }
                fos.flush();
            }
            return true;

        } catch (IOException e) {
            Log.e(Config.LOGTAG,
                    "VoicemailStorageUtils: I/O error downloading recording " + recordingSid, e);
            if (file.exists()) file.delete();
            return false;
        }
    }

    // ---------------------------------------------------------------------------
    // Filename helpers
    // ---------------------------------------------------------------------------

    @NonNull
    private static String chooseIdentifier(@NonNull VoicemailRecord record) {
        String id = sanitize(record.getRecordingSid());
        if (id == null) id = sanitize(record.getCallSid());
        if (id == null) id = sanitize(record.getTimeStamp_string());
        if (id == null) id = sanitize(record.getTime());
        if (id == null) id = "voicemail";
        return id;
    }

    @Nullable
    private static String sanitize(@Nullable String raw) {
        if (raw == null) return null;
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @NonNull
    private static String guessExtension(@Nullable String url) {
        if (url == null) return DEFAULT_EXTENSION;
        final int qi = url.indexOf('?');
        final String path = qi >= 0 ? url.substring(0, qi) : url;
        final int ls = path.lastIndexOf('/');
        final String filePart = ls >= 0 ? path.substring(ls + 1) : path;
        final int dot = filePart.lastIndexOf('.');
        if (dot > 0 && dot < filePart.length() - 1) {
            final String ext = filePart.substring(dot);
            if (ext.length() <= 5) return ext;
        }
        return DEFAULT_EXTENSION;
    }

    // ---------------------------------------------------------------------------
    // Miscellaneous utilities
    // ---------------------------------------------------------------------------

    @Nullable
    private static Account resolveAccountFromContext(@NonNull Context context) {
        final Context app = context.getApplicationContext();
        if (app instanceof XmppConnectionService) {
            return ((XmppConnectionService) app).getFirstAccount();
        }
        if (app instanceof ContextWrapper) {
            final Context base = ((ContextWrapper) app).getBaseContext();
            if (base instanceof XmppConnectionService) {
                return ((XmppConnectionService) base).getFirstAccount();
            }
        }
        return null;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    @NonNull
    private static String bytesToHex(@NonNull byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}