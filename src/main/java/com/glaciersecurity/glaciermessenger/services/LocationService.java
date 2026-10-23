package com.glaciersecurity.glaciermessenger.services;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;

import android.Manifest.permission;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.text.format.DateUtils;
import com.glaciersecurity.glaciermessenger.utils.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;

/**
 * The simplified service is to demonstrate how to make request to the system
 * to get supply of location data.
 *
 * Check the AndroidManifest for a number of essential permissions required to
 * conduct operations.
 */
public class LocationService extends Service {
    public static final int DEFAULT_INTERVAL = 5 * 60 * 1000;
    private static final String CHANNEL_ID = "LocationServiceChannel";

    private static final String TAG = Config.LOGTAG + "LocationService";
    public static final String EXTRA_LOCATION_SERVICE = "EXTRA_LOCATION_SERVICE";
    public static final int NOTIFICATION_ID = 2222;
    private static long updateInterval = DEFAULT_INTERVAL;
    private long updateIntervalPrevious = DEFAULT_INTERVAL;


    private LocationScanner mLocationScanner;
    private Handler mHandler;
    private NotificationManagerCompat mNotificationManager;
    private static boolean mStatus = false;
    private static boolean isRunning = false;

    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * The service is started as foreground which makes it essential to
     * invoke "startForeground" or it aborts.
     *
     * It's up to apps to provide better way of permission checking and requesting
     * based off AndroidX's specifications.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        if (isRunning) {
            stopSelf();
            return;
        }
        isRunning = true;
        mHandler = new Handler();
        mLocationScanner = new LocationScanner(this, false);
        updateInterval = getFrequencyFromPrefs();
        mNotificationManager = NotificationManagerCompat.from(this);
        Notification notification = getNotification();
        try {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Exception e) {
            mNotificationManager.cancel(NOTIFICATION_ID);
            broadcastScannerState(false);

            isRunning = false;
            Log.w(TAG, "onCreate, permission not granted, error: "+e.getMessage());
            stopSelf();
        }
    }

    private Notification getNotification() {
        String notificationIdString = String.valueOf(NOTIFICATION_ID);
        NotificationChannel channel = new NotificationChannel(
                        notificationIdString,
                CHANNEL_ID,
                        NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null); // No sound
        channel.enableVibration(false); // No vibration
        channel.setShowBadge(false);

        mNotificationManager.createNotificationChannel(channel);

        Notification.Builder builder = new Notification.Builder(this, channel.getId())
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentTitle("Location Tracking")
                .setContentText(String.format(getString(R.string.pref_live_location_activity_summary_dynamic), toDisplayTimeFreq()))
                .setSmallIcon(R.drawable.ic_location_pin_24dp)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);
        Notification notification = builder.build();
        return notification;
    }

    private long getFrequencyFromPrefs() {
        long intpref = DEFAULT_INTERVAL;
        try {
            String freqpref = getPreferences(getApplicationContext())
                    .getString("tracking_frequency",
                            getResources().getString(R.string.tracking_frequency));
            intpref = Long.parseLong(freqpref) * 1000L;
        } catch (Exception ignore) {
        }
        return intpref;
    }

    public static long getFrequency() {
        return updateInterval;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (mLocationScanner != null) {
            mLocationScanner.stop();
        }
        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFICATION_ID);
        }
        broadcastScannerState(false);
        stopForeground(true);
        super.onDestroy();
    }

    private void broadcastScannerState(boolean running) {
        Intent intent = new Intent(running ? Config.ACTION_LOCATION_SCANNER_STARTED : Config.ACTION_LOCATION_SCANNER_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private String toDisplayTimeFreq(){
        int intpref  = 300;
        try {
            String freqpref = getPreferences(getApplicationContext()).getString("tracking_frequency", getResources().getString(R.string.tracking_frequency));
            intpref =  Integer.parseInt(freqpref);
        }
        catch (Exception ignore){
        }
        long totalSecs = intpref;
        return DateUtils.formatElapsedTime(totalSecs);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.w(TAG, "onStartCommand, intent: "+intent+", flags: "+flags+", status: "+mStatus);
        boolean hasPermission = ContextCompat.checkSelfPermission(this,
                permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        mStatus = hasPermission;
        if (!hasPermission) {
            Log.w(TAG, "onStartCommand, missing location permission");
            mNotificationManager.cancel(NOTIFICATION_ID);
            broadcastScannerState(false);
            isRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) return START_STICKY;
        int ind = intent.getIntExtra(EXTRA_LOCATION_SERVICE, -1);
        switch (ind) {
            case 1: {
                updateIntervalPrevious = updateInterval;
                updateInterval = intent.getLongExtra("frequency", getFrequencyFromPrefs());
                String user = intent.getStringExtra("user");
                String org = intent.getStringExtra("org");
                if (updateIntervalPrevious == updateInterval) {
                    mLocationScanner.start(updateInterval, user, org);
                } else {
                    updateNotification();
                    mLocationScanner.restart(updateInterval, user, org);
                }
                broadcastScannerState(true);
                isRunning = true;
                break;
            }
            case 2: {
                mLocationScanner.stop();
                mNotificationManager.cancel(NOTIFICATION_ID);
                isRunning = false;
                broadcastScannerState(false);
                stopForeground(true);
                stopSelf();
                break;
            }
            default:
                Log.w(TAG, "onStartCommand, unknown indicator");
                break;
        }
        return START_STICKY;
    }

    private void updateNotification() {
        if (ActivityCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mNotificationManager.notify(NOTIFICATION_ID, getNotification());

    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
