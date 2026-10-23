package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.wireguard.ConfigParser;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class CoreProfile extends AbstractEntity implements Comparable<CoreProfile> {

    public static final String TABLENAME = "wireguard_config";
    public static final String PROFILE_NAME = "profile_name";
    public static final String DISPLAY_NAME = "display_Name";
    public static final String COLUMN_CONFIG = "config";
    public static final String LEGACY_NAME = "legacy_name";
    public static final String IS_RUNNING = "is_running";

    // the wg-quick config (which embeds the tunnel's private key) is encrypted
    // at rest with an Android-Keystore-backed AES-256-GCM key dedicated to this profile, so a
    // rooted device / debuggable-build database extraction does not yield the raw key. The
    // ciphertext, prefixed with ENCRYPTED_PREFIX, is what actually lives in COLUMN_CONFIG.
    private static final String ENCRYPTED_PREFIX = "ENC1:";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS_PREFIX = "glacier_wg_profile_";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_SIZE_BITS = 256;

    private Config config;
    private boolean isRunning = false;
    private final String profName;
    private String displayName;
    private String legacyName;
    // Set when this instance was loaded from a DB row without ENCRYPTED_PREFIX; getContentValues()
    // will write it back encrypted, and DatabaseBackend re-saves such rows once loaded to migrate them.
    private boolean legacyPlaintextConfig = false;

    public CoreProfile(String name, File configFilePath, String legacyName) {
        this.profName = name;
        this.isRunning = false;
        this.legacyName = legacyName;
        displayForProf();
        Thread parserThread = new Thread(() -> importCoreProfileConfig(configFilePath));

                parserThread.start();
        try {
            parserThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                    "Interrupted while parsing core profile config", e);
        }
    }

    public String getLegacyName() {
        return legacyName;
    }

    public void setLegacyName(String legacyName) {
        this.legacyName = legacyName;
    }
    private void displayForProf() {
        try {
            displayName = profName.replace(".conf","");
        } catch (Exception ignore){
        }
    }

    public CoreProfile(String name, String displayName, String config, int isRunning, String legacyName) {
        super();
        this.profName = name;
        this.displayName = displayName;
        parseConfig(config);
        this.isRunning = isRunning == 1;
        this.legacyName = legacyName;
    }

    private void parseConfig(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) {
            this.config = null;
            return;
        }
        final String wgQuickText;
        if (storedValue.startsWith(ENCRYPTED_PREFIX)) {
            wgQuickText = decryptConfig(profName, storedValue.substring(ENCRYPTED_PREFIX.length()));
            this.legacyPlaintextConfig = false;
        } else {
            // Row without the encrypted prefix; parsed as-is here,
            // and getContentValues() will persist it encrypted the next time this row is saved.
            wgQuickText = storedValue;
            this.legacyPlaintextConfig = true;
        }
        if (wgQuickText == null) {
            this.config = null;
            return;
        }
        try {
            this.config = ConfigParser.parseWithDefaults(wgQuickText);
        } catch (IOException | BadConfigException e) {
            this.config = null;
            Log.e(com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                    "Error parsing core profile config " + e.getMessage(), e);
        }
    }

    public Config getConfig() {
        return config;
    }

    /**
     * @return {@code true} if this instance was loaded from a DB row without the encrypted prefix
     * and should be re-saved (via {@code DatabaseBackend#addCoreProfile}) to migrate it to the
     * encrypted storage format.
     */
    public boolean needsConfigMigration() {
        return legacyPlaintextConfig && config != null;
    }

    // ── Keystore-backed config encryption ────────────────────────────

    @Nullable
    private static String encryptConfig(String profName, String wgQuickText) {
        try {
            final SecretKey key = getOrCreateProfileKey(profName);
            final Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            final byte[] iv = cipher.getIV();
            final byte[] ciphertext = cipher.doFinal(wgQuickText.getBytes(StandardCharsets.UTF_8));
            final ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                    "Failed to encrypt WireGuard config for profile " + profName, e);
            return null;
        }
    }

    @Nullable
    private static String decryptConfig(String profName, String encoded) {
        try {
            final SecretKey key = getExistingProfileKey(profName);
            if (key == null) {
                Log.w(com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                        "No Keystore key found for WireGuard profile " + profName);
                return null;
            }
            final byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
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
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                    "Failed to decrypt WireGuard config for profile " + profName, e);
            return null;
        }
    }

    private static SecretKey getOrCreateProfileKey(String profName) throws Exception {
        final SecretKey existing = getExistingProfileKey(profName);
        if (existing != null) {
            return existing;
        }
        final KeyGenerator keyGen =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        keyGen.init(new KeyGenParameterSpec.Builder(
                        keyAlias(profName),
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGen.generateKey();
    }

    @Nullable
    private static SecretKey getExistingProfileKey(String profName) throws Exception {
        final KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        final KeyStore.Entry entry = ks.getEntry(keyAlias(profName), null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        return null;
    }

    private static String keyAlias(String profName) {
        return KEY_ALIAS_PREFIX + profName;
    }

    /** Removes the Android Keystore key for {@code profName}, if any. Call when a profile row is deleted. */
    public static void deleteProfileKey(String profName) {
        try {
            final KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            final String alias = keyAlias(profName);
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias);
            }
        } catch (Exception e) {
            Log.w(com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                    "Failed to delete Keystore key for WireGuard profile " + profName, e);
        }
    }

    @NonNull
    @Override
    public String toString() {
        String name = getDisplayName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        name = getProfName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        if (config != null) {
            return config.toWgQuickString();
        }
        return "VPN_profile{" + "profName='" + profName + '\'' + '}';
    }

    public String getUUID() {
        return config != null ? Integer.toString(config.hashCode()) : "";
    }


    private void importCoreProfileConfig(File inputFile) {

        try (FileInputStream fis = new FileInputStream(inputFile);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            final StringBuilder wgQuickText = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                wgQuickText.append(line).append('\n');
            }
            config = ConfigParser.parseWithDefaults(wgQuickText.toString());
        } catch (IOException e) {
            config = null;
            Log.e(com.glaciersecurity.glaciermessenger.Config.LOGTAG, "IOException " + e.getMessage(), e);
        } catch (BadConfigException e) {
            config = null;
            // fail gracefully instead of rethrowing from this background
            // parser thread, matching parseConfig(String)'s handling of the same exception.
            Log.e(com.glaciersecurity.glaciermessenger.Config.LOGTAG, "BadConfigException " + e.getMessage(), e);
        } finally {
            if (inputFile.exists() && !inputFile.delete()) {
                Log.w(
                        com.glaciersecurity.glaciermessenger.Config.LOGTAG,
                        "Failed to delete core profile config file " + inputFile.getAbsolutePath());
            }
        }
    }

    public String getDisplayName() {
        if (displayName == null || displayName.isEmpty()){
            return profName;
        }
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProfName() {
        return profName;
    }

    @Override
    public int compareTo(CoreProfile o) {
        return getProfName().compareToIgnoreCase(o.getProfName());
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }


    public int getIcon() {
        return R.drawable.wireguard_icon;
    }

    public static CoreProfile fromCursor(Cursor cursor) {
        return new CoreProfile(
                cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(DISPLAY_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONFIG)),
                cursor.getInt(cursor.getColumnIndexOrThrow(IS_RUNNING)),
                cursor.getString(cursor.getColumnIndexOrThrow(LEGACY_NAME)));
    }

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(PROFILE_NAME, this.profName);
        values.put(DISPLAY_NAME, this.displayName);
        if (this.config != null) {
            final String encrypted = encryptConfig(this.profName, this.config.toWgQuickString());
            if (encrypted != null) {
                values.put(COLUMN_CONFIG, ENCRYPTED_PREFIX + encrypted);
            } else {
                // Never fall back to writing the plaintext config (private key included);
                // losing the stored profile is safer than defeating encryption at rest.
                values.putNull(COLUMN_CONFIG);
            }
        } else {
            values.putNull(COLUMN_CONFIG);
        }
        values.put(IS_RUNNING, this.isRunning);
        values.put(LEGACY_NAME, this.legacyName);
        return values;
    }
}
