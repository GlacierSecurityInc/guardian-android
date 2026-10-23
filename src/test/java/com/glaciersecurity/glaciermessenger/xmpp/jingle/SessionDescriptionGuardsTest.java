package com.glaciersecurity.glaciermessenger.xmpp.jingle;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * {@code checkNoNewline} and {@code checkNoWhitespace} are the two guards that stand between a
 * remote peer's Jingle stanza and the SDP this app generates from it. SDP is line-oriented, so a
 * newline inside an attribute value is not a malformed value -- it is an additional SDP line,
 * chosen by whoever sent the stanza.
 *
 * <p>{@code checkNoNewline} must inspect its {@code input}, not its {@code message} argument.
 * Every call site passes a compile-time constant error string for {@code message}, so a matcher
 * run over that string could never fire. Its single call site is the source-specific ("ssrc")
 * media attribute value in {@code SessionDescription.of}; without the guard, a value carrying a
 * newline would be written straight into the generated SDP.
 *
 * <p>The call site applies {@code .trim()} to the value afterwards, which is why an *interior*
 * newline is the case that matters: trim removes leading and trailing whitespace and leaves the
 * injected line intact.
 *
 * <p>These tests exercise the guards directly. The enclosing {@code SessionDescription.of} needs a
 * fully populated {@code RtpContentMap} -- transport, fingerprint, payload types -- to reach the
 * one line being guarded, and the guards are public statics, so driving them directly states the
 * property without that scaffolding.
 */
public class SessionDescriptionGuardsTest {

    private static final String MESSAGE =
            "A source specific media attribute value must not contain new lines";

    @Test
    public void aValueWithAnInteriorNewlineIsRejected() {
        // The injection this guard exists to stop: the trailing part becomes its own SDP line.
        final String injected = "cname:something\r\na=candidate:0 1 UDP 2122252543 192.0.2.1 9 typ host";
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionDescription.checkNoNewline(injected, MESSAGE));
    }

    @Test
    public void aBareLineFeedIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionDescription.checkNoNewline("before\nafter", MESSAGE));
    }

    @Test
    public void aBareCarriageReturnIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionDescription.checkNoNewline("before\rafter", MESSAGE));
    }

    @Test
    public void theGuardInspectsItsInputAndNotItsMessage() {
        // Pins which argument is inspected. A guard whose matcher ran over `message` would throw
        // here; a test that only fed it clean input could not tell the two apart.
        assertThat(SessionDescription.checkNoNewline("cname:legitimate", "unused\r\nmessage"))
                .isEqualTo("cname:legitimate");
    }

    @Test
    public void aLegitimateValueIsReturnedUnchanged() {
        assertThat(SessionDescription.checkNoNewline("cname:legitimate", MESSAGE))
                .isEqualTo("cname:legitimate");
    }

    @Test
    public void spacesAreStillAllowedInASourceSpecificValue() {
        // Deliberately the weaker of the two guards: an msid value is "<stream-id> <track-id>",
        // so the call site cannot use checkNoWhitespace here.
        final String msid = "msid:stream-id track-id";
        assertThat(SessionDescription.checkNoNewline(msid, MESSAGE)).isEqualTo(msid);
    }

    @Test
    public void checkNoWhitespaceStillRejectsASpace() {
        // The neighbouring guard, which was always correct -- present so that a future edit that
        // "unifies" the two has to break a test rather than quietly widen either one.
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionDescription.checkNoWhitespace("has space", "no whitespace allowed"));
    }
}
