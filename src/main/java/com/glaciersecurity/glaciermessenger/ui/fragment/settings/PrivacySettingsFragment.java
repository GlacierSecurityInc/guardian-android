package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import static com.glaciersecurity.glaciermessenger.AppSettings.WIPE_ALL_HISTORY;

import android.content.SharedPreferences;

import androidx.fragment.app.FragmentActivity;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.ui.SettingsActivity;
import com.glaciersecurity.glaciermessenger.ui.util.SettingsUtils;


public class PrivacySettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_privacy, rootKey);
        final ListPreference automaticMessageDeletion =
                findPreference(AppSettings.AUTOMATIC_MESSAGE_DELETION);
        final ListPreference globalMessageTimer =
                findPreference(AppSettings.GLOBAL_MESSAGE_TIMER);
        setValues(
                automaticMessageDeletion,
                R.array.automatic_message_deletion_values,
                value -> timeframeValueToName(requireContext(), value));
        setValues(
                globalMessageTimer,
                R.array.timer_options_durations_values,
                value -> timeframeValueToName(requireContext(), value));
    }


    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (WIPE_ALL_HISTORY.equals(preference.getKey())) {
            FragmentActivity activity = getActivity();
            if (activity instanceof SettingsActivity) {
                ((SettingsActivity) activity).wipeAllHistoryDialog();
                return true;
            } return false;
        }
        return super.onPreferenceTreeClick(preference);
    }


    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.AWAY_WHEN_SCREEN_IS_OFF, AppSettings.MANUALLY_CHANGE_PRESENCE -> {
                requireService().toggleScreenEventReceiver();
                requireService().refreshAllPresences();
            }
            case AppSettings.CONFIRM_MESSAGES,
                    AppSettings.BROADCAST_LAST_ACTIVITY,
                    AppSettings.ALLOW_MESSAGE_CORRECTION,
                    AppSettings.DND_ON_SILENT_MODE,
                    AppSettings.TREAT_VIBRATE_AS_SILENT-> {
                requireService().refreshAllPresences();
            }
            case AppSettings.SEND_CRASH_REPORTS -> {
                AppSettings appSettings = new AppSettings(getContext());
                if (appSettings.isSendCrashReportsToS3()){
                    appSettings.setSendCrashReportsToS3(false);
                    final SwitchPreferenceCompat s3Toggle = findPreference(AppSettings.SEND_CRASH_REPORTS_TO_S3);
                    if (s3Toggle != null) {
                        s3Toggle.setChecked(false);
                    }
                }
            }
            case AppSettings.AUTOMATIC_MESSAGE_DELETION -> {
                requireService().expireOldMessages(true);
            }
            case AppSettings.GLOBAL_MESSAGE_TIMER -> {
                int timer = Message.TIMER_NONE;
                final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                String timerStr = preferences.getString(AppSettings.GLOBAL_MESSAGE_TIMER , null);
                if (timerStr != null) {
                    try {
                        timer = Integer.parseInt(timerStr);
                    } catch (NumberFormatException nfe) {
                        timer = Message.TIMER_NONE;
                    }
                }
                changeGlobalTimer(timer);
            }
            case AppSettings.ALLOW_SCREENSHOTS -> {
                SettingsUtils.applyScreenshotSetting(requireActivity());
            }
        }
    }
    public void changeGlobalTimer(int timer) {
        for (Account account : requireService().getAccounts()) {
            if (account.getTimer() != timer) {
                account.setTimer(timer);
                requireService().databaseBackend.updateAccount(account);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_privacy);
        final android.os.Bundle args = getArguments();
        if (args != null && args.containsKey(com.glaciersecurity.glaciermessenger.ui.SettingsActivity.SCROLL_TO_PREFERENCE)) {
            scrollToPreference(args.getString(com.glaciersecurity.glaciermessenger.ui.SettingsActivity.SCROLL_TO_PREFERENCE));
        }
    }
}
