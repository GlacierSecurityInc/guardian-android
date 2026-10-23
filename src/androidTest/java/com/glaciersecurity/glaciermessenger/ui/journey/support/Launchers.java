package com.glaciersecurity.glaciermessenger.ui.journey.support;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;

/**
 * Starting activities, and asserting that one started.
 *
 * <p>Uses {@link Instrumentation.ActivityMonitor} rather than {@code espresso-intents}. Two
 * reasons, and the second is the one that matters: it avoids adding a dependency to a build that
 * pins them deliberately, and it works for activities that finish immediately. {@code Intents}
 * records an outgoing intent, but the launcher trampoline calls {@code finish()} inside
 * {@code onCreate}, so anything that waits for it to settle waits forever -- a monitor simply
 * reports that the class was seen.
 */
public final class Launchers {

    /** Long enough to cover a cold start on the slowest emulator in the fleet. */
    private static final long TIMEOUT_MS = 30_000L;

    private Launchers() {}

    private static Instrumentation instrumentation() {
        return InstrumentationRegistry.getInstrumentation();
    }

    private static Context target() {
        return instrumentation().getTargetContext();
    }

    /** The intent the home-screen icon sends: the launcher trampoline, in its own task. */
    public static Intent launcherIntent() {
        final Intent intent =
                target().getPackageManager().getLaunchIntentForPackage(target().getPackageName());
        if (intent == null) {
            throw new AssertionError("the app under test declares no launcher intent");
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /**
     * Opens one conversation directly, using the app's own constants.
     *
     * <p>This is the deterministic way into {@code ConversationFragment}: the alternative is to
     * click a row, which depends on the list having loaded and on the row being on screen. Tests
     * that are about the chat screen itself should come in this way; the click path deserves its
     * own test rather than being a precondition of every other one.
     */
    public static Intent conversationIntent(final String conversationUuid) {
        return new Intent(target(), ConversationsActivity.class)
                .setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION)
                .putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversationUuid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /**
     * Starts {@code intent} and waits for {@code expected} to come to the foreground, returning it.
     *
     * <p>The monitor has to be registered before the intent is sent, or a fast activity can start
     * and be missed.
     */
    public static Activity startAndWaitFor(final Intent intent, final Class<?> expected) {
        final Instrumentation.ActivityMonitor monitor =
                instrumentation().addMonitor(expected.getName(), null, false);
        try {
            target().startActivity(intent);
            final Activity seen = monitor.waitForActivityWithTimeout(TIMEOUT_MS);
            if (seen == null) {
                throw new AssertionError(
                        expected.getSimpleName()
                                + " did not start within "
                                + TIMEOUT_MS
                                + "ms of sending "
                                + intent);
            }
            return seen;
        } finally {
            instrumentation().removeMonitor(monitor);
        }
    }

    /**
     * Sends {@code intent} and reports whether {@code expected} came to the foreground, without
     * failing if it did not. For asserting that a route goes somewhere else.
     */
    public static boolean startAndSee(final Intent intent, final Class<?> expected) {
        final Instrumentation.ActivityMonitor monitor =
                instrumentation().addMonitor(expected.getName(), null, false);
        try {
            target().startActivity(intent);
            return monitor.waitForActivityWithTimeout(TIMEOUT_MS) != null;
        } finally {
            instrumentation().removeMonitor(monitor);
        }
    }
}
