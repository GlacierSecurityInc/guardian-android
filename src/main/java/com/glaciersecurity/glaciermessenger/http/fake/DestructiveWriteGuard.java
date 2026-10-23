package com.glaciersecurity.glaciermessenger.http.fake;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Refuses the handful of Glacier API calls that cost money or destroy data, so a debug build can
 * be pointed at a real tenant without one stray tap doing something irreversible.
 *
 * <p>This is the inverse of {@link FakeGlacierBackend}: that one answers everything and reaches
 * nothing, this one passes everything through <em>except</em> a short denylist. They are mutually
 * exclusive in practice — you want the fake when you have no backend, and this when you have a
 * real one you must not damage.
 *
 * <pre>{@code
 * ./gradlew installPlaystoreDebug -PglacierProdWriteGuard=true
 * }</pre>
 *
 * <h2>Why this exists rather than "be careful"</h2>
 * The phone module's drawer puts <em>Add Number</em> and <em>Release Number</em> two rows apart,
 * and both are one tap from a confirmation whose default is the affirmative. Exploring that UI on
 * a tenant with real numbers means one misread coordinate releases a number somebody is using, or
 * buys one nobody asked for. Reviewing a test suite for destructive calls catches the calls the
 * suite makes deliberately; it does not catch the app making them because a person — or an
 * automated UI driver — touched the wrong thing.
 *
 * <p>Blocking at the HTTP layer catches both, because every path to the backend goes through the
 * one OkHttp client this installs into.
 *
 * <h2>What is blocked, and what is deliberately not</h2>
 * Blocked: buying and releasing numbers, deleting call history, wiping chat history, and setting
 * any voicemail to {@code deleted}. These either spend money or destroy something that cannot be
 * recreated from the app.
 *
 * <p>Not blocked: renaming a number, and marking voicemail read or unread. Those mutate, but they
 * are free and a person can undo them from the UI, so blocking them would make the guard too
 * annoying to leave on — and a guard that gets switched off is worse than a narrower one that
 * stays on.
 *
 * <p>Same two-flag gate as the rest of this package: {@code BuildConfig.DEBUG} cannot be true in
 * a store build and {@code PROD_WRITE_GUARD} is hardcoded false for the release build type, so
 * both fold at compile time and R8 removes this class from release builds.
 */
public final class DestructiveWriteGuard {

    /** Endpoints that spend money or destroy data, by last path segment. */
    private static final Set<String> BLOCKED = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    // Spends money, and the number is not necessarily reclaimable afterwards.
                    "purchaseTwilioNumber",
                    // Gives up a number somebody may be reachable on. Irreversible in practice:
                    // Twilio does not guarantee you can get the same number back.
                    "releaseTwilioNumber",
                    // Destroys call records the backend is the only copy of.
                    "deleteCallLog",
                    "remove-call-history",
                    // Wipes the account's server-side chat history.
                    "clearEjabberdChatHistory")));

    /** Endpoints blocked only when they carry a destructive status. */
    private static final Set<String> STATUS_SENSITIVE = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("updateVoicemailStatus", "updateAllVoicemailStatus")));

    private static final String DESTRUCTIVE_STATUS = "deleted";

    private DestructiveWriteGuard() {
        throw new IllegalStateException("Do not instantiate");
    }

    public static boolean isEnabled() {
        return BuildConfig.DEBUG && BuildConfig.PROD_WRITE_GUARD;
    }

    /** The interceptor, or null when this build did not ask for the guard. */
    public static Interceptor interceptorOrNull() {
        if (!isEnabled()) {
            return null;
        }
        Log.w(Config.LOGTAG,
                "DestructiveWriteGuard: ACTIVE. Buying and releasing numbers, deleting call or "
                        + "chat history, and deleting voicemail are blocked for this build.");
        return DestructiveWriteGuard::intercept;
    }

    private static Response intercept(final Interceptor.Chain chain) throws IOException {
        final Request request = chain.request();
        final String path = request.url().encodedPath();
        if (!FakeGlacierBackend.isGlacierApiPath(path)) {
            return chain.proceed(request);
        }
        final String reason = blockReason(request);
        if (reason != null) {
            throw refuse(FakeGlacierBackend.lastSegment(path), reason);
        }
        return chain.proceed(request);
    }

    /**
     * Why this request must be refused, or null to let it through. Split out of the interceptor
     * and free of the {@code BuildConfig} gate so the denylist is testable on the JVM — the gate
     * is false in every unit-test build, so a guard tested only through {@link #interceptorOrNull}
     * would be tested not at all.
     */
    static String blockReason(final Request request) {
        final String path = request.url().encodedPath();
        if (!FakeGlacierBackend.isGlacierApiPath(path)) {
            return null;
        }
        final String endpoint = FakeGlacierBackend.lastSegment(path);
        if (BLOCKED.contains(endpoint)) {
            return "it spends money or destroys data";
        }
        if (STATUS_SENSITIVE.contains(endpoint) && isDeletion(request)) {
            return "status=" + DESTRUCTIVE_STATUS + " destroys voicemail";
        }
        return null;
    }

    private static boolean isDeletion(final Request request) {
        final String status = FakeGlacierBackend.formField(request, "status");
        return status != null
                && DESTRUCTIVE_STATUS.equalsIgnoreCase(status.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Fails the call outright rather than answering a synthetic success.
     *
     * <p>A faked success would leave the UI showing a number as released while the backend still
     * held it, which is a worse lie than an error: the person testing would believe the operation
     * happened. An IOException surfaces as the app's ordinary network-failure path, which every
     * one of these call sites already handles.
     */
    private static IOException refuse(final String endpoint, final String because) {
        final String message = "DestructiveWriteGuard: refused " + endpoint + " because "
                + because + ". This build was assembled with -PglacierProdWriteGuard=true.";
        Log.w(Config.LOGTAG, message);
        return new IOException(message);
    }
}
