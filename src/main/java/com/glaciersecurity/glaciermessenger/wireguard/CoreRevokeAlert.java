package com.glaciersecurity.glaciermessenger.wireguard;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.services.CoreConnectionTileService;
import com.glaciersecurity.glaciermessenger.ui.CoreConnectionActivity;
import com.glaciersecurity.glaciermessenger.utils.Log;

/**
 * Tells the user when Android handed the VPN to another app (or they disconnected Core from
 * system settings). Core cannot take the tunnel back on its own; reconnecting would fight
 * whichever VPN now holds it, so the user decides.
 */
public final class CoreRevokeAlert {

    private static final String PREFS = "core_revoke_alert";
    private static final String PREF_REVOKED_PROFILE = "revoked_profile";
    private static final String CHANNEL_ID = "core_alerts";
    private static final int NOTIFICATION_ID = 0xC0DE;

    // Set by the Core screen while resumed; it shows the dialog itself, so no banner then.
    private static volatile boolean coreScreenVisible;

    private CoreRevokeAlert() {
    }

    public static void setCoreScreenVisible(boolean visible) {
        coreScreenVisible = visible;
    }

    /**
     * Whether a GoBackend DOWN was a revoke. Every app-initiated teardown announces itself first,
     * and while connecting a DOWN is the old tunnel making way for the new one.
     *
     * <p>Not {@code VpnService.prepare()}: for an app the user already approved it silently
     * takes the VPN back, which would kick off the app that just connected.
     */
    static boolean isRevoke(boolean expectedTeardown, boolean connecting) {
        return !expectedTeardown && !connecting;
    }

    static void onRevoked(@NonNull Context context, @Nullable String profileName) {
        if (profileName == null) {
            return;
        }
        Log.w(Config.LOGTAG, "Core tunnel revoked by another VPN or system settings");
        // Banner first, flag second, then nudge the screen: if the Core screen resumes in
        // between, the nudge still makes it show the dialog and take the banner down.
        if (!coreScreenVisible) {
            postNotification(context);
        }
        prefs(context).edit().putString(PREF_REVOKED_PROFILE, profileName).commit();
        context.sendBroadcast(new Intent(CoreConnectionTileService.ACTION_CORE_CONNECTION_STATE_CHANGED)
                .setPackage(context.getPackageName()));
    }

    /** @return the profile that was running when Core was revoked, or null if no alert is pending. */
    @Nullable
    public static String getPendingProfile(@NonNull Context context) {
        return prefs(context).getString(PREF_REVOKED_PROFILE, null);
    }

    /** Core is back on, or the user chose to keep it off. */
    public static void clear(@NonNull Context context) {
        if (getPendingProfile(context) == null) {
            return;
        }
        prefs(context).edit().remove(PREF_REVOKED_PROFILE).apply();
        dismissNotification(context);
    }

    public static void dismissNotification(@NonNull Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void postNotification(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        final Intent open = new Intent(context, CoreConnectionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID,
                open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final String body = context.getString(R.string.core_revoked_body);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cc_power)
                .setContentTitle(context.getString(R.string.core_revoked_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            Log.e(Config.LOGTAG, "Unable to post Core revoke notification", e);
        }
    }

    // HIGH so it peeks: someone who misses it is browsing unprotected while believing otherwise.
    private static void ensureChannel(Context context) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.core_alerts_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.core_alerts_channel_description));
        channel.setGroup("status");
        manager.createNotificationChannel(channel);
    }
}
