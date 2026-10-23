package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import com.glaciersecurity.glaciermessenger.testutil.Elements;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

/**
 * IceUdpTransportInfo is where a call's network candidates cross the trust boundary: every
 * candidate line here was written by the remote peer (or by a server relaying for it) and is
 * parsed before any ICE connectivity check has established who that peer is. Two things follow,
 * and both are asserted below rather than assumed.
 *
 * <p>Reading, it has to reject rather than half-accept. A truncated candidate line, one that is
 * not a candidate at all, or one carrying a ufrag belonging to a different ICE session must come
 * back null -- the ufrag check in particular is what stops a candidate from an unrelated session
 * being spliced into this one.
 *
 * <p>Writing, {@code toSdpAttribute} puts attacker-influenced strings back into a line-oriented,
 * space-delimited format. A value containing a space would not corrupt some parser downstream, it
 * would silently become additional SDP parameters, so the whitespace guard is a real boundary and
 * is tested as one.
 *
 * <p>The round trip matters as much as either direction alone: a candidate parsed from the wire
 * and re-serialised is what the app hands to WebRTC, so parse and serialise are checked against
 * each other and not only against fixed strings.
 */
public class IceUdpTransportInfoTest {

    /** A complete host candidate, the shape libwebrtc emits. */
    private static final String HOST_CANDIDATE =
            "candidate:1 1 udp 2130706431 10.0.0.1 54321 typ host";

    private static IceUdpTransportInfo.Candidate candidate(final String sdp) {
        return IceUdpTransportInfo.Candidate.fromSdpAttribute(sdp, null);
    }

    /** Builds a Candidate directly, for cases a valid SDP line cannot express. */
    private static IceUdpTransportInfo.Candidate candidateWith(final String... attributes) {
        return IceUdpTransportInfo.Candidate.upgrade(
                Elements.withAttributes(new Element("candidate"), attributes));
    }

    // ---- parsing a candidate off the wire ----------------------------------------------

    @Test
    public void aHostCandidateIsParsedFieldByField() {
        final IceUdpTransportInfo.Candidate candidate = candidate(HOST_CANDIDATE);

        assertThat(candidate).isNotNull();
        assertThat(candidate.getFoundation()).isEqualTo(1);
        assertThat(candidate.getComponent()).isEqualTo(1);
        assertThat(candidate.getProtocol()).isEqualTo("udp");
        assertThat(candidate.getPriority()).isEqualTo(2130706431);
        assertThat(candidate.getIp()).isEqualTo("10.0.0.1");
        assertThat(candidate.getPort()).isEqualTo(54321);
        assertThat(candidate.getType()).isEqualTo("host");
    }

    @Test
    public void aParsedCandidateIsGivenAnId() {
        // The id is generated locally, not taken from the wire; two parses of the same line are
        // distinct candidates as far as the rest of the stack is concerned.
        assertThat(candidate(HOST_CANDIDATE).getId()).isNotEmpty();
        assertThat(candidate(HOST_CANDIDATE).getId())
                .isNotEqualTo(candidate(HOST_CANDIDATE).getId());
    }

    @Test
    public void theOptionalRelayFieldsAreParsedWhenPresent() {
        final IceUdpTransportInfo.Candidate candidate =
                candidate(
                        "candidate:4 1 udp 1694498815 192.0.2.3 45678 typ srflx raddr 10.0.0.1"
                                + " rport 54321 generation 0");

        assertThat(candidate.getType()).isEqualTo("srflx");
        assertThat(candidate.getRelAddr()).isEqualTo("10.0.0.1");
        assertThat(candidate.getRelPort()).isEqualTo(54321);
        assertThat(candidate.getGeneration()).isEqualTo(0);
    }

    @Test
    public void theProtocolIsNormalisedToLowerCase() {
        assertThat(candidate("candidate:1 1 UDP 2130706431 10.0.0.1 54321 typ host").getProtocol())
                .isEqualTo("udp");
    }

    @Test
    public void aLineThatIsNotACandidateIsRejected() {
        assertThat(candidate("fingerprint:sha-256 AA:BB")).isNull();
    }

    @Test
    public void aLineWithNoAttributeNameIsRejected() {
        assertThat(candidate("1 1 udp 2130706431 10.0.0.1 54321 typ host")).isNull();
    }

    @Test
    public void aTruncatedCandidateIsRejectedRatherThanPartiallyParsed() {
        // Five segments: everything up to the port is there and only the port is missing. The
        // tempting failure mode is to accept it with port 0.
        assertThat(candidate("candidate:1 1 udp 2130706431 10.0.0.1")).isNull();
    }

    @Test
    public void anEmptyCandidateValueIsRejected() {
        assertThat(candidate("candidate:")).isNull();
    }

    @Test
    public void aDanglingParameterKeyIsDroppedRatherThanThrowing() {
        // "typ" with no value. The parameter loop walks in pairs, so the bound has to stop one
        // short of the end; getting that wrong is an IndexOutOfBounds on a remote-controlled
        // string.
        final IceUdpTransportInfo.Candidate candidate =
                candidate("candidate:1 1 udp 2130706431 10.0.0.1 54321 typ");

        assertThat(candidate).isNotNull();
        assertThat(candidate.getType()).isNull();
    }

    // ---- the ufrag check ---------------------------------------------------------------

    @Test
    public void aCandidateTaggedWithAnotherSessionsUfragIsRejected() {
        // The security-relevant rejection: this candidate belongs to a different ICE session
        // and must not be adopted into the current one.
        assertThat(
                        IceUdpTransportInfo.Candidate.fromSdpAttribute(
                                HOST_CANDIDATE + " ufrag theirs", "ours"))
                .isNull();
    }

    @Test
    public void aCandidateTaggedWithTheCurrentUfragIsAccepted() {
        assertThat(
                        IceUdpTransportInfo.Candidate.fromSdpAttribute(
                                HOST_CANDIDATE + " ufrag ours", "ours"))
                .isNotNull();
    }

    @Test
    public void anUntaggedCandidateIsAcceptedAgainstAnyUfrag() {
        // A candidate carrying no ufrag is not attributable to another session, so it is taken.
        assertThat(IceUdpTransportInfo.Candidate.fromSdpAttribute(HOST_CANDIDATE, "ours"))
                .isNotNull();
    }

    // ---- serialising back out ----------------------------------------------------------

    @Test
    public void aCandidateSurvivesTheRoundTrip() {
        assertThat(candidate(HOST_CANDIDATE).toSdpAttribute(null)).isEqualTo(HOST_CANDIDATE);
    }

    @Test
    public void aRelayCandidateSurvivesTheRoundTrip() {
        final String sdp =
                "candidate:4 1 udp 1694498815 192.0.2.3 45678 typ srflx raddr 10.0.0.1 rport"
                        + " 54321 generation 0";

        assertThat(candidate(sdp).toSdpAttribute(null)).isEqualTo(sdp);
    }

    @Test
    public void theUfragIsAppendedWhenOneIsGiven() {
        assertThat(candidate(HOST_CANDIDATE).toSdpAttribute("ours"))
                .isEqualTo(HOST_CANDIDATE + " ufrag ours");
    }

    @Test
    public void aValueContainingWhitespaceIsRefusedRatherThanSplicedIn() {
        // The whole point of the guard: an ip of "10.0.0.1 typ relay" would otherwise serialise
        // into a line carrying an attacker-chosen extra parameter.
        final IceUdpTransportInfo.Candidate candidate =
                candidateWith(
                        "foundation", "1",
                        "component", "1",
                        "protocol", "udp",
                        "priority", "1",
                        "ip", "10.0.0.1 typ relay",
                        "port", "54321");

        assertThrows(IllegalArgumentException.class, () -> candidate.toSdpAttribute(null));
    }

    @Test
    public void everyRequiredFieldIsRefusedWhenMissing() {
        final String[] required = {"foundation", "component", "protocol", "priority", "ip", "port"};
        for (final String missing : required) {
            final Element element = new Element("candidate");
            element.setAttribute("foundation", "1");
            element.setAttribute("component", "1");
            element.setAttribute("protocol", "udp");
            element.setAttribute("priority", "1");
            element.setAttribute("ip", "10.0.0.1");
            element.setAttribute("port", "54321");
            element.removeAttribute(missing);

            final IceUdpTransportInfo.Candidate candidate =
                    IceUdpTransportInfo.Candidate.upgrade(element);
            assertThrows(
                    "expected a missing " + missing + " to be refused",
                    IllegalArgumentException.class,
                    () -> candidate.toSdpAttribute(null));
        }
    }

    @Test
    public void aNonUdpCandidateIsRefused() {
        // Only udp is supported; a tcp candidate must not be written out as though it were.
        final IceUdpTransportInfo.Candidate candidate =
                candidateWith(
                        "foundation", "1",
                        "component", "1",
                        "protocol", "tcp",
                        "priority", "1",
                        "ip", "10.0.0.1",
                        "port", "54321");

        assertThrows(IllegalArgumentException.class, () -> candidate.toSdpAttribute(null));
    }

    @Test
    public void aNonNumericFieldReadsAsZeroRatherThanThrowing() {
        // These getters are called on remote input, so they absorb garbage instead of
        // propagating a NumberFormatException up into the call setup path.
        final IceUdpTransportInfo.Candidate candidate =
                candidateWith("component", "not-a-number", "ip", "10.0.0.1");

        assertThat(candidate.getComponent()).isEqualTo(0);
        assertThat(candidate.getPort()).isEqualTo(0);
        assertThat(candidate.getNetwork()).isEqualTo(0);
    }

    // ---- the transport element itself --------------------------------------------------

    @Test
    public void aFreshTransportInfoIsAStub() {
        assertThat(new IceUdpTransportInfo().isStub()).isTrue();
        assertThat(IceUdpTransportInfo.STUB.isStub()).isTrue();
    }

    @Test
    public void aTransportInfoCarryingCredentialsIsNotAStub() {
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.setAttribute("ufrag", "abc");
        transportInfo.setAttribute("pwd", "secret");

        assertThat(transportInfo.isStub()).isFalse();
        assertThat(transportInfo.getCredentials())
                .isEqualTo(new IceUdpTransportInfo.Credentials("abc", "secret"));
    }

    @Test
    public void credentialsDoNotPrintTheirPassword() {
        // These end up in call-setup logs; the ufrag identifies the session, the password is
        // the shared secret that authenticates the ICE checks and must not be logged with it.
        final String printed =
                new IceUdpTransportInfo.Credentials("abc", "super-secret").toString();

        assertThat(printed).contains("abc");
        assertThat(printed).doesNotContain("super-secret");
    }

    @Test
    public void upgradeRejectsAnElementThatIsNotAnIceUdpTransport() {
        final Element wrongName = new Element("description", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        assertThrows(IllegalArgumentException.class, () -> IceUdpTransportInfo.upgrade(wrongName));

        final Element wrongNamespace = new Element("transport", "urn:xmpp:jingle:transports:s5b:1");
        assertThrows(
                IllegalArgumentException.class, () -> IceUdpTransportInfo.upgrade(wrongNamespace));
    }

    @Test
    public void upgradeCarriesAttributesAndChildrenAcross() {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        element.setAttribute("ufrag", "abc");
        element.setAttribute("pwd", "secret");
        element.addChild(new IceUdpTransportInfo.IceOption("trickle"));

        final IceUdpTransportInfo transportInfo = IceUdpTransportInfo.upgrade(element);

        assertThat(transportInfo.getCredentials().ufrag).isEqualTo("abc");
        assertThat(transportInfo.getIceOptions()).containsExactly("trickle");
    }

    @Test
    public void onlyWellKnownIceOptionsInTheRightNamespaceAreReported() {
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.addChild(new IceUdpTransportInfo.IceOption("trickle"));
        // Right namespace, unknown name.
        transportInfo.addChild(
                new Element("teleport", Namespace.JINGLE_TRANSPORT_ICE_OPTION));
        // Known name, wrong namespace -- must not be mistaken for an ice option.
        transportInfo.addChild(new Element("renomination", Namespace.JINGLE_APPS_DTLS));

        assertThat(transportInfo.getIceOptions()).containsExactly("trickle");
    }

    @Test
    public void candidatesAreReadBackOffTheTransport() {
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        assertThat(transportInfo.getCandidates()).isEmpty();

        final IceUdpTransportInfo withCandidates =
                transportInfo.withCandidates(ImmutableList.of(candidate(HOST_CANDIDATE)));

        assertThat(withCandidates.getCandidates()).hasSize(1);
        assertThat(withCandidates.getCandidates().get(0).getIp()).isEqualTo("10.0.0.1");
    }

    @Test
    public void cloneWrapperKeepsTheCredentialsAndDropsTheCandidates() {
        // Used to re-offer a transport without replaying the candidates already sent.
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.setAttribute("ufrag", "abc");
        transportInfo.setAttribute("pwd", "secret");
        transportInfo.addChild(candidate(HOST_CANDIDATE));

        final IceUdpTransportInfo wrapper = transportInfo.cloneWrapper();

        assertThat(wrapper.getCredentials()).isEqualTo(transportInfo.getCredentials());
        assertThat(wrapper.getCandidates()).isEmpty();
    }

    @Test
    public void cloneWrapperDoesNotShareAttributesWithTheOriginal() {
        // A shallow copy here would let a later credential change on the clone rewrite the
        // transport that was already put on the wire.
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.setAttribute("ufrag", "abc");

        final IceUdpTransportInfo wrapper = transportInfo.cloneWrapper();
        wrapper.setAttribute("ufrag", "changed");

        assertThat(transportInfo.getCredentials().ufrag).isEqualTo("abc");
    }

    // ---- fingerprint and setup ---------------------------------------------------------

    @Test
    public void thereIsNoFingerprintUntilOneIsAdded() {
        assertThat(new IceUdpTransportInfo().getFingerprint()).isNull();
    }

    @Test
    public void theDtlsFingerprintIsReadBackWithItsHashAndSetup() {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        final Element fingerprint = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
        fingerprint.setAttribute("hash", "sha-256");
        fingerprint.setAttribute("setup", "actpass");
        fingerprint.setContent("AA:BB:CC");
        element.addChild(fingerprint);

        final IceUdpTransportInfo.Fingerprint parsed =
                IceUdpTransportInfo.upgrade(element).getFingerprint();

        assertThat(parsed.getHash()).isEqualTo("sha-256");
        assertThat(parsed.getSetup()).isEqualTo(IceUdpTransportInfo.Setup.ACTPASS);
        assertThat(parsed.getContent()).isEqualTo("AA:BB:CC");
    }

    @Test
    public void aFingerprintWithNoSetupHasNoSetup() {
        final Element element = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
        assertThat(IceUdpTransportInfo.Fingerprint.upgrade(element).getSetup()).isNull();
    }

    @Test
    public void anUnrecognisedSetupIsNullRatherThanAnError() {
        assertThat(IceUdpTransportInfo.Setup.of("sideways")).isNull();
    }

    @Test
    public void theSetupRoleIsCaseInsensitive() {
        assertThat(IceUdpTransportInfo.Setup.of("actpass"))
                .isEqualTo(IceUdpTransportInfo.Setup.ACTPASS);
        assertThat(IceUdpTransportInfo.Setup.of("ACTIVE"))
                .isEqualTo(IceUdpTransportInfo.Setup.ACTIVE);
    }

    @Test
    public void theSetupRoleFlipsToItsOpposite() {
        assertThat(IceUdpTransportInfo.Setup.ACTIVE.flip())
                .isEqualTo(IceUdpTransportInfo.Setup.PASSIVE);
        assertThat(IceUdpTransportInfo.Setup.PASSIVE.flip())
                .isEqualTo(IceUdpTransportInfo.Setup.ACTIVE);
    }

    @Test
    public void actpassHasNoOpposite() {
        // actpass means "either role"; flipping it is a bug in the caller, not a no-op.
        assertThrows(IllegalStateException.class, IceUdpTransportInfo.Setup.ACTPASS::flip);
    }

    @Test
    public void modifyCredentialsReplacesTheCredentialsAndTheDtlsRole() {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        element.setAttribute("ufrag", "old");
        element.setAttribute("pwd", "old-secret");
        final Element fingerprint = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
        fingerprint.setAttribute("hash", "sha-256");
        fingerprint.setAttribute("setup", "actpass");
        fingerprint.setContent("AA:BB:CC");
        element.addChild(fingerprint);
        element.addChild(new IceUdpTransportInfo.IceOption("trickle"));

        final IceUdpTransportInfo modified =
                IceUdpTransportInfo.upgrade(element)
                        .modifyCredentials(
                                new IceUdpTransportInfo.Credentials("new", "new-secret"),
                                IceUdpTransportInfo.Setup.ACTIVE);

        assertThat(modified.getCredentials())
                .isEqualTo(new IceUdpTransportInfo.Credentials("new", "new-secret"));
        assertThat(modified.getFingerprint().getSetup())
                .isEqualTo(IceUdpTransportInfo.Setup.ACTIVE);
        // The fingerprint itself is the peer's key: the role changes, the hash must not.
        assertThat(modified.getFingerprint().getContent()).isEqualTo("AA:BB:CC");
        assertThat(modified.getIceOptions()).containsExactly("trickle");
    }

    @Test
    public void modifyCredentialsLeavesTheOriginalAlone() {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        element.setAttribute("ufrag", "old");
        element.setAttribute("pwd", "old-secret");
        final Element fingerprint = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
        fingerprint.setAttribute("setup", "actpass");
        element.addChild(fingerprint);

        final IceUdpTransportInfo original = IceUdpTransportInfo.upgrade(element);
        original.modifyCredentials(
                new IceUdpTransportInfo.Credentials("new", "new-secret"),
                IceUdpTransportInfo.Setup.ACTIVE);

        assertThat(original.getCredentials().ufrag).isEqualTo("old");
        assertThat(original.getFingerprint().getSetup())
                .isEqualTo(IceUdpTransportInfo.Setup.ACTPASS);
    }
}
