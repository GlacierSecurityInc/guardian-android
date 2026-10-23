package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.glaciersecurity.glaciermessenger.EndPointConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Every absolute URL in {@link EndPointConfig} must use a TLS-bearing scheme and must resolve to a
 * host that {@link OkHttpClientProvider} registered for certificate pinning.
 *
 * <h2>Why this test exists</h2>
 * <p>OkHttp's {@link okhttp3.CertificatePinner} pins per host and is silently inert for hosts
 * nobody listed, so an endpoint whose host was never registered produces no error, no warning and
 * no failing test. It can hide further when pins already in
 * {@link com.glaciersecurity.glaciermessenger.crypto.GlacierCertificatePinner} happen to match
 * that host's chain, which makes the file read as though it covered the endpoint.
 *
 * <p>The iOS app avoids this structurally:
 * {@code GlacierPinningConfiguration.makeServerTrustManager()} returns the same CA-pinning
 * evaluator for <em>every</em> host, so an endpoint cannot be added unpinned. Android cannot
 * copy that model — the client this pinner is attached to is also the messenger's general-purpose
 * HTTP client ({@code HttpConnectionManager} derives from it with {@code newBuilder()}, which
 * inherits the pinner) and it must keep reaching arbitrary third-party hosts for XEP-0363 file
 * transfer and for captcha URLs supplied by the XMPP server. A global "chain must contain a
 * Glacier CA" rule would reject all of those.
 *
 * <p>So this test ports the <em>property</em> instead of the mechanism: registration stays
 * per-host, but it cannot be forgotten. Adding an absolute URL to {@link EndPointConfig}
 * without pinning its host fails the build.
 *
 * <h2>Discovery is scheme-agnostic on purpose</h2>
 * <p>Constants are discovered by looking for <em>any</em> {@code scheme://} prefix, not by
 * matching {@code https://}. Searching for a known scheme would silently skip a {@code ws://},
 * {@code ftp://} or misspelled {@code htps://} endpoint — it would appear in no test, and the
 * bases already present would keep the non-vacuity check green while the new endpoint shipped
 * unpinned. Every discovered URL is then required to carry a TLS-bearing scheme, and every
 * TLS-bearing URL is required to be pinned.
 *
 * <h2>It does not read secrets</h2>
 * <p>{@code EndPointConfig} is gitignored and differs per checkout, and {@link
 * com.glaciersecurity.glaciermessenger.EndpointsTest} deliberately asserts nothing about the URLs
 * it holds. Neither does this test: it hardcodes no host and asserts a <em>relation</em> between
 * two sets both derived from that file at runtime. It therefore passes on any correctly
 * configured checkout and in CI, which writes the real file from
 * {@code secrets.ENDPOINT_CONFIG_JAVA} before running this tier.
 *
 * <p>Robolectric rather than plain JUnit for the same reason as {@code
 * GlacierCertificatePinnerTest}: the host-resolution path under test runs {@code
 * TextUtils.isEmpty} per URL, and the mockable android.jar answers the {@code
 * returnDefaultValues} {@code false} for every input. Real URLs would come out right by accident;
 * this way the code path is the real one.
 */
@RunWith(RobolectricTestRunner.class)
public class PinnedEndpointCoverageTest {

    /** {@code scheme://} — RFC 3986 scheme grammar, so a typo like {@code htps://} still matches. */
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*://.*");

    /**
     * Schemes whose connections carry TLS, and which OkHttp's pinner therefore applies to.
     *
     * <p>{@code wss} is here because a WebSocket over TLS on the shared client is pinned exactly
     * as {@code https} is; an endpoint added with that scheme should be held to the same rule
     * rather than rejected. Everything else — {@code http}, {@code ws}, {@code ftp}, {@code file}
     * and any misspelling — has nothing to pin.
     */
    private static final Set<String> TLS_SCHEMES =
            Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList("https", "wss")));

    /**
     * Hosts that are deliberately reachable without a pin. Empty, and it should stay that way.
     *
     * <p>This is the escape hatch for an endpoint that genuinely cannot be pinned — a third-party
     * host whose CA is outside Glacier's control and whose traffic carries no credential. Adding
     * an entry is a security decision, not a test fix: record <em>why</em> beside it, and satisfy
     * yourself that the request carries no {@code Authorization} header, because token capture
     * through an unpinned endpoint is a known step in privilege escalation. Deleting this test
     * instead of adding an entry here removes the only thing standing between a new endpoint and
     * shipping it unpinned.
     */
    private static final Set<String> DELIBERATELY_UNPINNED_HOSTS = Collections.emptySet();

    /** One absolute URL constant in {@link EndPointConfig}. */
    private static final class Endpoint {
        final String constant;
        final String scheme;
        final String host;

        Endpoint(final String constant, final String scheme, final String host) {
            this.constant = constant;
            this.scheme = scheme;
            this.host = host;
        }

        boolean isTls() {
            return TLS_SCHEMES.contains(scheme);
        }

        boolean isApiBase() {
            return constant.endsWith("_BASE");
        }

        @Override
        public String toString() {
            return constant + " (" + scheme + "://" + host + ")";
        }
    }

    /** Every absolute URL constant, whatever its scheme. */
    private static List<Endpoint> absoluteEndpoints() {
        final List<Endpoint> endpoints = new ArrayList<>();
        for (final Field field : EndPointConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            final String value;
            try {
                field.setAccessible(true);
                value = (String) field.get(null);
            } catch (final IllegalAccessException e) {
                throw new AssertionError("Could not read EndPointConfig." + field.getName(), e);
            }
            if (value == null || !ABSOLUTE_URL.matcher(value).matches()) {
                continue;
            }
            final URI uri;
            try {
                uri = new URI(value);
            } catch (final URISyntaxException e) {
                throw new AssertionError("EndPointConfig." + field.getName()
                        + " looks like an absolute URL but does not parse, so neither this test "
                        + "nor the app can tell which host it reaches.", e);
            }
            final String scheme = uri.getScheme();
            final String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                throw new AssertionError("EndPointConfig." + field.getName()
                        + " has no usable scheme or host, so it cannot be pinned.");
            }
            endpoints.add(new Endpoint(field.getName(),
                    scheme.toLowerCase(Locale.ROOT),
                    host.toLowerCase(Locale.ROOT)));
        }
        return endpoints;
    }

    /**
     * Reads a private host set out of {@link OkHttpClientProvider} by reflection.
     *
     * <p>Reflection rather than a {@code @VisibleForTesting} accessor so that production code
     * gains no API purely for this check. The field name is part of the contract being asserted:
     * if it is renamed, this test fails loudly and names the field it could not find, which is
     * the intended outcome — the rename should be a deliberate one.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> hostSet(final String fieldName) {
        try {
            final Field field = OkHttpClientProvider.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            final Set<String> value = (Set<String>) field.get(null);
            // Both resolvers return null rather than an empty set when nothing resolved.
            return value == null ? Collections.emptySet() : value;
        } catch (final NoSuchFieldException e) {
            throw new AssertionError(
                    "OkHttpClientProvider." + fieldName + " no longer exists. If the pinned host "
                            + "sets were renamed or restructured, update this test to match — do "
                            + "not delete it.", e);
        } catch (final IllegalAccessException e) {
            throw new AssertionError("Could not read OkHttpClientProvider." + fieldName, e);
        }
    }

    private static Set<String> apiHosts() {
        return hostSet("GLACIER_API_HOSTS");
    }

    private static Set<String> cloudflareHosts() {
        return hostSet("CLOUDFLARE_PINNED_HOSTS");
    }

    private static Set<String> allPinnedHosts() {
        final Set<String> all = new LinkedHashSet<>(apiHosts());
        all.addAll(cloudflareHosts());
        return all;
    }

    /**
     * The core coverage rule: every TLS endpoint's host is pinned.
     */
    @Test
    public void everyTlsEndpointUrlIsPinned() {
        final Set<String> pinned = allPinnedHosts();
        for (final Endpoint endpoint : absoluteEndpoints()) {
            if (!endpoint.isTls() || DELIBERATELY_UNPINNED_HOSTS.contains(endpoint.host)) {
                continue;
            }
            assertWithMessage(
                    "EndPointConfig." + endpoint.constant + " points at a host that no certificate "
                            + "pin covers. OkHttp pins per host and is silent for hosts nobody "
                            + "registered, so this endpoint would ship unpinned. Register it in "
                            + "OkHttpClientProvider.resolveGlacierApiHosts() (Glacier's own ALBs, "
                            + "Amazon Trust Services chain) or resolveCloudflarePinnedHosts() "
                            + "(Cloudflare-fronted, GTS + Let's Encrypt roots) — and check which "
                            + "CA it actually presents before choosing.")
                    .that(pinned)
                    .contains(endpoint.host);
        }
    }

    /**
     * No endpoint may use a scheme with nothing to pin.
     *
     * <p>Covers cleartext {@code http://} — which {@code base-config} in
     * {@code network_security_configuration.xml} refuses at request time anyway — and equally
     * {@code ws://}, {@code ftp://}, {@code file://} and any misspelled scheme, none of which
     * {@link #everyTlsEndpointUrlIsPinned} can protect.
     */
    @Test
    public void everyAbsoluteEndpointUrlUsesATlsScheme() {
        final Set<String> offenders = new TreeSet<>();
        for (final Endpoint endpoint : absoluteEndpoints()) {
            if (!endpoint.isTls()) {
                offenders.add(endpoint.toString());
            }
        }
        assertWithMessage(
                "These EndPointConfig constants use a scheme that carries no TLS, so there is "
                        + "nothing for the certificate pinner to apply to and the traffic is "
                        + "unprotected in transit. cleartextTrafficPermitted=\"false\" in "
                        + "network_security_configuration.xml will also refuse http:// at request "
                        + "time on device. Offenders: " + offenders)
                .that(offenders)
                .isEmpty();
    }

    /**
     * A coverage failure must not be "fixed" by moving an API base into the Cloudflare set.
     *
     * <p>The two sets are not interchangeable. {@code GLACIER_API_HOSTS} additionally gates JWT
     * attachment in {@code shouldApplyMobileApiHeaders} and is the set
     * {@code GlacierCertificatePinner.build} applies its fail-closed guard to, and the two carry
     * different CA lists — Amazon Trust Services for Glacier's own ALBs, GTS plus the Let's
     * Encrypt roots for Cloudflare. An API base filed under the Cloudflare set would lose its
     * bearer token and pin against CAs that never issue for it.
     *
     * <p>Membership in the API set is necessary but not sufficient: {@code build()} adds both pin
     * sets to one builder, and OkHttp treats multiple pins for a host as a union, so a base
     * appearing in <em>both</em> sets would quietly accept a Let's Encrypt chain for an
     * Amazon-backed API host. Absence from the Cloudflare set is asserted too.
     */
    @Test
    public void apiBasesArePinnedAsApiHostsAndNotAlsoAsCloudflareHosts() {
        final Set<String> apiHosts = apiHosts();
        final Set<String> cloudflareHosts = cloudflareHosts();
        for (final Endpoint endpoint : absoluteEndpoints()) {
            if (!endpoint.isApiBase()) {
                continue;
            }
            assertWithMessage(
                    "EndPointConfig." + endpoint.constant + " is an API base, so its host belongs "
                            + "in GLACIER_API_HOSTS. That set also decides which hosts receive the "
                            + "Authorization header and is the one the fail-closed guard checks, "
                            + "so a base registered anywhere else is pinned but unauthenticated.")
                    .that(apiHosts)
                    .contains(endpoint.host);
            assertWithMessage(
                    "EndPointConfig." + endpoint.constant + " is an API base but its host is also "
                            + "in CLOUDFLARE_PINNED_HOSTS. GlacierCertificatePinner.build() adds "
                            + "both pin sets to one builder and OkHttp accepts a host when ANY of "
                            + "its pins match, so this host would accept a Let's Encrypt chain — "
                            + "widening trust on an Amazon-backed API host, which is exactly what "
                            + "splitting the two sets exists to prevent.")
                    .that(cloudflareHosts)
                    .doesNotContain(endpoint.host);
        }
    }

    /**
     * Stated once, generally: a host in both sets receives the union of both pin sets.
     *
     * <p>{@link #apiBasesArePinnedAsApiHostsAndNotAlsoAsCloudflareHosts} catches the base-shaped
     * case at the point it matters and with a message naming the constant. This catches the
     * reverse — a Cloudflare-fronted host added to the API set, which would hand it the Amazon
     * pins and the JWT gate — and any future host that is neither.
     */
    @Test
    public void theTwoPinnedHostSetsAreDisjoint() {
        final Set<String> overlap = new TreeSet<>(apiHosts());
        overlap.retainAll(cloudflareHosts());
        assertWithMessage(
                "A host in both GLACIER_API_HOSTS and CLOUDFLARE_PINNED_HOSTS gets every pin from "
                        + "both sets, and OkHttp accepts the connection when any one of them "
                        + "matches. The split exists precisely so that the Let's Encrypt roots "
                        + "never apply to an Amazon-backed API host and the Amazon pins never "
                        + "apply to a Cloudflare-fronted one. Overlap: " + overlap)
                .that(overlap)
                .isEmpty();
    }

    /**
     * Guards against the whole class passing vacuously.
     *
     * <p>Every assertion above iterates a reflected collection, so a rename or refactor that made
     * either side come back empty would turn this file green while checking nothing. That is the
     * precise failure mode that would let an unpinned endpoint go unnoticed, so it gets its own
     * test.
     */
    @Test
    public void theReflectionFindsBothSidesOfTheComparison() {
        final List<Endpoint> endpoints = absoluteEndpoints();

        assertWithMessage("No absolute URL found in EndPointConfig — reflection is looking in the "
                + "wrong place, or the constants moved. Every other test here would pass "
                + "vacuously.")
                .that(endpoints)
                .isNotEmpty();

        assertWithMessage("No TLS-scheme endpoint found, so everyTlsEndpointUrlIsPinned checks "
                + "nothing.")
                .that(endpoints.stream().anyMatch(Endpoint::isTls))
                .isTrue();

        assertWithMessage("At least one EndPointConfig constant must be an API base ending in "
                + "_BASE for apiBasesArePinnedAsApiHostsAndNotAlsoAsCloudflareHosts to check "
                + "anything.")
                .that(endpoints.stream().anyMatch(Endpoint::isApiBase))
                .isTrue();

        assertThat(apiHosts()).isNotEmpty();
        assertThat(allPinnedHosts()).isNotEmpty();
    }
}
