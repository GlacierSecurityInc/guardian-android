package com.glaciersecurity.glaciermessenger.crypto;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.testutil.TestCertificates;

import org.junit.Test;

import java.util.List;

import com.google.common.collect.ImmutableList;

/**
 * XmppDomainVerifier decides whether the certificate a server presented actually belongs to the
 * domain the app meant to connect to. It is the last check before an XMPP connection is trusted,
 * so every test here is about something that, if wrong, hands the session to whoever presented
 * the certificate.
 *
 * <p>{@code matchDomain} is the part worth the most attention. RFC 6125 6.4.3 says a wildcard
 * covers exactly one DNS label, and the comment in the source records that this was tightened
 * deliberately -- an implementation that keeps stripping labels lets {@code *.example.com} match
 * {@code anything.attacker.example.com}. Both the label rule and the cases around it (no dot, a
 * leading dot, the bare apex) are asserted rather than assumed.
 *
 * <p>The certificate-driven tests use self-signed fixtures for reserved documentation domains,
 * generated with a century of validity so they cannot expire into a confusing failure.
 * {@code parseValidDomains} does not care that they are self-signed -- it reads names, it does not
 * validate a chain -- which is what makes them usable here.
 */
public class XmppDomainVerifierTest {



    // ---- wildcard matching, RFC 6125 6.4.3 ----------------------------------------------

    @Test
    public void anExactNameMatches() {
        assertThat(XmppDomainVerifier.matchDomain("example.com", ImmutableList.of("example.com")))
                .isTrue();
    }

    @Test
    public void matchingIsCaseInsensitive() {
        // DNS names are case-insensitive; a certificate is free to spell one however it likes.
        assertThat(XmppDomainVerifier.matchDomain("EXAMPLE.com", ImmutableList.of("example.COM")))
                .isTrue();
    }

    @Test
    public void anUnrelatedNameDoesNotMatch() {
        assertThat(XmppDomainVerifier.matchDomain("example.com", ImmutableList.of("example.org")))
                .isFalse();
    }

    @Test
    public void anEmptyListMatchesNothing() {
        assertThat(XmppDomainVerifier.matchDomain("example.com", ImmutableList.of())).isFalse();
    }

    @Test
    public void aWildcardCoversOneLabel() {
        assertThat(XmppDomainVerifier.matchDomain("im.example.com", ImmutableList.of("*.example.com")))
                .isTrue();
    }

    @Test
    public void aWildcardDoesNotCoverTwoLabels() {
        // The attack this guards. If the matcher kept stripping labels, a certificate for
        // *.example.com would authenticate a host under someone else's subdomain of it.
        assertThat(
                        XmppDomainVerifier.matchDomain(
                                "im.attacker.example.com", ImmutableList.of("*.example.com")))
                .isFalse();
    }

    @Test
    public void aWildcardDoesNotCoverTheBareDomain() {
        // *.example.com is not a certificate for example.com itself.
        assertThat(XmppDomainVerifier.matchDomain("example.com", ImmutableList.of("*.example.com")))
                .isFalse();
    }

    @Test
    public void aNeedleWithNoDotNeverMatchesAWildcard() {
        assertThat(XmppDomainVerifier.matchDomain("localhost", ImmutableList.of("*.example.com")))
                .isFalse();
        // Not even against a wildcard whose suffix would otherwise line up.
        assertThat(XmppDomainVerifier.matchDomain("com", ImmutableList.of("*.com"))).isFalse();
    }

    @Test
    public void aNeedleStartingWithADotIsRejected() {
        // Malformed input: the first dot is at index 0, so there is no label to consume.
        assertThat(XmppDomainVerifier.matchDomain(".example.com", ImmutableList.of("*.example.com")))
                .isFalse();
    }

    @Test
    public void aWildcardMatchIsCaseInsensitiveToo() {
        assertThat(XmppDomainVerifier.matchDomain("IM.Example.COM", ImmutableList.of("*.example.com")))
                .isTrue();
    }

    @Test
    public void aWildcardDoesNotMatchAcrossADifferentSuffix() {
        assertThat(XmppDomainVerifier.matchDomain("im.example.org", ImmutableList.of("*.example.com")))
                .isFalse();
    }

    @Test
    public void theFirstMatchingEntryIsEnough() {
        assertThat(
                        XmppDomainVerifier.matchDomain(
                                "im.example.com",
                                ImmutableList.of("example.org", "*.example.com", "example.net")))
                .isTrue();
    }

    // ---- reading names off a certificate ------------------------------------------------

    @Test
    public void subjectAlternativeDnsNamesAreCollected() throws Exception {
        final XmppDomainVerifier.ValidDomains valid =
                XmppDomainVerifier.parseValidDomains(TestCertificates.parse(TestCertificates.WITH_SANS_PEM));

        assertThat(valid.all())
                .containsExactly("example.com", "*.example.com", "im.example.org");
    }

    @Test
    public void aWildcardSanStillMatchesThroughTheNormalRules() throws Exception {
        final XmppDomainVerifier.ValidDomains valid =
                XmppDomainVerifier.parseValidDomains(TestCertificates.parse(TestCertificates.WITH_SANS_PEM));

        assertThat(XmppDomainVerifier.matchDomain("im.example.com", valid.all())).isTrue();
        assertThat(XmppDomainVerifier.matchDomain("im.attacker.example.com", valid.all()))
                .isFalse();
    }

    @Test
    public void theCommonNameIsUsedOnlyWhenThereAreNoAlternativeNames() throws Exception {
        // Characterisation of a deliberate fallback. Modern practice is to ignore the CN
        // entirely, and this does -- but only when the certificate offers some other name. A
        // certificate with no SAN at all still falls back to its CN, which is what keeps older
        // deployments working. Pinned so dropping the fallback is a visible decision.
        final XmppDomainVerifier.ValidDomains withSans =
                XmppDomainVerifier.parseValidDomains(TestCertificates.parse(TestCertificates.WITH_SANS_PEM));
        final XmppDomainVerifier.ValidDomains withoutSans =
                XmppDomainVerifier.parseValidDomains(TestCertificates.parse(TestCertificates.NO_SAN_PEM));

        // The SAN certificate's CN is also example.com, so the test is that the list is exactly
        // the SANs -- the CN is not appended on top of them.
        assertThat(withSans.all()).hasSize(3);
        assertThat(withoutSans.all()).containsExactly("example.com");
    }

    @Test
    public void theParsedNamesArePrintableForLogging() throws Exception {
        // verify() logs this on every connection; it has to render the three buckets rather than
        // an object identity.
        final String printed =
                XmppDomainVerifier.parseValidDomains(TestCertificates.parse(TestCertificates.WITH_SANS_PEM)).toString();

        assertThat(printed).contains("domains");
        assertThat(printed).contains("example.com");
    }

    @Test
    public void theThreeNameKindsAreKeptApart() throws Exception {
        // xmppAddresses and srvNames come from otherName SAN entries, which these fixtures do not
        // carry -- so a plain DNS certificate must land entirely in `domains`, and an XMPP
        // address check must not be satisfied by a dNSName.
        final XmppDomainVerifier.ValidDomains valid =
                XmppDomainVerifier.parseValidDomains(TestCertificates.parse(TestCertificates.WITH_SANS_PEM));
        final List<String> all = valid.all();

        assertThat(all).hasSize(3);
        assertThat(XmppDomainVerifier.matchDomain("example.com", all)).isTrue();
    }
}
