package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.regex.Pattern;

/**
 * {@link NotificationService#generateNickHighlightPattern} decides whether a group-chat message
 * notifies you. Both failure directions are bad and neither is visible: too loose and every
 * message containing your nick as a substring buzzes the phone, too tight and being addressed
 * directly goes unnoticed.
 *
 * <p>The nick is user-supplied and goes straight into a regex, so the {@code Pattern.quote} around
 * it is the part worth guarding -- without it a nick containing regex metacharacters either throws
 * at compile time or silently matches things it should not.
 *
 * <p>Plain JUnit: the method is {@code java.util.regex} and nothing else.
 */
public class NickHighlightPatternTest {

    private static boolean highlights(final String nick, final String body) {
        return NotificationService.generateNickHighlightPattern(nick).matcher(body).find();
    }

    @Test
    public void aNickOnItsOwnHighlights() {
        assertThat(highlights("alice", "alice")).isTrue();
    }

    @Test
    public void aNickAfterWhitespaceHighlights() {
        assertThat(highlights("alice", "ping alice")).isTrue();
        assertThat(highlights("alice", "ping\nalice")).isTrue();
    }

    @Test
    public void aNickFollowedByPunctuationHighlights() {
        assertThat(highlights("alice", "alice: can you look at this")).isTrue();
        assertThat(highlights("alice", "thanks alice!")).isTrue();
    }

    /** Punctuation is not whitespace, and the lookbehind only accepts start-of-body or a space. */
    @Test
    public void aNickPrecededByPunctuationDoesNotHighlight() {
        assertThat(highlights("alice", "cc:alice")).isFalse();
        assertThat(highlights("alice", "(alice)")).isFalse();
    }

    @Test
    public void aNickEmbeddedInALongerWordDoesNotHighlight() {
        assertThat(highlights("alice", "malice aforethought")).isFalse();
        assertThat(highlights("alice", "alicex is someone else")).isFalse();
    }

    /**
     * Highlighting is case-sensitive. Pinned as observed behaviour: being addressed as "Alice" at
     * the start of a sentence does not notify.
     */
    @Test
    public void highlightingIsCaseSensitive() {
        assertThat(highlights("alice", "Alice: hello")).isFalse();
    }

    /**
     * The nick is quoted, so its metacharacters are literal. Without the quote "a.c" would match
     * "abc" -- a nick that highlights on messages addressed to someone else entirely.
     */
    @Test
    public void regexMetacharactersInANickAreLiteral() {
        assertThat(highlights("a.c", "a.c hello")).isTrue();
        assertThat(highlights("a.c", "abc hello")).isFalse();
    }

    @Test
    public void aNickThatWouldNotCompileAsARegexIsStillUsable() {
        final Pattern pattern = NotificationService.generateNickHighlightPattern("[unclosed");
        assertThat(pattern.matcher("hi [unclosed!").find()).isTrue();
    }

    @Test
    public void aNickWithASpaceInItStillMatchesTheWholeNick() {
        assertThat(highlights("mad hatter", "hello mad hatter")).isTrue();
        assertThat(highlights("mad hatter", "hello mad")).isFalse();
    }
}
