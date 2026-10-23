package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What the shipped SQLCipher native library can actually do, on a real device.
 *
 * <p>This is the gate for the whole encryption project, and it is here rather than in the JVM tier
 * because there is nothing to test on the JVM: {@code libsqlcipher.so} is an Android native, which is
 * exactly why {@code net.zetetic} is excluded from the unit-test runtime classpath in build.gradle.
 *
 * <p>The load-bearing assertion is {@link #ftsFourIsCompiledIn()}. {@code DatabaseBackend.onCreate}
 * builds {@code messages_index} as {@code USING fts4 (... content="messages",
 * tokenize='unicode61')}, so a SQLCipher build without FTS4 would fail on every fresh install, and
 * the only remedy would be compiling a custom amalgamation -- a different project. Everything else
 * here pins the API shape the migration depends on.
 */
@RunWith(AndroidJUnit4.class)
public class SqlCipherRuntimeTest {

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** sqlcipher-android loads no native library of its own; see {@link SqlCipherNative}. */
    @Before
    public void loadNative() {
        SqlCipherNative.ensureLoaded();
    }

    /** A valid raw key: 64 hex digits, which SQLCipher uses directly instead of deriving via PBKDF2. */
    private static final String KEY = "x'" + repeat("a1b2c3d4", 8) + "'";

    private static final String OTHER_KEY = "x'" + repeat("0f1e2d3c", 8) + "'";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private List<String> compileOptions(final SQLiteDatabase database) {
        final List<String> options = new ArrayList<>();
        try (final Cursor cursor = database.rawQuery("PRAGMA compile_options", null)) {
            while (cursor.moveToNext()) {
                options.add(cursor.getString(0));
            }
        }
        return options;
    }

    /**
     * The gate. Without FTS4 the existing schema cannot be created at all, so nothing else in the
     * migration matters.
     */
    @Test
    public void ftsFourIsCompiledIn() {
        final SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            final List<String> options = compileOptions(database);
            assertWithMessage(
                            "SQLCipher must ship FTS4 or DatabaseBackend.onCreate cannot run;"
                                    + " compile_options were "
                                    + options)
                    .that(options)
                    .contains("ENABLE_FTS4");
        } finally {
            database.close();
        }
    }

    /**
     * The real statement from {@code DatabaseBackend}, executed verbatim -- external-content FTS4 plus
     * the unicode61 tokenizer, which is a separate compile-time option from FTS4 itself.
     */
    @Test
    public void theRealMessageIndexStatementExecutes() {
        final SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            database.execSQL(
                    "CREATE TABLE messages (uuid TEXT PRIMARY KEY, body TEXT)");
            database.execSQL(
                    "CREATE VIRTUAL TABLE messages_index USING fts4 (uuid,body,notindexed=\"uuid\","
                            + "content=\"messages\",tokenize='unicode61')");
            database.execSQL(
                    "INSERT INTO messages (uuid, body) VALUES ('u1', 'the quick brown fox')");
            database.execSQL("INSERT INTO messages_index(messages_index) VALUES('rebuild')");

            try (final Cursor cursor =
                    database.rawQuery(
                            "SELECT uuid FROM messages_index WHERE messages_index.body MATCH ?",
                            new String[] {"brown"})) {
                assertWithMessage("external-content FTS4 search must find the indexed row")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                assertThat(cursor.moveToFirst()).isTrue();
                assertThat(cursor.getString(0)).isEqualTo("u1");
            }
        } finally {
            database.close();
        }
    }

    @Test
    public void cipherVersionIsReported() {
        final SQLiteDatabase database = SQLiteDatabase.create(null);
        try (final Cursor cursor = database.rawQuery("PRAGMA cipher_version", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertThat(cursor.getString(0)).isNotEmpty();
        } finally {
            database.close();
        }
    }

    /** A keyed database must survive being closed and reopened -- otherwise nothing persists. */
    @Test
    public void aKeyedDatabaseRoundTrips() throws Exception {
        final File file = new File(folder.getRoot(), "keyed.db");

        SQLiteDatabase database =
                SQLiteDatabase.openOrCreateDatabase(file.getPath(), KEY, null, null);
        try {
            database.execSQL("CREATE TABLE t (v TEXT)");
            database.execSQL("INSERT INTO t (v) VALUES ('hello')");
        } finally {
            database.close();
        }

        database = SQLiteDatabase.openOrCreateDatabase(file.getPath(), KEY, null, null);
        try (final Cursor cursor = database.rawQuery("SELECT v FROM t", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertThat(cursor.getString(0)).isEqualTo("hello");
        } finally {
            database.close();
        }
    }

    /**
     * The file on disk must not be readable without the key. This is the actual encryption claim, so it is
     * asserted rather than assumed -- both that the wrong key fails and that the plaintext SQLite
     * magic is absent from the header.
     */
    @Test
    public void anEncryptedFileIsNotReadableWithoutTheKey() throws Exception {
        final File file = new File(folder.getRoot(), "secret.db");

        final SQLiteDatabase database =
                SQLiteDatabase.openOrCreateDatabase(file.getPath(), KEY, null, null);
        try {
            database.execSQL("CREATE TABLE t (v TEXT)");
            database.execSQL("INSERT INTO t (v) VALUES ('sensitive')");
        } finally {
            database.close();
        }

        assertWithMessage("an encrypted database must not carry the plaintext SQLite header")
                .that(DatabaseFileHeader.classify(file))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);

        try {
            final SQLiteDatabase wrong =
                    SQLiteDatabase.openOrCreateDatabase(file.getPath(), OTHER_KEY, null, null);
            try (final Cursor cursor = wrong.rawQuery("SELECT v FROM t", null)) {
                cursor.moveToFirst();
                fail("opening with the wrong key must not succeed");
            } finally {
                wrong.close();
            }
        } catch (final Exception expected) {
            // SQLCipher reports this as a generic SQLite error; the type is not part of the contract.
        }
    }

    /**
     * A plaintext database must NOT open through SQLCipher. This is what makes the header sniff in
     * {@link DatabaseFileHeader} necessary rather than merely tidy: there is no graceful fallback, so
     * the format has to be known before the open is attempted.
     */
    @Test
    public void aPlaintextDatabaseDoesNotOpenWithAKey() throws Exception {
        final File file = new File(folder.getRoot(), "plain.db");

        final android.database.sqlite.SQLiteDatabase plain =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            plain.execSQL("CREATE TABLE t (v TEXT)");
        } finally {
            plain.close();
        }
        assertThat(DatabaseFileHeader.classify(file))
                .isEqualTo(DatabaseFileHeader.Format.PLAINTEXT);

        try {
            final SQLiteDatabase keyed =
                    SQLiteDatabase.openOrCreateDatabase(file.getPath(), KEY, null, null);
            try (final Cursor cursor = keyed.rawQuery("SELECT v FROM t", null)) {
                cursor.moveToFirst();
                fail("a plaintext database must not open with a key");
            } finally {
                keyed.close();
            }
        } catch (final Exception expected) {
            // Expected: SQLCipher cannot read an unencrypted file when a key is supplied.
        }
    }

    /**
     * Records the cursor hierarchy the migration has to account for. SQLCipher cursors are
     * {@code net.zetetic.database.AbstractWindowedCursor}, NOT {@code
     * android.database.AbstractWindowedCursor}, so both {@code instanceof} checks in {@code
     * CursorUtils} go false against them and the 4 MB cursor window is silently lost. Pinned here so
     * the fix for that has a test that fails without it.
     */
    @Test
    public void sqlCipherCursorsAreNotFrameworkWindowedCursors() {
        final SQLiteDatabase database = SQLiteDatabase.create(null);
        try {
            database.execSQL("CREATE TABLE t (v TEXT)");
            database.execSQL("INSERT INTO t (v) VALUES ('x')");
            try (final Cursor cursor = database.rawQuery("SELECT v FROM t", null)) {
                assertWithMessage("SQLCipher cursors still satisfy the android.database.Cursor API")
                        .that(cursor)
                        .isInstanceOf(Cursor.class);
                assertWithMessage(
                                "if this ever becomes true, CursorUtils' framework branch would start"
                                        + " working again and the zetetic branch could be dropped")
                        .that(cursor)
                        .isNotInstanceOf(android.database.AbstractWindowedCursor.class);
                assertThat(cursor).isInstanceOf(net.zetetic.database.AbstractWindowedCursor.class);
            }
        } finally {
            database.close();
        }
    }

    private static String repeat(final String unit, final int times) {
        final StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; ++i) {
            builder.append(unit);
        }
        return builder.toString();
    }
}
