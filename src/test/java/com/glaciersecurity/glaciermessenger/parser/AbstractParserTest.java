package com.glaciersecurity.glaciermessenger.parser;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;

import org.junit.Test;

import java.text.ParseException;
import java.time.Instant;

/**
 * AbstractParser holds the static helpers every stanza parser shares, and the ones that matter
 * most all decide <em>when a message happened</em> from a value the sender chose.
 *
 * <p>A stanza can carry XEP-0203 delay elements saying it was really sent earlier. That is how
 * offline messages and MAM catch-up get ordered correctly, and it is also the obvious lever for
 * placing a message wherever you like in someone's conversation. Two things keep it honest, and
 * both are asserted here: several delays resolve to the <em>earliest</em> rather than the last one
 * parsed, and {@code parseTimestamp} clamps the result to now, so no stanza can claim to be from
 * the future.
 *
 * <p>{@code getTimestamp} deliberately does not clamp. The test below pins that difference so it
 * stays a decision rather than drifting into an inconsistency.
 *
 * <p>The error helpers are the other half: they turn an error stanza into something a person
 * reads, preferring the server's own text over the machine-readable condition and falling back
 * rather than returning nothing.
 *
 * <p>Expected instants come from {@code java.time}, an independent implementation of the same
 * format. Asserting against SimpleDateFormat here would only prove it agrees with itself.
 */
public class AbstractParserTest {

    /** ASCII unit separator, the delimiter extractErrorMessage puts between condition and text. */
    private static final char UNIT_SEPARATOR = '';

    private static long epochMillis(final String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static Element withDelay(final String... stamps) {
        final Element message = new Element("message");
        for (final String stamp : stamps) {
            final Element delay = message.addChild("delay", "urn:xmpp:delay");
            delay.setAttribute("stamp", stamp);
        }
        return message;
    }

    // ---- parsing one timestamp ----------------------------------------------------------

    @Test
    public void aUtcTimestampIsParsed() throws ParseException {
        assertThat(AbstractParser.parseTimestamp("2026-01-15T12:00:00Z"))
                .isEqualTo(epochMillis("2026-01-15T12:00:00Z"));
    }

    @Test
    public void fractionalSecondsAreKept() throws ParseException {
        // MAM archives commonly carry milliseconds; dropping them collapses the ordering of
        // messages sent within the same second.
        assertThat(AbstractParser.parseTimestamp("2026-01-15T12:00:00.500Z"))
                .isEqualTo(epochMillis("2026-01-15T12:00:00.500Z"));
    }

    @Test
    public void anOffsetIsHonouredRatherThanAssumedToBeUtc() throws ParseException {
        assertThat(AbstractParser.parseTimestamp("2026-01-15T13:00:00+0100"))
                .isEqualTo(epochMillis("2026-01-15T12:00:00Z"));
    }

    @Test
    public void aFutureTimestampIsClampedToNow() throws ParseException {
        // The sender picks this value. Without the clamp, a stanza could pin itself to the top of
        // a conversation indefinitely.
        final long before = System.currentTimeMillis();
        final long parsed = AbstractParser.parseTimestamp("2099-01-01T00:00:00Z");
        final long after = System.currentTimeMillis();

        assertThat(parsed).isAtLeast(before);
        assertThat(parsed).isAtMost(after);
    }

    @Test
    public void aWellFormedButInvalidTimestampIsAParseException() {
        // Long enough to survive the fixed-offset slicing below, but not a date.
        assertThrows(
                ParseException.class,
                () -> AbstractParser.parseTimestamp("not-a-date-at-all-x+0000"));
    }

    @Test
    public void aShortTimestampThrowsAnUncheckedExceptionRatherThanParseException() {
        // Characterisation, not a requirement. Both parsers slice at fixed offsets --
        // substring(0, 19) and substring(length - 5) -- before SimpleDateFormat ever sees the
        // value, so anything shorter than 19 characters dies with StringIndexOutOfBounds rather
        // than the ParseException the signature declares.
        //
        // Not reachable as a crash today: parseTimestamp(Element, ...) and PresenceParser's idle
        // handler are the two places a wire value gets in, and both catch Throwable. Pinned here
        // so that tightening the contract is a deliberate change with a visible test, rather
        // than something discovered when a third caller is added without the same guard.
        assertThrows(
                StringIndexOutOfBoundsException.class,
                () -> AbstractParser.parseTimestamp("last thursday"));
        assertThrows(
                StringIndexOutOfBoundsException.class, () -> AbstractParser.getTimestamp("nope"));
    }

    @Test
    public void getTimestampDoesNotClampToNow() throws ParseException {
        // The difference is deliberate: getTimestamp is used where a future value is meaningful
        // rather than suspicious, so it reports what was sent.
        assertThat(AbstractParser.getTimestamp("2099-01-01T00:00:00Z"))
                .isEqualTo(epochMillis("2099-01-01T00:00:00Z"));
    }

    @Test
    public void getTimestampRefusesNullRatherThanReturningZero() {
        assertThrows(IllegalArgumentException.class, () -> AbstractParser.getTimestamp(null));
    }

    // ---- picking a timestamp off a stanza -----------------------------------------------

    @Test
    public void aStanzaWithNoDelayKeepsTheSuppliedDefault() {
        assertThat(AbstractParser.parseTimestamp(new Element("message"), 1234L)).isEqualTo(1234L);
    }

    @Test
    public void aSingleDelayIsUsed() {
        assertThat(AbstractParser.parseTimestamp(withDelay("2026-01-15T12:00:00Z"), 1234L))
                .isEqualTo(epochMillis("2026-01-15T12:00:00Z"));
    }

    @Test
    public void theEarliestDelayWins() {
        // A stanza relayed through several hops carries a delay per hop. The one that dates the
        // message is the earliest, so this takes the minimum rather than the last one seen.
        final Element message =
                withDelay(
                        "2026-01-15T14:00:00Z", "2026-01-15T12:00:00Z", "2026-01-15T13:00:00Z");

        assertThat(AbstractParser.parseTimestamp(message, 1234L))
                .isEqualTo(epochMillis("2026-01-15T12:00:00Z"));
    }

    @Test
    public void anUnparseableDelayIsIgnoredRatherThanFatal() {
        // One bad hop must not lose the good one, and must not throw out of the parse path.
        final Element message = withDelay("not a timestamp", "2026-01-15T12:00:00Z");

        assertThat(AbstractParser.parseTimestamp(message, 1234L))
                .isEqualTo(epochMillis("2026-01-15T12:00:00Z"));
    }

    @Test
    public void aDelayWithNoStampFallsBackToTheDefault() {
        final Element message = new Element("message");
        message.addChild("delay", "urn:xmpp:delay");

        assertThat(AbstractParser.parseTimestamp(message, 1234L)).isEqualTo(1234L);
    }

    @Test
    public void aDelayInTheWrongNamespaceIsNotADelay() {
        final Element message = new Element("message");
        final Element delay = message.addChild("delay", "urn:example:not-really-delay");
        delay.setAttribute("stamp", "2026-01-15T12:00:00Z");

        assertThat(AbstractParser.parseTimestamp(message, 1234L)).isEqualTo(1234L);
    }

    // ---- error stanzas ------------------------------------------------------------------

    @Test
    public void aStanzaWithNoErrorHasNoMessage() {
        assertThat(AbstractParser.errorMessage(new Element("iq"))).isNull();
        assertThat(AbstractParser.extractErrorMessage(new Element("iq"))).isNull();
    }

    @Test
    public void anEmptyErrorElementHasNoMessage() {
        final Element iq = new Element("iq");
        iq.addChild("error");

        assertThat(AbstractParser.errorMessage(iq)).isNull();
    }

    @Test
    public void theServersOwnTextIsPreferredOverTheConditionName() {
        final Element iq = new Element("iq");
        final Element error = iq.addChild("error");
        error.addChild("service-unavailable", "urn:ietf:params:xml:ns:xmpp-stanzas");
        error.addChild("text").setContent("Try again later");

        assertThat(AbstractParser.errorMessage(iq)).isEqualTo("Try again later");
    }

    @Test
    public void blankTextFallsBackToTheConditionName() {
        // Some servers send an empty <text/>; that must not become an empty error message.
        final Element iq = new Element("iq");
        final Element error = iq.addChild("error");
        error.addChild("service-unavailable", "urn:ietf:params:xml:ns:xmpp-stanzas");
        error.addChild("text").setContent("   ");

        assertThat(AbstractParser.errorMessage(iq)).isEqualTo("service unavailable");
    }

    @Test
    public void theConditionNameIsReadableRatherThanHyphenated() {
        final Element iq = new Element("iq");
        iq.addChild("error")
                .addChild("remote-server-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas");

        assertThat(AbstractParser.errorMessage(iq)).isEqualTo("remote server not found");
    }

    @Test
    public void anApplicationConditionIsPreferredOverTheGenericOne() {
        // The standard condition is the coarse one; a server that also sends its own, in its own
        // namespace, is saying something more specific. That is ordered first deliberately.
        final Element iq = new Element("iq");
        final Element error = iq.addChild("error");
        error.addChild("not-acceptable", "urn:ietf:params:xml:ns:xmpp-stanzas");
        error.addChild("too-many-devices", "urn:example:glacier:errors");

        assertThat(AbstractParser.errorMessage(iq)).isEqualTo("too many devices");
    }

    @Test
    public void extractErrorMessageTagsTheMessageWithItsCondition() {
        // Same text, but prefixed with the machine-readable condition and a unit separator, so a
        // caller can split the two apart again.
        final Element iq = new Element("iq");
        final Element error = iq.addChild("error");
        error.addChild("service-unavailable", "urn:ietf:params:xml:ns:xmpp-stanzas");
        error.addChild("text").setContent("Try again later");

        assertThat(AbstractParser.extractErrorMessage(iq))
                .isEqualTo("service-unavailable" + UNIT_SEPARATOR + "Try again later");
    }

    // ---- avatars -------------------------------------------------------------------------

    @Test
    public void avatarDataIsReadFromTheItemPayload() {
        final Element items = new Element("items");
        items.addChild("item").addChild("data", "urn:xmpp:avatar:data").setContent("BASE64");

        assertThat(AbstractParser.avatarDataFromItems(items)).isEqualTo("BASE64");
    }

    @Test
    public void thereIsNoAvatarDataWithoutAnItem() {
        assertThat(AbstractParser.avatarDataFromItems(new Element("items"))).isNull();
    }
}
