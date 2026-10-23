package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import org.junit.Test;

/**
 * A Content is one stream inside a Jingle session -- the audio, the video, or the file being
 * sent. Two things about it are easy to get wrong and are what this covers.
 *
 * <p>First, dispatch. {@code getDescription} and {@code getTransport} pick a concrete stanza
 * class off the child's namespace, and an unrecognised namespace has to degrade to the generic
 * wrapper rather than to null or to the wrong class -- a remote peer chooses these namespaces.
 *
 * <p>Second, and more subtly, {@code Senders} is written from the perspective of the session
 * rather than of the device holding it. {@code Senders.INITIATOR} means "the initiator sends",
 * which is an outgoing stream if we are the initiator and an incoming one if we are not, so
 * {@code asMediaAttribute} has to flip sendonly and recvonly depending on which end is asking.
 * Getting that backwards produces a call that negotiates successfully and then carries no audio
 * in one direction, which is why all eight combinations are asserted rather than a sample.
 *
 * <p>Not covered here: {@code getSecurity}/{@code setSecurity}, which need the axolotl stack, and
 * {@code Senders.of(Media, boolean)}, which needs a SessionDescription.Media and so the
 * Robolectric tier.
 */
public class ContentTest {

    private static Content contentWithTransport(final String namespace) {
        final Element element = new Element("content", Namespace.JINGLE);
        element.setAttribute("creator", "initiator");
        element.setAttribute("name", "a-stream");
        element.addChild(new Element("transport", namespace));
        return Content.upgrade(element);
    }

    private static Content contentWithDescription(final String namespace) {
        final Element element = new Element("content", Namespace.JINGLE);
        element.setAttribute("creator", "initiator");
        element.setAttribute("name", "a-stream");
        element.addChild(new Element("description", namespace));
        return Content.upgrade(element);
    }

    // ---- the content element ------------------------------------------------------------

    @Test
    public void aContentIsNamedAndAttributedToItsCreator() {
        final Content content = new Content(Content.Creator.INITIATOR, Content.Senders.BOTH, "voice");

        assertThat(content.getContentName()).isEqualTo("voice");
        assertThat(content.getCreator()).isEqualTo(Content.Creator.INITIATOR);
        assertThat(content.getNamespace()).isEqualTo(Namespace.JINGLE);
    }

    @Test
    public void upgradeRejectsAnElementThatIsNotAContent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Content.upgrade(new Element("description", Namespace.JINGLE)));
    }

    @Test
    public void theCreatorIsWrittenInLowerCase() {
        // It goes on the wire as an XML attribute value; the enum name would be wrong there.
        final Content content =
                new Content(Content.Creator.RESPONDER, Content.Senders.BOTH, "voice");

        assertThat(content.getAttribute("creator")).isEqualTo("responder");
    }

    @Test
    public void anUnknownCreatorIsAnError() {
        // Unlike Setup.of, this one does not absorb a bad value -- noted so a change of heart
        // shows up here rather than in a call that half-starts.
        assertThrows(IllegalArgumentException.class, () -> Content.Creator.of("bystander"));
    }

    // ---- senders ------------------------------------------------------------------------

    @Test
    public void aContentWithNoSendersAttributeMeansBoth() {
        // Per XEP-0166 the default is "both", and an absent attribute must not read as null.
        final Element element = new Element("content", Namespace.JINGLE);
        assertThat(Content.upgrade(element).getSenders()).isEqualTo(Content.Senders.BOTH);
    }

    @Test
    public void theDefaultSendersValueIsNotWrittenOut() {
        // "both" is the default, so writing it would only add noise to every stanza.
        final Content content = new Content(Content.Creator.INITIATOR, Content.Senders.BOTH, "v");

        assertThat(content.getAttribute("senders")).isNull();
        assertThat(content.getSenders()).isEqualTo(Content.Senders.BOTH);
    }

    @Test
    public void aNonDefaultSendersValueIsWrittenInLowerCase() {
        final Content content =
                new Content(Content.Creator.INITIATOR, Content.Senders.RESPONDER, "v");

        assertThat(content.getAttribute("senders")).isEqualTo("responder");
        assertThat(content.getSenders()).isEqualTo(Content.Senders.RESPONDER);
    }

    @Test
    public void sendersIsReadCaseInsensitively() {
        assertThat(Content.Senders.of("None")).isEqualTo(Content.Senders.NONE);
        assertThat(Content.Senders.of("initiator")).isEqualTo(Content.Senders.INITIATOR);
    }

    @Test
    public void bothEndsAgreeThatSendrecvIsBidirectional() {
        assertThat(Content.Senders.BOTH.asMediaAttribute(true)).isEqualTo("sendrecv");
        assertThat(Content.Senders.BOTH.asMediaAttribute(false)).isEqualTo("sendrecv");
    }

    @Test
    public void bothEndsAgreeThatNoneIsInactive() {
        assertThat(Content.Senders.NONE.asMediaAttribute(true)).isEqualTo("inactive");
        assertThat(Content.Senders.NONE.asMediaAttribute(false)).isEqualTo("inactive");
    }

    @Test
    public void aOneWayStreamIsSendonlyToTheEndThatSendsIt() {
        // senders=initiator: outgoing for the initiator, incoming for the responder.
        assertThat(Content.Senders.INITIATOR.asMediaAttribute(true)).isEqualTo("sendonly");
        assertThat(Content.Senders.INITIATOR.asMediaAttribute(false)).isEqualTo("recvonly");
    }

    @Test
    public void aOneWayStreamIsRecvonlyToTheEndThatReceivesIt() {
        // senders=responder: the mirror image of the case above.
        assertThat(Content.Senders.RESPONDER.asMediaAttribute(true)).isEqualTo("recvonly");
        assertThat(Content.Senders.RESPONDER.asMediaAttribute(false)).isEqualTo("sendonly");
    }

    @Test
    public void receiveOnlyNamesTheOtherEndAsTheSender() {
        assertThat(Content.Senders.receiveOnly(true))
                .containsExactly(Content.Senders.RESPONDER);
        assertThat(Content.Senders.receiveOnly(false))
                .containsExactly(Content.Senders.INITIATOR);
    }

    // ---- description dispatch -----------------------------------------------------------

    @Test
    public void aContentWithNoDescriptionHasNeitherDescriptionNorNamespace() {
        final Content content = Content.upgrade(new Element("content", Namespace.JINGLE));

        assertThat(content.getDescription()).isNull();
        assertThat(content.getDescriptionNamespace()).isNull();
    }

    @Test
    public void aFileTransferDescriptionIsRecognised() {
        final Content content = contentWithDescription(Namespace.JINGLE_APPS_FILE_TRANSFER);

        assertThat(content.getDescription()).isInstanceOf(FileTransferDescription.class);
        assertThat(content.getDescriptionNamespace())
                .isEqualTo(Namespace.JINGLE_APPS_FILE_TRANSFER);
    }

    @Test
    public void anUnknownDescriptionFallsBackToTheGenericWrapper() {
        // A namespace we do not implement must still parse as *something*, so the session can be
        // declined rather than crashing the parse.
        final GenericDescription description =
                contentWithDescription("urn:example:jingle:apps:holograms:1").getDescription();

        assertThat(description).isNotNull();
        assertThat(description.getNamespace()).isEqualTo("urn:example:jingle:apps:holograms:1");
    }

    @Test
    public void aDescriptionCanBeAttached() {
        final Content content = new Content(Content.Creator.INITIATOR, Content.Senders.BOTH, "f");
        content.setDescription(
                GenericDescription.upgrade(
                        new Element("description", Namespace.JINGLE_APPS_FILE_TRANSFER)));

        assertThat(content.getDescriptionNamespace())
                .isEqualTo(Namespace.JINGLE_APPS_FILE_TRANSFER);
    }

    @Test
    public void attachingANullDescriptionIsRefused() {
        final Content content = new Content(Content.Creator.INITIATOR, Content.Senders.BOTH, "f");

        assertThrows(NullPointerException.class, () -> content.setDescription(null));
    }

    // ---- transport dispatch -------------------------------------------------------------

    @Test
    public void aContentWithNoTransportHasNoTransport() {
        assertThat(Content.upgrade(new Element("content", Namespace.JINGLE)).getTransport())
                .isNull();
    }

    @Test
    public void eachKnownTransportNamespaceGetsItsOwnStanzaClass() {
        assertThat(contentWithTransport(Namespace.JINGLE_TRANSPORTS_IBB).getTransport())
                .isInstanceOf(IbbTransportInfo.class);
        assertThat(contentWithTransport(Namespace.JINGLE_TRANSPORTS_S5B).getTransport())
                .isInstanceOf(SocksByteStreamsTransportInfo.class);
        assertThat(contentWithTransport(Namespace.JINGLE_TRANSPORT_ICE_UDP).getTransport())
                .isInstanceOf(IceUdpTransportInfo.class);
        assertThat(
                        contentWithTransport(Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL)
                                .getTransport())
                .isInstanceOf(WebRTCDataChannelTransportInfo.class);
    }

    @Test
    public void anUnknownTransportFallsBackToTheGenericWrapper() {
        final GenericTransportInfo transport =
                contentWithTransport("urn:example:jingle:transports:carrier-pigeon:1")
                        .getTransport();

        assertThat(transport).isNotNull();
        assertThat(transport.getNamespace())
                .isEqualTo("urn:example:jingle:transports:carrier-pigeon:1");
    }

    @Test
    public void aTransportCanBeAttachedAndReadBack() {
        final Content content = new Content(Content.Creator.INITIATOR, Content.Senders.BOTH, "v");
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.setAttribute("ufrag", "abc");
        content.setTransport(transportInfo);

        final GenericTransportInfo read = content.getTransport();

        assertThat(read).isInstanceOf(IceUdpTransportInfo.class);
        assertThat(((IceUdpTransportInfo) read).getCredentials().ufrag).isEqualTo("abc");
    }
}
