package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/**
 * The UnifiedPush database, end to end against a real Keystore and the real native library.
 *
 * <p>This is the canary for the {@code history} migration: it proves the dependency resolves, the
 * native library loads, the Keystore-sealed passphrase round-trips, and a database this app creates
 * is genuinely unreadable on disk -- on a database whose entire contents are reproducible by a push
 * re-registration.
 *
 * <p><b>Why the teardown is not optional.</b> Tests run under the orchestrator with {@code
 * clearPackageData}, which wipes app storage between tests but leaves AndroidKeyStore entries alone,
 * because the system owns them. A passphrase left behind therefore outlives the database it belongs
 * to, and the next test starts with a key that decrypts nothing -- which shows up as a confusing
 * "cannot open database" rather than as the leak it is. Both the credential and the singleton are
 * reset at each end.
 */
@RunWith(AndroidJUnit4.class)
public class UnifiedPushDatabaseEncryptionTest {

    private static final String DATABASE_NAME = "unified-push-distributor-encrypted";
    private static final String LEGACY_DATABASE_NAME = "unified-push-distributor";

    // RFC 2606 reserves example.com precisely so fixtures never name a reachable host, and no real
    // account or endpoint belongs in a test database.
    private static final String ACCOUNT = "tester@example.com";
    private static final String TRANSPORT = "push.example.com";
    private static final String ENDPOINT = "https://push.example.com/UP/endpoint-1";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * {@code UnifiedPushDatabase} caches one helper for the process, and {@code GlacierGuardian}
     * may already have built it. Clearing the field is the only way for a test to observe its own
     * fixture rather than whatever startup left behind -- the same reflection trick
     * {@code DatabaseDowngradeGuardTest} uses on the downgrade statics.
     */
    private void resetSingleton() throws Exception {
        final Field field = UnifiedPushDatabase.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void deleteDatabaseFiles() {
        for (final String name : new String[] {DATABASE_NAME, LEGACY_DATABASE_NAME}) {
            final File database = context().getDatabasePath(name);
            for (final String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
                final File file = new File(database.getPath() + suffix);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    @Before
    public void reset() throws Exception {
        resetSingleton();
        deleteDatabaseFiles();
        KeystoreCredentialStore.remove(context(), DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
    }

    @After
    public void cleanUp() throws Exception {
        resetSingleton();
        deleteDatabaseFiles();
        KeystoreCredentialStore.remove(context(), DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
    }

    @Test
    public void aFreshDatabaseIsCreatedEncrypted() {
        final UnifiedPushDatabase database = UnifiedPushDatabase.getInstance(context());
        assertThat(database.register("com.example.app", "instance-1")).isTrue();

        final File file = context().getDatabasePath(DATABASE_NAME);
        assertThat(file.exists()).isTrue();
        assertWithMessage("the UnifiedPush database must not be plaintext SQLite on disk")
                .that(DatabaseFileHeader.classify(file))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }

    @Test
    public void rowsSurviveReopening() throws Exception {
        UnifiedPushDatabase.getInstance(context()).register("com.example.app", "instance-1");
        final long expiration = System.currentTimeMillis() + 7 * 86_400_000L;
        assertThat(
                        UnifiedPushDatabase.getInstance(context())
                                .updateEndpoint(
                                        "instance-1", ACCOUNT, TRANSPORT, ENDPOINT, expiration))
                .isTrue();

        // Drop the cached helper so the next call reopens the file from disk with a passphrase
        // fetched from Keystore, which is the path a cold process actually takes.
        resetSingleton();

        final UnifiedPushDatabase reopened = UnifiedPushDatabase.getInstance(context());
        final UnifiedPushDatabase.ApplicationEndpoint endpoint =
                reopened.getEndpoint(ACCOUNT, TRANSPORT, "instance-1");
        assertThat(endpoint).isNotNull();
        assertThat(endpoint.application).isEqualTo("com.example.app");
        assertThat(endpoint.endpoint).isEqualTo(ENDPOINT);
    }

    /** The passphrase must persist, not be regenerated per open -- a new key would orphan the file. */
    @Test
    public void thePassphraseIsStableAcrossOpens() throws Exception {
        UnifiedPushDatabase.getInstance(context()).register("com.example.app", "instance-1");
        final String first =
                KeystoreCredentialStore.retrieve(
                        context(), DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
        assertThat(first).isNotNull();
        assertThat(first).hasLength(64);

        resetSingleton();
        UnifiedPushDatabase.getInstance(context());

        assertThat(
                        KeystoreCredentialStore.retrieve(
                                context(), DatabasePassphrase.UNIFIED_PUSH_KEY_ID))
                .isEqualTo(first);
    }

    /**
     * The upgrade path every existing install takes exactly once.
     *
     * <p>The encrypted database has its own filename, so the legacy file is never in the way -- it
     * is deleted for hygiene. The property
     * that matters here is that both facts hold at once: the legacy file is gone, and the file the
     * app now uses is a different, encrypted one.
     */
    @Test
    public void aLegacyPlaintextDatabaseIsRemovedAndTheEncryptedOneIsASeparateFile()
            throws Exception {
        final File legacy = context().getDatabasePath(LEGACY_DATABASE_NAME);
        //noinspection ResultOfMethodCallIgnored
        legacy.getParentFile().mkdirs();

        final android.database.sqlite.SQLiteDatabase old =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacy, null);
        try {
            old.execSQL(
                    "CREATE TABLE push (account TEXT, transport TEXT, application TEXT NOT NULL,"
                            + " instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER"
                            + " DEFAULT 0)");
            old.execSQL("INSERT INTO push (application, instance) VALUES ('com.old.app', 'stale')");
        } finally {
            old.close();
        }
        assertThat(DatabaseFileHeader.classify(legacy))
                .isEqualTo(DatabaseFileHeader.Format.PLAINTEXT);

        resetSingleton();
        final UnifiedPushDatabase database = UnifiedPushDatabase.getInstance(context());
        assertThat(database.register("com.example.app", "instance-1")).isTrue();

        assertWithMessage("the legacy endpoint store must not be left on disk")
                .that(DatabaseFileHeader.classify(legacy))
                .isEqualTo(DatabaseFileHeader.Format.ABSENT);
        assertWithMessage("the database in use must be a separate, encrypted file")
                .that(DatabaseFileHeader.classify(context().getDatabasePath(DATABASE_NAME)))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
        assertWithMessage("rows from the discarded legacy database must not reappear")
                .that(database.getEndpoint(ACCOUNT, TRANSPORT, "stale"))
                .isNull();
    }

    /**
     * Downgrade, then upgrade back. The encrypted database must not survive an intervening old
     * build.
     *
     * <p>An older build registers and unregisters push targets in its own legacy database and
     * cannot see the encrypted one. If only the legacy file were deleted on the way back up, the
     * encrypted database would resume frozen at the moment of the downgrade: registrations cancelled
     * while downgraded would reappear and be renewed, and registrations made while downgraded would
     * be invisible. Both stores are discarded instead, so everything re-registers.
     */
    @Test
    public void anInterveningOlderBuildInvalidatesTheEncryptedDatabase() throws Exception {
        // Before the downgrade: a registration exists in the encrypted database.
        UnifiedPushDatabase.getInstance(context()).register("com.example.app", "cancelled-later");
        final long expiration = System.currentTimeMillis() + 7 * 86_400_000L;
        UnifiedPushDatabase.getInstance(context())
                .updateEndpoint("cancelled-later", ACCOUNT, TRANSPORT, ENDPOINT, expiration);
        assertThat(
                        UnifiedPushDatabase.getInstance(context())
                                .getEndpoint(ACCOUNT, TRANSPORT, "cancelled-later"))
                .isNotNull();

        // The downgrade: an older build runs and leaves its own legacy database behind.
        final File legacy = context().getDatabasePath(LEGACY_DATABASE_NAME);
        final android.database.sqlite.SQLiteDatabase asOldBuild =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacy, null);
        try {
            asOldBuild.execSQL(
                    "CREATE TABLE push (account TEXT, transport TEXT, application TEXT NOT NULL,"
                            + " instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER"
                            + " DEFAULT 0)");
        } finally {
            asOldBuild.close();
        }

        // The upgrade back.
        resetSingleton();
        final UnifiedPushDatabase database = UnifiedPushDatabase.getInstance(context());
        assertThat(database.register("com.example.app", "fresh")).isTrue();

        assertWithMessage(
                        "a registration from before the downgrade must not survive it -- the old"
                                + " build may have cancelled it where this database cannot see")
                .that(database.getEndpoint(ACCOUNT, TRANSPORT, "cancelled-later"))
                .isNull();
        assertWithMessage("and the legacy file must be gone too")
                .that(DatabaseFileHeader.classify(legacy))
                .isEqualTo(DatabaseFileHeader.Format.ABSENT);
    }

    /**
     * The reason for the separate filename: an older build must still be able to work after a
     * downgrade.
     *
     * <p>Simulated by doing what such a build does -- open {@code unified-push-distributor} with the
     * framework helper. It must succeed, which it only can because the encrypted database took a
     * different name. Encrypting in place would leave that open throwing "file is not a database"
     * with no path to recovery.
     */
    @Test
    public void anOlderBuildCanStillCreateItsOwnPlaintextDatabase() throws Exception {
        UnifiedPushDatabase.getInstance(context()).register("com.example.app", "instance-1");
        assertThat(DatabaseFileHeader.classify(context().getDatabasePath(DATABASE_NAME)))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);

        final File legacy = context().getDatabasePath(LEGACY_DATABASE_NAME);
        final android.database.sqlite.SQLiteDatabase asOldBuild =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacy, null);
        try {
            asOldBuild.execSQL(
                    "CREATE TABLE push (account TEXT, transport TEXT, application TEXT NOT NULL,"
                            + " instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER"
                            + " DEFAULT 0)");
            asOldBuild.execSQL(
                    "INSERT INTO push (application, instance) VALUES ('com.example.app', 'i')");
        } finally {
            asOldBuild.close();
        }
        assertWithMessage("a pre-encryption build must be able to use its own database after a downgrade")
                .that(DatabaseFileHeader.classify(legacy))
                .isEqualTo(DatabaseFileHeader.Format.PLAINTEXT);
    }

    /**
     * Losing the Keystore key is recoverable here, and only here: every row is re-established by the
     * push handshake. The same behaviour applied to {@code history} would be silent, total loss of
     * the user's messages, so this test also documents the boundary.
     */
    @Test
    public void aLostPassphraseRecreatesTheDatabaseRatherThanFailing() throws Exception {
        UnifiedPushDatabase.getInstance(context()).register("com.example.app", "instance-1");
        final File file = context().getDatabasePath(DATABASE_NAME);
        assertThat(file.exists()).isTrue();

        // Simulate a cleared Keystore: the stored ciphertext stays, the key behind it is gone, so
        // retrieve() returns null while hasStoredCredential() is still true.
        KeystoreCredentialStore.remove(context(), DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
        try (final FileOutputStream ignored = new FileOutputStream(file, true)) {
            // no-op; the file is left in place so the recreate path has something to discard
        }
        context()
                .getSharedPreferences("glacier_keystore_creds", Context.MODE_PRIVATE)
                .edit()
                .putString(
                        "glacier_cred_" + DatabasePassphrase.UNIFIED_PUSH_KEY_ID,
                        "not-decryptable")
                .commit();

        resetSingleton();
        final UnifiedPushDatabase database = UnifiedPushDatabase.getInstance(context());
        // Forces the lazy create; the old file was discarded along with the unusable key.
        assertThat(database.register("com.example.app", "instance-1")).isTrue();

        assertWithMessage("a database recreated after key loss must still be encrypted")
                .that(DatabaseFileHeader.classify(file))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }

    /**
     * The passphrase must be on disk before {@code require} hands it out, not merely queued for
     * writing.
     *
     * <p>{@code SharedPreferences.apply()} would satisfy a read-back from the in-memory cache while
     * the ciphertext was still unwritten. If the process then died after the database had been
     * encrypted, the next launch would find no stored credential, take the "never stored" branch,
     * and mint a different passphrase over a database it could never open again -- without even
     * reaching the key-loss path, which keys off the stored ciphertext being present. So the
     * property under test is specifically that the credential is readable through a fresh
     * SharedPreferences handle the moment {@code require} returns.
     */
    @Test
    public void thePassphraseIsOnDiskBeforeRequireReturns() {
        final String credentialId = "database_test_only_durability";
        try {
            DatabasePassphrase.require(context(), credentialId);

            assertWithMessage("require() must not return before the credential is persisted")
                    .that(KeystoreCredentialStore.hasStoredCredential(context(), credentialId))
                    .isTrue();
            assertThat(
                            context()
                                    .getSharedPreferences(
                                            "glacier_keystore_creds", Context.MODE_PRIVATE)
                                    .getString("glacier_cred_" + credentialId, null))
                    .isNotNull();
        } finally {
            KeystoreCredentialStore.remove(context(), credentialId);
        }
    }

    /**
     * {@link DatabasePassphrase#require} must never mint a replacement key while undecryptable
     * ciphertext is on disk. For {@code history} that refusal is the difference between an
     * explainable error and silently abandoning every message the user has.
     */
    @Test
    public void requireRefusesToReplaceAnUndecryptableKey() {
        final String credentialId = "database_test_only_key";
        try {
            context()
                    .getSharedPreferences("glacier_keystore_creds", Context.MODE_PRIVATE)
                    .edit()
                    .putString("glacier_cred_" + credentialId, "not-decryptable")
                    .commit();

            final DatabasePassphrase.UnavailableException thrown =
                    assertThrows(
                            DatabasePassphrase.UnavailableException.class,
                            () -> DatabasePassphrase.require(context(), credentialId));
            assertThat(thrown.reason).isEqualTo(DatabasePassphrase.Unavailable.KEY_LOST);
        } finally {
            KeystoreCredentialStore.remove(context(), credentialId);
        }
    }
}
