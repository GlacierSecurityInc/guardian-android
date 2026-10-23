package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import static com.glaciersecurity.glaciermessenger.AppSettings.DELETE_OMEMO_IDENTIES;
import static com.glaciersecurity.glaciermessenger.AppSettings.WIPE_ALL_HISTORY;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.ui.SettingsActivity;
import com.google.common.base.Strings;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.OmemoSetting;

public class SecuritySettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_security, rootKey);
        final ListPreference omemo = findPreference(AppSettings.OMEMO);
        final ListPreference automaticMessageDeletion =
                findPreference(AppSettings.AUTOMATIC_MESSAGE_DELETION);
        final ListPreference globalMessageTimer =
                findPreference(AppSettings.GLOBAL_MESSAGE_TIMER);
        if (omemo == null || automaticMessageDeletion == null) {
            throw new IllegalStateException("The preference resource file is missing preferences");
        }
        omemo.setSummaryProvider(new OmemoSummaryProvider());
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
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.OMEMO -> {
                OmemoSetting.load(requireContext());
            }
            case AppSettings.REQUIRE_CHANNEL_BINDING -> {
                reconnectAccounts();
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
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_security);
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (WIPE_ALL_HISTORY.equals(preference.getKey())) {
            ((SettingsActivity) getActivity()).wipeAllHistoryDialog();
            return true;
        }
        if ( DELETE_OMEMO_IDENTIES.equals(preference.getKey())) {
            ((SettingsActivity) getActivity()).deleteOmemoIdentities();
        }
        return super.onPreferenceTreeClick(preference);
    }

    public void changeGlobalTimer(int timer) {
        for (Account account : requireService().getAccounts()) {
            if (account.getTimer() != timer) {
                account.setTimer(timer);
                requireService().databaseBackend.updateAccount(account);
            }
        }
    }



    private static class OmemoSummaryProvider
            implements Preference.SummaryProvider<ListPreference> {

        @Nullable
        @Override
        public CharSequence provideSummary(@NonNull ListPreference preference) {
            final var context = preference.getContext();
            final var sharedPreferences = preference.getSharedPreferences();
            final String value;
            if (sharedPreferences == null) {
                value = null;
            } else {
                value =
                        sharedPreferences.getString(
                                preference.getKey(),
                                context.getString(R.string.omemo_setting_default));
            }
            return switch (Strings.nullToEmpty(value)) {
                case "always" -> context.getString(R.string.pref_omemo_setting_summary_always);
                case "default_off" -> context.getString(
                        R.string.pref_omemo_setting_summary_default_off);
                default -> context.getString(R.string.pref_omemo_setting_summary_default_on);
            };
        }
    }
}
