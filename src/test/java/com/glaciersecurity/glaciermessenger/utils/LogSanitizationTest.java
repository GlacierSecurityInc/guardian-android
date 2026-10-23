package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.List;

/**
 * {@link Log} must not let a message forge extra logcat lines.
 *
 * <p>Several of this app's log messages interpolate strings it did not author — XMPP element and
 * namespace names straight off the wire, {@code Intent} extras handed to exported receivers — and
 * logcat is line-oriented, so an embedded newline would let a remote peer write whatever it liked
 * as an apparently separate, apparently app-authored entry (CWE-117).
 *
 * <p>These run on the Robolectric tier because the wrapper delegates to {@code android.util.Log},
 * which is a stub returning 0 against the mockable android.jar — a plain JUnit test could not see
 * what, if anything, was written. {@link ShadowLog} records the real arguments.
 */
@RunWith(RobolectricTestRunner.class)
public class LogSanitizationTest {

    private static final String TAG = "log-sanitization-test";

    private int originalLogLevel;

    /**
     * Sets {@link Log}'s level gate and returns what it was.
     *
     * <p>Writing {@code Config.logLevel} would not do it: {@code Log.logLevel} is a static field
     * initialised from {@code Config.logLevel} once, when the class is first loaded, so whether an
     * assignment lands depends on which test in the fork touched {@link Log} first. Reaching for
     * the field itself is deterministic.
     */
    private static int setLogLevel(final int level) {
        try {
            final Field field = Log.class.getDeclaredField("logLevel");
            field.setAccessible(true);
            final int previous = field.getInt(null);
            field.setInt(null, level);
            return previous;
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("Log.logLevel is no longer a private static int", e);
        }
    }

    @Before
    public void setUp() {
        // Shipped builds run at 0, where every wrapper method returns without logging at all.
        // Raise it so the sanitizer is actually exercised, and put it back afterwards — the field
        // is static and other tests in the same fork would inherit the change.
        originalLogLevel = setLogLevel(5);
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        setLogLevel(originalLogLevel);
        ShadowLog.clear();
    }

    private static String onlyMessage() {
        final List<ShadowLog.LogItem> logs = ShadowLog.getLogsForTag(TAG);
        assertThat(logs).hasSize(1);
        return logs.get(0).msg;
    }

    @Test
    public void newlinesInAMessageAreReplaced() {
        Log.d(TAG, "connected\nE/other: forged entry");
        assertThat(onlyMessage()).isEqualTo("connected E/other: forged entry");
    }

    @Test
    public void carriageReturnsAreReplaced() {
        Log.w(TAG, "a\r\nb");
        assertThat(onlyMessage()).isEqualTo("a  b");
    }

    @Test
    public void otherControlCharactersAreReplaced() {
        // Built from char values rather than written into the literal: a raw NUL, ESC or
        // DEL byte in the source makes git classify this whole file as binary, which costs
        // every diff, blame and textual merge on it.
        final String input = "a" + (char) 0x00 + "b" + (char) 0x1b + "c" + (char) 0x7f + "d\tE";
        Log.e(TAG, input);
        assertThat(onlyMessage()).isEqualTo("a b c d E");
    }

    @Test
    public void anOrdinaryMessageIsPassedThroughUnchanged() {
        final String message = "account@example.invalid: bound resource — 3 callbacks left";
        Log.i(TAG, message);
        assertThat(onlyMessage()).isEqualTo(message);
    }

    @Test
    public void everyLevelSanitizes() {
        Log.v(TAG, "v\nx");
        Log.d(TAG, "d\nx");
        Log.i(TAG, "i\nx");
        Log.w(TAG, "w\nx");
        Log.e(TAG, "e\nx");
        final List<ShadowLog.LogItem> logs = ShadowLog.getLogsForTag(TAG);
        assertThat(logs).hasSize(5);
        for (final ShadowLog.LogItem item : logs) {
            assertThat(item.msg).doesNotContain("\n");
        }
    }

    @Test
    public void theThrowableOverloadsSanitize() {
        Log.e(TAG, "failed\nW/fake: forged", new IllegalStateException("boom"));
        assertThat(onlyMessage()).isEqualTo("failed W/fake: forged");
    }

    /**
     * Only that {@code sanitize()} passes {@code null} straight through rather than dereferencing
     * it. It is deliberately not named for the wrapper being null-safe, because it is not: the
     * platform's {@code android_util_Log_println_native} rejects a null message with an NPE, and
     * ShadowLog does not model that. Do not read this as permission to log a nullable string.
     */
    @Test
    public void aNullMessageReachesTheLoggerInsteadOfFailingInSanitize() {
        Log.d(TAG, null);
        assertThat(ShadowLog.getLogsForTag(TAG)).hasSize(1);
    }

    @Test
    public void nothingIsLoggedAtTheShippedLogLevel() {
        setLogLevel(0);
        Log.e(TAG, "error\nforged");
        assertThat(ShadowLog.getLogsForTag(TAG)).isEmpty();
    }
}
