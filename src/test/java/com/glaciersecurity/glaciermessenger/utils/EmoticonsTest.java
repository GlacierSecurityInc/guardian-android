package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@code isOnlyEmoji} decides whether an incoming message is rendered as large standalone emoji
 * instead of ordinary text. That much is cosmetic, but the parser walks untrusted message bodies
 * code point by code point and {@code getEmojiPattern} compiles a regex out of what it finds, so
 * the interesting cases are the ones where it could disagree with itself: zero-width joiner
 * sequences, variation selectors, keycaps, flags and skin-tone modifiers.
 *
 * <p>Seeded from upstream Conversations' {@code eu.siacs.conversations.utils.EmoticonsTest}. This
 * fork's copy of {@code Emoticons} predates several of upstream's Unicode range updates, so two
 * of upstream's cases do not hold here; they are kept below as characterisation rather than
 * dropped, so the gap stays visible.
 */
public class EmoticonsTest {

    @Test
    public void aPlainLetterIsNotEmoji() {
        assertThat(Emoticons.isEmoji("a")).isFalse();
    }

    @Test
    public void aSingleCatIsEmoji() {
        assertThat(Emoticons.isEmoji("🐈")).isTrue();
    }

    @Test
    public void aZeroWidthJoinerSequenceIsOneEmoji() {
        // Family = man + ZWJ + woman + ZWJ + boy. Splitting on the ZWJ would render three.
        assertThat(Emoticons.isEmoji("👨‍👩‍👦")).isTrue();
    }

    @Test
    public void aVariationSelectorSequenceIsOneEmoji() {
        // Heavy black heart + VS16, the emoji-presentation selector.
        assertThat(Emoticons.isEmoji("❤️")).isTrue();
    }

    @Test
    public void twoEmojiAreNotASingleEmoji() {
        assertThat(Emoticons.isEmoji("👍👎")).isFalse();
    }

    @Test
    public void twoEmojiAreStillOnlyEmoji() {
        assertThat(Emoticons.isOnlyEmoji("👍👎")).isTrue();
    }

    @Test
    public void oneEmojiFollowedByALetterIsNotOnlyEmoji() {
        assertThat(Emoticons.isOnlyEmoji("👍a")).isFalse();
    }

    @Test
    public void textAroundAnEmojiIsNotOnlyEmoji() {
        assertThat(Emoticons.isOnlyEmoji("hello 👍")).isFalse();
    }

    @Test
    public void aKeycapIsEmoji() {
        // digit + variation selector + combining enclosing keycap
        assertThat(Emoticons.isEmoji("1️⃣")).isTrue();
    }

    @Test
    public void aRegionalIndicatorFlagIsEmoji() {
        assertThat(Emoticons.isEmoji("🇩🇪")).isTrue();
    }

    @Test
    public void aSkinToneModifierStaysAttachedToItsBaseEmoji() {
        // Thumbs up + Fitzpatrick type-4. If the modifier detached, this would read as two.
        assertThat(Emoticons.isEmoji("👍🏽")).isTrue();
    }

    @Test
    public void anEmptyStringIsNotOnlyEmoji() {
        assertThat(Emoticons.isOnlyEmoji("")).isFalse();
    }

    @Test
    public void codePointsOutsideThisForksRangesAreNotRecognised() {
        // Characterisation, not an endorsement. Upstream detects all three of these; this copy
        // does not, because no UnicodeRange here covers the Arrows block (U+2190..U+21FF) or
        // Miscellaneous Symbols and Arrows (U+2B00..U+2BFF). Anything built on top of them is
        // missed as well -- the third case is upstream's black cat, cat + ZWJ + U+2B1B.
        // If the ranges are refreshed from upstream, this test flips and should be deleted.
        assertThat(Emoticons.isEmoji("⬛")).isFalse();
        assertThat(Emoticons.isEmoji("↩️")).isFalse();
        assertThat(Emoticons.isEmoji("🐈‍⬛")).isFalse();
    }

    @Test
    public void theEmojiPatternGivesUpOnAnAbsurdlyLongRun() {
        // MAX_EMOIJS (42) caps how large an alternation generatePattern will build, so a body
        // stuffed with distinct emoji cannot be turned into a pathological regex. Past the cap
        // it returns the empty pattern rather than a 42-branch one.
        final StringBuilder many = new StringBuilder();
        for (int cp = 0x1F600; cp < 0x1F600 + 43; ++cp) {
            many.appendCodePoint(cp);
        }
        assertThat(Emoticons.getEmojiPattern(many.toString()).pattern()).isEmpty();

        final StringBuilder few = new StringBuilder();
        for (int cp = 0x1F600; cp < 0x1F600 + 3; ++cp) {
            few.appendCodePoint(cp);
        }
        assertThat(Emoticons.getEmojiPattern(few.toString()).pattern()).isNotEmpty();
    }
}
