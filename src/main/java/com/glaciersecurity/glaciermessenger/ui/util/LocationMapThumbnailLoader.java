package com.glaciersecurity.glaciermessenger.ui.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;
import com.glaciersecurity.glaciermessenger.utils.BitmapUtils;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.mapbox.maps.Style;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Loads a Mapbox Static Images API thumbnail for a shared-location bubble, off the UI thread,
 * with a small in-memory cache so scrolling a message back into view doesn't re-fetch it. Mirrors
 * {@link LocationLabelResolver}'s tag-based guard against a view being recycled mid-fetch. Leaves
 * {@code target} untouched (falling back to the plain placeholder background) when no Mapbox
 * access token is configured or the fetch fails.
 */
public class LocationMapThumbnailLoader {

    private static final String STYLE_URI_PREFIX = "mapbox://styles/";
    private static final String FALLBACK_STYLE_ID = "mapbox/dark-v11";
    private static final int CACHE_SIZE_BYTES = 6 * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
                @Override
                protected int sizeOf(final String key, final Bitmap bitmap) {
                    return bitmap.getByteCount();
                }
            };

    private LocationMapThumbnailLoader() {}

    public static void loadInto(
            final Context context,
            final GeoPoint point,
            final int widthPx,
            final int heightPx,
            final ImageView target) {
        target.setTag(point);

        // mapbox_access_token is defined in a gitignored resource file supplied on real build
        // machines, not always present (e.g. this repo's CI), so it can't be referenced as
        // R.string.mapbox_access_token without breaking those builds — look it up by name instead.
        final Resources resources = context.getResources();
        final int tokenResId =
                resources.getIdentifier("mapbox_access_token", "string", context.getPackageName());
        if (tokenResId == 0) {
            return;
        }
        final String token = resources.getString(tokenResId);
        if (TextUtils.isEmpty(token)) {
            return;
        }

        final String cacheKey = cacheKey(point, widthPx, heightPx);
        final Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        final String url = buildStaticImageUrl(point, widthPx, heightPx, token);
        EXECUTOR.execute(
                () -> {
                    final Bitmap bitmap = fetchBitmap(url, Math.max(widthPx, heightPx));
                    if (bitmap == null) {
                        return;
                    }
                    CACHE.put(cacheKey, bitmap);
                    MAIN.post(
                            () -> {
                                if (target.getTag() == point) {
                                    target.setImageBitmap(bitmap);
                                }
                            });
                });
    }

    static String buildStaticImageUrl(
            final GeoPoint point, final int widthPx, final int heightPx, final String token) {
        return String.format(
                Locale.US,
                "https://api.mapbox.com/styles/v1/%s/static/%.6f,%.6f,%.1f/%dx%d?access_token=%s",
                styleId(),
                point.getLongitude(),
                point.getLatitude(),
                Config.Map.FINAL_ZOOM_LEVEL,
                Math.max(1, widthPx),
                Math.max(1, heightPx),
                token);
    }

    private static String styleId() {
        if (Style.DARK.startsWith(STYLE_URI_PREFIX)) {
            return Style.DARK.substring(STYLE_URI_PREFIX.length());
        }
        return FALLBACK_STYLE_ID;
    }

    private static String cacheKey(final GeoPoint point, final int widthPx, final int heightPx) {
        return String.format(
                Locale.US,
                "%.5f,%.5f,%dx%d",
                point.getLatitude(),
                point.getLongitude(),
                widthPx,
                heightPx);
    }

    private static Bitmap fetchBitmap(final String url, final int maxSizePx) {
        final Request request = new Request.Builder().url(url).get().build();
        try (Response response = OkHttpClientProvider.getOkHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            // The static-images request already asks for the bubble's size, but Mapbox honours
            // it loosely (and serves @2x for some styles), so decode against it as well - these
            // bitmaps are held in a 6 MB LruCache, where an oversized one costs several slots.
            return BitmapUtils.decodeSampledStream(response.body().byteStream(), maxSizePx);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }
}
