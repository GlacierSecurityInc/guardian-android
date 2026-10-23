package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.database.Cursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.glaciersecurity.glaciermessenger.persistance.SqlCipherNative;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * That {@link CursorUtils#upgradeCursorWindowSize(Cursor)} still enlarges the window once the
 * cursor comes from SQLCipher.
 *
 * <p>This is the test the landmine needed. {@code CursorUtils} was two {@code instanceof} checks
 * against {@code android.database} types, and SQLCipher's cursors are an entirely parallel
 * hierarchy -- {@code net.zetetic.database.sqlcipher.SQLiteCursor} extends {@code
 * net.zetetic.database.AbstractWindowedCursor}, and its windows are {@code
 * net.zetetic.database.CursorWindow}. Neither check matches, nothing throws, and the only symptom is
 * that a conversation with large message bodies stops loading. A test that merely asserted "some
 * window is set" would have passed throughout, so this one reads the window size back.
 *
 * <p>Device-only: Robolectric ships a different SQLite and cannot load {@code libsqlcipher.so}.
 */
@RunWith(AndroidJUnit4.class)
public class CursorWindowSizeTest {

    private static final int FOUR_MB = 4 * 1024 * 1024;
    private static final int DEFAULT_WINDOW_BYTES = 2 * 1024 * 1024;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void loadNative() {
        SqlCipherNative.ensureLoaded();
    }

    /** Establishes the baseline: without the call, the window is the 2 MB default. */
    @Test
    public void aSqlCipherCursorStartsAtTheDefaultWindowSize() throws Exception {
        try (final Cursor cursor = sqlCipherCursor()) {
            assertThat(cursor.moveToFirst()).isTrue();
            final net.zetetic.database.CursorWindow window =
                    ((net.zetetic.database.AbstractWindowedCursor) cursor).getWindow();
            assertThat(window).isNotNull();
            assertWithMessage(
                            "baseline assumption: an untouched SQLCipher cursor uses the small"
                                    + " default window")
                    .that(window.getWindowSizeBytes())
                    .isAtMost(DEFAULT_WINDOW_BYTES);
        }
    }

    /**
     * The regression itself. Before the fix this failed, because neither {@code instanceof} matched
     * and the method returned having done nothing at all.
     */
    @Test
    public void upgradeEnlargesTheWindowOfASqlCipherCursor() throws Exception {
        try (final Cursor cursor = sqlCipherCursor()) {
            CursorUtils.upgradeCursorWindowSize(cursor);

            final net.zetetic.database.CursorWindow window =
                    ((net.zetetic.database.AbstractWindowedCursor) cursor).getWindow();
            assertWithMessage("SQLCipher cursors must get the enlarged window too")
                    .that(window.getWindowSizeBytes())
                    .isEqualTo(FOUR_MB);
        }
    }

    /**
     * The framework path must keep working -- the placeholder database still hands these out, and
     * adding the SQLCipher branch must not have displaced it.
     *
     * <p>Asserted by window identity rather than by size: {@code android.database.CursorWindow}
     * exposes no public size getter, so replacement of the window object is the only observable
     * signal that the branch ran.
     */
    @Test
    public void upgradeStillReplacesTheWindowOfAFrameworkCursor() throws Exception {
        final File file = new File(folder.getRoot(), "plain.db");
        final android.database.sqlite.SQLiteDatabase database =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            database.execSQL("CREATE TABLE t (v TEXT)");
            database.execSQL("INSERT INTO t (v) VALUES ('x')");
            try (final Cursor cursor = database.rawQuery("SELECT v FROM t", null)) {
                assertThat(cursor.moveToFirst()).isTrue();
                final android.database.AbstractWindowedCursor windowed =
                        (android.database.AbstractWindowedCursor) cursor;
                final android.database.CursorWindow before = windowed.getWindow();

                CursorUtils.upgradeCursorWindowSize(cursor);

                assertWithMessage("the framework branch must still install its own window")
                        .that(windowed.getWindow())
                        .isNotSameInstanceAs(before);
            }
        } finally {
            database.close();
        }
    }

    // A "read a 3 MB row" test used to live here and was removed deliberately: it passes with and
    // without the SQLCipher branch, because SQLite grows the window on its own to fit a single
    // oversized row. It looked like the strongest test in the file and discriminated nothing. The
    // window-size assertion above is the real guard.

    /** An unkeyed SQLCipher database, which is enough to produce a net.zetetic cursor. */
    private Cursor sqlCipherCursor() {
        final net.zetetic.database.sqlcipher.SQLiteDatabase database =
                net.zetetic.database.sqlcipher.SQLiteDatabase.create(null);
        database.execSQL("CREATE TABLE t (v TEXT)");
        database.execSQL("INSERT INTO t (v) VALUES ('x')");
        return database.rawQuery("SELECT v FROM t", null);
    }
}
