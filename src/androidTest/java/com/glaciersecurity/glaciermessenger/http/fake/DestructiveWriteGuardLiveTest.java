package com.glaciersecurity.glaciermessenger.http.fake;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Proves {@link DestructiveWriteGuard} is actually installed in the client the app uses.
 *
 * <p>{@code DestructiveWriteGuardTest} covers the denylist itself, but it exercises
 * {@code blockReason} directly — it says nothing about whether the interceptor was ever added to
 * {@code OkHttpClientProvider}'s chain. That wiring is the whole protection, and a refactor could
 * silently drop it while every unit test stayed green. This asserts the guard from the outside:
 * issue a real release request through the real client and require that it never reaches the
 * network.
 *
 * <p><strong>Why this is safe to run.</strong> It asks to release a number in
 * the range reserved for fiction ({@code 202-555-01xx}), which no tenant owns. If the guard were
 * missing, the request would reach the console and be refused there — the assertion would fail
 * and nothing would be released. The test cannot cause the harm it is testing for. That is
 * deliberate: a guard test that could destroy something when the guard is broken is not a test
 * anybody will run twice.
 *
 * <pre>{@code
 * adb -s <serial> shell am instrument -w \
 *   -e class com.glaciersecurity.glaciermessenger.http.fake.DestructiveWriteGuardLiveTest \
 *   com.glaciersecurity.glaciermessenger.test/androidx.test.runner.AndroidJUnitRunner
 * }</pre>
 *
 * <p><strong>The two blocking tests need no sign-in, deliberately.</strong> Every request here
 * is built without the JWT headers. {@link OkHttpClientProvider#buildJwtFormRequestBuilder}
 * fetches the Cognito token while <em>building</em> the request — before {@code newCall()} ever
 * reaches the interceptor chain this guard lives in — so on a signed-out device it blocks on the
 * reauthentication latch until it times out and the run <em>hangs rather than fails</em>
 * (measured: 25+ minutes sitting at 0/3). That dependency was incidental: {@link
 * DestructiveWriteGuard#blockReason} reads {@code encodedPath()} and never looks at a header, so
 * an unauthenticated request is refused identically. Dropping the headers makes every assertion
 * here about what the guard does rather than about who is signed in.
 *
 * <p>What makes that <em>sufficient</em> for the two blocking tests is interceptor order rather
 * than how the request was built. {@code OkHttpClientProvider.createClient} adds the guard
 * before the interceptor that applies the mobile-API headers, so a denylisted path is refused by
 * the guard and the chain stops — {@code addJwtHeaders} is never reached and no token is
 * fetched. They run anywhere the build flag is set, managed emulator included, in about a
 * millisecond each.
 *
 * <p><strong>{@link #aReadIsStillLetThrough} does not get that protection, and needs a signed-in
 * device.</strong> Its whole point is that the guard lets it <em>through</em> — so the chain
 * continues to the mobile-API interceptor, {@code shouldApplyMobileApiHeaders} returns true for
 * an {@code /api/mobile/} path on a Glacier host, and {@code addJwtHeaders} calls {@code
 * getCognitoTokens()} regardless of the fact that this class built the request without headers.
 * On a device whose session is in the credential-auth-state error class that ends in {@code
 * waitForValidSessionAfterReauthenticationSignal}, whose {@code waitLatch.await()} has <em>no
 * timeout</em> inside a {@code while (true)} — so it blocks until the user reauthenticates or
 * something cancels the wait. Building the request unauthenticated cannot avoid this; only
 * bypassing the auth interceptor could, and a production auth-bypass path is not worth having
 * for a test. So this one keeps its session requirement, and its {@code glacierLiveBackend=true}
 * gate means "a device that can reach the console <em>and</em> is signed in", not merely
 * reachable.
 *
 * <p>All three still need {@code -PglacierProdWriteGuard=true}: without the guard compiled in
 * there is nothing to verify, and the request would reach the console for real. That gate stays.
 */
public class DestructiveWriteGuardLiveTest {

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    /** TEST-NET for phone numbers: the block reserved for fiction. No tenant owns this. */
    private static final String UNOWNABLE_NUMBER = "+12025550199";

    /**
     * The opt-in gate. <strong>{@code @Before}, deliberately, not {@code @BeforeClass}</strong> —
     * see the class comment: an assumption thrown from a class-level hook is reported against the
     * class, so none of these three methods ever starts and AGP's device runner sees three fewer
     * results than it enumerated and fails the whole run. Thrown from here it is reported against
     * each method, and the three are reported as skipped. The message lands in that test's
     * {@code logcat-<class>-<method>.txt} beside the results XML; the XML's
     * {@code <skipped/>} itself is bare.
     */
    @Before
    public void requireAGuardedBuild() {
        // The gate first: it is a BuildConfig read and needs no client, so an unguarded build --
        // which is every ordinary run -- skips without touching any app state.
        assumeTrue(
                "this build was not assembled with -PglacierProdWriteGuard=true, so there is no "
                        + "guard to verify",
                DestructiveWriteGuard.isEnabled());
        OkHttpClientProvider.init(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test
    public void releasingANumberIsRefusedBeforeItLeavesTheProcess() throws IOException {
        try (Response response = execute(unauthenticatedPost("api/mobile/releaseTwilioNumber",
                "releaseNumber=" + enc(UNOWNABLE_NUMBER)))) {
            fail("the guard is not installed in OkHttpClientProvider's interceptor chain: the "
                    + "release request reached the network and came back " + response.code());
        } catch (final IOException expected) {
            assertTrue(
                    "an IOException was thrown, but not by the guard -- this may be an ordinary "
                            + "network failure rather than the protection working: "
                            + expected.getMessage(),
                    expected.getMessage() != null
                            && expected.getMessage().contains("DestructiveWriteGuard"));
        }
    }

    @Test
    public void buyingANumberIsRefusedBeforeItLeavesTheProcess() throws IOException {
        // The one that costs money. If this ever reaches the console, it buys something.
        try (Response response = execute(unauthenticatedPost("api/mobile/purchaseTwilioNumber",
                "purchaseNum=" + enc(UNOWNABLE_NUMBER)))) {
            fail("the guard is not installed: the purchase request reached the network and came "
                    + "back " + response.code());
        } catch (final IOException expected) {
            assertTrue(expected.getMessage() != null
                    && expected.getMessage().contains("DestructiveWriteGuard"));
        }
    }

    @Test
    public void aReadIsStillLetThrough() throws IOException {
        // A guard that blocked everything would pass the two tests above while making the build
        // useless. This is the other half of the contract: a path that is not on the denylist has
        // to reach the network.
        //
        // GET, not POST: is-live answers 404 to a POST, which looks like the guard blocking it.
        //
        // The assertion is "the guard did not refuse this", not "the backend said yes". Signed
        // out the console answers 401, and a 401 is still proof the request left the process --
        // which is the only thing this test is about. Asserting isSuccessful() here would have
        // made the test depend on a session it does not need, and would report a broken guard
        // when the truth was an unauthenticated device.
        //
        // Opt-in, unlike the two above, because this is the one test here that genuinely needs the
        // console reachable. A device that cannot resolve the host does not fail it -- it hangs.
        // OkHttp's callTimeout cannot cut a blocked InetAddress.getAllByName, which sits in native
        // code and is not interruptible, so the 60s timeout on the app's client never fires:
        // measured 20+ minutes on a managed emulator with no route, one socket opened and then
        // silence. A gate is the only thing that keeps that out of an unattended run.
        //
        // It also needs the device to be *signed in*, which the two tests above do not -- see the
        // class comment. Building the request without JWT headers does not keep the app's
        // mobile-API interceptor from fetching a token, because unlike a denylisted write this
        // request is not stopped by the guard first; on a bad session that fetch can block with
        // no timeout. The gate below covers both conditions rather than only reachability.
        final Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "opt in with -Pandroid.testInstrumentationRunnerArguments.glacierLiveBackend=true"
                        + " on a device that can actually reach the console and is signed in;"
                        + " without it this test would hang rather than fail",
                "true".equals(arguments.getString("glacierLiveBackend")));

        try (Response response = execute(unauthenticatedGet("api/mobile/is-live"))) {
            assertTrue("a read came back with no HTTP status at all", response.code() > 0);
        } catch (final IOException e) {
            final String message = e.getMessage() == null ? "" : e.getMessage();
            assertFalse("the guard refused a read, so it is blocking more than the denylist: "
                    + message, message.contains("DestructiveWriteGuard"));
            // Anything else means the device could not reach the console after all. That is no
            // evidence either way about the guard, so skip rather than report a pass -- a
            // vacuous pass here would hide the guard blocking everything.
            assumeTrue("no route to the console (" + message + "), so the pass-through half of "
                    + "the guard's contract could not be checked", false);
        }
    }

    /**
     * A POST built <em>without</em> the JWT headers — see the class comment. The guard matches on
     * the URL path alone, so this is refused exactly as an authenticated request would be, and it
     * cannot block on a token fetch the way {@code buildJwtFormRequestBuilder} does.
     */
    private static Request unauthenticatedPost(final String path, final String form) {
        return new Request.Builder()
                .url(Endpoints.url(path))
                .post(RequestBody.create(form, FORM))
                .build();
    }

    /** The read-side counterpart of {@link #unauthenticatedPost}. */
    private static Request unauthenticatedGet(final String path) {
        return new Request.Builder().url(Endpoints.url(path)).get().build();
    }

    /** Issues the request through the app's own client, which is where the guard is installed. */
    private static Response execute(final Request request) throws IOException {
        return OkHttpClientProvider.getOkHttpClient().newCall(request).execute();
    }

    private static String enc(final String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
