package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import com.glaciersecurity.glaciermessenger.testutil.Elements;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

/**
 * The two byte-stream transports a file transfer can be negotiated over: SOCKS5 (XEP-0260), which
 * is tried first because it is direct, and in-band bytestreams (XEP-0261), which is the fallback
 * that tunnels the file through the server.
 *
 * <p>For SOCKS5 the interesting part is {@code getTransportInfo}, which reads the peer's progress
 * through candidate negotiation off a single element -- used a candidate, could not, the proxy
 * failed, or the proxy is now active. It is a small state machine over remote input, and the case
 * worth pinning is that a state element with no candidate id reads as <em>no state</em> rather
 * than as a state naming nothing: acting on a candidate-used that names no candidate would mean
 * activating whichever one happened to be first.
 *
 * <p>For in-band the interesting part is the constructor's preconditions. The block size is what
 * every subsequent chunk is measured against, so a zero or negative one is refused at
 * construction rather than producing a transfer that never advances.
 */
public class ByteStreamTransportInfoTest {

    private static SocksByteStreamsTransportInfo socksWith(final Element... children) {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORTS_S5B);
        element.setAttribute("sid", "a-transport-id");
        for (final Element child : children) {
            element.addChild(child);
        }
        return SocksByteStreamsTransportInfo.upgrade(element);
    }

    private static Element child(final String name, final String... attributes) {
        return Elements.withAttributes(new Element(name), attributes);
    }

    // ---- SOCKS5: negotiation state --------------------------------------------------------

    @Test
    public void aTransportWithNoStateElementHasNoState() {
        assertThat(socksWith().getTransportInfo()).isNull();
    }

    @Test
    public void aUsedCandidateIsReportedWithItsId() {
        final SocksByteStreamsTransportInfo.TransportInfo state =
                socksWith(child("candidate-used", "cid", "candidate-1")).getTransportInfo();

        assertThat(state).isInstanceOf(SocksByteStreamsTransportInfo.CandidateUsed.class);
        assertThat(((SocksByteStreamsTransportInfo.CandidateUsed) state).cid)
                .isEqualTo("candidate-1");
    }

    @Test
    public void aUsedCandidateWithNoIdIsNoStateAtAll() {
        // Acting on this would mean picking a candidate the peer never named.
        assertThat(socksWith(child("candidate-used")).getTransportInfo()).isNull();
        assertThat(socksWith(child("candidate-used", "cid", "")).getTransportInfo()).isNull();
    }

    @Test
    public void anActivatedCandidateIsReportedWithItsId() {
        final SocksByteStreamsTransportInfo.TransportInfo state =
                socksWith(child("activated", "cid", "candidate-2")).getTransportInfo();

        assertThat(state).isInstanceOf(SocksByteStreamsTransportInfo.Activated.class);
        assertThat(((SocksByteStreamsTransportInfo.Activated) state).cid).isEqualTo("candidate-2");
    }

    @Test
    public void anActivatedCandidateWithNoIdIsNoStateAtAll() {
        assertThat(socksWith(child("activated")).getTransportInfo()).isNull();
    }

    @Test
    public void aCandidateErrorNeedsNoId() {
        // "I could not reach any of your candidates" names nothing by design.
        assertThat(socksWith(child("candidate-error")).getTransportInfo())
                .isInstanceOf(SocksByteStreamsTransportInfo.CandidateError.class);
    }

    @Test
    public void aProxyErrorNeedsNoId() {
        assertThat(socksWith(child("proxy-error")).getTransportInfo())
                .isInstanceOf(SocksByteStreamsTransportInfo.ProxyError.class);
    }

    @Test
    public void proxyErrorIsCheckedBeforeEverythingElse() {
        // A stanza carrying several state elements is malformed; the order the code checks them
        // in is what decides the outcome, so it is pinned rather than left to chance.
        final SocksByteStreamsTransportInfo.TransportInfo state =
                socksWith(
                                child("candidate-used", "cid", "candidate-1"),
                                child("proxy-error"))
                        .getTransportInfo();

        assertThat(state).isInstanceOf(SocksByteStreamsTransportInfo.ProxyError.class);
    }

    // ---- SOCKS5: the rest -------------------------------------------------------------------

    @Test
    public void theTransportIdAndDestinationAreReadOffTheAttributes() {
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORTS_S5B);
        element.setAttribute("sid", "a-transport-id");
        element.setAttribute("dstaddr", "0123456789abcdef");

        final SocksByteStreamsTransportInfo transportInfo =
                SocksByteStreamsTransportInfo.upgrade(element);

        assertThat(transportInfo.getTransportId()).isEqualTo("a-transport-id");
        assertThat(transportInfo.getDestinationAddress()).isEqualTo("0123456789abcdef");
    }

    @Test
    public void aBrokenCandidateIsSkippedRatherThanFailingTheWholeList() {
        // Candidates come from the peer. One unparseable entry must not lose the others, or a
        // malformed candidate becomes a denial of service on the transfer.
        final Element candidate = new Element("candidate", Namespace.JINGLE_TRANSPORTS_S5B);
        candidate.setAttribute("cid", "candidate-1");
        // No host, port or jid: enough to fail construction.

        assertThat(socksWith(candidate).getCandidates()).isEmpty();
    }

    @Test
    public void onlyCandidatesInTheS5bNamespaceAreConsidered() {
        final Element foreign = new Element("candidate", Namespace.JINGLE_TRANSPORT_ICE_UDP);
        foreign.setAttribute("cid", "candidate-1");

        assertThat(socksWith(foreign).getCandidates()).isEmpty();
    }

    @Test
    public void socksUpgradeRejectsAnElementThatIsNotAnS5bTransport() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SocksByteStreamsTransportInfo.upgrade(
                                new Element("description", Namespace.JINGLE_TRANSPORTS_S5B)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SocksByteStreamsTransportInfo.upgrade(
                                new Element("transport", Namespace.JINGLE_TRANSPORTS_IBB)));
    }

    @Test
    public void aSocksTransportCanBeBuiltWithNoCandidates() {
        // Legitimate: it means "I have no route to offer", and the peer answers with its own.
        final SocksByteStreamsTransportInfo transportInfo =
                new SocksByteStreamsTransportInfo("a-transport-id", ImmutableList.of());

        assertThat(transportInfo.getTransportId()).isEqualTo("a-transport-id");
        assertThat(transportInfo.getNamespace()).isEqualTo(Namespace.JINGLE_TRANSPORTS_S5B);
    }

    @Test
    public void aSocksTransportNeedsATransportId() {
        assertThrows(
                NullPointerException.class,
                () -> new SocksByteStreamsTransportInfo(null, ImmutableList.of()));
    }

    // ---- in-band bytestreams ----------------------------------------------------------------

    @Test
    public void anInBandTransportCarriesItsIdAndBlockSize() {
        final IbbTransportInfo transportInfo = new IbbTransportInfo("a-transport-id", 4096);

        assertThat(transportInfo.getTransportId()).isEqualTo("a-transport-id");
        assertThat(transportInfo.getBlockSize()).isEqualTo(4096L);
        assertThat(transportInfo.getNamespace()).isEqualTo(Namespace.JINGLE_TRANSPORTS_IBB);
    }

    @Test
    public void anInBandTransportNeedsATransportId() {
        assertThrows(NullPointerException.class, () -> new IbbTransportInfo(null, 4096));
    }

    @Test
    public void aBlockSizeOfZeroOrLessIsRefused() {
        // Every chunk of the transfer is measured against this. Zero would be a transfer that
        // never advances, and it is cheaper to refuse it here than to diagnose it later.
        assertThrows(IllegalArgumentException.class, () -> new IbbTransportInfo("sid", 0));
        assertThrows(IllegalArgumentException.class, () -> new IbbTransportInfo("sid", -1));
    }

    @Test
    public void anAbsentOrUnparseableBlockSizeReadsAsNull() {
        // It arrives from the peer, so "not a number" has to be a value the caller can test for
        // rather than an exception out of a getter.
        final Element element = new Element("transport", Namespace.JINGLE_TRANSPORTS_IBB);
        element.setAttribute("sid", "a-transport-id");

        assertThat(IbbTransportInfo.upgrade(element).getBlockSize()).isNull();

        element.setAttribute("block-size", "not-a-number");
        assertThat(IbbTransportInfo.upgrade(element).getBlockSize()).isNull();
    }

    @Test
    public void inBandUpgradeRejectsAnElementThatIsNotAnIbbTransport() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IbbTransportInfo.upgrade(
                                new Element("transport", Namespace.JINGLE_TRANSPORTS_S5B)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IbbTransportInfo.upgrade(
                                new Element("description", Namespace.JINGLE_TRANSPORTS_IBB)));
    }
}
