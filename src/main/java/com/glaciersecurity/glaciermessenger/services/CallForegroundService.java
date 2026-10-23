package com.glaciersecurity.glaciermessenger.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.utils.AppLifecycleHandler;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service used to keep Twilio voice calls alive when the UI is backgrounded.
 */
public class CallForegroundService extends Service {
    public static final String ACTION_START = "com.glaciersecurity.glaciermessenger.action.START_CALL_SERVICE";
    public static final String ACTION_STOP = "com.glaciersecurity.glaciermessenger.action.STOP_CALL_SERVICE";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "twilio_call";

    /** Set once this service is actually holding foreground state; see {@link #start}. */
    private static final AtomicBoolean IN_FOREGROUND = new AtomicBoolean(false);

    /**
     * Starts (or refreshes) the phoneCall foreground service covering an in-progress Twilio call.
     *
     * <p>phoneCall is a restricted foreground service type, so an app targeting Android 15+ may
     * not start it from a BOOT_COMPLETED receiver; the platform throws
     * ForegroundServiceStartNotAllowedException at startForegroundService(), before
     * onStartCommand() runs, which is why the gate has to live here rather than in the service.
     *
     * <p>Every real caller arrives from ExternalCallActivity, so the process always has a started
     * Activity; a boot-originated start never does. Refreshing a notification for a service that
     * is already foreground is not a background start, so allow that unconditionally.
     */
    public static void start(final Context context) {
        if (!IN_FOREGROUND.get() && !AppLifecycleHandler.isAppInForeground()) {
            Log.w(Config.LOGTAG, "refusing to start call foreground service with no started activity");
            return;
        }
        final Intent intent = new Intent(context, CallForegroundService.class);
        intent.setAction(ACTION_START);
        Compatibility.startPhoneCallForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            IN_FOREGROUND.set(false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Only an explicit ACTION_START from an in-progress Twilio call may take this service
        // into the foreground. phoneCall is a restricted foreground service type: apps targeting
        // Android 15+ may not start it from a BOOT_COMPLETED receiver, and doing so throws
        // ForegroundServiceStartNotAllowedException. A null intent (system re-delivery after the
        // process was killed) or any unrecognised action is not a live call, so stop instead of
        // going foreground. Calling stopSelf() here also clears the startForegroundService()
        // "must call startForeground()" obligation, so this cannot trip the 5s ANR either.
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startCallForeground();
        return START_NOT_STICKY;
    }

    private void startCallForeground() {
        try {
            // ServiceCompat handles the pre-Q "no type argument" form itself, and matches how
            // IncomingCallNotificationService -- the app's other phoneCall service -- enters
            // foreground state.
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            IN_FOREGROUND.set(true);
        } catch (SecurityException | IllegalStateException e) {
            // IllegalStateException covers ForegroundServiceStartNotAllowedException (API 31+)
            // and its Android 15 restricted-type variant. The primary guard is at the call site
            // in start(), because on Android 15 the refusal is thrown there; this only covers a
            // refusal raised at startForeground() itself.
            Log.e(Config.LOGTAG, "Unable to start call foreground service", e);
            IN_FOREGROUND.set(false);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        IN_FOREGROUND.set(false);
        super.onDestroy();
    }

    private Notification createNotification() {
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ongoing_calls_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);

        Intent launchIntent = new Intent(this, ExternalCallActivity.class);
        launchIntent.putExtra("returning", "true");
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_call_24dp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentTitle(getString(R.string.ongoing_call))
                .setContentText(getString(R.string.tap_to_return_to_phone_call))
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

