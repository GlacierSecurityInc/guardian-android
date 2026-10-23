package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.location.Location;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link LocationHelper} does two jobs for shared locations: parse the {@code lat,long} out of a
 * geo: URI someone else sent, and decide which of two fixes to believe.
 *
 * <p>The parser takes remote input, so the interesting cases are the malformed ones. Every one of
 * them leaves by the same door: null for nothing at all, NumberFormatException for anything that
 * is not a pair of numbers. Pinned here so the callers' handling stays honest about what can come
 * out.
 *
 * <p>Robolectric rather than plain JUnit: {@code isBetterLocation} reads back everything it is
 * given through {@link Location}, whose mockable-jar stub answers 0/null for every getter -- the
 * whole comparison would collapse to one branch and pass vacuously.
 */
@RunWith(RobolectricTestRunner.class)
public class LocationHelperTest {

    private static final long SIGNIFICANT = Config.Map.LOCATION_FIX_SIGNIFICANT_TIME_DELTA;

    private static Location location(
            final String provider, final long time, final float accuracy) {
        final Location location = new Location(provider);
        location.setTime(time);
        location.setAccuracy(accuracy);
        return location;
    }

    // ---- parsing --------------------------------------------------------------------------

    @Test
    public void aPlainLatLongParses() {
        final GeoPoint point = LocationHelper.parseLatLong("51.5074,-0.1278");
        assertThat(point.getLatitude()).isWithin(1e-9).of(51.5074);
        assertThat(point.getLongitude()).isWithin(1e-9).of(-0.1278);
    }

    @Test
    public void aTrailingZoomQueryIsStrippedFromTheLongitude() {
        final GeoPoint point = LocationHelper.parseLatLong("51.5074,-0.1278?z=16");
        assertThat(point.getLongitude()).isWithin(1e-9).of(-0.1278);
    }

    @Test
    public void nullAndEmptyParseToNothing() {
        assertThat(LocationHelper.parseLatLong(null)).isNull();
        assertThat(LocationHelper.parseLatLong("")).isNull();
    }

    @Test
    public void aNonNumericCoordinateIsRejected() {
        assertThrows(NumberFormatException.class, () -> LocationHelper.parseLatLong("north,west"));
    }

    /**
     * Malformed coordinate pairs should fail with the parser's supported invalid-input mode rather
     * than leaking implementation details like array-bounds failures.
     */
    @Test
    public void aCoordinatePairWithNoCommaIsRejected() {
        assertThrows(NumberFormatException.class, () -> LocationHelper.parseLatLong("51.5074"));
    }

    /**
     * The other two ways the split fails to yield a pair. A trailing comma is the subtle one:
     * String.split drops the trailing empty field, so "51.5074," is a one-element array too.
     */
    @Test
    public void anythingOtherThanTwoCoordinatesIsRejected() {
        assertThrows(NumberFormatException.class, () -> LocationHelper.parseLatLong("51.5074,"));
        assertThrows(
                NumberFormatException.class, () -> LocationHelper.parseLatLong("51.5074,-0.1278,9"));
    }

    // ---- which fix to believe ---------------------------------------------------------------

    @Test
    public void anyFixBeatsNoFix() {
        assertThat(LocationHelper.isBetterLocation(location("gps", 0L, 100f), null)).isTrue();
    }

    @Test
    public void aSignificantlyNewerFixWinsEvenIfLessAccurate() {
        final Location previous = location("gps", 1_000_000L, 5f);
        final Location candidate = location("gps", 1_000_000L + SIGNIFICANT + 1, 500f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isTrue();
    }

    @Test
    public void aSignificantlyOlderFixLosesEvenIfMoreAccurate() {
        final Location previous = location("gps", 1_000_000L, 500f);
        final Location candidate = location("gps", 1_000_000L - SIGNIFICANT - 1, 5f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isFalse();
    }

    @Test
    public void amongRecentFixesTheMoreAccurateOneWins() {
        final Location previous = location("gps", 1_000_000L, 50f);
        final Location candidate = location("gps", 1_000_000L - 1_000, 10f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isTrue();
    }

    @Test
    public void aNewerFixOfEqualAccuracyWins() {
        final Location previous = location("gps", 1_000_000L, 20f);
        final Location candidate = location("gps", 1_000_500L, 20f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isTrue();
    }

    /** Slightly worse but newer is acceptable from the same provider, which is the common case. */
    @Test
    public void aNewerSlightlyLessAccurateFixFromTheSameProviderWins() {
        final Location previous = location("gps", 1_000_000L, 20f);
        final Location candidate = location("gps", 1_000_500L, 100f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isTrue();
    }

    @Test
    public void aNewerSlightlyLessAccurateFixFromAnotherProviderLoses() {
        final Location previous = location("gps", 1_000_000L, 20f);
        final Location candidate = location("network", 1_000_500L, 100f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isFalse();
    }

    @Test
    public void aMuchLessAccurateFixLosesEvenFromTheSameProvider() {
        final Location previous = location("gps", 1_000_000L, 20f);
        final Location candidate = location("gps", 1_000_500L, 500f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isFalse();
    }

    @Test
    public void anOlderLessAccurateFixLoses() {
        final Location previous = location("gps", 1_000_000L, 20f);
        final Location candidate = location("gps", 999_500L, 50f);
        assertThat(LocationHelper.isBetterLocation(candidate, previous)).isFalse();
    }
}
