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
import com.glaciersecurity.glaciermessenger.ui.RtpSessionActivity;
import com.glaciersecurity.glaciermessenger.utils.AppLifecycleHandler;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service used to keep XMPP/Jingle calls alive when the UI is backgrounded.
 *
 * <p>CallIntegration only registers with the Telecom self-managed ConnectionService on devices
 * where CallIntegration.selfManaged() is true. On every other device -- including any device
 * where the OS/manufacturer combination is known-broken, and every device where selfManaged() is
 * hardcoded to false -- audio routing, ringback, and ringing tones are driven entirely by app
 * code via AppRTCAudioManager with no Telecom-provided foreground guarantee. This service closes
 * that gap the same way CallForegroundService does for Twilio calls.
 */
public class JingleCallForegroundService extends Service {
    public static final String ACTION_START =
            "com.glaciersecurity.glaciermessenger.action.START_JINGLE_CALL_SERVICE";
    public static final String ACTION_STOP =
            "com.glaciersecurity.glaciermessenger.action.STOP_JINGLE_CALL_SERVICE";

    public static final String EXTRA_ACCOUNT = RtpSessionActivity.EXTRA_ACCOUNT;
    public static final String EXTRA_WITH = RtpSessionActivity.EXTRA_WITH;
    public static final String EXTRA_SESSION_ID = RtpSessionActivity.EXTRA_SESSION_ID;

    private static final int NOTIFICATION_ID = 1003;
    private static final String CHANNEL_ID = "jingle_call";

    /** Set once this service is actually holding foreground state; see {@link #start}. */
    private static final AtomicBoolean IN_FOREGROUND = new AtomicBoolean(false);

    public static void start(
            final Context context,
            final String account,
            final String with,
            final String sessionId) {
        // phoneCall is a restricted foreground service type, so an app targeting Android 15+ may
        // not start it from a BOOT_COMPLETED receiver; the platform throws
        // ForegroundServiceStartNotAllowedException at startForegroundService() below, before
        // onStartCommand() runs. XmppConnectionService is started from BOOT_COMPLETED and can
        // reach CallIntegration.startAudioRouting() -> here, so the gate belongs at this call
        // site rather than inside the service.
        //
        // Every real caller arrives from a call the user just placed or answered, and all of
        // those go through RtpSessionActivity, so the process always has a started Activity. A
        // boot-originated start never does. Refreshing a notification for a service that is
        // already foreground is not a background start, so allow that unconditionally.
        if (!IN_FOREGROUND.get() && !AppLifecycleHandler.isAppInForeground()) {
            Log.w(
                    Config.LOGTAG,
                    "refusing to start jingle call foreground service with no started activity");
            return;
        }
        final Intent intent = new Intent(context, JingleCallForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_WITH, with);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        Compatibility.startPhoneCallForegroundService(context, intent);
    }

    public static void stop(final Context context) {
        final Intent intent = new Intent(context, JingleCallForegroundService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        final String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            IN_FOREGROUND.set(false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Only an explicit ACTION_START from a live Jingle session may take this service into the
        // foreground. phoneCall is a restricted foreground service type: apps targeting Android
        // 15+ may not start it from a BOOT_COMPLETED receiver, and doing so throws
        // ForegroundServiceStartNotAllowedException. A null intent (system re-delivery after the
        // process was killed) or any unrecognised action is not a live call, so stop instead of
        // going foreground. Calling stopSelf() here also clears the startForegroundService()
        // "must call startForeground()" obligation, so this cannot trip the 5s ANR either.
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startCallForeground(intent);
        return START_NOT_STICKY;
    }

    private void startCallForeground(@Nullable final Intent intent) {
        try {
            // ServiceCompat handles the pre-Q "no type argument" form itself, and matches how
            // IncomingCallNotificationService -- the app's other phoneCall service -- enters
            // foreground state.
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    createNotification(intent),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            IN_FOREGROUND.set(true);
        } catch (final SecurityException | IllegalStateException e) {
            // IllegalStateException covers ForegroundServiceStartNotAllowedException (API 31+)
            // and its Android 15 restricted-type variant. The primary guard is at the call site
            // in start(), because on Android 15 the refusal is thrown there; this only covers a
            // refusal raised at startForeground() itself.
            Log.e(Config.LOGTAG, "Unable to start jingle call foreground service", e);
            IN_FOREGROUND.set(false);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        IN_FOREGROUND.set(false);
        super.onDestroy();
    }

    private Notification createNotification(@Nullable final Intent startIntent) {
        final NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        final NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.ongoing_calls_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);

        final Intent launchIntent = new Intent(this, RtpSessionActivity.class);
        launchIntent.setAction(Intent.ACTION_VIEW);
        if (startIntent != null) {
            launchIntent.putExtra(EXTRA_ACCOUNT, startIntent.getStringExtra(EXTRA_ACCOUNT));
            launchIntent.putExtra(EXTRA_WITH, startIntent.getStringExtra(EXTRA_WITH));
            launchIntent.putExtra(EXTRA_SESSION_ID, startIntent.getStringExtra(EXTRA_SESSION_ID));
        }
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_call_24dp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentTitle(getString(R.string.ongoing_call))
                .setContentText(getString(R.string.return_to_ongoing_call))
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
