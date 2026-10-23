package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.geo.GeoPoint;

import org.junit.Test;

import java.util.Locale;

/**
 * The two pieces of a shared-location bubble that are pure formatting: the coordinate label shown
 * under it, and the Mapbox static-image URL fetched for the thumbnail.
 *
 * <p>Both are built with {@code String.format}, and both have to be built with {@link Locale#US}
 * regardless of device locale. A comma decimal separator would make the label read "51,5074,
 * -0,1278" and -- the part that actually breaks -- would produce a Mapbox URL whose comma-separated
 * lon,lat path segment has four commas in it, so the thumbnail silently never loads for anyone in
 * a comma-decimal locale.
 *
 * <p>The lon,lat ordering in the URL is the other pinned invariant: Mapbox takes longitude first
 * while everything else here is lat-first, so a swap puts the pin in the wrong hemisphere without
 * failing.
 *
 * <p>Plain JUnit: both methods are String.format over doubles.
 */
public class LocationBubbleFormattingTest {

    private static final GeoPoint LONDON = new GeoPoint(51.5074, -0.1278);

    @Test
    public void theCoordinateLabelIsFourDecimalPlacesLatitudeFirst() {
        assertThat(LocationLabelResolver.coordinateLabel(LONDON)).isEqualTo("51.5074, -0.1278");
    }

    @Test
    public void theCoordinateLabelUsesADotEvenInACommaDecimalLocale() {
        final Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(LocationLabelResolver.coordinateLabel(LONDON)).isEqualTo("51.5074, -0.1278");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void theCoordinateLabelRoundsRatherThanTruncates() {
        assertThat(LocationLabelResolver.coordinateLabel(new GeoPoint(0.00005, -0.00005)))
                .isEqualTo("0.0001, -0.0001");
    }

    @Test
    public void theThumbnailUrlCarriesLongitudeBeforeLatitude() {
        final String url = LocationMapThumbnailLoader.buildStaticImageUrl(LONDON, 320, 180, "tok");
        assertThat(url).contains("/static/-0.127800,51.507400,");
    }

    @Test
    public void theThumbnailUrlCarriesTheRequestedPixelSizeAndToken() {
        final String url = LocationMapThumbnailLoader.buildStaticImageUrl(LONDON, 320, 180, "tok");
        assertThat(url).startsWith("https://api.mapbox.com/styles/v1/");
        assertThat(url).contains("/320x180?");
        assertThat(url).endsWith("access_token=tok");
    }

    /**
     * A view that has not been measured yet reports zero, and Mapbox rejects a 0x0 request, so the
     * size is clamped to at least one pixel rather than producing a URL that always 422s.
     */
    @Test
    public void aZeroOrNegativeSizeIsClampedToOnePixel() {
        assertThat(LocationMapThumbnailLoader.buildStaticImageUrl(LONDON, 0, 0, "tok"))
                .contains("/1x1?");
        assertThat(LocationMapThumbnailLoader.buildStaticImageUrl(LONDON, -5, -5, "tok"))
                .contains("/1x1?");
    }

    @Test
    public void theThumbnailUrlUsesADotEvenInACommaDecimalLocale() {
        final Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(LocationMapThumbnailLoader.buildStaticImageUrl(LONDON, 320, 180, "tok"))
                    .contains("/static/-0.127800,51.507400,");
        } finally {
            Locale.setDefault(original);
        }
    }

    /** The style is a Mapbox style *id*, so the {@code mapbox://styles/} URI prefix must be gone. */
    @Test
    public void theStyleIsAnIdNotAStyleUri() {
        final String url = LocationMapThumbnailLoader.buildStaticImageUrl(LONDON, 320, 180, "tok");
        assertThat(url).doesNotContain("mapbox://");
        final String styleId =
                url.substring(
                        "https://api.mapbox.com/styles/v1/".length(), url.indexOf("/static/"));
        assertThat(styleId).isNotEmpty();
        assertThat(styleId).doesNotContain("://");
    }
}
