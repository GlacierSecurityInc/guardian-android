package com.glaciersecurity.glaciermessenger;

import static com.glaciersecurity.glaciermessenger.persistance.FileBackend.getBackupDirectoryCustom;

import android.content.Context;
import android.os.Build;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import java.security.SecureRandom;

import com.glaciersecurity.glaciermessenger.R;

public class AppSettings {

    public static final String KEEP_FOREGROUND_SERVICE = "enable_foreground_service";
    public static final String AWAY_WHEN_SCREEN_IS_OFF = "away_when_screen_off";
    public static final String TREAT_VIBRATE_AS_SILENT = "treat_vibrate_as_silent";
    public static final String DND_ON_SILENT_MODE = "dnd_on_silent_mode";
    public static final String MANUALLY_CHANGE_PRESENCE = "manually_change_presence";
    public static final String BLIND_TRUST_BEFORE_VERIFICATION = "btbv";
    public static final String AUTOMATIC_MESSAGE_DELETION = "automatic_message_deletion";
    public static final String BROADCAST_LAST_ACTIVITY = "last_activity";
    public static final String THEME = "theme";
    public static final String DYNAMIC_COLORS = "dynamic_colors";
    public static final String SHOW_DYNAMIC_TAGS = "show_dynamic_tags";
    public static final String OMEMO = "omemo";
    public static final String ALLOW_SCREENSHOTS = "allow_screenshots";
    public static final String RINGTONE = "call_ringtone";
    public static final String BTBV = "btbv";

    public static final String CONFIRM_MESSAGES = "confirm_messages";
    public static final String ALLOW_MESSAGE_CORRECTION = "allow_message_correction";

    public static final String REQUIRE_CHANNEL_BINDING = "channel_binding_required";
    public static final String NOTIFICATION_RINGTONE = "notification_ringtone";
    public static final String NOTIFICATION_HEADS_UP = "notification_headsup";
    public static final String NOTIFICATION_VIBRATE = "vibrate_on_notification";
    public static final String NOTIFICATION_LED = "led";
    public static final String SHOW_CONNECTION_OPTIONS = "show_connection_options";
    public static final String CHANNEL_DISCOVERY_METHOD = "channel_discovery_method";
    public static final String SEND_CRASH_REPORTS = "send_crash_reports";
    public static final String SEND_CRASH_REPORTS_TO_S3 = "send_crash_reports_to_s3";
    public static final String COLORFUL_CHAT_BUBBLES = "use_green_background";
    public static final String LARGE_FONT = "large_font";
    public static final String SHOW_AVATARS = "show_avatars";
    public static final String CALL_INTEGRATION = "call_integration";
    // Its own key: the switch persists its default (true) whenever settings open below API 35,
    // and that must not read as an opt-in after an upgrade to 15+ or a restore onto one.
    public static final String CALL_INTEGRATION_API35 = "call_integration_api35";
    public static final String ALIGN_START = "align_start";
    public static final String BACKUP_LOCATION = "backup_location";
    public static final String GLOBAL_MESSAGE_TIMER = "global_message_timer";

    private static final String ACCEPT_INVITES_FROM_STRANGERS = "accept_invites_from_strangers";
    private static final String INSTALLATION_ID = "im.conversations.android.install_id";

    private static final String EXTERNAL_STORAGE_AUTHORITY =
            "com.android.externalstorage.documents";

    public static final String TRACKING_ENABLED = "tracking_enabled";
    public static final String FORCE_TRACKING = "force_tracking";
    public static final String TRACKING_FREQUENCY = "tracking_frequency";
    public static final String LIVE_LOCATION_TRACKING = "live_location_tracking";
    public static final String ALLOW_CONTACTS_SEE_LOCATION = "allow_contacts_see_location";
    public static final String ENABLE_BIOMETRICS = "enable_biometrics";
    public static final String DNS_ENABLED = "dns_enabled";
    public static final String PHONE = "phone";
    public static final String DELETE_OMEMO_IDENTIES = "delete_omemo_identities";
    public static final String WIPE_ALL_HISTORY = "wipe_all_history";



    private final Context context;

    public AppSettings(final Context context) {
        this.context = context;
    }

    public Uri getRingtone() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String incomingCallRingtone =
                sharedPreferences.getString(
                        RINGTONE, context.getString(R.string.incoming_call_ringtone));
        return Strings.isNullOrEmpty(incomingCallRingtone) ? null : Uri.parse(incomingCallRingtone);
    }

    public void setRingtone(final Uri uri) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putString(RINGTONE, uri == null ? null : uri.toString()).apply();
    }

    public Uri getNotificationTone() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String incomingCallRingtone =
                sharedPreferences.getString(
                        NOTIFICATION_RINGTONE, context.getString(R.string.notification_ringtone));
        return Strings.isNullOrEmpty(incomingCallRingtone) ? null : Uri.parse(incomingCallRingtone);
    }

    public void setNotificationTone(final Uri uri) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences
                .edit()
                .putString(NOTIFICATION_RINGTONE, uri == null ? null : uri.toString())
                .apply();
    }

    /** Telecom-managed G2G calls; an explicit opt-in from API 35, see CallIntegration. */
    public boolean isCallIntegration() {
        if (Build.VERSION.SDK_INT >= 35) {
            return getBooleanPreference(CALL_INTEGRATION_API35, R.bool.call_integration_api35);
        }
        return getBooleanPreference(CALL_INTEGRATION, R.bool.call_integration);
    }

    public boolean isBTBVEnabled() {
        return getBooleanPreference(BTBV, R.bool.btbv);
    }

    public boolean isAllowScreenshots() {
        return getBooleanPreference(ALLOW_SCREENSHOTS, R.bool.allow_screenshots);
    }

    public boolean isColorfulChatBubbles() {
        return getBooleanPreference(COLORFUL_CHAT_BUBBLES, R.bool.use_green_background);
    }

    public boolean isLargeFont() {
        return getBooleanPreference(LARGE_FONT, R.bool.large_font);
    }

    public boolean isAcceptInvitesFromStrangers() {
        return getBooleanPreference(
                ACCEPT_INVITES_FROM_STRANGERS, R.bool.accept_invites_from_strangers);
    }

    private boolean getBooleanPreference(@NonNull final String name, @BoolRes int res) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPreferences.getBoolean(name, context.getResources().getBoolean(res));
    }

    public String getOmemo() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPreferences.getString(
                OMEMO, context.getString(R.string.omemo_setting_default));
    }

    public Uri getBackupLocation() {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String location = sharedPreferences.getString(BACKUP_LOCATION, null);
        if (Strings.isNullOrEmpty(location)) {
            final var directory = FileBackend.getBackupDirectory(context);
            return Uri.fromFile(directory);
        }
        return Uri.parse(location);
    }

    public String getBackupLocationAsPath() {
        return asPath(getBackupLocation());
    }


    public static String getCustomBackupLocationAsPath() {
        return getBackupDirectoryCustom().getAbsolutePath();
    }

    public static String asPath(final Uri uri) {
        final var scheme = uri.getScheme();
        final var path = uri.getPath();
        if (path == null) {
            return uri.toString();
        }
        if ("file".equalsIgnoreCase(scheme)) {
            return path;
        } else if ("content".equalsIgnoreCase(scheme)) {
            if (EXTERNAL_STORAGE_AUTHORITY.equalsIgnoreCase(uri.getAuthority())) {
                final var parts = Splitter.on(':').limit(2).splitToList(path);
                if (parts.size() == 2 && "/tree/primary".equals(parts.get(0))) {
                    return Joiner.on('/')
                            .join(Environment.getExternalStorageDirectory(), parts.get(1));
                }
            }
        }
        return uri.toString();
    }

    public void setBackupLocation(final Uri uri) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences
                .edit()
                .putString(BACKUP_LOCATION, uri == null ? "" : uri.toString())
                .apply();
    }

    public boolean isSendCrashReports() {
        return getBooleanPreference(SEND_CRASH_REPORTS, R.bool.send_crash_reports);
    }

    public boolean isSendCrashReportsToS3() {
        return getBooleanPreference(SEND_CRASH_REPORTS_TO_S3, R.bool.send_crash_reports_to_s3);
    }

    public void setSendCrashReports(boolean value) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean(SEND_CRASH_REPORTS, value).apply();
    }

    public boolean isRequireChannelBinding() {
        return getBooleanPreference(REQUIRE_CHANNEL_BINDING, R.bool.require_channel_binding);
    }

    public synchronized long getInstallationId() {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final long existing = sharedPreferences.getLong(INSTALLATION_ID, 0);
        if (existing != 0) {
            return existing;
        }
        final var secureRandom = new SecureRandom();
        final var installationId = secureRandom.nextLong();
        sharedPreferences.edit().putLong(INSTALLATION_ID, installationId).apply();
        return installationId;
    }

    public synchronized void resetInstallationId() {
        final var secureRandom = new SecureRandom();
        final var installationId = secureRandom.nextLong();
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putLong(INSTALLATION_ID, installationId)
                .apply();
    }

    public void setSendCrashReportsToS3(boolean value) {
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean(SEND_CRASH_REPORTS_TO_S3, value).apply();
    }
}
