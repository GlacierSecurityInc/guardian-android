package com.glaciersecurity.glaciermessenger.crypto;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.testutil.TestCertificates;

import org.junit.Test;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * CombiningTrustManager is how the app trusts a certificate that the platform does not: it chains
 * its own trust managers in front of the system default and accepts a chain that any one of them
 * accepts. That makes it a deliberate widening of trust, and the property that keeps it safe is
 * that it widens and never narrows-to-nothing -- a chain no manager accepts still has to be
 * rejected, not fall off the end of the loop silently.
 *
 * <p>The fakes here accept or reject outright rather than carrying real certificates, because what
 * is under test is the combining logic, not JSSE's path validation. {@code combineWithDefault}
 * appends the real platform trust manager after them, so a chain the fakes reject is then judged
 * by the platform -- and an empty chain is something the platform always rejects, which is what
 * makes "everything refused" reachable without minting a certificate.
 *
 * <p>{@code checkClientTrusted} does not behave like {@code checkServerTrusted}, and the last test
 * pins that difference deliberately. See the comment there.
 */
public class CombiningTrustManagerTest {

    private static final X509Certificate[] EMPTY_CHAIN = new X509Certificate[0];


    /**
     * The chain the "everything rejects it" cases use. An empty array does not work: JSSE throws
     * IllegalArgumentException("null or zero-length certificate chain") before it validates
     * anything, and CombiningTrustManager only catches CertificateException -- so an empty chain
     * exercises the propagation of the wrong exception rather than the rejection path.
     */
    private static X509Certificate[] untrustedChain() throws CertificateException {
        return new X509Certificate[] {TestCertificates.parse(TestCertificates.NO_SAN_PEM)};
    }

    /** Accepts everything, and records whether it was consulted. */
    private static class AcceptingTrustManager implements X509TrustManager {
        int serverChecks = 0;

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {}

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
            serverChecks++;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return EMPTY_CHAIN;
        }
    }

    /** Rejects everything, and records whether it was consulted. */
    private static class RejectingTrustManager implements X509TrustManager {
        int serverChecks = 0;

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            throw new CertificateException("nope");
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            serverChecks++;
            throw new CertificateException("nope");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return EMPTY_CHAIN;
        }
    }

    @Test
    public void aChainOneManagerAcceptsIsTrusted() throws Exception {
        final AcceptingTrustManager accepting = new AcceptingTrustManager();
        final X509TrustManager combined = CombiningTrustManager.combineWithDefault(accepting);

        // No throw: the extra manager is exactly the point of this class.
        combined.checkServerTrusted(EMPTY_CHAIN, "RSA");

        assertThat(accepting.serverChecks).isEqualTo(1);
    }

    @Test
    public void aChainNoManagerAcceptsIsStillRejected() throws Exception {
        // The safety property. An empty chain is refused by the bundled managers and by the
        // platform default that combineWithDefault appends, so nothing is left to accept it --
        // and the loop has to propagate that rather than falling through.
        final RejectingTrustManager rejecting = new RejectingTrustManager();
        final X509TrustManager combined = CombiningTrustManager.combineWithDefault(rejecting);
        final X509Certificate[] chain = untrustedChain();

        assertThrows(
                CertificateException.class, () -> combined.checkServerTrusted(chain, "RSA"));
        assertThat(rejecting.serverChecks).isEqualTo(1);
    }

    @Test
    public void theFirstManagerToAcceptEndsTheSearch() throws Exception {
        // Ordering matters for cost, not just for correctness: the managers are consulted in
        // turn and the walk stops at the first acceptance rather than polling all of them.
        final AcceptingTrustManager first = new AcceptingTrustManager();
        final AcceptingTrustManager second = new AcceptingTrustManager();
        final X509TrustManager combined = CombiningTrustManager.combineWithDefault(first, second);

        combined.checkServerTrusted(EMPTY_CHAIN, "RSA");

        assertThat(first.serverChecks).isEqualTo(1);
        assertThat(second.serverChecks).isEqualTo(0);
    }

    @Test
    public void aRejectionFallsThroughToTheNextManager() throws Exception {
        final RejectingTrustManager rejecting = new RejectingTrustManager();
        final AcceptingTrustManager accepting = new AcceptingTrustManager();
        final X509TrustManager combined =
                CombiningTrustManager.combineWithDefault(rejecting, accepting);

        combined.checkServerTrusted(EMPTY_CHAIN, "RSA");

        assertThat(rejecting.serverChecks).isEqualTo(1);
        assertThat(accepting.serverChecks).isEqualTo(1);
    }

    @Test
    public void theAcceptedIssuersAreTheUnionOfEveryManagersIssuers() throws Exception {
        // Used by callers deciding what to present; dropping the platform's issuers here would
        // silently narrow what the app claims to trust.
        final X509TrustManager platformOnly = TrustManagers.createDefaultTrustManager();
        final X509TrustManager combined =
                CombiningTrustManager.combineWithDefault(new AcceptingTrustManager());

        final int platformIssuers = platformOnly.getAcceptedIssuers().length;

        assertThat(platformIssuers).isGreaterThan(0);
        // The fake contributes none, so the union is exactly the platform's set.
        assertThat(combined.getAcceptedIssuers()).hasLength(platformIssuers);
    }

    @Test
    public void theDefaultTrustManagerIsAlwaysAppended() throws Exception {
        // combineWithDefault is the only public way to build one, and the "default" half of its
        // name is load-bearing: without it, bundling one extra CA would replace the system
        // trust store rather than add to it.
        final RejectingTrustManager rejecting = new RejectingTrustManager();
        final X509TrustManager combined = CombiningTrustManager.combineWithDefault(rejecting);

        assertThat(combined.getAcceptedIssuers().length)
                .isEqualTo(TrustManagers.createDefaultTrustManager().getAcceptedIssuers().length);
    }

    @Test
    public void clientTrustIsDecidedByTheLastManagerAlone() throws Exception {
        // Characterisation, not a requirement. checkClientTrusted has no `return` on success, so
        // it keeps iterating after a manager accepts and only the final manager's verdict can
        // throw. An accepting manager in front of the platform default therefore does NOT make a
        // chain trusted, which is the opposite of checkServerTrusted's behaviour just above.
        //
        // Nothing on this line calls checkClientTrusted -- the app is the TLS client, and this
        // method is what a server would use -- so this is pinned rather than filed as a defect.
        // If it is ever fixed, this test is where the change shows up.
        final List<String> order = new ArrayList<>();
        final X509TrustManager accepting =
                new AcceptingTrustManager() {
                    @Override
                    public void checkClientTrusted(
                            final X509Certificate[] chain, final String authType) {
                        order.add("accepting");
                    }
                };
        final X509TrustManager combined = CombiningTrustManager.combineWithDefault(accepting);
        final X509Certificate[] chain = untrustedChain();

        assertThrows(
                CertificateException.class, () -> combined.checkClientTrusted(chain, "RSA"));
        assertThat(order).containsExactly("accepting");
    }

    @Test
    public void aNonCertificateFailureIsNotAbsorbedByTheFallthrough() throws Exception {
        // Only CertificateException is caught, so a manager that fails some other way stops the
        // walk instead of deferring to the platform default behind it. That is the safe
        // direction -- it refuses rather than silently widening trust -- but it does mean one
        // misbehaving bundled manager can refuse a chain the platform would have accepted.
        final X509TrustManager exploding =
                new AcceptingTrustManager() {
                    @Override
                    public void checkServerTrusted(
                            final X509Certificate[] chain, final String authType) {
                        throw new IllegalStateException("boom");
                    }
                };
        final X509TrustManager combined = CombiningTrustManager.combineWithDefault(exploding);
        final X509Certificate[] chain = untrustedChain();

        assertThrows(
                IllegalStateException.class, () -> combined.checkServerTrusted(chain, "RSA"));
    }
}
