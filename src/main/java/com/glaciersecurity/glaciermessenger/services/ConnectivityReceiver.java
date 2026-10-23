package com.glaciersecurity.glaciermessenger.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcast receiver and utility class for monitoring network connectivity.
 *
 * <p>The previous implementation relied solely on the deprecated
 * {@link NetworkInfo} APIs which can return incorrect information on newer
 * Android versions.  This version prefers the
 * {@link ConnectivityManager#getNetworkCapabilities(Network)} API when
 * available and falls back to the legacy check on older devices.  This helps
 * ensure we don't mistakenly report "no network" when a valid connection is
 * present.</p>
 */
public class ConnectivityReceiver extends BroadcastReceiver {
    private static final AtomicBoolean LAST_CONNECTED_STATE = new AtomicBoolean(false);
    private static final AtomicLong LAST_RESTORE_ELAPSED_MS = new AtomicLong(-1L);
    private static final AtomicLong STABLE_CONNECTED_SINCE_ELAPSED_MS = new AtomicLong(-1L);

    private final ConnectivityReceiverListener connectivityReceiverListener;

    public ConnectivityReceiver(ConnectivityReceiverListener connectivityReceiverListener) {
        super();
        this.connectivityReceiverListener = connectivityReceiverListener;
    }

    @Override
    public void onReceive(Context context, Intent arg1) {
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(arg1.getAction())) {
            // Called for the side effect, not the result: isConnected() is what maintains
            // LAST_CONNECTED_STATE and the STABLE_CONNECTED_SINCE_ELAPSED_MS stamp that
            // hasBeenConnectedFor() reads. The listener no longer takes the value -- every
            // implementation re-derives it through the toolbar adapter.
            isConnected(context);

            if (connectivityReceiverListener != null) {
                connectivityReceiverListener.onNetworkConnectionChanged();
            }
        }
    }

    /**
     * Determine whether the device currently has a network connection.
     *
     * @param context application or activity context
     * @return {@code true} if a network connection is available and
     *         established; {@code false} otherwise
     */
    public static boolean isConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            updateConnectivityState(false);
            return false;
        }

        final boolean connected;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) {
                updateConnectivityState(false);
                return false;
            }
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            // TRANSPORT_VPN matters because Core is a VPN: while the tunnel is up it is the
            // active network, and Android only merges the underlying WIFI/CELLULAR transport into
            // its capabilities when the VPN declares an underlying network. Without this clause a
            // device on Core reads as offline, which silently dropped every request gated on it.
            connected = capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            );
        } else {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            connected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
        updateConnectivityState(connected);
        return connected;
    }

    public static boolean hasBeenConnectedFor(Context context, long minimumStableMs) {
        if (!isConnected(context)) {
            return false;
        }
        if (minimumStableMs <= 0L) {
            return true;
        }
        final long stableSince = STABLE_CONNECTED_SINCE_ELAPSED_MS.get();
        return stableSince > 0L
                && (SystemClock.elapsedRealtime() - stableSince) >= minimumStableMs;
    }

    public static long getLastRestoreElapsedRealtimeMs() {
        return LAST_RESTORE_ELAPSED_MS.get();
    }

    public static long getStableConnectedSinceElapsedRealtimeMs() {
        return STABLE_CONNECTED_SINCE_ELAPSED_MS.get();
    }

    private static void updateConnectivityState(boolean isConnected) {
        final boolean wasConnected = LAST_CONNECTED_STATE.getAndSet(isConnected);
        final long nowElapsed = SystemClock.elapsedRealtime();
        if (isConnected) {
            if (!wasConnected) {
                LAST_RESTORE_ELAPSED_MS.set(nowElapsed);
                STABLE_CONNECTED_SINCE_ELAPSED_MS.set(nowElapsed);
            } else if (STABLE_CONNECTED_SINCE_ELAPSED_MS.get() <= 0L) {
                STABLE_CONNECTED_SINCE_ELAPSED_MS.set(nowElapsed);
            }
        } else {
            STABLE_CONNECTED_SINCE_ELAPSED_MS.set(-1L);
        }
    }

    public interface ConnectivityReceiverListener {
        void onNetworkConnectionChanged();
    }
}
