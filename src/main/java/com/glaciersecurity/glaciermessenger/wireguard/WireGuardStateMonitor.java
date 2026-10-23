package com.glaciersecurity.glaciermessenger.wireguard;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.services.CoreConnectionTileService;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Observes the system VPN connection and keeps the wireguard_config table in sync with the
 * currently running WireGuard tunnels. This allows the application to react to VPN disconnects
 * that happen while the UI is in the background.
 */
public final class WireGuardStateMonitor {

    private static WireGuardStateMonitor instance;

    public static synchronized void initialize(@NonNull Context context) {
        if (instance == null) {
            instance = new WireGuardStateMonitor(context.getApplicationContext());
        }
    }

    public static void notifyStateChanged() {
        notifyStateChanged(null);
    }

    /**
     * A listen request for VPN networks. The builder's defaults include NOT_VPN, which no VPN
     * network has, so without removing it the request matches nothing and never calls back.
     */
    @NonNull
    public static NetworkRequest vpnNetworkRequest() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
    }

    /** @return true when {@code capabilities} describe a VPN this app established. */
    public static boolean isOwnVpn(@NonNull NetworkCapabilities capabilities) {
        return capabilities.getOwnerUid() == Process.myUid();
    }

    /**
     * GoBackend reports DOWN only when the tunnel really went down, including when Android
     * revoked it because another VPN app connected, which is otherwise silent.
     */
    public static void notifyTunnelDown(@Nullable String tunnelName) {
        WireGuardStateMonitor monitor = instance;
        if (monitor != null) {
            monitor.handleTunnelDown(tunnelName);
        }
    }

    public static void notifyStateChanged(@Nullable Runnable onSynced) {
        WireGuardStateMonitor monitor = instance;
        if (monitor != null) {
            monitor.handleVpnStatusChanged(onSynced);
        } else if (onSynced != null) {
            onSynced.run();
        }
    }

    private final Context appContext;
    private static final long TRANSITION_RECHECK_DELAY_MS = 1_000L;

    private final ScheduledExecutorService executorService =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "WireGuardStateMonitor");
                thread.setDaemon(true);
                return thread;
            });
    private final Object statusLock = new Object();
    private Boolean lastKnownStatus;
    private boolean transitionRecheckScheduled;
    private ConnectivityManager.NetworkCallback vpnStatusCallback;
    // Only losing our own tunnel is an authoritative disconnect; another VPN app's
    // network coming and going says nothing about Core.
    private final Set<Network> ownVpnNetworks = ConcurrentHashMap.newKeySet();

    private WireGuardStateMonitor(Context context) {
        this.appContext = context;
        startMonitoring();
    }

    private void startMonitoring() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            Log.e(Config.LOGTAG, "ConnectivityManager unavailable; VPN state changes won't be monitored");
            handleVpnStatusChanged(null);
            return;
        }
        NetworkRequest request = vpnNetworkRequest();
        vpnStatusCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                handleVpnStatusChanged(null);
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities capabilities) {
                if (isOwnVpn(capabilities)) {
                    ownVpnNetworks.add(network);
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (ownVpnNetworks.remove(network)) {
                    handleVpnLost(null);
                } else {
                    handleVpnStatusChanged(null);
                }
            }
        };
        try {
            connectivityManager.registerNetworkCallback(request, vpnStatusCallback);
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "Unable to register VPN status callback", e);
            vpnStatusCallback = null;
        }
        handleVpnStatusChanged(null);
    }

    private void handleVpnStatusChanged(@Nullable Runnable onSynced) {
        // Derive the truth straight from the backend here (not from
        // isRunningTunnel(), which now reads the source of truth this monitor
        // feeds) and reconcile it back in. This is a query (onAvailable / startup /
        // tunnel state-change), so an empty reading is not authoritative: it must
        // not clear a known-connected state, or it would break the boot-restore
        // that runs off the persisted running row.
        final GoBackend backend = PersistentConnectionProperties.getInstance().getBackend();
        if (backend == null) {
            // Can't determine ground truth yet; leave the source of truth untouched.
            if (onSynced != null) {
                onSynced.run();
            }
            return;
        }
        final Set<String> runningProfiles = getRunningCoreProfileNames();
        final boolean isActive = !runningProfiles.isEmpty();
        synchronized (statusLock) {
            lastKnownStatus = isActive;
        }
        syncState(runningProfiles, false, onSynced);
    }

    // The VPN network was lost — an authoritative disconnect that clears state.
    private void handleVpnLost(@Nullable Runnable onSynced) {
        synchronized (statusLock) {
            lastKnownStatus = false;
        }
        syncState(Collections.emptySet(), true, onSynced);
    }

    private void handleTunnelDown(@Nullable String tunnelName) {
        // A DOWN for the old tunnel is part of switching profiles, so while a connect is
        // pending leave the clear to the connect result or the transition timeout.
        final PersistentConnectionProperties props = PersistentConnectionProperties.getInstance();
        final boolean connecting =
                props.getConnectionState() == PersistentConnectionProperties.CoreConnectionState.CONNECTING;
        final boolean revoked = CoreRevokeAlert.isRevoke(props.consumeExpectedTeardown(), connecting);
        final Set<String> runningProfiles = getRunningCoreProfileNames();
        synchronized (statusLock) {
            lastKnownStatus = !runningProfiles.isEmpty();
        }
        syncState(runningProfiles, !connecting,
                revoked ? () -> CoreRevokeAlert.onRevoked(appContext, tunnelName) : null);
    }

    private Set<String> getRunningCoreProfileNames() {
        final GoBackend backend = PersistentConnectionProperties.getInstance().getBackend();
        if (backend == null) {
            return Collections.emptySet();
        }
        final Set<String> runningNames = new HashSet<>();
        try {
            final Set<String> backendNames = backend.getRunningTunnelNames();
            if (backendNames != null) {
                runningNames.addAll(backendNames);
            }
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to query running tunnel names", e);
        }
        if (!runningNames.isEmpty()) {
            return runningNames;
        }
        for (WgTunnel tunnel : PersistentConnectionProperties.getInstance().getTunnels()) {
            try {
                if (backend.getState(tunnel) == Tunnel.State.UP) {
                    runningNames.add(tunnel.getName());
                }
            } catch (Exception e) {
                Log.e(Config.LOGTAG,
                        "Unable to query tunnel state for " + tunnel.getName(), e);
            }
        }
        return runningNames;
    }

    private void syncState(Set<String> runningProfiles, boolean allowClearOnEmpty,
                           @Nullable Runnable onSynced) {
        final Set<String> snapshot = runningProfiles.isEmpty()
                ? Collections.emptySet()
                : new HashSet<>(runningProfiles);
        executorService.execute(() -> {
            // Reconcile into the single source of truth, which also persists it.
            // Notify foreground UI on any observable change so a screen can
            // repaint — this is the single notification path that lets a screen
            // converge to the settled state even when the change originates here
            // (recheck loop / 15s timeout) rather than from a tap; without it a
            // spinner could linger after the state had already settled. Compare
            // the connection state across the reconcile in addition to the
            // running-set delta it reports, because a transition can clear
            // without the running set changing (e.g. a failed connect goes
            // CONNECTING -> DISCONNECTED while the running set stays empty). A
            // no-op reconcile leaves both equal and broadcasts nothing, so the
            // receivers re-querying us can't spin into a loop.
            final PersistentConnectionProperties props = PersistentConnectionProperties.getInstance();
            final PersistentConnectionProperties.CoreConnectionState before = props.getConnectionState();
            boolean changed = props.reconcileRunningProfiles(snapshot, allowClearOnEmpty);
            if (changed || props.getConnectionState() != before) {
                broadcastConnectionStateChanged();
            }
            if (props.getConnectionState() == PersistentConnectionProperties.CoreConnectionState.CONNECTED) {
                CoreRevokeAlert.clear(appContext);
            }
            scheduleTransitionRecheckIfNeeded();
            if (onSynced != null) {
                onSynced.run();
            }
        });
    }

    private void broadcastConnectionStateChanged() {
        try {
            Intent intent = new Intent(CoreConnectionTileService.ACTION_CORE_CONNECTION_STATE_CHANGED);
            intent.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(intent);
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "Unable to broadcast core connection state change", e);
        }
    }

    private void scheduleTransitionRecheckIfNeeded() {
        if (!PersistentConnectionProperties.getInstance().isConnectionTransitioning()) {
            synchronized (statusLock) {
                transitionRecheckScheduled = false;
            }
            return;
        }
        synchronized (statusLock) {
            if (transitionRecheckScheduled) {
                return;
            }
            transitionRecheckScheduled = true;
        }
        executorService.schedule(() -> {
            synchronized (statusLock) {
                transitionRecheckScheduled = false;
            }
            if (PersistentConnectionProperties.getInstance().isConnectionTransitioning()) {
                handleVpnStatusChanged(null);
            }
        }, TRANSITION_RECHECK_DELAY_MS, TimeUnit.MILLISECONDS);
    }
}
