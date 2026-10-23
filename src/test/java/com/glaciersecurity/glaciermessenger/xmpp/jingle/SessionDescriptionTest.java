package com.glaciersecurity.glaciermessenger.xmpp.jingle;

import static com.google.common.truth.Truth.assertThat;

import android.util.Pair;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * SessionDescription is the SDP parser. Everything it parses came from the remote peer by way of
 * a Jingle stanza, and what comes out of it is handed to WebRTC to set up the media path -- so a
 * line that lands in the wrong place does not fail loudly, it produces a call that negotiates and
 * then behaves wrongly.
 *
 * <p>The structural rule is the one worth pinning: SDP is a flat list of lines, and {@code m=} is
 * what divides session-level from media-level. Attributes before the first {@code m=} describe the
 * whole session; everything after belongs to the media section it follows, and a {@code c=} line
 * means a different thing on each side of that boundary. Getting the split wrong would, for
 * instance, apply one stream's ICE credentials to another.
 *
 * <p>This needs the Robolectric tier rather than plain JUnit, and not for a superficial reason:
 * {@code parseAttribute} returns an {@code android.util.Pair}, and under the mockable android.jar
 * that constructor stores nothing, so every attribute would come back as a null key mapped to a
 * null value and the tests would pass while asserting nothing. Robolectric supplies the real
 * implementation. Until the JaCoCo {@code includeNoLocationClasses} fix this tier also counted for
 * zero coverage; see src/test/README.md.
 */
@RunWith(RobolectricTestRunner.class)
public class SessionDescriptionTest {

    private static final String CRLF = "\r\n";

    /** A two-stream offer of the shape libwebrtc produces, trimmed to what the parser reads. */
    private static final String OFFER =
            String.join(
                    CRLF,
                    "v=0",
                    "o=- 8770656990916039506 2 IN IP4 127.0.0.1",
                    "s=-",
                    "t=0 0",
                    "a=group:BUNDLE 0 1",
                    "a=msid-semantic: WMS",
                    "m=audio 9 UDP/TLS/RTP/SAVPF 111 103",
                    "c=IN IP4 0.0.0.0",
                    "a=ice-ufrag:audioufrag",
                    "a=ice-pwd:audiopwd",
                    "a=mid:0",
                    "a=sendrecv",
                    "m=video 9 UDP/TLS/RTP/SAVPF 96",
                    "c=IN IP4 192.0.2.1",
                    "a=ice-ufrag:videoufrag",
                    "a=mid:1",
                    "a=recvonly");

    private static SessionDescription offer() {
        return SessionDescription.parse(OFFER);
    }

    // ---- the session level ----------------------------------------------------------------

    @Test
    public void theSessionVersionAndNameAreParsed() {
        final SessionDescription sessionDescription = offer();

        assertThat(sessionDescription.version).isEqualTo(0);
        assertThat(sessionDescription.name).isEqualTo("-");
    }

    @Test
    public void attributesBeforeTheFirstMediaLineBelongToTheSession() {
        final SessionDescription sessionDescription = offer();

        assertThat(sessionDescription.attributes.get("group")).containsExactly("BUNDLE 0 1");
        assertThat(sessionDescription.attributes.get("msid-semantic")).containsExactly(" WMS");
    }

    @Test
    public void mediaLevelAttributesDoNotLeakIntoTheSession() {
        // The boundary, from the session side. An ice-ufrag that reached session level would be
        // read as applying to every stream.
        assertThat(offer().attributes.get("ice-ufrag")).isEmpty();
        assertThat(offer().attributes.get("mid")).isEmpty();
    }

    @Test
    public void aConnectionLineBeforeAnyMediaIsSessionLevel() {
        final SessionDescription sessionDescription =
                SessionDescription.parse(
                        String.join(CRLF, "v=0", "s=-", "c=IN IP4 198.51.100.1", "t=0 0"));

        assertThat(sessionDescription.connectionData).isEqualTo("IN IP4 198.51.100.1");
    }

    // ---- the media sections ----------------------------------------------------------------

    @Test
    public void eachMediaLineStartsAStream() {
        final SessionDescription sessionDescription = offer();

        assertThat(sessionDescription.media).hasSize(2);
        assertThat(sessionDescription.media.get(0).media).isEqualTo("audio");
        assertThat(sessionDescription.media.get(1).media).isEqualTo("video");
    }

    @Test
    public void theMediaLinePortAndProtocolAreParsed() {
        final SessionDescription.Media audio = offer().media.get(0);

        assertThat(audio.port).isEqualTo(9);
        assertThat(audio.protocol).isEqualTo("UDP/TLS/RTP/SAVPF");
    }

    @Test
    public void attributesAttachToTheMediaSectionTheyFollow() {
        // The boundary, from the media side, and the reason it matters: these are the ICE
        // credentials, and crossing them between streams breaks connectivity in a way that looks
        // like a network fault.
        final SessionDescription sessionDescription = offer();

        assertThat(sessionDescription.media.get(0).attributes.get("ice-ufrag"))
                .containsExactly("audioufrag");
        assertThat(sessionDescription.media.get(1).attributes.get("ice-ufrag"))
                .containsExactly("videoufrag");
    }

    @Test
    public void aStreamDoesNotInheritTheOneBeforeIt() {
        // The audio section has an ice-pwd and the video section does not; the video section must
        // not pick up the audio one when the attribute map is rolled over.
        assertThat(offer().media.get(1).attributes.get("ice-pwd")).isEmpty();
    }

    @Test
    public void aConnectionLineAfterAMediaLineBelongsToThatStream() {
        final SessionDescription sessionDescription = offer();

        assertThat(sessionDescription.media.get(0).connectionData).isEqualTo("IN IP4 0.0.0.0");
        assertThat(sessionDescription.media.get(1).connectionData).isEqualTo("IN IP4 192.0.2.1");
    }

    @Test
    public void aValuelessAttributeIsKeptWithAnEmptyValue() {
        // Direction attributes carry no value; dropping them would lose whether a stream is
        // send-only, receive-only or both.
        assertThat(offer().media.get(0).attributes.containsKey("sendrecv")).isTrue();
        assertThat(offer().media.get(0).attributes.get("sendrecv")).containsExactly("");
        assertThat(offer().media.get(1).attributes.get("recvonly")).containsExactly("");
    }

    @Test
    public void repeatedAttributesAreAllKept() {
        // rtpmap appears once per codec; keeping only the last would leave one codec negotiable.
        final SessionDescription sessionDescription =
                SessionDescription.parse(
                        String.join(
                                CRLF,
                                "v=0",
                                "s=-",
                                "m=audio 9 UDP/TLS/RTP/SAVPF 111 103",
                                "a=rtpmap:111 opus/48000/2",
                                "a=rtpmap:103 ISAC/16000"));

        assertThat(sessionDescription.media.get(0).attributes.get("rtpmap"))
                .containsExactly("111 opus/48000/2", "103 ISAC/16000")
                .inOrder();
    }

    // ---- malformed input ---------------------------------------------------------------------

    @Test
    public void linesThatAreNotKeyEqualsValueAreSkipped() {
        // It arrives from the peer. A junk line has to be ignored rather than throw out of the
        // parse, or one bad line refuses the whole call.
        final SessionDescription sessionDescription =
                SessionDescription.parse(
                        String.join(
                                CRLF,
                                "v=0",
                                "this is not sdp",
                                "",
                                "toolong=value",
                                "s=-",
                                "=novalue"));

        assertThat(sessionDescription.version).isEqualTo(0);
        assertThat(sessionDescription.name).isEqualTo("-");
    }

    @Test
    public void aNonNumericVersionReadsAsZeroRatherThanThrowing() {
        assertThat(SessionDescription.parse("v=nonsense" + CRLF + "s=-").version).isEqualTo(0);
    }

    @Test
    public void anEmptyInputParsesToAnEmptySession() {
        final SessionDescription sessionDescription = SessionDescription.parse("");

        assertThat(sessionDescription.media).isEmpty();
        assertThat(sessionDescription.attributes.isEmpty()).isTrue();
    }

    // ---- the small helpers ------------------------------------------------------------------

    @Test
    public void anAttributeIsSplitOnTheFirstColonOnly() {
        // Values legitimately contain colons -- an IPv6 candidate address, for one -- so only the
        // first separates the name from the value.
        final Pair<String, String> attribute =
                SessionDescription.parseAttribute("candidate:1 1 udp 2130706431 ::1 54321");

        assertThat(attribute.first).isEqualTo("candidate");
        assertThat(attribute.second).isEqualTo("1 1 udp 2130706431 ::1 54321");
    }

    @Test
    public void anAttributeWithNoColonHasAnEmptyValue() {
        final Pair<String, String> attribute = SessionDescription.parseAttribute("sendrecv");

        assertThat(attribute.first).isEqualTo("sendrecv");
        assertThat(attribute.second).isEmpty();
    }

    @Test
    public void theIgnorantIntParserAbsorbsGarbage() {
        assertThat(SessionDescription.ignorantIntParser("42")).isEqualTo(42);
        assertThat(SessionDescription.ignorantIntParser("not a number")).isEqualTo(0);
        assertThat(SessionDescription.ignorantIntParser("")).isEqualTo(0);
    }

    @Test
    public void isIntDistinguishesWhatIgnorantIntParserCannot() {
        // ignorantIntParser maps both "0" and "nonsense" to 0, so callers that need to tell them
        // apart ask this instead.
        assertThat(SessionDescription.isInt("0")).isTrue();
        assertThat(SessionDescription.isInt("nonsense")).isFalse();
        assertThat(SessionDescription.isInt(null)).isFalse();
        assertThat(SessionDescription.isInt("")).isFalse();
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    public void aParsedOfferSurvivesBeingWrittenBackOut() {
        // toString is what gets handed to WebRTC, so the structure has to survive the round trip
        // even though the generated text is not byte-identical to the input.
        final SessionDescription reparsed = SessionDescription.parse(offer().toString());

        assertThat(reparsed.version).isEqualTo(0);
        assertThat(reparsed.name).isEqualTo("-");
        assertThat(reparsed.media).hasSize(2);
        assertThat(reparsed.media.get(0).media).isEqualTo("audio");
        assertThat(reparsed.media.get(1).media).isEqualTo("video");
        assertThat(reparsed.attributes.get("group")).containsExactly("BUNDLE 0 1");
        assertThat(reparsed.media.get(0).attributes.get("ice-ufrag"))
                .containsExactly("audioufrag");
    }

    @Test
    public void theWrittenFormUsesCrlfLineEndings() {
        // SDP is line-oriented on CRLF; a bare LF would make the whole body one unparsed line to
        // a strict peer.
        assertThat(offer().toString()).contains(CRLF);
    }
}
