package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.utils.ImStyleParser.Style;

import org.junit.Test;

import java.util.List;

/**
 * XEP-0393 message styling, applied to every incoming body. The parser returns spans by index,
 * and {@code StylingHelper} feeds those indices straight to {@code SpannableString.setSpan},
 * so an off-by-one here becomes an IndexOutOfBounds on a remote message. The boundary rules
 * (a directive only opens after whitespace, only closes before whitespace, and never spans a
 * newline) are what keep an adversarial body from producing a style that runs to the end.
 */
public class ImStyleParserTest {

    @Test
    public void boldIsOneSpanCoveringItsDelimiters() {
        final List<Style> styles = ImStyleParser.parse("*bold*");
        assertThat(styles).hasSize(1);
        assertThat(styles.get(0).getKeyword()).isEqualTo("*");
        assertThat(styles.get(0).getStart()).isEqualTo(0);
        assertThat(styles.get(0).getEnd()).isEqualTo(5);
    }

    @Test
    public void theOtherThreeDirectivesParseTheSameWay() {
        assertThat(ImStyleParser.parse("_italic_")).hasSize(1);
        assertThat(ImStyleParser.parse("~strike~")).hasSize(1);
        assertThat(ImStyleParser.parse("`code`")).hasSize(1);
    }

    @Test
    public void aDirectiveInTheMiddleOfAWordDoesNotOpenAStyle() {
        // Otherwise snake_case_identifiers and a*b*c arithmetic would render as styled text.
        assertThat(ImStyleParser.parse("a*b*c")).isEmpty();
        assertThat(ImStyleParser.parse("some_variable_name")).isEmpty();
    }

    @Test
    public void aDirectiveFollowedByWhitespaceDoesNotOpenAStyle() {
        assertThat(ImStyleParser.parse("* not a list item")).isEmpty();
    }

    @Test
    public void aStyleDoesNotRunPastANewline() {
        // An unterminated directive must not swallow the rest of the message.
        assertThat(ImStyleParser.parse("*unterminated\nnext line")).isEmpty();
    }

    @Test
    public void anUnterminatedDirectiveProducesNoStyle() {
        assertThat(ImStyleParser.parse("*unterminated")).isEmpty();
    }

    @Test
    public void anEmptyStyleIsRejected() {
        // "**" has nothing between the delimiters; ALLOW_EMPTY is false.
        assertThat(ImStyleParser.parse("**")).isEmpty();
    }

    @Test
    public void styleInsideASentenceIsFoundAtTheRightOffsets() {
        final List<Style> styles = ImStyleParser.parse("not *bold* here");
        assertThat(styles).hasSize(1);
        assertThat(styles.get(0).getStart()).isEqualTo(4);
        assertThat(styles.get(0).getEnd()).isEqualTo(9);
    }

    @Test
    public void stylesNest() {
        final List<Style> styles = ImStyleParser.parse("*bold _italic_*");
        assertThat(styles).hasSize(2);
        assertThat(styles.get(0).getKeyword()).isEqualTo("*");
        assertThat(styles.get(1).getKeyword()).isEqualTo("_");
        assertThat(styles.get(1).getStart()).isEqualTo(6);
        assertThat(styles.get(1).getEnd()).isEqualTo(13);
    }

    @Test
    public void codeSpansAreNotParsedForNestedStyles() {
        // Inside a code span the asterisks are literal.
        final List<Style> styles = ImStyleParser.parse("`*a*`");
        assertThat(styles).hasSize(1);
        assertThat(styles.get(0).getKeyword()).isEqualTo("`");
    }

    @Test
    public void aTripleBacktickBlockIsOneStyle() {
        final List<Style> styles = ImStyleParser.parse("```x```");
        assertThat(styles).hasSize(1);
        assertThat(styles.get(0).getKeyword()).isEqualTo("```");
        assertThat(styles.get(0).getStart()).isEqualTo(0);
        assertThat(styles.get(0).getEnd()).isEqualTo(6);
    }

    @Test
    public void everyReportedSpanLiesInsideTheText() {
        // The property that keeps StylingHelper from throwing on a hostile body.
        final String[] bodies = {
            "*a*", "*a _b_ c*", "```c```", "`x`", "~z~", "*a*b*c*", "_ _a_ _", "*_~`a`~_*",
            "**a**", "``a``", "*a\n*b*", "‎*a*‏",
        };
        for (final String body : bodies) {
            for (final Style style : ImStyleParser.parse(body)) {
                assertThat(style.getStart()).isAtLeast(0);
                assertThat(style.getEnd()).isLessThan(body.length());
                assertThat(style.getStart()).isLessThan(style.getEnd());
            }
        }
    }
}
