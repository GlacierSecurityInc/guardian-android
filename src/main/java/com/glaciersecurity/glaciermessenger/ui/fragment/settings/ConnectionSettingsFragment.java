package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;
public class ConnectionSettingsFragment extends XmppPreferenceFragment {

    private static final String GROUPS_AND_CONFERENCES = "groups_and_conferences";

    public static boolean hideChannelDiscovery() {
        return QuickConversationsService.isQuicksy()
                || QuickConversationsService.isPlayStoreFlavor()
                || Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY);
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_connection, rootKey);
        final var connectionOptions = findPreference(AppSettings.SHOW_CONNECTION_OPTIONS);
        final var channelDiscovery = findPreference(AppSettings.CHANNEL_DISCOVERY_METHOD);
        final var groupsAndConferences = findPreference(GROUPS_AND_CONFERENCES);
        if (connectionOptions == null || channelDiscovery == null || groupsAndConferences == null) {
            throw new IllegalStateException();
        }
        if (QuickConversationsService.isQuicksy()) {
            connectionOptions.setVisible(false);
        }
        if (hideChannelDiscovery()) {
            groupsAndConferences.setVisible(false);
            channelDiscovery.setVisible(false);
        }
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.SHOW_CONNECTION_OPTIONS -> {
                reconnectAccounts();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_connection_options);
    }
}
