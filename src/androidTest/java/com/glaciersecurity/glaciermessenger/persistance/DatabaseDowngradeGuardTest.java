package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * The guard that stops an older build from crash-looping on a newer schema.
 *
 * <p>Installing 7.2.2 over 7.2.3 used to kill the app on every launch: {@code SQLiteOpenHelper}'s
 * default {@code onDowngrade} throws, and {@code GlacierGuardian.onCreate} touches the database
 * before any UI exists to explain why. {@link DatabaseBackend#isNewerThanApp} now reads the
 * version out of the file directly, before the helper can open it, and the app shows a dialog
 * instead. Real Play Store upgrades never go backwards, so this path is only ever reached by a
 * sideload -- which makes it exactly the kind of code that rots unnoticed.
 *
 * <p>Two things make this device-only. The check opens a real SQLite file read-only and reads its
 * {@code user_version} pragma, and its corrupt-file branch depends on how Android's own SQLite
 * build reacts to a file that is not a database at all -- Robolectric ships a different SQLite.
 *
 * <p>Rather than damage the app's real {@code history} file, each test points the check at a
 * scratch file through a {@link ContextWrapper}. {@code isNewerThanApp} resolves the path through
 * {@code context.getDatabasePath}, so that is all the redirection it takes.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseDowngradeGuardTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private File scratch;

    /** A well-formed 64-hex-digit passphrase. Literal rather than String.repeat, which needs
     * API 34 and this suite also runs on API 30. */
    private static final String SIXTY_FOUR_HEX =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /**
     * The result of the check is cached in static fields, and {@code GlacierGuardian.onCreate} has
     * already run it against the real database by the time any test starts. Reset them so each test
     * observes its own fixture rather than the latched startup answer.
     *
     * <p>Every static the guard gains has to be added here, or tests silently read the startup
     * answer instead of their own.
     */
    private static void resetDowngradeLatch() throws Exception {
        for (final String name :
                new String[] {"downgradeChecked", "databaseNewerThanApp", "databaseEncrypted"}) {
            final Field field = DatabaseBackend.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, false);
        }
        final Field onDisk = DatabaseBackend.class.getDeclaredField("onDiskVersion");
        onDisk.setAccessible(true);
        onDisk.setInt(null, 0);
    }

    @Before
    public void setUp() throws Exception {
        resetDowngradeLatch();
        scratch = new File(context.getCacheDir(), "downgrade-probe.db");
        if (scratch.exists() && !scratch.delete()) {
            throw new IllegalStateException("could not clear " + scratch);
        }
    }

    /** A context whose {@code getDatabasePath} always resolves to the scratch file. */
    private Context contextPointingAtScratch() {
        return new ContextWrapper(context) {
            @Override
            public File getDatabasePath(final String name) {
                return scratch;
            }
        };
    }

    private void writeDatabaseAtVersion(final int version) {
        final SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(scratch, null);
        db.setVersion(version);
        db.close();
    }

    @Test
    public void aDatabaseFromANewerBuildIsRefused() throws Exception {
        final int newer = DatabaseBackend.getAppDatabaseVersion() + 1;
        writeDatabaseAtVersion(newer);

        assertThat(DatabaseBackend.isNewerThanApp(contextPointingAtScratch())).isTrue();

        assertThat(DatabaseBackend.isDatabaseNewerThanApp()).isTrue();
        assertWithMessage("the version shown to the user in the 'app is older than your data' dialog")
                .that(DatabaseBackend.getOnDiskVersion())
                .isEqualTo(newer);
    }

    @Test
    public void aDatabaseAtThisBuildsVersionIsAccepted() throws Exception {
        writeDatabaseAtVersion(DatabaseBackend.getAppDatabaseVersion());

        assertThat(DatabaseBackend.isNewerThanApp(contextPointingAtScratch())).isFalse();
        assertThat(DatabaseBackend.isDatabaseNewerThanApp()).isFalse();
    }

    @Test
    public void anOlderDatabaseIsAcceptedSoThatOnUpgradeCanRun() throws Exception {
        writeDatabaseAtVersion(DatabaseBackend.getAppDatabaseVersion() - 1);

        assertWithMessage("an older schema must reach onUpgrade, not be refused")
                .that(DatabaseBackend.isNewerThanApp(contextPointingAtScratch()))
                .isFalse();
    }

    @Test
    public void aMissingDatabaseFileIsNotADowngrade() throws Exception {
        // First launch after install: nothing on disk, and the build is about to create it.
        assertThat(scratch.exists()).isFalse();

        assertThat(DatabaseBackend.isNewerThanApp(contextPointingAtScratch())).isFalse();
    }

    @Test
    public void anUnreadableFileIsNotReportedAsADowngrade() throws Exception {
        try (FileOutputStream out = new FileOutputStream(scratch)) {
            out.write("this is not a database".getBytes(StandardCharsets.UTF_8));
        }

        assertWithMessage("a corrupt file is a different failure and must not be mislabelled")
                .that(DatabaseBackend.isNewerThanApp(contextPointingAtScratch()))
                .isFalse();
    }

    @Test
    public void anUnreadableFileIsNotCachedAsAnAnswer() throws Exception {
        try (FileOutputStream out = new FileOutputStream(scratch)) {
            out.write("this is not a database".getBytes(StandardCharsets.UTF_8));
        }
        DatabaseBackend.isNewerThanApp(contextPointingAtScratch());

        // A transient lock or a mid-write file must be re-probed rather than latched into a
        // permanent "fine", so the second call has to see the real answer once the file is
        // readable again.
        scratch.delete();
        writeDatabaseAtVersion(DatabaseBackend.getAppDatabaseVersion() + 1);

        assertThat(DatabaseBackend.isNewerThanApp(contextPointingAtScratch())).isTrue();
    }

    /**
     * A file with no SQLite magic and no passphrase behind it is corruption, not ciphertext, and
     * must keep going to the real opener so the framework reports the actual error. This is the
     * behaviour the header check could most easily have broken: an encrypted database and a corrupt
     * one look identical from the first 16 bytes.
     */
    @Test
    public void anUnreadableFileWithNoPassphraseIsCorruptNotEncrypted() throws Exception {
        KeystoreCredentialStore.remove(context, DatabasePassphrase.HISTORY_KEY_ID);
        try (FileOutputStream out = new FileOutputStream(scratch)) {
            out.write("this is not a database".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(DatabaseBackend.accessState(contextPointingAtScratch()))
                .isEqualTo(DatabaseBackend.DatabaseAccess.UNREADABLE);
        assertThat(DatabaseBackend.isDatabaseEncrypted()).isFalse();
    }

    /**
     * The anti-brick case. Once a build has encrypted the database, an older build installed over it
     * finds bytes the framework opener cannot read. Without this it would be logged as "could not
     * read database version", answered false, handed to SQLiteOpenHelper, and crash-loop out of
     * {@code GlacierGuardian.onCreate} before any UI exists.
     */
    @Test
    public void anEncryptedFileIsReportedAsEncryptedWhenAPassphraseExists() throws Exception {
        try {
            KeystoreCredentialStore.store(
                    context, DatabasePassphrase.HISTORY_KEY_ID, SIXTY_FOUR_HEX);
            try (FileOutputStream out = new FileOutputStream(scratch)) {
                // SQLCipher encrypts page one in full, so the SQLite magic is absent.
                final byte[] ciphertext = new byte[512];
                new java.util.Random(7).nextBytes(ciphertext);
                out.write(ciphertext);
            }

            assertThat(DatabaseBackend.accessState(contextPointingAtScratch()))
                    .isEqualTo(DatabaseBackend.DatabaseAccess.ENCRYPTED);
            assertThat(DatabaseBackend.isDatabaseEncrypted()).isTrue();
            assertWithMessage("an encrypted database is not a downgrade")
                    .that(DatabaseBackend.isNewerThanApp(contextPointingAtScratch()))
                    .isFalse();
        } finally {
            KeystoreCredentialStore.remove(context, DatabasePassphrase.HISTORY_KEY_ID);
        }
    }

    /**
     * Encryption is not latched the way the schema version is: a migration can convert the file
     * while the process runs, so the answer has to be re-read rather than cached.
     */
    @Test
    public void theEncryptedAnswerIsNotCached() throws Exception {
        try {
            KeystoreCredentialStore.store(
                    context, DatabasePassphrase.HISTORY_KEY_ID, SIXTY_FOUR_HEX);
            try (FileOutputStream out = new FileOutputStream(scratch)) {
                out.write(new byte[512]);
            }
            assertThat(DatabaseBackend.accessState(contextPointingAtScratch()))
                    .isEqualTo(DatabaseBackend.DatabaseAccess.ENCRYPTED);

            scratch.delete();
            writeDatabaseAtVersion(DatabaseBackend.getAppDatabaseVersion());

            assertThat(DatabaseBackend.accessState(contextPointingAtScratch()))
                    .isEqualTo(DatabaseBackend.DatabaseAccess.OK);
        } finally {
            KeystoreCredentialStore.remove(context, DatabasePassphrase.HISTORY_KEY_ID);
        }
    }

    @Test
    public void theAnswerIsCachedOnceItIsDefinite() throws Exception {
        writeDatabaseAtVersion(DatabaseBackend.getAppDatabaseVersion());
        assertThat(DatabaseBackend.isNewerThanApp(contextPointingAtScratch())).isFalse();

        // Replace the file with a newer one. The cached negative must stand: the on-disk version
        // only ever moves through onUpgrade, and an install that would lower it replaces the
        // process, so re-probing on every getReadableDatabase() call would be pure overhead.
        scratch.delete();
        writeDatabaseAtVersion(DatabaseBackend.getAppDatabaseVersion() + 1);

        assertThat(DatabaseBackend.isNewerThanApp(contextPointingAtScratch())).isFalse();
    }
}
