package com.glaciersecurity.glaciermessenger.crypto;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.CertificatePinner;

/**
 * The fail-closed contract of {@link GlacierCertificatePinner#build}: the <em>API</em> host
 * argument alone decides whether a pinning client may be built, and a resolvable Cloudflare host
 * can never stand in for it.
 *
 * <p>The hazard being guarded: if the tracking host were merged into a single combined set, an
 * environment where every PROD/DEV/STAGING_BASE failed to resolve but SEND_TRACKING_URL did would
 * produce a non-empty set holding only the tracking host — which would satisfy the guard and let
 * a release build run with every Glacier API host unpinned. Keeping the arguments separate
 * prevents that; these tests hold it in place.
 *
 * <p>Robolectric rather than plain JUnit: {@code build} calls {@code TextUtils.isEmpty} per host,
 * and against the mockable android.jar that answers the {@code returnDefaultValues} {@code false}
 * for <em>every</em> input. Real hosts would still be handled correctly by accident, but the
 * blank-host case would be unobservable.
 *
 * <p><strong>What cannot be asserted here.</strong> On the no-API-host path the production code
 * throws {@code IllegalStateException} in a release build and returns {@link
 * CertificatePinner#DEFAULT} in a debug one. Unit tests compile against the debug variant, where
 * {@code BuildConfig.DEBUG} is true, so only the debug half is reachable — the release throw is
 * out of reach of any JVM test. {@code DEFAULT} pins nothing, so asserting it is still the right
 * proxy: it says the tracking host bought no pinning on its own.
 */
@RunWith(RobolectricTestRunner.class)
public class GlacierCertificatePinnerTest {

    // RFC 2606 / RFC 6761 reserved names. Never a host this app actually talks to.
    private static final String API_HOST = "api.example.com";
    private static final String CLOUDFLARE_HOST = "tracking.example.net";

    private static Set<String> hosts(final String... values) {
        return new LinkedHashSet<>(java.util.Arrays.asList(values));
    }

    /** True when the pinner holds at least one pin for {@code host}. */
    private static boolean isPinned(final CertificatePinner pinner, final String host) {
        try {
            // check() returns silently when no pin is registered for the host, and gets as far as
            // validating the (empty) chain when one is — which cannot succeed.
            pinner.check(host, Collections.emptyList());
            return false;
        } catch (final SSLPeerUnverifiedException expected) {
            return true;
        }
    }

    @Test
    public void aCloudflareHostAloneDoesNotSatisfyTheGuard() {
        // The hazard, stated directly: API hosts unresolved, tracking host resolved.
        final CertificatePinner pinner = GlacierCertificatePinner.build(null, hosts(CLOUDFLARE_HOST));

        assertThat(pinner).isSameInstanceAs(CertificatePinner.DEFAULT);
        assertThat(isPinned(pinner, CLOUDFLARE_HOST)).isFalse();
    }

    @Test
    public void anEmptyApiHostSetIsTreatedTheSameAsNull() {
        final CertificatePinner pinner =
                GlacierCertificatePinner.build(Collections.emptySet(), hosts(CLOUDFLARE_HOST));

        assertThat(pinner).isSameInstanceAs(CertificatePinner.DEFAULT);
    }

    @Test
    public void bothHostKindsArePinnedWhenTheApiHostsResolve() {
        // Non-vacuity: without this the three DEFAULT assertions above would also pass against a
        // build() that disabled pinning unconditionally.
        final CertificatePinner pinner =
                GlacierCertificatePinner.build(hosts(API_HOST), hosts(CLOUDFLARE_HOST));

        assertThat(pinner).isNotSameInstanceAs(CertificatePinner.DEFAULT);
        assertThat(isPinned(pinner, API_HOST)).isTrue();
        assertThat(isPinned(pinner, CLOUDFLARE_HOST)).isTrue();
    }

    @Test
    public void theCloudflareArgumentIsOptional() {
        final CertificatePinner pinner = GlacierCertificatePinner.build(hosts(API_HOST), null);

        assertThat(pinner).isNotSameInstanceAs(CertificatePinner.DEFAULT);
        assertThat(isPinned(pinner, API_HOST)).isTrue();
        assertThat(isPinned(pinner, CLOUDFLARE_HOST)).isFalse();
    }

    @Test
    public void blankHostsAreSkippedRatherThanPinned() {
        final CertificatePinner pinner =
                GlacierCertificatePinner.build(hosts(API_HOST, ""), hosts("", CLOUDFLARE_HOST));

        assertThat(isPinned(pinner, API_HOST)).isTrue();
        assertThat(isPinned(pinner, CLOUDFLARE_HOST)).isTrue();
        assertThat(isPinned(pinner, "")).isFalse();
    }
}
