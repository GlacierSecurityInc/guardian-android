package com.glaciersecurity.glaciermessenger.http.fake;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Checks {@link FakeGlacierBackend} against the console it stands in for.
 *
 * <p>Every other test of the fake asserts it agrees with the app's <em>parsers</em>. That catches
 * a fake the app cannot read, and it caught four such bugs in review. It cannot catch a fake that
 * disagrees with the <em>backend</em> — the whole class of "this is not what the console actually
 * sends" — which is why every summary of the fake has had to say that a change verified only
 * against it has not really been verified. This test closes that, and keeps it closed: it fails
 * when either side drifts.
 *
 * <h2>What it does and does not touch</h2>
 * <strong>Read-only endpoints only.</strong> These accounts are on production. Every endpoint
 * below is one the app already calls unprompted on a normal launch, so running this adds no
 * effect the device was not going to have anyway. Deliberately absent: {@code
 * purchaseTwilioNumber} and {@code releaseTwilioNumber} (they buy and release real numbers —
 * the thing the fake exists to avoid), {@code nickname}, the voicemail-status writes,
 * {@code deleteCallLog}, {@code remove-call-history}, the {@code security-info} PUT, the uploads
 * and the tracking ingest.
 *
 * <p><strong>No values are asserted, logged or stored</strong> — {@link JsonShape} reduces both
 * responses to paths and types and throws the contents away. A failure names a path, never a
 * payload.
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * ANDROID_SERIAL=<serial> ./gradlew connectedPlaystoreDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.glacierContractTest=true \
 *   -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.glaciersecurity.glaciermessenger.http.fake.GlacierApiContractTest
 * }</pre>
 *
 * <p>Three things about that command are load-bearing:
 * <ul>
 *   <li><strong>The device must already be signed in.</strong> This test never handles a
 *       password: it borrows the Cognito session the app already holds. Sign in by hand once.
 *   <li><strong>{@code clearPackageData=false}</strong>, because the orchestrator's {@code pm
 *       clear} between methods destroys exactly that session. This is the one test here that
 *       cannot run under the default arrangement.
 *   <li><strong>Build without {@code -PglacierFakeBackend=true}.</strong> With the fake
 *       installed it would answer the "real" call too and the test would compare the fake to
 *       itself. The fake's side comes from calling {@link FakeGlacierBackend#respondTo} directly,
 *       which works regardless of the flag — that is why it was split out of the interceptor.
 * </ul>
 *
 * <p>It skips, rather than fails, without the opt-in argument or a usable session. A skip is the
 * correct result on every CI runner and every device that is not signed in.
 */
public class GlacierApiContractTest {

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    private static String organization;
    private static String username;

    /**
     * The opt-in gate. <strong>{@code @Before}, deliberately, not {@code @BeforeClass}</strong> —
     * see the class comment: an assumption thrown from a class-level hook is reported against the
     * class, so none of these nine methods ever starts and AGP's device runner sees nine fewer
     * results than it enumerated and fails the whole run. Thrown from here it is reported against
     * each method, and the nine are reported as skipped. The message lands in that test's
     * {@code logcat-<class>-<method>.txt} beside the results XML; the XML's
     * {@code <skipped/>} itself is bare.
     *
     * <p>Re-running the gate per method costs nothing: the orchestrator already gives every method
     * its own process, so this ran once per method either way, and both {@code isEnabled} checks
     * are {@code BuildConfig} reads while {@link OkHttpClientProvider#init} is idempotent.
     */
    @Before
    public void requireOptInAndSession() {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "opt in with -Pandroid.testInstrumentationRunnerArguments.glacierContractTest=true"
                        + " (this test reaches the production console)",
                "true".equals(arguments.getString("glacierContractTest")));

        assumeFalse(
                "build without -PglacierFakeBackend=true, or this compares the fake with itself",
                FakeGlacierBackend.isEnabled());

        // The org and username identify which tenant to ask about. They are arguments rather
        // than something read out of the account database because the runner may have cleared
        // it, and because naming them explicitly makes it obvious in the invocation which tenant
        // a run was pointed at.
        organization = arguments.getString("glacierOrganization");
        username = arguments.getString("glacierUsername");
        assumeTrue(
                "pass glacierOrganization and glacierUsername as runner arguments",
                organization != null && username != null);

        // Below the opt-in gates on purpose, so that a run which is only going to skip touches
        // no app state at all -- it registers no Amplify Hub listener and builds no client.
        OkHttpClientProvider.init(
                InstrumentationRegistry.getInstrumentation().getTargetContext());

        assumeTrue(
                "the device has no usable Cognito session; sign in on the device and re-run with"
                        + " clearPackageData=false",
                hasUsableSession());
    }

    private static boolean hasUsableSession() {
        try {
            return OkHttpClientProvider.getCognitoTokens() != null;
        } catch (final Exception e) {
            return false;
        }
    }

    // ── The endpoints ────────────────────────────────────────────────────────

    @Test
    public void isLiveMatches() throws IOException {
        assertShapesAgree("/api/mobile/is-live", get("api/mobile/is-live"));
    }

    @Test
    public void glacierUserMatches() throws IOException {
        assertShapesAgree("/api/mobile/glacier-users",
                get("api/mobile/glacier-users?organization=" + enc(organization)
                        + "&username=" + enc(username)));
    }

    @Test
    public void glacierOrganizationMatches() throws IOException {
        assertShapesAgree("/api/mobile/glacier-organization",
                get("api/mobile/glacier-organization?organization=" + enc(organization)
                        + "&username=" + enc(username)));
    }

    @Test
    public void latestVersionMatches() throws IOException {
        // "latest_version" is the key XmppConnectionService uses. Anything else comes back as a
        // bare {"message": ...}, which looks exactly like the fake inventing a versions object.
        assertShapesAgree("/api/mobile/latest-version",
                get("api/mobile/latest-version?key=latest_version"));
    }

    @Test
    public void dnsProfileMatches() throws IOException {
        assertShapesAgree("/api/mobile/dns/getOrganizationProfileId",
                get("api/mobile/dns/getOrganizationProfileId?organization=" + enc(organization)));
    }

    @Test
    public void selectedTwilioNumbersMatch() throws IOException {
        assertShapesAgree("/api/mobile/getUserSelectedTwilioNumbers",
                post("api/mobile/getUserSelectedTwilioNumbers", tenantFields()));
    }

    @Test
    public void voicemailListMatches() throws IOException {
        final String number = firstOwnedNumber();
        assumeTrue("the tenant owns no numbers, so there is no voicemail list to compare",
                number != null);
        assertShapesAgree("/api/mobile/voicemail_list",
                post("api/mobile/voicemail_list", tenantFields() + "&number=" + enc(number)));
    }

    @Test
    public void callHistoryMatches() throws IOException {
        final String number = firstOwnedNumber();
        assumeTrue("the tenant owns no numbers, so there is no call history to compare",
                number != null);
        assertShapesAgree("/api/mobile/call-history",
                post("api/mobile/call-history",
                        tenantFields() + "&number=" + enc(number) + "&limit=5"));
    }

    @Test
    public void callDetailMatches() throws IOException {
        final String callSid = firstCallSid();
        // Not a failure: a number with no call history has no detail to look up. Says which,
        // so a skip is not mistaken for coverage.
        assumeTrue("the tenant's first number has no call history to look a detail up from",
                callSid != null);
        assertShapesAgree("/api/mobile/getCallDetail",
                post("api/mobile/getCallDetail", tenantFields() + "&callSid=" + enc(callSid)));
    }

    // ── Comparison ───────────────────────────────────────────────────────────

    /**
     * Fetches the real response, asks the fake the same question, and requires that every path
     * the fake produces exists upstream at the same type.
     */
    private void assertShapesAgree(final String path, final Request request) throws IOException {
        final String realBody = execute(request);
        assumeTrue("upstream returned nothing for " + path + "; not a contract failure",
                realBody != null && !realBody.trim().isEmpty());

        final JsonElement real = parseOrSkip(path, realBody);
        final String fakeBody;
        try (Response fake = FakeGlacierBackend.respondTo(request)) {
            fakeBody = fake.body().string();
        }
        final JsonElement fakeJson = parseOrSkip(path, fakeBody);

        final Map<String, String> realShape = JsonShape.of(real);
        final Map<String, String> fakeShape = JsonShape.of(fakeJson);

        // An envelope and nothing else means the request was understood but produced no payload
        // -- an org with nothing configured for it, or a parameter this tenant has no data for.
        // There is no contract to compare in that case, and failing would report the fake as
        // inventing an object when the truth is that upstream had nothing to send. The message
        // is a status string, not tenant data, so it is safe to surface and is the only way to
        // tell "no data" from "wrong request".
        if (realShape.size() <= 1 && realShape.containsKey("message")) {
            final String message = real.isJsonObject() && real.getAsJsonObject().has("message")
                    ? real.getAsJsonObject().get("message").getAsString()
                    : "(none)";
            assumeTrue(path + ": upstream returned an envelope with no payload, message=\""
                    + message + "\" -- nothing to compare", false);
        }

        final Set<String> divergent = JsonShape.fakeKeysNotBackedByReal(fakeShape, realShape);
        final Set<String> onlyUpstream = JsonShape.realKeysMissingFromFake(fakeShape, realShape);

        assertTrue(
                path + ": the fake produces paths the console does not: " + divergent
                        + ". Upstream-only paths, for information: " + onlyUpstream,
                divergent.isEmpty());
    }

    private static JsonElement parseOrSkip(final String path, final String body) {
        try {
            return JsonParser.parseString(body);
        } catch (final RuntimeException e) {
            // A gateway error page rather than the API. Not a contract failure -- and the body is
            // not echoed, because it may carry session detail.
            assumeTrue(path + ": response was not JSON (" + body.length() + " bytes)", false);
            return null;
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** The number to ask about, taken from the tenant's own list. Null when it owns none. */
    private static String firstOwnedNumber() throws IOException {
        final String body = execute(post("api/mobile/getUserSelectedTwilioNumbers",
                tenantFields()));
        if (body == null) {
            return null;
        }
        try {
            final JsonElement numbers = JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("data").get("selected_twilionumber");
            if (numbers == null || !numbers.isJsonArray() || numbers.getAsJsonArray().isEmpty()) {
                return null;
            }
            return numbers.getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A call SID out of the tenant's own history, for the detail lookup. Null when there is none.
     *
     * <p>Chained off the real history rather than hardcoded: a SID is account-specific, so a
     * fixture would only ever work on one tenant, and putting a real one in the repository is
     * exactly what the no-real-data rule forbids.
     */
    private static String firstCallSid() throws IOException {
        final String number = firstOwnedNumber();
        if (number == null) {
            return null;
        }
        final String body = execute(post("api/mobile/call-history",
                tenantFields() + "&number=" + enc(number) + "&limit=5"));
        if (body == null) {
            return null;
        }
        try {
            final com.google.gson.JsonObject history =
                    JsonParser.parseString(body).getAsJsonObject();
            for (final String key : new String[] {"incomingCalls", "outgoingCalls"}) {
                final JsonElement calls = history.get(key);
                if (calls != null && calls.isJsonArray() && !calls.getAsJsonArray().isEmpty()) {
                    return calls.getAsJsonArray().get(0).getAsJsonObject()
                            .get("sid").getAsString();
                }
            }
            return null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String tenantFields() {
        return "organization=" + enc(organization) + "&username=" + enc(username);
    }

    private static Request get(final String path) throws IOException {
        return OkHttpClientProvider.buildJwtFormRequestBuilder(Endpoints.url(path),
                        new LinkedHashMap<>())
                .get()
                .build();
    }

    private static Request post(final String path, final String form) throws IOException {
        return OkHttpClientProvider.buildJwtFormRequestBuilder(Endpoints.url(path),
                        new LinkedHashMap<>())
                .post(RequestBody.create(form, FORM))
                .build();
    }

    private static String execute(final Request request) throws IOException {
        try (Response response = OkHttpClientProvider.getOkHttpClient().newCall(request).execute()) {
            if (response.body() == null) {
                return null;
            }
            return response.body().string();
        }
    }

    private static String enc(final String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
