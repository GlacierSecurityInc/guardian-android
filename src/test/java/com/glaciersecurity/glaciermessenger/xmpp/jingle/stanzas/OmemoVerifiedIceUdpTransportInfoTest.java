package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import org.junit.Test;

/**
 * An OMEMO-verified call carries its DTLS fingerprint encrypted, so that the fingerprint which
 * authenticates the media path is itself authenticated by the OMEMO session rather than by the
 * XMPP server relaying it. The whole point is defeated if a plaintext fingerprint is also present:
 * a server in the middle could add one, and a client that reads the plaintext copy would verify
 * the media against a key the attacker chose while still showing the call as verified.
 *
 * <p>{@code ensureNoPlaintextFingerprint} is the assertion that stops that, and
 * {@code upgrade} decides which transports get treated as verified in the first place. Both are
 * covered here, including the case that looks like a mistake and is not: a transport already
 * carrying a plaintext fingerprint is returned <em>unchanged</em> rather than upgraded, so it is
 * never mistaken for a verified one.
 */
public class OmemoVerifiedIceUdpTransportInfoTest {

    private static IceUdpTransportInfo transportWithFingerprintIn(final String namespace) {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        element.setAttribute("ufrag", "abc");
        element.setAttribute("pwd", "secret");
        final Element fingerprint = element.addChild("fingerprint", namespace);
        fingerprint.setAttribute("setup", "actpass");
        fingerprint.setContent("AA:BB:CC");
        return IceUdpTransportInfo.upgrade(element);
    }

    private static IceUdpTransportInfo transportWithNoFingerprint() {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        element.setAttribute("ufrag", "abc");
        return IceUdpTransportInfo.upgrade(element);
    }

    // ---- the guard ----------------------------------------------------------------------

    @Test
    public void aPlaintextFingerprintOnAVerifiedTransportIsRefused() {
        // The attack: an encrypted fingerprint the peer authenticated, plus a plaintext one a
        // relay could have inserted. Reading the plaintext copy would verify the media against
        // the wrong key while the UI still says the call is verified.
        final OmemoVerifiedIceUdpTransportInfo verified = new OmemoVerifiedIceUdpTransportInfo();
        final Element fingerprint = verified.addChild("fingerprint", Namespace.JINGLE_APPS_DTLS);
        fingerprint.setContent("AA:BB:CC");

        assertThrows(IllegalStateException.class, verified::ensureNoPlaintextFingerprint);
    }

    @Test
    public void aVerifiedTransportWithNoPlaintextFingerprintPasses() {
        final OmemoVerifiedIceUdpTransportInfo verified = new OmemoVerifiedIceUdpTransportInfo();
        verified.addChild("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)
                .setContent("encrypted");

        verified.ensureNoPlaintextFingerprint();
    }

    @Test
    public void anEmptyVerifiedTransportPasses() {
        new OmemoVerifiedIceUdpTransportInfo().ensureNoPlaintextFingerprint();
    }

    @Test
    public void theGuardIgnoresAFingerprintInAnotherNamespace() {
        // It is specifically the DTLS namespace that matters -- the OMEMO-verified fingerprint
        // lives under its own and must not trip the check that exists to exclude the plaintext.
        final OmemoVerifiedIceUdpTransportInfo verified = new OmemoVerifiedIceUdpTransportInfo();
        verified.addChild("fingerprint", "urn:example:some:other:namespace");

        verified.ensureNoPlaintextFingerprint();
    }

    // ---- deciding what counts as verified ------------------------------------------------

    @Test
    public void aTransportWithAnOmemoFingerprintBecomesVerified() {
        final IceUdpTransportInfo upgraded =
                OmemoVerifiedIceUdpTransportInfo.upgradeIfOmemoVerified(
                        transportWithFingerprintIn(Namespace.OMEMO_DTLS_SRTP_VERIFICATION));

        assertThat(upgraded).isInstanceOf(OmemoVerifiedIceUdpTransportInfo.class);
    }

    @Test
    public void aTransportWithAPlaintextFingerprintIsLeftAlone() {
        // Deliberately *not* upgraded. A plaintext fingerprint is an ordinary unverified call, and
        // wrapping it in the verified type would be claiming a guarantee that does not hold.
        final IceUdpTransportInfo original =
                transportWithFingerprintIn(Namespace.JINGLE_APPS_DTLS);

        final IceUdpTransportInfo upgraded = OmemoVerifiedIceUdpTransportInfo.upgradeIfOmemoVerified(original);

        assertThat(upgraded).isSameInstanceAs(original);
        assertThat(upgraded).isNotInstanceOf(OmemoVerifiedIceUdpTransportInfo.class);
    }

    @Test
    public void aPlaintextFingerprintWinsOverAnOmemoOne() {
        // Both present. The plaintext check runs first, so the transport stays unverified rather
        // than being upgraded into a type whose own guard would then reject it.
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        element.addChild("fingerprint", Namespace.JINGLE_APPS_DTLS).setContent("plaintext");
        element.addChild("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)
                .setContent("encrypted");
        final IceUdpTransportInfo original = IceUdpTransportInfo.upgrade(element);

        assertThat(OmemoVerifiedIceUdpTransportInfo.upgradeIfOmemoVerified(original)).isSameInstanceAs(original);
    }

    @Test
    public void aTransportWithNoFingerprintAtAllIsLeftAlone() {
        final IceUdpTransportInfo original = transportWithNoFingerprint();

        assertThat(OmemoVerifiedIceUdpTransportInfo.upgradeIfOmemoVerified(original)).isSameInstanceAs(original);
    }

    @Test
    public void upgradingCarriesTheCredentialsAndChildrenAcross() {
        final IceUdpTransportInfo upgraded =
                OmemoVerifiedIceUdpTransportInfo.upgradeIfOmemoVerified(
                        transportWithFingerprintIn(Namespace.OMEMO_DTLS_SRTP_VERIFICATION));

        assertThat(upgraded.getCredentials())
                .isEqualTo(new IceUdpTransportInfo.Credentials("abc", "secret"));
        assertThat(upgraded.findChild("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION))
                .isNotNull();
    }

    @Test
    public void anUpgradedTransportPassesItsOwnGuard() {
        // The two halves agreeing: anything upgrade() decides is verified must be something
        // ensureNoPlaintextFingerprint accepts, or the type could not be used.
        final OmemoVerifiedIceUdpTransportInfo upgraded =
                (OmemoVerifiedIceUdpTransportInfo)
                        OmemoVerifiedIceUdpTransportInfo.upgradeIfOmemoVerified(
                                transportWithFingerprintIn(
                                        Namespace.OMEMO_DTLS_SRTP_VERIFICATION));

        upgraded.ensureNoPlaintextFingerprint();
    }
}
