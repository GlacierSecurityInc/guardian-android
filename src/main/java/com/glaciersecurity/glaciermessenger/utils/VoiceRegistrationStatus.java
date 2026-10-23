package com.glaciersecurity.glaciermessenger.utils;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether this device is currently registered to receive Twilio call invites, and if not, why.
 *
 * <p>Registration failing is the quietest failure in the app: the phone simply never rings.
 * There is no missed call, no notification and nothing for the user to notice until somebody
 * tells them a call went unanswered. Logging alone does not help either, because
 * {@code Config.logLevel} is 0 in a production build and the {@link Log} wrapper drops
 * everything.
 *
 * <p>So the outcome is recorded here instead, where it survives a release build and the Phone
 * module — or a bug report — can read it back.
 *
 * <p>Written from the FCM callback thread and the Twilio SDK's threads, read from the UI thread,
 * so state is published as an immutable snapshot swapped in atomically.
 */
public final class VoiceRegistrationStatus {

    /** Where a registration attempt gave up. Named so a bug report says something useful. */
    public enum Stage {
        /** The backend did not return a usable voice access token. */
        ACCESS_TOKEN,
        /** Firebase would not hand out an FCM token to register with. */
        FCM_TOKEN,
        /** Twilio rejected the registration itself. */
        TWILIO_REGISTER
    }

    /** An immutable snapshot of the last failure. */
    public static final class Failure {
        private final Stage stage;
        private final int errorCode;
        private final String message;
        private final long timestamp;

        Failure(final Stage stage, final int errorCode, final String message,
                final long timestamp) {
            this.stage = stage;
            this.errorCode = errorCode;
            this.message = message;
            this.timestamp = timestamp;
        }

        public Stage getStage() {
            return stage;
        }

        /** Provider error code, or 0 when the failure carried none. */
        public int getErrorCode() {
            return errorCode;
        }

        public String getMessage() {
            return message;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.US,
                    "%s failed: %d %s",
                    stage,
                    errorCode,
                    message == null ? "(no message)" : message);
        }
    }

    private final AtomicReference<Failure> lastFailure = new AtomicReference<>(null);
    private volatile boolean registered = false;

    /** Registration succeeded; the device can receive call invites. Clears any past failure. */
    public void recordRegistered() {
        registered = true;
        lastFailure.set(null);
    }

    /**
     * Registration did not complete. Recorded rather than thrown, because every caller is a
     * callback with nowhere to throw to.
     *
     * @param timestamp when the failure happened, supplied by the caller so this class stays
     *                  free of ambient state and its behaviour is testable
     */
    public void recordFailure(final Stage stage, final int errorCode, final String message,
                              final long timestamp) {
        registered = false;
        lastFailure.set(new Failure(stage, errorCode, message, timestamp));
    }

    /** True only after a registration that actually succeeded. */
    public boolean isRegistered() {
        return registered;
    }

    /** The last failure, or {@code null} if registration has never failed since it last worked. */
    public Failure getLastFailure() {
        return lastFailure.get();
    }

    /**
     * One line describing the current state, for a bug report or a diagnostics screen. Never
     * {@code null}.
     */
    public String describe() {
        if (registered) {
            return "registered for call invites";
        }
        final Failure failure = lastFailure.get();
        if (failure == null) {
            return "not registered for call invites yet";
        }
        return "not registered for call invites — " + failure;
    }
}
