package com.glaciersecurity.glaciermessenger.utils;

import static com.glaciersecurity.glaciermessenger.receiver.SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;


public class Compatibility {

    public static boolean hasStoragePermission() {
        return true;
    }

    public static boolean s() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private static boolean runsTwentyFour() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean runsTwentySix() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static boolean twentyEight() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    private static boolean getBooleanPreference(Context context, String name, @BoolRes int res) {
        return getPreferences(context).getBoolean(name, context.getResources().getBoolean(res));
    }

    public static SharedPreferences getPreferences(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static boolean targetsTwentySix(Context context) {
        try {
            final PackageManager packageManager = context.getPackageManager();
            final ApplicationInfo applicationInfo =
                    packageManager.getApplicationInfo(context.getPackageName(), 0);
            return applicationInfo.targetSdkVersion >= 26;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return true; // when in doubt…
        }
    }

    private static boolean targetsTwentyFour(Context context) {
        try {
            final PackageManager packageManager = context.getPackageManager();
            final ApplicationInfo applicationInfo =
                    packageManager.getApplicationInfo(context.getPackageName(), 0);
            return applicationInfo.targetSdkVersion >= 24;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return true; // when in doubt…
        }
    }

    public static boolean runsAndTargetsTwentySix(Context context) {
        return runsTwentySix() && targetsTwentySix(context);
    }

    public static boolean runsAndTargetsTwentyFour(Context context) {
        return runsTwentyFour() && targetsTwentyFour(context);
    }

    public static boolean keepForegroundService(Context context) {
        return runsAndTargetsTwentySix(context)
                || getBooleanPreference(
                        context,
                        AppSettings.KEEP_FOREGROUND_SERVICE,
                        R.bool.enable_foreground_service);
    }


    public static void startService(final Context context, final Intent intent) {
        try {
            if (Compatibility.runsAndTargetsTwentySix(context)) {
                intent.putExtra(EXTRA_NEEDS_FOREGROUND_SERVICE, true);
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (final RuntimeException e) {
        }
    }

    /**
     * Starts a {@code phoneCall}-typed foreground service.
     *
     * <p>{@code phoneCall} is a <i>restricted</i> foreground service type: an app targeting
     * Android 15 or higher may not start one from a {@code BOOT_COMPLETED} broadcast receiver, and
     * the platform throws {@code ForegroundServiceStartNotAllowedException} when it tries. That
     * throw lands here, on {@code startForegroundService()}, before the target service's
     * {@code onStartCommand()} ever runs -- so the service cannot defend itself and the guard has
     * to live at the call site.
     *
     * <p>Callers are responsible for the "is this a real call?" gate; this only makes sure a
     * refused start is logged rather than crashing the process.
     */
    public static void startPhoneCallForegroundService(
            final Context context, final Intent intent) {
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (final RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (API 31+) and its Android 15
            // restricted-type variant are IllegalStateExceptions; a missing
            // FOREGROUND_SERVICE_PHONE_CALL permission surfaces as a SecurityException.
            Log.e(Config.LOGTAG, "unable to start phoneCall foreground service", e);
        }
    }

    @SuppressLint("UnsupportedChromeOsCameraSystemFeature")
    public static boolean hasFeatureCamera(final Context context) {
        final PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static int getRestrictBackgroundStatus(
            @NonNull final ConnectivityManager connectivityManager) {
        try {
            return connectivityManager.getRestrictBackgroundStatus();
        } catch (final Exception e) {
            return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean isActiveNetworkMetered(
            @NonNull final ConnectivityManager connectivityManager) {
        try {
            return connectivityManager.isActiveNetworkMetered();
        } catch (final RuntimeException e) {
            // when in doubt better assume it's metered
            return true;
        }
    }

    public static Bundle pgpStartIntentSenderOptions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                    .toBundle();
        } else {
            return null;
        }
    }
}
