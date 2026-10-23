package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import com.glaciersecurity.glaciermessenger.testutil.Elements;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * RtpDescription is the codec half of a call offer: which payload types each side can encode, at
 * what clock rate, with how many channels. It exists in two representations at once -- the Jingle
 * XML the peers exchange, and the SDP lines WebRTC is configured from -- and this class translates
 * between them.
 *
 * <p>So the round trip is the thing to test. A payload type that survives XML but comes back from
 * SDP subtly different does not fail: both sides agree on a number and then encode against
 * different assumptions, which is a call that connects and carries noise.
 *
 * <p>The defaults carry the same risk quietly. RFC 4566 says an omitted channel count means one,
 * so {@code getChannels} returns 1 rather than 0 for an absent attribute, and
 * {@code toSdpAttribute} leaves the count off again when it is 1 -- writing {@code /1} explicitly
 * would be legal but would stop the output matching what the peer sent. Both directions are
 * asserted.
 *
 * <p>Robolectric rather than plain JUnit: RtpDescription reaches SessionDescription, which returns
 * {@code android.util.Pair}, and under the mockable android.jar that constructor stores nothing.
 */
@RunWith(RobolectricTestRunner.class)
public class RtpDescriptionTest {

    private static RtpDescription descriptionWith(final Element... children) {
        final Element element = new Element("description", Namespace.JINGLE_APPS_RTP);
        element.setAttribute("media", "audio");
        for (final Element child : children) {
            element.addChild(child);
        }
        return RtpDescription.upgrade(element);
    }

    private static Element payloadTypeElement(final String... attributes) {
        return Elements.withAttributes(
                new Element("payload-type", Namespace.JINGLE_APPS_RTP), attributes);
    }

    // ---- the description itself -----------------------------------------------------------

    @Test
    public void upgradeRejectsAnElementThatIsNotAnRtpDescription() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RtpDescription.upgrade(new Element("transport", Namespace.JINGLE_APPS_RTP)));
    }

    @Test
    public void aDescriptionWithNoPayloadTypesOffersNoCodecs() {
        assertThat(descriptionWith().getPayloadTypes()).isEmpty();
    }

    @Test
    public void everyPayloadTypeChildIsOffered() {
        final RtpDescription description =
                descriptionWith(
                        payloadTypeElement("id", "111", "name", "opus", "clockrate", "48000"),
                        payloadTypeElement("id", "0", "name", "PCMU", "clockrate", "8000"));

        assertThat(description.getPayloadTypes()).hasSize(2);
        assertThat(description.getPayloadTypes().get(0).getPayloadTypeName()).isEqualTo("opus");
    }

    @Test
    public void childrenThatAreNotPayloadTypesAreNotCodecs() {
        final RtpDescription description =
                descriptionWith(
                        payloadTypeElement("id", "111", "name", "opus", "clockrate", "48000"),
                        new Element("bandwidth", Namespace.JINGLE_APPS_RTP));

        assertThat(description.getPayloadTypes()).hasSize(1);
    }

    // ---- payload types, from SDP ------------------------------------------------------------

    @Test
    public void aStereoCodecIsParsedFromItsRtpmapLine() {
        final RtpDescription.PayloadType opus = RtpDescription.PayloadType.ofSdpString("111 opus/48000/2");

        assertThat(opus.getId()).isEqualTo("111");
        assertThat(opus.getPayloadTypeName()).isEqualTo("opus");
        assertThat(opus.getClockRate()).isEqualTo(48000);
        assertThat(opus.getChannels()).isEqualTo(2);
    }

    @Test
    public void anOmittedChannelCountMeansOne() {
        // RFC 4566: if the channel count is absent it MUST be assumed to be one. Returning 0
        // would configure an encoder with no channels.
        final RtpDescription.PayloadType pcmu = RtpDescription.PayloadType.ofSdpString("0 PCMU/8000");

        assertThat(pcmu.getChannels()).isEqualTo(1);
        // And it is not written into the element either, so the XML stays as the peer sent it.
        assertThat(pcmu.getAttribute("channels")).isNull();
    }

    @Test
    public void anRtpmapLineWithNoClockRateIsRejected() {
        // Name and clock rate together identify the codec; a line with only a name is not
        // something to guess a rate for.
        assertThat(RtpDescription.PayloadType.ofSdpString("111 opus")).isNull();
        assertThat(RtpDescription.PayloadType.ofSdpString("111")).isNull();
        assertThat(RtpDescription.PayloadType.ofSdpString("")).isNull();
    }

    @Test
    public void anUnparseableClockRateReadsAsZeroRatherThanThrowing() {
        final RtpDescription.PayloadType payloadType =
                RtpDescription.PayloadType.ofSdpString("111 opus/not-a-number");

        assertThat(payloadType.getClockRate()).isEqualTo(0);
    }

    // ---- payload types, back to SDP ---------------------------------------------------------

    @Test
    public void aStereoCodecSurvivesTheRoundTrip() {
        assertThat(RtpDescription.PayloadType.ofSdpString("111 opus/48000/2").toSdpAttribute())
                .isEqualTo("111 opus/48000/2");
    }

    @Test
    public void aMonoCodecSurvivesTheRoundTripWithoutGainingAChannelCount() {
        // Writing "/1" would be legal SDP but would stop the line matching what the peer sent,
        // and these lines are compared as text in places.
        assertThat(RtpDescription.PayloadType.ofSdpString("0 PCMU/8000").toSdpAttribute())
                .isEqualTo("0 PCMU/8000");
    }

    @Test
    public void aPayloadTypeBuiltByHandSerialisesTheSameWay() {
        assertThat(new RtpDescription.PayloadType("111", "opus", 48000, 2).toSdpAttribute())
                .isEqualTo("111 opus/48000/2");
        assertThat(new RtpDescription.PayloadType("0", "PCMU", 8000, 1).toSdpAttribute())
                .isEqualTo("0 PCMU/8000");
    }

    @Test
    public void aPayloadTypeWithNoNameCannotBeSerialised() {
        final RtpDescription.PayloadType nameless =
                RtpDescription.PayloadType.of(payloadTypeElement("id", "111", "clockrate", "48000"));

        assertThrows(IllegalArgumentException.class, nameless::toSdpAttribute);
    }

    @Test
    public void aPayloadTypeNameWithWhitespaceIsRefused() {
        // The name goes into a space-delimited line, so a space in it would silently become
        // additional SDP fields -- the same boundary the candidate serialiser guards.
        final RtpDescription.PayloadType payloadType =
                RtpDescription.PayloadType.of(
                        payloadTypeElement(
                                "id", "111", "name", "opus 48000", "clockrate", "48000"));

        assertThrows(IllegalArgumentException.class, payloadType::toSdpAttribute);
    }

    @Test
    public void ofRejectsAnElementThatIsNotAPayloadType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RtpDescription.PayloadType.of(new Element("parameter")));
    }

    @Test
    public void anAbsentIdReadsAsZeroWithoutThrowing() {
        final RtpDescription.PayloadType payloadType =
                RtpDescription.PayloadType.of(payloadTypeElement("name", "opus"));

        assertThat(payloadType.getIntId()).isEqualTo(0);
        assertThat(payloadType.getId()).isNull();
    }

    @Test
    public void aNonNumericIdReadsAsZero() {
        final RtpDescription.PayloadType payloadType =
                RtpDescription.PayloadType.of(payloadTypeElement("id", "not-a-number"));

        assertThat(payloadType.getIntId()).isEqualTo(0);
    }

    @Test
    public void anUnparseableChannelCountFallsBackToOne() {
        // Same reasoning as the absent case: one channel is the safe assumption, zero is not.
        final RtpDescription.PayloadType payloadType =
                RtpDescription.PayloadType.of(
                        payloadTypeElement("id", "111", "channels", "stereo"));

        assertThat(payloadType.getChannels()).isEqualTo(1);
    }

    // ---- codec parameters ---------------------------------------------------------------------

    @Test
    public void codecParametersAreReadOffThePayloadType() {
        // These carry things like opus's useinbandfec; losing them silently changes how the
        // codec is configured on one side only.
        final Element element = payloadTypeElement("id", "111", "name", "opus");
        final Element parameter = element.addChild("parameter", Namespace.JINGLE_APPS_RTP);
        parameter.setAttribute("name", "useinbandfec");
        parameter.setAttribute("value", "1");

        final List<RtpDescription.Parameter> parameters =
                RtpDescription.PayloadType.of(element).getParameters();

        assertThat(parameters).hasSize(1);
        assertThat(parameters.get(0).getParameterName()).isEqualTo("useinbandfec");
    }

    @Test
    public void aPayloadTypeWithNoParametersHasNone() {
        assertThat(RtpDescription.PayloadType.of(payloadTypeElement("id", "111")).getParameters())
                .isEmpty();
    }

    // ---- header extensions ---------------------------------------------------------------------

    @Test
    public void aHeaderExtensionIsParsedFromItsSdpLine() {
        final RtpDescription.RtpHeaderExtension extension =
                RtpDescription.RtpHeaderExtension.ofSdpString("1 urn:ietf:params:rtp-hdrext:ssrc-audio-level");

        assertThat(extension.getId()).isEqualTo("1");
        assertThat(extension.getUri()).isEqualTo("urn:ietf:params:rtp-hdrext:ssrc-audio-level");
    }

    @Test
    public void aHeaderExtensionWithNoUriIsRejected() {
        assertThat(RtpDescription.RtpHeaderExtension.ofSdpString("1")).isNull();
        assertThat(RtpDescription.RtpHeaderExtension.ofSdpString("")).isNull();
    }

    @Test
    public void headerExtensionsAreReadOffTheDescription() {
        final Element extension =
                new Element("rtp-hdrext", "urn:xmpp:jingle:apps:rtp:rtp-hdrext:0");
        extension.setAttribute("id", "1");
        extension.setAttribute("uri", "urn:ietf:params:rtp-hdrext:ssrc-audio-level");

        assertThat(descriptionWith(extension).getHeaderExtensions()).hasSize(1);
    }
}
