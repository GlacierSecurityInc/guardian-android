package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@link PlaybackSpeedOptions} is the whole state machine behind the 1x/1.5x/2x button on a voice
 * message: the index is what gets persisted and restored, and every accessor normalises it. The
 * reason to test it rather than read it is the normalisation -- a stale or corrupt persisted index
 * must not throw from inside a list adapter's bind, and a negative one must not wrap the wrong way
 * and show a speed the audio is not actually playing at.
 *
 * <p>Plain JUnit: the arithmetic touches no Android API, and the label lookups are R constants.
 */
public class PlaybackSpeedOptionsTest {

    private static final int OPTION_COUNT = 3;

    @Test
    public void theDefaultIsNormalSpeed() {
        assertThat(PlaybackSpeedOptions.defaultIndex()).isEqualTo(0);
        assertThat(PlaybackSpeedOptions.defaultSpeed()).isEqualTo(1.0f);
        assertThat(PlaybackSpeedOptions.speedForIndex(PlaybackSpeedOptions.defaultIndex()))
                .isEqualTo(PlaybackSpeedOptions.defaultSpeed());
    }

    @Test
    public void cyclingVisitsEverySpeedAndReturnsToTheStart() {
        assertThat(PlaybackSpeedOptions.speedForIndex(0)).isEqualTo(1.0f);
        assertThat(PlaybackSpeedOptions.speedForIndex(1)).isEqualTo(1.5f);
        assertThat(PlaybackSpeedOptions.speedForIndex(2)).isEqualTo(2.0f);

        int index = PlaybackSpeedOptions.defaultIndex();
        index = PlaybackSpeedOptions.cycleIndex(index);
        assertThat(index).isEqualTo(1);
        index = PlaybackSpeedOptions.cycleIndex(index);
        assertThat(index).isEqualTo(2);
        index = PlaybackSpeedOptions.cycleIndex(index);
        assertThat(index).isEqualTo(0);
    }

    @Test
    public void cyclingFromAnOutOfRangeIndexLandsBackInRange() {
        assertThat(PlaybackSpeedOptions.cycleIndex(99)).isEqualTo(1);
        assertThat(PlaybackSpeedOptions.cycleIndex(-1)).isEqualTo(0);
    }

    @Test
    public void normalisingIsIdentityInsideTheRange() {
        for (int i = 0; i < OPTION_COUNT; i++) {
            assertThat(PlaybackSpeedOptions.normalizeIndex(i)).isEqualTo(i);
        }
    }

    /**
     * Java's % keeps the sign of the dividend, so the explicit correction in normalizeIndex is
     * what stops a negative persisted index producing a negative array subscript.
     */
    @Test
    public void aNegativeIndexWrapsForwardsRatherThanThrowing() {
        assertThat(PlaybackSpeedOptions.normalizeIndex(-1)).isEqualTo(2);
        assertThat(PlaybackSpeedOptions.normalizeIndex(-2)).isEqualTo(1);
        assertThat(PlaybackSpeedOptions.normalizeIndex(-3)).isEqualTo(0);
        assertThat(PlaybackSpeedOptions.speedForIndex(-1)).isEqualTo(2.0f);
    }

    @Test
    public void aWildlyOutOfRangeIndexIsStillAnswerable() {
        assertThat(PlaybackSpeedOptions.normalizeIndex(Integer.MAX_VALUE)).isLessThan(OPTION_COUNT);
        assertThat(PlaybackSpeedOptions.speedForIndex(Integer.MAX_VALUE)).isAtLeast(1.0f);
        assertThat(PlaybackSpeedOptions.speedForIndex(Integer.MIN_VALUE + 1)).isAtLeast(1.0f);
    }

    /** Each speed needs its own label, or the button shows a speed other than the one in effect. */
    @Test
    public void everySpeedHasADistinctLabelResource() {
        final int first = PlaybackSpeedOptions.labelResIdForIndex(0);
        final int second = PlaybackSpeedOptions.labelResIdForIndex(1);
        final int third = PlaybackSpeedOptions.labelResIdForIndex(2);
        assertThat(first).isNotEqualTo(second);
        assertThat(second).isNotEqualTo(third);
        assertThat(first).isNotEqualTo(third);
    }

    @Test
    public void labelLookupNormalisesTheIndexToo() {
        assertThat(PlaybackSpeedOptions.labelResIdForIndex(-1))
                .isEqualTo(PlaybackSpeedOptions.labelResIdForIndex(2));
        assertThat(PlaybackSpeedOptions.labelResIdForIndex(OPTION_COUNT))
                .isEqualTo(PlaybackSpeedOptions.labelResIdForIndex(0));
    }
}
