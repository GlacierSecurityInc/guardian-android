package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the outcome of the most recent security-info upload so a failing report is visible
 * without a debug build.
 *
 * <p>{@link com.glaciersecurity.glaciermessenger.Config#logLevel} is 0 in shipped builds and
 * {@link Log} gates every call on it, so the only previous signal that the Security Hub upload
 * had stopped working was a log line that never reached logcat. The Security Hub screen reads
 * this back, which makes "this device is not reaching the console" observable on the device
 * itself instead of only as an absent row on the console.
 */
public final class SecurityHubReportStatus {

    private static final String KEY_AT = "sechub_last_upload_at";
    private static final String KEY_OK = "sechub_last_upload_ok";
    private static final String KEY_DETAIL = "sechub_last_upload_detail";
    private static final String DISPLAY_FORMAT = "MMM d, yyyy h:mm a";

    private SecurityHubReportStatus() {
    }

    public static void recordSuccess(final Context context) {
        record(context, true, null);
    }

    public static void recordFailure(final Context context, final String detail) {
        record(context, false, detail);
    }

    /** {@code true} when the last attempt succeeded, or when no attempt has been recorded yet. */
    public static boolean lastAttemptSucceeded(final Context context) {
        if (context == null) {
            return true;
        }
        final SharedPreferences preferences = preferences(context);
        return preferences.getLong(KEY_AT, 0L) == 0L || preferences.getBoolean(KEY_OK, true);
    }

    /**
     * A one-line summary of the last upload for the Security Hub screen.
     *
     * @param neverReportedText shown when this device has never recorded an attempt
     */
    public static String describe(final Context context, final String neverReportedText) {
        if (context == null) {
            return neverReportedText;
        }
        final SharedPreferences preferences = preferences(context);
        final long at = preferences.getLong(KEY_AT, 0L);
        if (at == 0L) {
            return neverReportedText;
        }
        final String when =
                new SimpleDateFormat(DISPLAY_FORMAT, Locale.getDefault()).format(new Date(at));
        if (preferences.getBoolean(KEY_OK, true)) {
            return when;
        }
        final String detail = preferences.getString(KEY_DETAIL, null);
        return detail == null || detail.isEmpty() ? when : when + " — " + detail;
    }

    private static void record(final Context context, final boolean ok, final String detail) {
        if (context == null) {
            return;
        }
        final SharedPreferences.Editor editor = preferences(context).edit();
        editor.putLong(KEY_AT, System.currentTimeMillis());
        editor.putBoolean(KEY_OK, ok);
        if (detail == null) {
            editor.remove(KEY_DETAIL);
        } else {
            editor.putString(KEY_DETAIL, detail);
        }
        editor.apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }
}
