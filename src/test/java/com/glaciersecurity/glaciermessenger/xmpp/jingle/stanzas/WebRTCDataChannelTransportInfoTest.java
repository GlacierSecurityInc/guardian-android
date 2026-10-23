package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import org.junit.Test;

/**
 * The WebRTC data-channel transport wraps an ICE-UDP transport rather than replacing it: the ICE
 * candidates and DTLS fingerprint still live in a nested {@code <transport/>}, and this outer
 * element only adds the SCTP parameters the data channel needs on top.
 *
 * <p>That nesting is what the tests are about. Every accessor here reaches through to the inner
 * transport, so the question each one answers is what happens when it is not there -- a stanza
 * from a peer is free to omit it. Most degrade to null or an empty list; two do not, and those
 * are pinned as characterisation rather than quietly left.
 */
public class WebRTCDataChannelTransportInfoTest {

    private static final String CANDIDATE =
            "candidate:1 1 udp 2130706431 10.0.0.1 54321 typ host";

    private static WebRTCDataChannelTransportInfo withInnerTransport() {
        final WebRTCDataChannelTransportInfo transportInfo = new WebRTCDataChannelTransportInfo();
        final IceUdpTransportInfo inner = new IceUdpTransportInfo();
        inner.setAttribute("ufrag", "abc");
        inner.setAttribute("pwd", "secret");
        inner.addChild(IceUdpTransportInfo.Candidate.fromSdpAttribute(CANDIDATE, null));
        transportInfo.addChild(inner);
        return transportInfo;
    }

    // ---- the wrapper's own parameters ------------------------------------------------------

    @Test
    public void aFreshTransportCarriesTheDataChannelNamespace() {
        assertThat(new WebRTCDataChannelTransportInfo().getNamespace())
                .isEqualTo(Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL);
    }

    @Test
    public void theSctpParametersAreReadWhenPresent() {
        final WebRTCDataChannelTransportInfo transportInfo = new WebRTCDataChannelTransportInfo();
        transportInfo.setAttribute("sctp-port", "5000");
        transportInfo.setAttribute("max-message-size", "262144");

        assertThat(transportInfo.getSctpPort()).isEqualTo(5000);
        assertThat(transportInfo.getMaxMessageSize()).isEqualTo(262144);
    }

    @Test
    public void absentSctpParametersReadAsNull() {
        // Null rather than a default: the caller decides what an unspecified port means, and a
        // fabricated 0 would look like a real port.
        final WebRTCDataChannelTransportInfo transportInfo = new WebRTCDataChannelTransportInfo();

        assertThat(transportInfo.getSctpPort()).isNull();
        assertThat(transportInfo.getMaxMessageSize()).isNull();
    }

    @Test
    public void unparseableSctpParametersReadAsNull() {
        // They come from the peer, so "not a number" must be a value rather than an exception.
        final WebRTCDataChannelTransportInfo transportInfo = new WebRTCDataChannelTransportInfo();
        transportInfo.setAttribute("sctp-port", "five thousand");
        transportInfo.setAttribute("max-message-size", "");

        assertThat(transportInfo.getSctpPort()).isNull();
        assertThat(transportInfo.getMaxMessageSize()).isNull();
    }

    @Test
    public void upgradeRejectsAnElementThatIsNotADataChannelTransport() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WebRTCDataChannelTransportInfo.upgrade(
                                new Element(
                                        "description",
                                        Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WebRTCDataChannelTransportInfo.upgrade(
                                new Element("transport", Namespace.JINGLE_TRANSPORT_ICE_UDP)));
    }

    // ---- reaching through to the nested transport -------------------------------------------

    @Test
    public void theInnerIceTransportIsFoundByItsNamespace() {
        final IceUdpTransportInfo inner = withInnerTransport().innerIceUdpTransportInfo();

        assertThat(inner).isNotNull();
        assertThat(inner.getCredentials())
                .isEqualTo(new IceUdpTransportInfo.Credentials("abc", "secret"));
    }

    @Test
    public void thereIsNoInnerTransportWhenTheStanzaOmitsIt() {
        assertThat(new WebRTCDataChannelTransportInfo().innerIceUdpTransportInfo()).isNull();
    }

    @Test
    public void aNestedTransportInTheWrongNamespaceIsNotTheInnerTransport() {
        final WebRTCDataChannelTransportInfo transportInfo = new WebRTCDataChannelTransportInfo();
        transportInfo.addChild(new Element("transport", Namespace.JINGLE_TRANSPORTS_S5B));

        assertThat(transportInfo.innerIceUdpTransportInfo()).isNull();
    }

    @Test
    public void candidatesComeFromTheInnerTransport() {
        assertThat(withInnerTransport().getCandidates()).hasSize(1);
        assertThat(withInnerTransport().getCandidates().get(0).getIp()).isEqualTo("10.0.0.1");
    }

    @Test
    public void candidatesAreEmptyRatherThanNullWithNoInnerTransport() {
        // Callers iterate this directly, so the missing-inner-transport case is handled here.
        assertThat(new WebRTCDataChannelTransportInfo().getCandidates()).isEmpty();
    }

    @Test
    public void credentialsComeFromTheInnerTransport() {
        assertThat(withInnerTransport().getCredentials())
                .isEqualTo(new IceUdpTransportInfo.Credentials("abc", "secret"));
    }

    @Test
    public void credentialsAreNullWithNoInnerTransport() {
        assertThat(new WebRTCDataChannelTransportInfo().getCredentials()).isNull();
    }

    @Test
    public void aCandidateIsAddedToTheInnerTransport() {
        final WebRTCDataChannelTransportInfo transportInfo = withInnerTransport();
        transportInfo.addCandidate(
                IceUdpTransportInfo.Candidate.fromSdpAttribute(
                        "candidate:2 1 udp 2130706431 10.0.0.2 54322 typ host", null));

        assertThat(transportInfo.getCandidates()).hasSize(2);
    }

    @Test
    public void cloneWrapperKeepsTheParametersAndDropsTheCandidates() {
        final WebRTCDataChannelTransportInfo transportInfo = withInnerTransport();
        transportInfo.setAttribute("sctp-port", "5000");

        final WebRTCDataChannelTransportInfo wrapper = transportInfo.cloneWrapper();

        assertThat(wrapper.getSctpPort()).isEqualTo(5000);
        assertThat(wrapper.getCredentials())
                .isEqualTo(new IceUdpTransportInfo.Credentials("abc", "secret"));
        assertThat(wrapper.getCandidates()).isEmpty();
    }

    @Test
    public void theTwoAccessorsThatAssumeAnInnerTransportThrowWithoutOne() {
        // Characterisation, not a requirement. getCandidates and getCredentials both handle a
        // missing inner transport; cloneWrapper and addCandidate dereference it unguarded, so a
        // stanza that omits the nested transport turns into an NPE rather than a rejected offer.
        //
        // Not reachable from the parse path today -- the transport is built locally before either
        // is called. Pinned so that adding a guard, or reaching them from a peer's stanza, is a
        // visible change.
        final WebRTCDataChannelTransportInfo empty = new WebRTCDataChannelTransportInfo();

        assertThrows(NullPointerException.class, empty::cloneWrapper);
        assertThrows(
                NullPointerException.class,
                () ->
                        empty.addCandidate(
                                IceUdpTransportInfo.Candidate.fromSdpAttribute(CANDIDATE, null)));
    }
}
