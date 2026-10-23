package com.glaciersecurity.glaciermessenger.ui.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;

public final class SmsUnavailableDialogHelper {

    public static final String SMS_OUTBOUND_UNAVAILABLE_NOTICE_SHOWN =
            "sms_outbound_unavailable_notice_shown";

    private SmsUnavailableDialogHelper() {
    }

    public static void showIfFirstTime(@NonNull Activity activity) {
        final SharedPreferences preferences = Compatibility.getPreferences(activity);
        if (preferences.getBoolean(SMS_OUTBOUND_UNAVAILABLE_NOTICE_SHOWN, false)) {
            return;
        }
        preferences.edit().putBoolean(SMS_OUTBOUND_UNAVAILABLE_NOTICE_SHOWN, true).apply();
        show(activity);
    }

    public static void resetNoticeShown(@NonNull Context context) {
        Compatibility.getPreferences(context)
                .edit()
                .putBoolean(SMS_OUTBOUND_UNAVAILABLE_NOTICE_SHOWN, false)
                .apply();
    }

    public static void show(@NonNull Activity activity) {
        if (activity.isFinishing()) {
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sms_outbound_unavailable_title)
                .setMessage(R.string.sms_outbound_unavailable_message)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(
                        R.string.sms_outbound_unavailable_read_more,
                        (dialog, which) -> openReadMoreLink(activity))
                .show();
    }

    private static void openReadMoreLink(@NonNull Activity activity) {
        final Intent intent =
                new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(activity.getString(R.string.sms_outbound_unavailable_url)));
        if (intent.resolveActivity(activity.getPackageManager()) == null) {
            Toast.makeText(activity, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        try {
            activity.startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT)
                    .show();
        }
    }
}
