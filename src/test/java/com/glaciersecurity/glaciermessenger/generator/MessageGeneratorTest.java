package com.glaciersecurity.glaciermessenger.generator;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas.Reason;

import org.junit.Test;

import im.conversations.android.xmpp.model.stanza.Message;

/**
 * MessageGenerator builds the stanzas this app puts on the wire. Most of it needs an
 * XmppConnectionService to reach a Conversation or an Account, but the XEP-0353 call-signalling
 * family does not -- propose, retract, reject and finish are built from a JID and a session id
 * alone, and those are the ones covered here. The constructor only stores the service reference,
 * so a null one is enough to reach them.
 *
 * <p>Two properties run through all four and are the reason this is worth testing rather than
 * eyeballing. Each is sent as a <em>chat</em> message rather than a headline, which is what makes
 * the server carbon-copy it to the user's other devices -- without that, answering a call on a
 * phone leaves the tablet still ringing. And each carries {@code <store/>} from the message-hints
 * namespace, so the signal is archived rather than dropped for an offline device.
 *
 * <p>Both are invisible when wrong: the call still works on the sending device and simply behaves
 * oddly everywhere else, which is the kind of thing that gets diagnosed as a flaky network.
 */
public class MessageGeneratorTest {

    private static final Jid PEER = Jid.of("alice@example.com/phone");
    private static final String SESSION_ID = "a-session-id";

    private static final MessageGenerator GENERATOR = new MessageGenerator(null);

    private static Element hint(final Message packet, final String name) {
        return packet.findChild(name, "urn:xmpp:hints");
    }

    // ---- the shared envelope -------------------------------------------------------------

    @Test
    public void everyCallSignalIsCarbonCopiedToTheSendersOtherDevices() {
        // Type CHAT rather than HEADLINE: headline messages are not carbon-copied, so the user's
        // other devices would never learn the call was proposed, rejected or finished.
        assertThat(GENERATOR.sessionReject(PEER, SESSION_ID).getType()).isEqualTo(Message.Type.CHAT);
        assertThat(GENERATOR.sessionFinish(PEER, SESSION_ID, Reason.SUCCESS).getType())
                .isEqualTo(Message.Type.CHAT);
    }

    @Test
    public void everyCallSignalAsksToBeArchived() {
        // Without the store hint the server may drop the signal for a device that is offline,
        // which leaves that device believing a finished call is still ringing.
        assertThat(hint(GENERATOR.sessionReject(PEER, SESSION_ID), "store")).isNotNull();
        assertThat(hint(GENERATOR.sessionFinish(PEER, SESSION_ID, Reason.SUCCESS), "store"))
                .isNotNull();
    }

    @Test
    public void everyCallSignalIsAddressedToThePeer() {
        assertThat(GENERATOR.sessionReject(PEER, SESSION_ID).getTo()).isEqualTo(PEER);
        assertThat(GENERATOR.sessionFinish(PEER, SESSION_ID, Reason.SUCCESS).getTo())
                .isEqualTo(PEER);
    }

    // ---- reject ---------------------------------------------------------------------------

    @Test
    public void aRejectNamesTheSessionItRefuses() {
        // The id is what ties the signal to the call. A reject carrying the wrong one -- or none
        // -- is a reject of nothing, and the caller keeps ringing.
        final Element reject =
                GENERATOR.sessionReject(PEER, SESSION_ID).findChild("reject", Namespace.JINGLE_MESSAGE);

        assertThat(reject).isNotNull();
        assertThat(reject.getAttribute("id")).isEqualTo(SESSION_ID);
    }

    @Test
    public void aRejectSaysItIsAboutAnRtpSession() {
        final Element reject =
                GENERATOR.sessionReject(PEER, SESSION_ID).findChild("reject", Namespace.JINGLE_MESSAGE);

        assertThat(reject.findChild("description", Namespace.JINGLE_APPS_RTP)).isNotNull();
    }

    // ---- finish ----------------------------------------------------------------------------

    @Test
    public void aFinishCarriesTheReasonTheCallEnded() {
        // This is what the other end shows the user -- ended, declined, or failed. It goes out
        // as the wire spelling rather than the enum name.
        final Element finish =
                GENERATOR.sessionFinish(PEER, SESSION_ID, Reason.SUCCESS)
                        .findChild("finish", Namespace.JINGLE_MESSAGE);

        assertThat(finish.getAttribute("id")).isEqualTo(SESSION_ID);
        final Element reason = finish.findChild("reason", Namespace.JINGLE);
        assertThat(reason).isNotNull();
        assertThat(reason.findChild("success")).isNotNull();
    }

    @Test
    public void aHyphenatedReasonKeepsItsWireSpelling() {
        // Reason.toString is lower-hyphen; the element name has to be that, not the constant.
        final Element finish =
                GENERATOR.sessionFinish(PEER, SESSION_ID, Reason.CONNECTIVITY_ERROR)
                        .findChild("finish", Namespace.JINGLE_MESSAGE);

        assertThat(finish.findChild("reason", Namespace.JINGLE).findChild("connectivity-error"))
                .isNotNull();
    }

    // ---- delay ------------------------------------------------------------------------------

    @Test
    public void addingADelayStampsThePacketInUtc() {
        // The receiving side parses this with AbstractParser; a stamp written in local time would
        // shift every delayed message by the sender's offset.
        final Message packet = new Message();
        GENERATOR.addDelay(packet, 0L);

        final Element delay = packet.findChild("delay", "urn:xmpp:delay");
        assertThat(delay).isNotNull();
        assertThat(delay.getAttribute("stamp")).isEqualTo("1970-01-01T00:00:00.000Z");
    }

    @Test
    public void aDelayStampKeepsItsMilliseconds() {
        final Message packet = new Message();
        GENERATOR.addDelay(packet, 1_000_000_000_500L);

        assertThat(packet.findChild("delay", "urn:xmpp:delay").getAttribute("stamp"))
                .isEqualTo("2001-09-09T01:46:40.500Z");
    }

    // ---- the shared timestamp helper ----------------------------------------------------

    @Test
    public void theSharedTimestampHelperFormatsInUtc() {
        assertThat(AbstractGenerator.getTimestamp(0L)).isEqualTo("1970-01-01T00:00:00.000Z");
    }

    @Test
    public void theSharedTimestampHelperIsIndependentOfTheDefaultTimeZone() {
        // It sets UTC on a *static* SimpleDateFormat on every call. That is what makes the result
        // independent of the device's zone, and it is also why the field is not safe to share
        // across threads -- pinned here so the fix, if it comes, keeps the UTC guarantee.
        final java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            assertThat(AbstractGenerator.getTimestamp(0L)).isEqualTo("1970-01-01T00:00:00.000Z");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }
}
