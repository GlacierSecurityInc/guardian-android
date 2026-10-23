package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import android.content.Context;

import androidx.fragment.app.FragmentActivity;
import android.content.SharedPreferences;

import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.primitives.Ints;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils;

public abstract class XmppPreferenceFragment extends PreferenceFragmentCompat {

    private final SharedPreferences.OnSharedPreferenceChangeListener
            sharedPreferenceChangeListener =
                    (sharedPreferences, key) -> {
                        if (key == null) {
                            return;
                        }
                        if (isAdded()) {
                            onSharedPreferenceChanged(key);
                        }
                    };

    protected void onSharedPreferenceChanged(@NonNull String key) {
    }

    public void onBackendConnected() {}

    @Override
    public void onResume() {
        super.onResume();
        final var sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) {
            sharedPreferences.registerOnSharedPreferenceChangeListener(
                    this.sharedPreferenceChangeListener);
        }
        final var xmppActivity = requireXmppActivity();
        if (xmppActivity.xmppConnectionService != null) {
            this.onBackendConnected();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        final var sharedPreferences = getPreferenceManager().getSharedPreferences();
        if (sharedPreferences != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    this.sharedPreferenceChangeListener);
        }
    }

    protected void reconnectAccounts() {
        final var service = requireService();
        for (final Account account : service.getAccounts()) {
            if (account.isEnabled()) {
                service.reconnectAccountInBackground(account);
            }
        }
    }

    protected XmppActivity requireXmppActivity() {
        final var activity = requireActivity();
        if (activity instanceof XmppActivity xmppActivity) {
            return xmppActivity;
        }
        throw new IllegalStateException();
    }

    protected XmppConnectionService requireService() {
        final var xmppActivity = requireXmppActivity();
        final var service = xmppActivity.xmppConnectionService;
        if (service != null) {
            return service;
        }
        throw new IllegalStateException();
    }

    protected void runOnUiThread(final Runnable runnable) {
        final FragmentActivity activity = getActivity();
        if (activity == null || !isAdded()) {
            Log.w(Config.LOGTAG, "Fragment not attached; skipping UI runnable");
            return;
        }
        activity.runOnUiThread(runnable);
    }

    protected static String timeframeValueToName(final Context context, final int value) {
        if (value == 0) {
            return context.getString(R.string.never);
        } else {
            return TimeFrameUtils.resolve(context, 1000L * value);
        }
    }

    protected void setValues(
            final ListPreference listPreference,
            @ArrayRes int resId,
            final Function<Integer, String> valueToName) {
        final int[] choices = getResources().getIntArray(resId);
        final CharSequence[] entries = new CharSequence[choices.length];
        final CharSequence[] entryValues = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; ++i) {
            final int value = choices[i];
            entryValues[i] = String.valueOf(choices[i]);
            entries[i] = valueToName.apply(value);
        }
        listPreference.setEntries(entries);
        listPreference.setEntryValues(entryValues);
        listPreference.setSummaryProvider(
                new Preference.SummaryProvider<ListPreference>() {
                    @Nullable
                    @Override
                    public CharSequence provideSummary(@NonNull ListPreference preference) {
                        final Integer value =
                                Ints.tryParse(Strings.nullToEmpty(preference.getValue()));
                        return valueToName.apply(value == null ? 0 : value);
                    }
                });
    }
}
