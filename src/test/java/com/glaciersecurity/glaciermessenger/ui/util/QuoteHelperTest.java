package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@link QuoteHelper} decides which lines of a message body are drawn as a quotation. It matters
 * because it is the only thing separating "what this person wrote" from "what this person is
 * quoting someone else as writing", and a false positive rewrites attribution inside the bubble
 * with no visible seam -- {@code >5 people came} styled as a quote of "5 people came".
 *
 * <p>The guillemet form is the subtle half: {@code «...»} used inline is a quotation *mark*, not a
 * block quote, so a closed pair must not start one. Only an unclosed opening guillemet at the
 * start of a line is treated as quoting.
 *
 * <p>Plain JUnit: the path is CharSequence arithmetic through UIHelper's line-start helpers.
 */
public class QuoteHelperTest {

    // ---- what starts a quote --------------------------------------------------------------

    @Test
    public void aLineBeginningWithAngleBracketIsAQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("> quoted text")).isTrue();
        assertThat(QuoteHelper.bodyContainsQuoteStart(">no space is fine too")).isTrue();
    }

    @Test
    public void indentationBeforeTheAngleBracketIsAllowed() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("   > quoted text")).isTrue();
    }

    @Test
    public void aQuoteCanStartOnAnyLineNotJustTheFirst() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("some reply\n> quoted text")).isTrue();
    }

    @Test
    public void anAngleBracketMidSentenceIsNotAQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("a > b")).isFalse();
        assertThat(QuoteHelper.bodyContainsQuoteStart("if x > y then")).isFalse();
    }

    /** Comparisons and arrows are the common false positives, and both read as ordinary text. */
    @Test
    public void aComparisonAtTheStartOfALineIsNotAQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart(">5 people came")).isFalse();
        assertThat(QuoteHelper.bodyContainsQuoteStart(">= 5")).isFalse();
    }

    @Test
    public void nestedAngleBracketsStillStartAQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart(">> doubly quoted")).isTrue();
    }

    @Test
    public void ordinaryTextContainsNoQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("nothing quoted here")).isFalse();
        assertThat(QuoteHelper.bodyContainsQuoteStart("")).isFalse();
    }

    // ---- guillemets -----------------------------------------------------------------------

    @Test
    public void anUnclosedOpeningGuillemetStartsAQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("»quoted text")).isTrue();
    }

    /** A closed pair is inline quotation, not a block quote -- restyling it would be wrong. */
    @Test
    public void aClosedGuillemetPairIsNotAQuote() {
        assertThat(QuoteHelper.bodyContainsQuoteStart("»quoted text«")).isFalse();
        assertThat(QuoteHelper.bodyContainsQuoteStart("he said »hello« to me")).isFalse();
    }

    @Test
    public void characterClassificationMatchesTheConstants() {
        assertThat(QuoteHelper.isPositionQuoteCharacter(">x", 0)).isTrue();
        assertThat(QuoteHelper.isPositionQuoteEndCharacter("<x", 0)).isTrue();
        assertThat(QuoteHelper.isPositionAltQuoteCharacter("»x", 0)).isTrue();
        assertThat(QuoteHelper.isPositionAltQuoteEndCharacter("«x", 0)).isTrue();
    }

    // ---- nesting depth --------------------------------------------------------------------

    @Test
    public void aSingleLevelOfQuotingIsNotTooDeep() {
        assertThat(QuoteHelper.isNestedTooDeeply("> once")).isFalse();
    }

    @Test
    public void twoLevelsOfQuotingIsTooDeep() {
        assertThat(QuoteHelper.isNestedTooDeeply(">> twice")).isTrue();
        assertThat(QuoteHelper.isNestedTooDeeply("> > twice with a space")).isTrue();
    }

    @Test
    public void aLineThatIsNotAQuoteIsNeverTooDeep() {
        assertThat(QuoteHelper.isNestedTooDeeply("plain text")).isFalse();
    }

    // ---- normalising guillemets to angle brackets -------------------------------------------

    @Test
    public void anOpeningGuillemetQuoteIsRewrittenAsAnAngleBracket() {
        assertThat(QuoteHelper.replaceAltQuoteCharsInText("»quoted"))
                .isEqualTo(">quoted");
    }

    @Test
    public void anInlineGuillemetPairIsLeftAlone() {
        final String inline = "he said »hello« to me";
        assertThat(QuoteHelper.replaceAltQuoteCharsInText(inline)).isEqualTo(inline);
    }

    @Test
    public void textWithoutGuillemetsIsUnchanged() {
        assertThat(QuoteHelper.replaceAltQuoteCharsInText("> already an angle bracket"))
                .isEqualTo("> already an angle bracket");
    }

    @Test
    public void aGuillemetQuoteOnALaterLineIsRewrittenToo() {
        assertThat(QuoteHelper.replaceAltQuoteCharsInText("reply\n»quoted"))
                .isEqualTo("reply\n>quoted");
    }
}
