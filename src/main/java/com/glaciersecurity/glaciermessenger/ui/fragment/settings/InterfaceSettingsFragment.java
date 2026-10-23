package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.SettingsActivity;

public class InterfaceSettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_interface, rootKey);
        final var themePreference = findPreference("theme");
        final var dynamicColors = findPreference("dynamic_colors");
        if (themePreference == null || dynamicColors == null) {
            throw new IllegalStateException(
                    "The preference resource file did not contain theme or color preferences");
        }
        themePreference.setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    if (newValue instanceof final String theme) {
                        final int desiredNightMode = GlacierGuardian.getDesiredNightMode(theme);
                        requireSettingsActivity().setDesiredNightMode(desiredNightMode);
                    }
                    return true;
                });
        dynamicColors.setVisible(false);
        dynamicColors.setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    requireSettingsActivity().setDynamicColors(Boolean.TRUE.equals(newValue));
                    return true;
                });
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_interface);
    }

    public SettingsActivity requireSettingsActivity() {
        final var activity = requireActivity();
        if (activity instanceof SettingsActivity settingsActivity) {
            return settingsActivity;
        }
        throw new IllegalStateException(
                String.format(
                        "%s is not %s",
                        activity.getClass().getName(), SettingsActivity.class.getName()));
    }
}
