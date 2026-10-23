package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import com.glaciersecurity.glaciermessenger.Config;

// A hint, not a gate: ConnectivityManager reports a VPN as connected in airplane mode and can
// miss USB tethering, so callers still attempt the connection and only use this to label why it
// failed (upstream c8bb1a6c7).
public class NetworkManager {

    private final Context context;

    public NetworkManager(final Context context) {
        this.context = context;
    }

    public Hint getHint() {
        final var connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return Hint.ACTIVE;
        }
        try {
            final Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return getNoInternetOrAirplaneMode();
            }
            final NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null) {
                return getNoInternetOrAirplaneMode();
            }
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return Hint.NO_INTERNET;
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && isAirplaneMode()) {
                return Hint.AIRPLANE_MODE;
            }
            return Hint.ACTIVE;
        } catch (final RuntimeException e) {
            return Hint.ACTIVE;
        }
    }

    // For gating an immediate reconnect. Unlike getHint(), a VPN in airplane mode counts: with
    // wifi on underneath it is a working network, and treating it as down left accounts sitting
    // out a backoff of up to five minutes after every network change.
    public boolean hasInternet() {
        final var connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return true;
        }
        try {
            final Network activeNetwork = connectivityManager.getActiveNetwork();
            final NetworkCapabilities capabilities =
                    activeNetwork == null
                            ? null
                            : connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (final RuntimeException e) {
            return true;
        }
    }

    private boolean isAirplaneMode() {
        return Settings.Global.getInt(
                        context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0)
                != 0;
    }

    private Hint getNoInternetOrAirplaneMode() {
        return isAirplaneMode() ? Hint.AIRPLANE_MODE : Hint.NO_INTERNET;
    }

    public enum Hint {
        ACTIVE,
        NO_INTERNET,
        AIRPLANE_MODE
    }
}
