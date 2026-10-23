package com.glaciersecurity.glaciermessenger.ui.journey.support;

import android.os.SystemClock;

import java.util.concurrent.Callable;

/**
 * Bounded polling, for what Espresso's synchronisation cannot see: the app binds
 * {@code XmppConnectionService} and loads its conversations on a background executor, so the main
 * looper goes idle several times while the screen is still empty.
 *
 * <p>Polling rather than an {@code IdlingResource} because that would have to be fed from
 * {@code src/main}, which this repository keeps free of test hooks.
 */
public final class Wait {

    /** Long enough for a cold service bind on the slowest emulator in the fleet. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private static final long INTERVAL_MS = 250L;

    private Wait() {}

    public static void until(final String what, final Callable<Boolean> condition) {
        until(what, DEFAULT_TIMEOUT_MS, condition);
    }

    /**
     * An exception from {@code condition} counts as "not yet" -- these read view and service state
     * that legitimately does not exist yet. The last one is attached to the failure, so a
     * condition that is broken rather than merely early still says so.
     */
    public static void until(
            final String what, final long timeoutMs, final Callable<Boolean> condition) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        final long start = SystemClock.uptimeMillis();
        Throwable last = null;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
                last = null;
            } catch (final Throwable notYet) {
                last = notYet;
            }
            SystemClock.sleep(INTERVAL_MS);
        }
        final long waited = SystemClock.uptimeMillis() - start;
        throw new AssertionError(
                "timed out after " + waited + "ms waiting for: " + what, last);
    }
}
