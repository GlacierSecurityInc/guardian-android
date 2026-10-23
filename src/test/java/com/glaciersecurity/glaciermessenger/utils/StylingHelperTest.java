package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link StylingHelper#subSequence} re-hangs spans that straddle the cut, and the only cases worth
 * pinning are the ones where the subsequence does not start at 0 -- that is when a span offset and
 * a span coordinate in the original string stop being the same number. {@link CharSequenceUtils}
 * splits a message body on newlines, so every line after the first takes that path.
 *
 * <p>Robolectric rather than plain JUnit: against the mockable android.jar SpannableString stores
 * nothing and getSpans returns null, so every assertion below would pass without the code running.
 */
@RunWith(RobolectricTestRunner.class)
public class StylingHelperTest {

    private static SpannableString styled(final String text, final int start, final int end) {
        final SpannableString spannable = new SpannableString(text);
        spannable.setSpan(
                new StyleSpan(android.graphics.Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    /** Every StyleSpan surviving the cut, as {begin, end} pairs in the subsequence's own frame. */
    private static int[][] styleSpans(final CharSequence cs) {
        final Spanned spanned = (Spanned) cs;
        final StyleSpan[] spans = spanned.getSpans(0, spanned.length(), StyleSpan.class);
        final int[][] ranges = new int[spans.length][];
        for (int i = 0; i < spans.length; ++i) {
            ranges[i] = new int[] {spanned.getSpanStart(spans[i]), spanned.getSpanEnd(spans[i])};
        }
        return ranges;
    }

    @Test
    public void spanStraddlingTheCutStaysInsideTheSubsequence() {
        // bold covers [2,18) of a 20 char body; the cut takes the last 10 chars.
        final CharSequence sub = StylingHelper.subSequence(styled("0123456789abcdefghij", 2, 18), 10, 20);

        assertThat(sub.length()).isEqualTo(10);
        for (final int[] range : styleSpans(sub)) {
            assertThat(range).isEqualTo(new int[] {0, 8});
        }
    }

    @Test
    public void spanCanReachTheEndOfTheSubsequence() {
        final CharSequence sub = StylingHelper.subSequence(styled("0123456789abcdefghij", 2, 20), 10, 20);

        assertThat(styleSpans(sub)).isNotEmpty();
        for (final int[] range : styleSpans(sub)) {
            assertThat(range).isEqualTo(new int[] {0, 10});
        }
    }

    @Test
    public void spanFullyInsideTheSubsequenceIsUnchanged() {
        final CharSequence sub = StylingHelper.subSequence(styled("0123456789abcdefghij", 12, 15), 10, 20);

        assertThat(styleSpans(sub)).isNotEmpty();
        for (final int[] range : styleSpans(sub)) {
            assertThat(range).isEqualTo(new int[] {2, 5});
        }
    }
}
