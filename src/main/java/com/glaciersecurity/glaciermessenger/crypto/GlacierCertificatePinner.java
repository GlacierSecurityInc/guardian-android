package com.glaciersecurity.glaciermessenger.crypto;

import android.text.TextUtils;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

import java.util.Set;

import okhttp3.CertificatePinner;

/**
 * Builds the OkHttp {@link CertificatePinner} used for all Glacier backend connections.
 *
 * <h2>Why CA-level pinning?</h2>
 * <p>AWS ALB certificates managed by ACM are rotated automatically and without notice.
 * Pinning a leaf certificate would break connectivity on every rotation.  Pinning the
 * intermediate (or root) CA instead means the pin survives leaf rotation while still
 * blocking connections to any server that cannot present a chain rooted at a trusted CA.
 *
 * <h2>Pin sets</h2>
 *
 * <h3>Glacier REST API — AWS ALB (Amazon Trust Services)</h3>
 * <p>ACM-issued certificates chain through Amazon RSA 2048 M01 or M04 to Amazon Root CA 1.
 * All three are included so the app is resilient to Amazon rotating which intermediate it
 * uses on a given ALB.
 * <ul>
 *   <li>{@link #AMAZON_RSA_2048_M01} — primary ACM intermediate CA</li>
 *   <li>{@link #AMAZON_RSA_2048_M04} — alternate ACM intermediate CA</li>
 *   <li>{@link #AMAZON_ROOT_CA_1}    — Amazon root CA (last-resort fallback)</li>
 * </ul>
 *
 * <h3>Staging / dev consoles (Google Trust Services)</h3>
 * <p>The staging and dev console hosts present GTS-signed certificates, so both the GTS
 * intermediate and root are registered alongside the Amazon pins for every host in the set.
 * <ul>
 *   <li>{@link #GTS_INTERMEDIATE} — GTS intermediate CA (WE1)</li>
 *   <li>{@link #GTS_ROOT}         — GTS Root R4 fallback</li>
 * </ul>
 *
 * <h3>The tracking endpoint</h3>
 * <p>{@code EndPointConfig.SEND_TRACKING_URL} is on a different host from PROD/DEV/STAGING_BASE.
 * Because OkHttp applies pins only to hosts that were registered, the GTS pins below matching its
 * chain does not by itself mean it is covered; {@code OkHttpClientProvider.PINNED_HOSTS} adds it
 * explicitly.
 *
 * <p>It needs pinning because that request carries GPS coordinates, username, organization, a
 * device id and an {@code Authorization: Bearer} Cognito token, and token capture through an
 * unpinned endpoint is a known step in privilege escalation.
 *
 * <p>It is pinned to a <em>different</em> CA set from the Glacier API hosts, because a
 * Cloudflare-fronted host is not Glacier's to control: Universal SSL issues from more than one
 * CA family and moves between them without notice, so a GTS-only pin here would be a latent
 * outage. {@link #ISRG_ROOT_X1} and {@link #ISRG_ROOT_X2} cover the Let's Encrypt family
 * alongside the GTS pins, at <em>root</em> level so that Cloudflare's frequent intermediate
 * rotations do not break it. Those two roots are deliberately absent from the Amazon-backed API
 * hosts.
 *
 * <p>If Cloudflare ever moves to a third CA family, add that root here rather than unpinning.
 * Moving the host to a certificate Glacier controls would remove the problem outright.
 *
 * <h2>How OkHttp evaluates the pin set</h2>
 * <p>OkHttp accepts a connection when <em>any</em> certificate in the server's presented
 * chain matches <em>any</em> pin registered for that hostname.  Listing multiple pins is
 * therefore a union, not an intersection — adding a backup pin does not weaken the
 * primary pin; it ensures the app stays online if Amazon or Google rotate intermediates.
 *
 * <h2>Updating pins</h2>
 * <ol>
 *   <li>Add the new CA's SPKI SHA-256 hash as a new constant below.</li>
 *   <li>Wire it into {@link #build} alongside the existing pins.</li>
 *   <li>Ship the release and let it propagate before removing the old pin.</li>
 *   <li>Remove the old constant in the next release cycle.</li>
 * </ol>
 *
 * <h2>Verifying pins against a live endpoint</h2>
 * <pre>{@code
 * # Dump the chain your ALB presents and compute SPKI SHA-256 for each cert:
 * openssl s_client -connect <your-api-host>:443 -showcerts </dev/null 2>/dev/null \
 *   | awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' \
 *   | while IFS= read -r line; do
 *       if [[ "$line" == "-----BEGIN CERTIFICATE-----" ]]; then cert="$line"
 *       elif [[ "$line" == "-----END CERTIFICATE-----" ]]; then
 *           cert="$cert"$'\n'"$line"
 *           echo "$cert" \
 *             | openssl x509 -pubkey -noout \
 *             | openssl pkey -pubin -outform der \
 *             | openssl dgst -sha256 -binary \
 *             | base64
 *       else cert="$cert"$'\n'"$line"; fi
 *   done
 * }</pre>
 *
 * <p>OkHttp also logs the correct pin values in the {@link javax.net.ssl.SSLPeerUnverifiedException}
 * message when a mismatch occurs, which can be used to verify pins against a test build.
 */
public final class GlacierCertificatePinner {

    // The pin values in this public copy are PLACEHOLDERS and will not validate against any
    // real host. Replace each one with the SPKI SHA-256 hash of the CA named in its Javadoc
    // before shipping a build, and keep network_security_configuration.xml (main and debug)
    // in sync.

    // ── Amazon Trust Services — Glacier REST API (AWS ALB / ACM) ─────────────
    //
    // Source: https://www.amazontrust.com/repository/
    // These are SPKI SHA-256 hashes in OkHttp's "sha256/<base64>" format.

    /**
     * Amazon RSA 2048 M01 intermediate CA.
     * Primary intermediate used by ACM-issued certificates on AWS ALBs.
     */
    static final String AMAZON_RSA_2048_M01 =
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /**
     * Amazon RSA 2048 M04 intermediate CA.
     * Alternate intermediate; ACM may issue chains through this CA on some ALBs.
     */
    static final String AMAZON_RSA_2048_M04 =
            "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=";

    /**
     * Amazon Root CA 1.
     * Root of the Amazon Trust Services hierarchy — last-resort fallback if Amazon
     * introduces a new intermediate CA not yet listed here.
     */
    static final String AMAZON_ROOT_CA_1 =
            "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=";

    // ── Google Trust Services — staging / dev consoles ───────────────────────
    //
    // The staging and dev console hosts present GTS-signed certificates, so both
    // the Amazon and GTS pins are registered for every host in the set. OkHttp
    // accepts the connection when ANY pin matches ANY cert in the presented
    // chain, so adding GTS pins here does not weaken the Amazon pin set for
    // production traffic.

    /**
     * GTS intermediate CA (subject {@code CN=WE1}), used by the staging / dev console hosts.
     */
    static final String GTS_INTERMEDIATE =
            "sha256/DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD=";

    /**
     * GTS Root R4 — root fallback for the Google Trust Services hierarchy.
     */
    static final String GTS_ROOT =
            "sha256/EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE=";

    // ── Let's Encrypt (ISRG) — Cloudflare-fronted hosts only ─────────────────
    //
    // Cloudflare Universal SSL issues from more than one CA family and moves between
    // them without notice. Measured 2026-09-22: four of five sampled Cloudflare hosts
    // presented GTS WE1 -> GTS Root R4, one presented Let's Encrypt YE2 -> ISRG Root X2.
    // Pinning a Cloudflare host to GTS alone is therefore a latent outage.
    //
    // These are ROOTS, deliberately. Cloudflare rotates intermediates often (WE1/WE2,
    // E5/E6/YE2); the roots below have multi-decade lifetimes, so a root pin survives the
    // rotation that would break an intermediate pin. OkHttp's chain cleaner appends the
    // trust anchor before CertificatePinner runs, so a root pin does match.
    //
    // X1 is the RSA root, which Let's Encrypt no longer serves by default but still issues
    // under; X2 is the ECDSA root.
    //
    // NOT applied to the Amazon-backed Glacier API hosts -- see build().

    /** ISRG Root X1 — Let's Encrypt RSA root. */
    static final String ISRG_ROOT_X1 =
            "sha256/FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF=";

    /** ISRG Root X2 — Let's Encrypt ECDSA root. */
    static final String ISRG_ROOT_X2 =
            "sha256/GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG=";

    private GlacierCertificatePinner() {
        throw new IllegalStateException("Do not instantiate");
    }

    /**
     * Returns a {@link CertificatePinner} configured for the Glacier backend.
     *
     * <p>Both the Amazon Trust Services chain (production ALB) and the Google Trust
     * Services chain (staging / dev consoles) are pinned for each entry in
     * {@code glacierApiHosts}. That set comes from {@code PROD_BASE}/{@code DEV_BASE}/
     * {@code STAGING_BASE} only — the tracking host is deliberately absent and therefore
     * unpinned; see the class javadoc.
     *
     * <p>Debug builds invoked with {@code -PglacierInsecureTestBackend=true} skip pinning
     * entirely so instrumented fault-injection tests can reach a local MockWebServer. Release
     * builds hardcode that flag to false.
     *
     * <p>If the host set is empty — which can happen when
     * {@code EndPointConfig.PROD_BASE}/{@code DEV_BASE}/{@code STAGING_BASE} are
     * misconfigured — debug builds log and continue unpinned to preserve developer
     * workflow, while release builds throw and fail closed.
     *
     * @param glacierApiHosts hostnames extracted from endpoint bases,
     *                        e.g. {@code ["api.glacier.example.com", "staging-api.glacier.example.com"]}
     * @return a configured {@link CertificatePinner}; never {@code null}
     */
    public static CertificatePinner build(final Set<String> glacierApiHosts,
                                          final Set<String> cloudflareHosts) {
        // Fault-injection tests point the app at a local MockWebServer to exercise 500s,
        // truncated payloads and token-less rollback responses. No pin can match a throwaway
        // test certificate, so those runs -- and only those -- opt out here. Both halves of the
        // guard matter: ALLOW_INSECURE_TEST_BACKEND is hardcoded false for the release build
        // type, and BuildConfig.DEBUG cannot be true in a store build, so neither a mistyped
        // Gradle property nor an edit to this file alone can ship an unpinned release.
        if (BuildConfig.DEBUG && BuildConfig.ALLOW_INSECURE_TEST_BACKEND) {
            Log.w(Config.LOGTAG,
                    "GlacierCertificatePinner: pinning DISABLED for this debug build "
                            + "(-PglacierInsecureTestBackend=true). Never use this build against "
                            + "production.");
            return CertificatePinner.DEFAULT;
        }

        if (glacierApiHosts == null || glacierApiHosts.isEmpty()) {
            final String message =
                    "GlacierCertificatePinner: no valid Glacier API hosts were resolved from "
                            + "EndPointConfig.PROD_BASE/DEV_BASE/STAGING_BASE.";
            if (BuildConfig.DEBUG) {
                Log.w(Config.LOGTAG, message + " Certificate pinning is disabled for this debug build.");
                return CertificatePinner.DEFAULT;
            }
            throw new IllegalStateException(message + " Refusing to run release build without pinning.");
        }

        final CertificatePinner.Builder builder = new CertificatePinner.Builder();
        for (final String host : glacierApiHosts) {
            if (TextUtils.isEmpty(host)) {
                continue;
            }
            builder
                    .add(host, AMAZON_RSA_2048_M01)
                    .add(host, AMAZON_RSA_2048_M04)
                    .add(host, AMAZON_ROOT_CA_1)
                    .add(host, GTS_INTERMEDIATE)
                    .add(host, GTS_ROOT);
        }
        // Cloudflare-fronted hosts get their own, narrower set. The Let's Encrypt roots are
        // added ONLY here: Glacier controls the ACM chain on its own ALBs, so accepting an
        // ISRG-rooted certificate for an API host would widen that trust for no reason. The
        // Amazon pins are correspondingly absent here -- Cloudflare never issues from them.
        if (cloudflareHosts != null) {
            for (final String host : cloudflareHosts) {
                if (TextUtils.isEmpty(host)) {
                    continue;
                }
                builder
                        .add(host, GTS_INTERMEDIATE)
                        .add(host, GTS_ROOT)
                        .add(host, ISRG_ROOT_X1)
                        .add(host, ISRG_ROOT_X2);
            }
        }
        return builder.build();
    }
}
