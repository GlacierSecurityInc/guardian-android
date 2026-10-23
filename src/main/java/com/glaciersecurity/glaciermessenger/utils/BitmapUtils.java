package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;

import com.glaciersecurity.glaciermessenger.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Downsampling wrappers around {@link BitmapFactory}. Decoding at full resolution allocates
 * width * height * 4 bytes no matter how small the view showing the image is, which is how a
 * single camera-resolution picture turns into tens of megabytes of heap; every decode here
 * runs a bounds-only pass first and picks an {@link BitmapFactory.Options#inSampleSize} that
 * keeps the shorter edge at or above the size the caller actually needs.
 *
 * <p>Like {@code BitmapFactory} itself these return {@code null} rather than throwing when the
 * source isn't a decodable image (or can't be read), so callers keep their existing null paths.
 */
public final class BitmapUtils {

    private BitmapUtils() {}

    /**
     * Shorter edge of the display, as the bound for an image drawn to fill the screen.
     *
     * <p>The shorter edge, not the longer one: {@link #calcSampleSize} keeps <em>both</em> edges
     * at or above the bound, so passing 2424 on a 1080x2424 screen refuses to halve a 4032x3024
     * photo at all (halving would put its height under 2424) and decodes the full 46 MB. Against
     * 1080 the same photo comes down to 2016x1512 - 11.6 MB, still more pixels than the screen
     * can show whichever way it is rotated.
     */
    public static int displayBound(final Context context) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels, metrics.heightPixels);
    }

    public static Bitmap decodeSampledFile(final String path, final int maxSize) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = calcSampleSize(options, maxSize);
        try {
            return BitmapFactory.decodeFile(path, options);
        } catch (final OutOfMemoryError e) {
            options.inSampleSize *= 2;
            return BitmapFactory.decodeFile(path, options);
        }
    }

    public static Bitmap decodeSampledResource(
            final Resources resources, final int resourceId, final int maxSize) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, resourceId, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = calcSampleSize(options, maxSize);
        try {
            return BitmapFactory.decodeResource(resources, resourceId, options);
        } catch (final OutOfMemoryError e) {
            options.inSampleSize *= 2;
            return BitmapFactory.decodeResource(resources, resourceId, options);
        }
    }

    /**
     * Decodes a stream that can only be read once (a network response body, a content
     * resolver stream) by buffering it, so the bounds pass and the real decode can both
     * read it. Does not close the stream; that stays with the caller.
     */
    public static Bitmap decodeSampledStream(final InputStream inputStream, final int maxSize) {
        final byte[] bytes;
        try {
            bytes = readFully(inputStream);
        } catch (final IOException | OutOfMemoryError e) {
            return null;
        }
        return decodeSampledByteArray(bytes, maxSize);
    }

    public static Bitmap decodeSampledByteArray(final byte[] bytes, final int maxSize) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = calcSampleSize(options, maxSize);
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (final OutOfMemoryError e) {
            options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        }
    }

    /**
     * Power-of-two sample size that keeps both edges at or above {@code maxSize} px, matching
     * the loop FileBackend has always used for message thumbnails.
     */
    public static int calcSampleSize(final BitmapFactory.Options options, final int maxSize) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (maxSize <= 0 || height <= 0 || width <= 0) {
            return inSampleSize;
        }
        if (height > maxSize || width > maxSize) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) > maxSize && (halfWidth / inSampleSize) > maxSize) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static byte[] readFully(final InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
