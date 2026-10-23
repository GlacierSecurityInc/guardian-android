package com.glaciersecurity.glaciermessenger.entities;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.EncryptedPreferencesHelper;
import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;
import com.glaciersecurity.glaciermessenger.utils.Log;

public class CognitoAccount extends AbstractEntity {

    public static final String TABLENAME    = "cognito_account";
    public static final String USERNAME     = "username";

    /**
     * @deprecated The PASSWORD column is not used. The real credential is held exclusively in
     * EncryptedSharedPreferences (primary) and KeystoreCredentialStore (secondary).
     * This constant is retained only so the DatabaseBackend migration to version 69
     * can reference the column name when rebuilding the table without it.
     * Remove this constant after the migration ships to all devices.
     */
    @Deprecated
    public static final String PASSWORD     = "password";

    public static final String ORGANIZATION = "organization";
    public static final String ACCOUNT      = "account";

    protected String username;
    protected String password;
    protected String account;
    protected String organization;

    private Context cacontext;

    // ── Constructors ──────────────────────────────────────────────────

    public CognitoAccount(final String name, final String password,
                          final String organization, final String account,
                          Context context) {
        this(java.util.UUID.randomUUID().toString(), name, password, organization, account, context);
    }

    /**
     * Context-free constructor — used internally when reconstructing from the
     * context-aware {@link #fromCursor(Cursor, Context)} path. The password
     * field is already resolved by the caller; no persistence is performed here.
     */
    private CognitoAccount(final String uuid, final String name,
                           final String password, final String organization,
                           final String account) {
        this.uuid         = uuid;
        this.username     = name;
        this.password     = password;
        this.organization = organization;
        this.account      = account;
    }

    private CognitoAccount(final String uuid, final String name, final String password,
                           final String organization, final String account,
                           Context context) {
        this.cacontext    = context;
        persistPassword(uuid, password, context);
        this.uuid         = uuid;
        this.username     = name;
        this.password     = password;
        this.organization = organization;
        this.account      = account;
    }

    // ── Storage persistence ───────────────────────────────────────────

    /**
     * Writes the password to BOTH stores.
     *
     * Primary   — EncryptedSharedPreferences (Tink / AES-256-GCM keyset)
     * Secondary — KeystoreCredentialStore    (raw Keystore AES-256-GCM)
     *
     * Both writes are attempted independently so a failure in one does not
     * prevent the other from succeeding.
     */
    private static void persistPassword(final String uuid,
                                        final String password,
                                        final Context context) {
        if (password == null || context == null) return;

        // Primary write
        try {
            SharedPreferences prefs =
                    EncryptedPreferencesHelper.getEncryptedSharedPreferences(context);
            prefs.edit().putString(uuid, password).apply();
        } catch (EncryptedPreferencesHelper.CredentialStoreCorruptedException e) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: primary store corrupted during write for "
                            + uuid + " — credential only in secondary store until user re-authenticates", e);
        } catch (Exception e) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: unexpected error writing to primary store", e);
        }

        // Secondary write (always attempted, independent of primary result)
        final boolean secondaryOk =
                KeystoreCredentialStore.store(context.getApplicationContext(), uuid, password);
        if (!secondaryOk) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: secondary store write also failed for " + uuid
                            + " — credential persistence degraded");
        }
    }

    // ── Static factory methods ────────────────────────────────────────

    /**
     * Serialises this account for SQLite insertion/update.
     *
     * <p>The PASSWORD column is intentionally omitted. The column is dropped
     * entirely in the version-69 schema migration in DatabaseBackend.
     */
    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(USERNAME,     this.username);
        // PASSWORD deliberately excluded — credential lives in EncryptedSharedPreferences
        // and KeystoreCredentialStore, never in SQLite.
        values.put(ORGANIZATION, this.organization);
        values.put(ACCOUNT,      this.account);
        values.put(UUID,         uuid);
        return values;
    }

    /**
     * Reconstructs a {@link CognitoAccount} from a database cursor, loading the
     * real password from EncryptedSharedPreferences / KeystoreCredentialStore.
     *
     * <p>There is deliberately no context-free overload: the password cannot be
     * loaded without a {@link Context}, so all call sites must supply one; {@link com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend}
     * already does this via {@code dbcontext}.
     */
    public static CognitoAccount fromCursor(final Cursor cursor, final Context context) {
        final String uuid = cursor.getString(cursor.getColumnIndexOrThrow(UUID));
        final String password = loadPassword(uuid, context);
        final CognitoAccount account = new CognitoAccount(
                uuid,
                cursor.getString(cursor.getColumnIndexOrThrow(USERNAME)),
                password,
                cursor.getString(cursor.getColumnIndexOrThrow(ORGANIZATION)),
                cursor.getString(cursor.getColumnIndexOrThrow(ACCOUNT)));
        account.cacontext = context == null ? null : context.getApplicationContext();
        return account;
    }

    // ── Password loading ──────────────────────────────────────────────

    /**
     * Loads password using a two-store fallback strategy:
     *
     * 1. Try EncryptedSharedPreferences (primary).
     * 2. On {@link EncryptedPreferencesHelper.CredentialStoreCorruptedException},
     *    fall back to {@link KeystoreCredentialStore} (secondary) and log a
     *    warning — the UI layer should then prompt re-authentication.
     * 3. If both fail, return {@code null} — the caller (e.g., XmppConnectionService)
     *    will detect the missing password and surface a login screen.
     *
     * Nothing is auto-wiped at any step.
     */
    private static String loadPassword(final String uuid, final Context context) {
        if (uuid == null || context == null) return null;

        // ── Step 1: primary store ──────────────────────────────────────
        try {
            SharedPreferences prefs =
                    EncryptedPreferencesHelper.getEncryptedSharedPreferences(context);
            final String password = prefs.getString(uuid, null);
            if (password != null) return password;
            Log.w(Config.LOGTAG,
                    "CognitoAccount: password not found in primary store for " + uuid);
        } catch (EncryptedPreferencesHelper.CredentialStoreCorruptedException e) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: primary store corrupted while loading password for "
                            + uuid + " — attempting secondary store recovery", e);
        } catch (Exception e) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: unexpected error reading primary store for " + uuid, e);
        }

        // ── Step 2: secondary (Keystore) store ─────────────────────────
        final String recovered =
                KeystoreCredentialStore.retrieve(context.getApplicationContext(), uuid);
        if (recovered != null) {
            Log.w(Config.LOGTAG,
                    "CognitoAccount: password recovered from secondary store for " + uuid
                            + " — primary store should be re-established on next successful login");
            return recovered;
        }

        // ── Step 3: both stores failed ─────────────────────────────────
        Log.e(Config.LOGTAG,
                "CognitoAccount: password unavailable from both stores for " + uuid
                        + " — user must re-authenticate");
        return null;
    }

    // ── Deletion ──────────────────────────────────────────────────────

    /**
     * Removes the credential from BOTH stores. Called only during explicit
     * account deletion or user-initiated logout.
     */
    public static void deleteAccountInfo(Context context) {
        // Primary
        try {
            SharedPreferences prefs =
                    EncryptedPreferencesHelper.getEncryptedSharedPreferences(context);
            prefs.edit().clear().apply();
        } catch (EncryptedPreferencesHelper.CredentialStoreCorruptedException e) {
            Log.w(Config.LOGTAG,
                    "CognitoAccount: primary store already corrupted during deleteAccountInfo", e);
        } catch (Exception e) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: error clearing primary store", e);
        }

        // Secondary
        KeystoreCredentialStore.removeAll(context.getApplicationContext());
    }

    /**
     * Removes a single stored credential id from both secure stores.
     */
    public static void deleteStoredCredential(Context context, String credentialId) {
        if (context == null || credentialId == null) {
            return;
        }

        try {
            SharedPreferences prefs =
                    EncryptedPreferencesHelper.getEncryptedSharedPreferences(context);
            prefs.edit().remove(credentialId).apply();
        } catch (EncryptedPreferencesHelper.CredentialStoreCorruptedException e) {
            Log.w(Config.LOGTAG,
                    "CognitoAccount: primary store corrupted while removing credential " + credentialId, e);
        } catch (Exception e) {
            Log.e(Config.LOGTAG,
                    "CognitoAccount: error removing credential from primary store " + credentialId, e);
        }

        KeystoreCredentialStore.remove(context.getApplicationContext(), credentialId);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String getUserName()     { return username; }
    public String getOrganization() { return organization; }
    public String getAccount()      { return account; }
    public void setPassword(final String password) {
        this.password = password;
        if (this.uuid != null && this.cacontext != null) {
            persistPassword(this.uuid, password, this.cacontext);
        }
    }

    /**
     * Returns the in-memory password if available (set via constructor or
     * {@link #fromCursor(Cursor, Context)}), otherwise attempts to load from
     * the two-store fallback chain.
     */
    public String getPassword() {
        if (password != null) return password;
        if (cacontext != null) {
            password = loadPassword(uuid, cacontext);
        }
        return password;
    }
}
