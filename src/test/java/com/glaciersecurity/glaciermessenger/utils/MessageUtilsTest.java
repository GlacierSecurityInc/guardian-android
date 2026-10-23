package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@code treatAsDownloadable} decides, from the body of a message that just arrived, whether the
 * app turns it into a file transfer instead of showing it as text. It is the gate in front of the
 * "share files with your contacts" path, and its whole input is attacker-controlled, so the
 * negative cases matter more than the positive ones: anything that is not a well-known scheme on
 * a single line must stay ordinary text.
 *
 * <p>{@code filterLtrRtl} strips runs of bidirectional overrides, which are the classic way to
 * make a filename or URL render as something other than what it is.
 */
public class MessageUtilsTest {

    /** 96 hex characters: the 48-byte IV+key anchor an aesgcm URL carries. */
    private static final String IV_KEY_48 =
            "0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 88 hex characters: the shorter 44-byte variant the pattern also accepts. */
    private static final String IV_KEY_44 =
            "0123456789abcdef0123456789abcdef0123456789abcdef"
                    + "0123456789abcdef0123456789abcdef01234567";

    @Test
    public void anHttpsUrlAnnouncedOutOfBandIsDownloadable() {
        assertThat(MessageUtils.treatAsDownloadable("https://example.com/photo.jpg", true))
                .isTrue();
    }

    @Test
    public void aBareHttpsUrlWithoutOutOfBandIsJustText() {
        // Otherwise every link anyone pastes would start a download.
        assertThat(MessageUtils.treatAsDownloadable("https://example.com/photo.jpg", false))
                .isFalse();
    }

    @Test
    public void anEncryptedAnchorMakesAnHttpsUrlDownloadableWithoutOutOfBand() {
        assertThat(MessageUtils.treatAsDownloadable(
                        "https://example.com/photo.jpg#" + IV_KEY_48, false))
                .isTrue();
    }

    @Test
    public void anAesGcmUrlIsDownloadable() {
        assertThat(MessageUtils.treatAsDownloadable(
                        "aesgcm://example.com/photo.jpg#" + IV_KEY_48, false))
                .isTrue();
    }

    @Test
    public void theShorterFortyFourByteAnchorIsAlsoAccepted() {
        assertThat(MessageUtils.treatAsDownloadable(
                        "aesgcm://example.com/photo.jpg#" + IV_KEY_44, false))
                .isTrue();
    }

    @Test
    public void anAesGcmUrlWithATruncatedAnchorIsNotDownloadable() {
        // A short anchor cannot be a real IV+key, so it must not be handed to the decryptor.
        assertThat(MessageUtils.treatAsDownloadable("aesgcm://example.com/photo.jpg#00ff", false))
                .isFalse();
    }

    @Test
    public void anAesGcmUrlWithNoAnchorAtAllIsNotDownloadable() {
        assertThat(MessageUtils.treatAsDownloadable("aesgcm://example.com/photo.jpg", true))
                .isFalse();
    }

    @Test
    public void anAesGcmUrlMayBeFollowedByADataUriThumbnail() {
        assertThat(MessageUtils.treatAsDownloadable(
                        "aesgcm://example.com/photo.jpg#" + IV_KEY_48 + "\ndata:image/png;base64,AA",
                        false))
                .isTrue();
    }

    @Test
    public void anUnknownSchemeIsNotDownloadable() {
        assertThat(MessageUtils.treatAsDownloadable("ftp://example.com/photo.jpg", true)).isFalse();
        assertThat(MessageUtils.treatAsDownloadable("file:///etc/passwd", true)).isFalse();
        assertThat(MessageUtils.treatAsDownloadable("javascript:alert(1)", true)).isFalse();
    }

    @Test
    public void aUrlWithTextAroundItIsNotDownloadable() {
        assertThat(MessageUtils.treatAsDownloadable("look: https://example.com/a.jpg", true))
                .isFalse();
        assertThat(MessageUtils.treatAsDownloadable(
                        "https://example.com/a.jpg\nhave a look", true))
                .isFalse();
    }

    @Test
    public void plainTextIsNotDownloadable() {
        assertThat(MessageUtils.treatAsDownloadable("hello there", true)).isFalse();
        assertThat(MessageUtils.treatAsDownloadable("", true)).isFalse();
    }

    @Test
    public void aMalformedUriIsNotDownloadable() {
        // A body that cannot even be parsed as a URI must fall through to text, not throw.
        assertThat(MessageUtils.treatAsDownloadable("https://exa mple.com/a.jpg", true)).isFalse();
        assertThat(MessageUtils.treatAsDownloadable("https://[bad", true)).isFalse();
    }

    @Test
    public void anUppercaseSchemeIsNotDownloadable() {
        // Characterisation of an inconsistency rather than a rule worth keeping. The body of
        // treatAsDownloadable compares the protocol with equalsIgnoreCase, but it never gets
        // that far: the URL.WELL_KNOWN_SCHEMES gate above it is a case-sensitive list
        // membership test, and java.net.URI preserves the case the sender wrote. So "HTTPS://"
        // falls through to plain text. It fails closed, so this is a papercut, not a hole.
        assertThat(MessageUtils.treatAsDownloadable("HTTPS://example.com/photo.jpg", true))
                .isFalse();
    }

    @Test
    public void filterLtrRtlRemovesALongRunOfDirectionOverrides() {
        // Three or more LTR/RTL pairs is the shape used to reverse how a name or URL renders.
        final String spoofed = "‎a‏‎b‏‎c‏tail";
        assertThat(MessageUtils.filterLtrRtl(spoofed)).isEqualTo("tail");
    }

    @Test
    public void filterLtrRtlLeavesShorterRunsAlone() {
        // Two pairs are within what legitimate mixed-direction text produces.
        final String benign = "‎a‏‎b‏";
        assertThat(MessageUtils.filterLtrRtl(benign)).isEqualTo(benign);
    }

    @Test
    public void filterLtrRtlLeavesOrdinaryTextUntouched() {
        assertThat(MessageUtils.filterLtrRtl("hello world")).isEqualTo("hello world");
    }
}
