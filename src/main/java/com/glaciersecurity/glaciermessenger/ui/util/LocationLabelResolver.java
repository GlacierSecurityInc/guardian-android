package com.glaciersecurity.glaciermessenger.ui.util;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.TextView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves a short "locality, region"-style label for a shared-location bubble via the
 * on-device {@link Geocoder}, off the UI thread, with a small in-memory cache so scrolling a
 * message back into view doesn't re-geocode it. Falls back to formatted coordinates when
 * geocoding is unavailable or returns nothing.
 */
public class LocationLabelResolver {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, String> CACHE = new LruCache<>(64);

    private LocationLabelResolver() {}

    public static String coordinateLabel(final GeoPoint point) {
        return String.format(Locale.US, "%.4f, %.4f", point.getLatitude(), point.getLongitude());
    }

    /**
     * Shows the coordinate fallback immediately, then swaps in a geocoded label if one resolves
     * before {@code target} is recycled for a different message.
     */
    public static void resolveInto(final Context context, final GeoPoint point, final TextView target) {
        target.setText(coordinateLabel(point));
        target.setTag(point);

        final String cacheKey = cacheKey(point);
        final String cached = CACHE.get(cacheKey);
        if (cached != null) {
            target.setText(cached);
            return;
        }
        if (!Geocoder.isPresent()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(
                () -> {
                    final String label = geocode(appContext, point);
                    if (label == null) {
                        return;
                    }
                    CACHE.put(cacheKey, label);
                    MAIN.post(
                            () -> {
                                if (target.getTag() == point) {
                                    target.setText(label);
                                }
                            });
                });
    }

    private static String cacheKey(final GeoPoint point) {
        return String.format(Locale.US, "%.5f,%.5f", point.getLatitude(), point.getLongitude());
    }

    @SuppressWarnings("deprecation")
    private static String geocode(final Context context, final GeoPoint point) {
        try {
            final Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            final List<Address> addresses =
                    geocoder.getFromLocation(point.getLatitude(), point.getLongitude(), 1);
            if (addresses == null || addresses.isEmpty()) {
                return null;
            }
            final Address address = addresses.get(0);
            final String locality = address.getLocality();
            final String admin = address.getAdminArea();
            if (locality != null && admin != null) {
                return locality + ", " + admin;
            }
            if (locality != null) {
                return locality;
            }
            return address.getAddressLine(0);
        } catch (final Exception e) {
            return null;
        }
    }
}
