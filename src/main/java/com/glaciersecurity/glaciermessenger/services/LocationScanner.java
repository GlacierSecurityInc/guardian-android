package com.glaciersecurity.glaciermessenger.services;

import android.Manifest.permission;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.Handler;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.WakeLockHelper;



import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static android.location.LocationRequest.QUALITY_HIGH_ACCURACY;
import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.buildJwtJsonRequestBuilder;
import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A location listener to receive update of location data from the system.
 */
public class LocationScanner implements LocationListener {

    private Context mContext;
    private Handler mHandler;
    private LocationManager mLocationManager;
    private Location mPrevLocation;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private PowerManager.WakeLock mSendWakeLock;
    private boolean mRegistered = false;
    private boolean mUseFuse = false;
    private String user;
    private String org;
    private ExecutorService mSendExecutor;
    // delivers NETWORK_PROVIDER callbacks on API 31+; owned here so stop() can shut it down
    private ExecutorService mProviderExecutor;

    public LocationScanner(Context context, boolean fuse) {
        mContext = context;
        mHandler = new Handler();
        mLocationManager =
              (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        mPrevLocation = new Location(" ");
        mPowerManager =
              (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (mPowerManager != null) {
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LocationScanner.class.getName());
            mSendWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    LocationScanner.class.getName() + ":send");
        }
        mUseFuse = fuse;
        mSendExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) {
            Log.w(Config.LOGTAG, "onLocationChanged, NULL location!!");
            return;
        }
        mPrevLocation.setLatitude(location.getLatitude());
        mPrevLocation.setLongitude(location.getLongitude());
        mPrevLocation.setTime(location.getTime());
        mPrevLocation.setAccuracy(location.getAccuracy());

        sendLocation(location);
    }

    private void sendLocation(Location location) {
        try {
            WakeLockHelper.acquire(mSendWakeLock);
            ensureSendExecutor();
            mSendExecutor.execute(() -> {
                try {
                    SendTrackingRequest(location);
                } finally {
                    WakeLockHelper.release(mSendWakeLock);
                }
            });
        } catch (final Exception e) {
            WakeLockHelper.release(mSendWakeLock);
        }
    }

    public void SendTrackingRequest(Location markerPoint) {
        if (user == null || org == null) {
            return;
        }
        if (markerPoint == null) {
            return;
        }

        final OkHttpClient client = getOkHttpClient();
        final String sendTrackingUrl = EndPointConfig.SEND_TRACKING_URL;

        // build JSON safely with JSONObject
        final String jsonPayload;
        try {
            final String deviceId =
                    user + "_" + Settings.Secure.getString(
                            mContext.getContentResolver(), Settings.Secure.ANDROID_ID);

            final JSONObject coordinates = new JSONObject();
            coordinates.put("latitude",  markerPoint.getLatitude());   // double — no injection risk
            coordinates.put("longitude", markerPoint.getLongitude());  // double — no injection risk

            final JSONObject payload = new JSONObject();
            payload.put("coordinates", coordinates);
            payload.put("timestamp",   parseTimestamp()); // fixed-format timestamp string
            payload.put("device_id",   deviceId);        // escaped by JSONObject.put()
            payload.put("username",    user);             // escaped by JSONObject.put()
            payload.put("organization", org);             // escaped by JSONObject.put()

            jsonPayload = payload.toString();
        } catch (final JSONException e) {
            // JSONException is thrown only for NaN/Infinity doubles, which
            // Location.getLatitude()/getLongitude() never produce.
            Log.e(Config.LOGTAG, "SendTrackingRequest: failed to build JSON payload", e);
            return;
        }
        // ─────────────────────────────────────────────────────────────────────

        final Request request;
        try {
            request = buildJwtJsonRequestBuilder(sendTrackingUrl, jsonPayload).build();
        } catch (final IOException e) {
            return;
        }

        try (final Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
            }
        } catch (final IOException e) {
        }
    }
    public String parseTimestamp()  {
        LocalDateTime dateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        return dateTime.format(formatter);
    }


    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
    }

    @Override
    public void onProviderEnabled(String s) {
    }

    @Override
    public void onProviderDisabled(String s) {
    }

    /**
     * To register to the system for location update. It is strongly recommended
     * register to "Network" and "GPS" providers separately to achieve the better
     * reliability. It's also recommended set 'quality' to 'QUALITY_HIGH_ACCURACY' for
     * the Network provider in order to receive highr rate of location data update from
     * the system. By default, GPS provider is set to 'QUALITY_HIGH_ACCURACY'.
     */
    public void start(long frequency, String user, String org) {
        Log.i(Config.LOGTAG, "start, registering listener");
        this.user = user;
        this.org = org;
        ensureSendExecutor();
        if (!mRegistered && mUseFuse) {
            if (ContextCompat.checkSelfPermission(mContext,
                permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mLocationManager.requestLocationUpdates(
                       LocationManager.FUSED_PROVIDER, frequency, 0, this);
                mRegistered = true;
                enableWakelock();
            } else {
                return;
            }
        } else if (!mRegistered) {
            if (ContextCompat.checkSelfPermission(mContext,
                permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            // Network and GPS are registered independently: a device without one of them
            // should still receive updates from the other, and mRegistered must only be set
            // when at least one registration actually succeeded.
            boolean registered = false;
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    LocationRequest request = new LocationRequest.Builder(frequency)
                            .setQuality(QUALITY_HIGH_ACCURACY)
                            .build();
                    mLocationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            request, ensureProviderExecutor(), this);
                } else {
                    mLocationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, frequency, 0, this);
                }
                registered = true;
            } catch (Exception ex) {
                Log.e(Config.LOGTAG, "Network provider not available, message: "+ex.getMessage());
            }
            try {
                mLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, frequency, 0, this);
                registered = true;
            } catch (Exception ex) {
                Log.e(Config.LOGTAG, "GPS provider not available, message: "+ex.getMessage());
            }
            if (registered) {
                enableWakelock();
                mRegistered = true;
            }
        }
    }

    public void stop() {
        if (mRegistered) {
            mLocationManager.removeUpdates(this);
            mRegistered = false;
            disableWakelock();
        }
        shutdownProviderExecutor();
        shutdownSendExecutor();
    }

    /**
     * It's necessary to enable the wakelock when the listener is registered
     * to avoid device to get into deep sleep and hance stopping supply of
     * location data from the system.
     */
    private void enableWakelock() {
        synchronized (this) {
            if (!mWakeLock.isHeld()) {
                mWakeLock.acquire();
            }
        }
    }

    private void disableWakelock() {
        synchronized (this) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    private synchronized void ensureSendExecutor() {
        if (mSendExecutor == null || mSendExecutor.isShutdown()) {
            mSendExecutor = Executors.newSingleThreadExecutor();
        }
    }

    private synchronized void shutdownSendExecutor() {
        if (mSendExecutor != null && !mSendExecutor.isShutdown()) {
            mSendExecutor.shutdown();
        }
    }

    private synchronized Executor ensureProviderExecutor() {
        if (mProviderExecutor == null || mProviderExecutor.isShutdown()) {
            mProviderExecutor = Executors.newSingleThreadExecutor();
        }
        return mProviderExecutor;
    }

    private synchronized void shutdownProviderExecutor() {
        if (mProviderExecutor != null && !mProviderExecutor.isShutdown()) {
            mProviderExecutor.shutdown();
        }
    }

    public void restart(long updateInterval, String user, String org) {
        stop();
        start(updateInterval, user, org);
    }
}

