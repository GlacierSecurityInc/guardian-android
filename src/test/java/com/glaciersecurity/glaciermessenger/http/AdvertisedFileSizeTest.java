package com.glaciersecurity.glaciermessenger.http;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * {@link HttpDownloadConnection#isUsableAdvertisedSize(Long)}, the gate on whether an incoming
 * transfer may skip the HEAD request.
 *
 * <p>The size comes from the peer's {@code size} attribute, which {@code Message.FileParams}
 * parses with {@code Longs.tryParse} and no bound, so negative and absurd values both arrive
 * intact. Taking the shortcut on one of those would start a download that never passed the
 * 100 MiB check, since that check lives in the HEAD path being skipped.
 */
public class AdvertisedFileSizeTest {

    /** Mirrors the private constant; kept here so a change to the cap fails a test, not silently. */
    private static final long MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024L;

    @Test
    public void anOrdinarySizeIsUsable() {
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(4L * 1024 * 1024)).isTrue();
    }

    @Test
    public void zeroIsUsable() {
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(0L)).isTrue();
    }

    @Test
    public void anAbsentSizeIsNotUsable() {
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(null)).isFalse();
    }

    @Test
    public void aNegativeSizeIsNotUsable() {
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(-1L)).isFalse();
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(Long.MIN_VALUE)).isFalse();
    }

    @Test
    public void aSizeOverTheDownloadCapIsNotUsable() {
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(MAX_DOWNLOAD_BYTES + 1)).isFalse();
    }

    @Test
    public void theLargestUsableSizeLeavesRoomForTheGcmTag() {
        // The caller adds 16 bytes for the tag; the boundary has to be below the cap by that much
        // so the addition can neither exceed the cap nor overflow.
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(MAX_DOWNLOAD_BYTES - 16)).isTrue();
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(MAX_DOWNLOAD_BYTES - 15)).isFalse();
    }

    @Test
    public void theSizesThatUsedToOverflowAreNotUsable() {
        // These are the values that motivated the check: adding the tag to any of them wrapped
        // past Long.MAX_VALUE and left a negative expected size.
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(Long.MAX_VALUE)).isFalse();
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(Long.MAX_VALUE - 15)).isFalse();
        assertThat(HttpDownloadConnection.isUsableAdvertisedSize(Long.MAX_VALUE - 16)).isFalse();
    }
}
