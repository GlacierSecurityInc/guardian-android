package com.glaciersecurity.glaciermessenger.services;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.ui.CoreConnectionActivity;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.ui.CoreConnectionActivity;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.glaciersecurity.glaciermessenger.wireguard.WireGuardStateMonitor;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quick Settings tile that mirrors the VPN toggle behaviour from {@link com.glaciersecurity.glaciermessenger.ui.CoreConnectionActivity}.
 */
public class CoreConnectionTileService extends TileService {

    public static final String ACTION_CORE_CONNECTION_STATE_CHANGED =
            "com.glaciersecurity.glaciermessenger.action.CORE_CONNECTION_STATE_CHANGED";
    public static final String EXTRA_OPEN_CORE_PAGE =
            "com.glaciersecurity.glaciermessenger.extra.OPEN_CORE_PAGE";
    public static final String EXTRA_AUTO_CONNECT =
            "com.glaciersecurity.glaciermessenger.extra.AUTO_CONNECT";
    // single-use, in-memory token proving EXTRA_AUTO_CONNECT came from this
    // TileService and not from an external app targeting the exported CoreConnectionActivity.
    public static final String EXTRA_AUTO_CONNECT_TOKEN =
            "com.glaciersecurity.glaciermessenger.extra.AUTO_CONNECT_TOKEN";

    private static final String PREFS_CORE_CONNECTION = "core_connection_ui";
    private static final String PREF_LAST_PROFILE = "last_profile";

    private static final String PREFS_TILE = "core_connection_tile";
    private static final String KEY_PENDING_ACTION = "pending_action";

    private static final int ACTION_NONE = 0;
    private static final int ACTION_CONNECT = 1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // While the tile is visible, follow the same connection-state broadcast the
    // app surfaces use. The state can clear asynchronously — e.g. the monitor's
    // recheck loop resolves a DISCONNECTING transition after our own toggle
    // already painted the tile — and without this the tile would stay on
    // "Disconnecting" until the shade is reopened (onStartListening).
    private final BroadcastReceiver coreStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                executor.execute(CoreConnectionTileService.this::refreshTileState);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Executor already shut down (service tearing down); nothing to refresh.
            }
        }
    };
    private boolean coreStateReceiverRegistered;

    @Override
    public void onStartListening() {
        super.onStartListening();
        registerCoreStateReceiver();
        executor.execute(() -> {
            resumePendingActionIfNeeded();
            refreshTileState();
        });
    }

    @Override
    public void onStopListening() {
        unregisterCoreStateReceiver();
        super.onStopListening();
    }

    private void registerCoreStateReceiver() {
        if (coreStateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_CORE_CONNECTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, coreStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        coreStateReceiverRegistered = true;
    }

    private void unregisterCoreStateReceiver() {
        if (!coreStateReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(coreStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "Core tile state receiver already unregistered", e);
        }
        coreStateReceiverRegistered = false;
    }

    @Override
    public void onClick() {
        super.onClick();
        Runnable action = () -> executor.execute(this::handleToggleRequest);
        if (isLocked()) {
            unlockAndRun(action);
        } else {
            action.run();
        }
    }


    @Override
    public void onTileAdded() {
        super.onTileAdded();
        executor.execute(this::refreshTileState);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterCoreStateReceiver();
        executor.shutdownNow();
    }

    private void handleToggleRequest() {
        PersistentConnectionProperties connectionProperties = PersistentConnectionProperties.getInstance();
        ensureBackend(connectionProperties);

        DatabaseBackend databaseBackend = DatabaseBackend.getInstance(getApplicationContext());
        PersistentConnectionProperties.CoreConnectionState connectionState =
                connectionProperties.getConnectionState();
        boolean isRunning = connectionState == PersistentConnectionProperties.CoreConnectionState.CONNECTED;
        boolean transitioning = connectionState == PersistentConnectionProperties.CoreConnectionState.CONNECTING
                || connectionState == PersistentConnectionProperties.CoreConnectionState.DISCONNECTING;
        CoreProfile runningProfile = databaseBackend.getRunningCoreProfile();

        if (transitioning) {
            refreshTileState();
            return;
        }

        if (isRunning) {
            disconnectCurrentTunnel(connectionProperties, databaseBackend, runningProfile);
            refreshTileState();
            return;
        }

        Intent prepareIntent = GoBackend.VpnService.prepare(this);
        if (prepareIntent != null) {
            rememberPendingAction(ACTION_CONNECT);
            showToast(R.string.core_quick_tile_permission_required);
            launchCoreConnectionPage(true);
            return;
        }

        connectPreferredTunnel(connectionProperties, databaseBackend, runningProfile);
        refreshTileState();
    }

    private void connectPreferredTunnel(PersistentConnectionProperties connectionProperties,
                                        DatabaseBackend databaseBackend,
                                        @Nullable CoreProfile runningProfile) {
        List<CoreProfile> profiles = databaseBackend.getCoreProfiles();
        CoreProfile targetProfile = selectPreferredProfile(profiles, runningProfile);

        if (targetProfile == null) {
            showToast(R.string.core_quick_tile_no_profile);
            return;
        }

        if (targetProfile.getConfig() == null) {
            showToast(R.string.missing_vpn_config);
            return;
        }

        try {
            Tunnel tunnel = connectionProperties.getTunnel(targetProfile.getProfName());
            GoBackend backend = connectionProperties.getBackend();
            if (backend == null) {
                showToast(R.string.core_quick_tile_toggle_failed);
                return;
            }
            connectionProperties.markProfileConnecting(targetProfile.getProfName());
            backend.setState(tunnel, Tunnel.State.UP, targetProfile.getConfig());
            // Update the single source of truth (also persists) once setState returns
            // successfully; monitor reconciliation may also confirm this state.
            connectionProperties.markProfileConnected(targetProfile.getProfName());
            persistLastProfile(targetProfile);
            notifyUiStateChanged();
        } catch (Exception e) {
            connectionProperties.markConnectionTransitionFailed();
            Log.e(Config.LOGTAG, "Unable to enable WireGuard from tile", e);
            showToast(R.string.core_quick_tile_toggle_failed);
        }
    }

    private void disconnectCurrentTunnel(PersistentConnectionProperties connectionProperties,
                                         DatabaseBackend databaseBackend,
                                         @Nullable CoreProfile runningProfile) {
        CoreProfile target = runningProfile;
        if (target == null) {
            List<CoreProfile> profiles = databaseBackend.getCoreProfiles();
            target = selectPreferredProfile(profiles, null);
        }

        if (target == null) {
            showToast(R.string.core_quick_tile_no_profile);
            return;
        }

        try {
            Tunnel tunnel = connectionProperties.getTunnel(target.getProfName());
            GoBackend backend = connectionProperties.getBackend();
            if (backend == null) {
                showToast(R.string.core_quick_tile_toggle_failed);
                return;
            }
            connectionProperties.expectTeardown();
            backend.setState(tunnel, Tunnel.State.DOWN, null);
            // Hold UI/source-of-truth in DISCONNECTING until the backend reports
            // DOWN/empty so stale UP snapshots cannot re-add the profile.
            connectionProperties.markAllDisconnected();
            notifyUiStateChanged();
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to disable WireGuard from tile", e);
            showToast(R.string.core_quick_tile_toggle_failed);
        }
    }

    private void refreshTileState() {
        DatabaseBackend databaseBackend = DatabaseBackend.getInstance(getApplicationContext());
        PersistentConnectionProperties connectionProperties = PersistentConnectionProperties.getInstance();
        ensureBackend(connectionProperties);

        PersistentConnectionProperties.CoreConnectionState connectionState =
                connectionProperties.getConnectionState();
        CoreProfile runningProfile = databaseBackend.getRunningCoreProfile();
        List<CoreProfile> profiles = databaseBackend.getCoreProfiles();
        CoreProfile detailProfile = selectPreferredProfile(profiles, runningProfile);
        boolean hasProfiles = !profiles.isEmpty();

        updateTileUi(connectionState, hasProfiles, detailProfile);
    }

    private void updateTileUi(PersistentConnectionProperties.CoreConnectionState connectionState,
                              boolean hasProfiles, @Nullable CoreProfile detailProfile) {
        boolean isRunning = connectionState == PersistentConnectionProperties.CoreConnectionState.CONNECTED;
        boolean connecting = connectionState == PersistentConnectionProperties.CoreConnectionState.CONNECTING;
        boolean disconnecting = connectionState == PersistentConnectionProperties.CoreConnectionState.DISCONNECTING;
        mainHandler.post(() -> {
            Tile tile = getQsTile();
            if (tile == null) {
                return;
            }

            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_glacier_brand));
            tile.setLabel(getString(R.string.core_quick_tile_label));
            tile.setContentDescription(getString(R.string.core_quick_tile_label));

            int state;
            if (!hasProfiles) {
                state = Tile.STATE_UNAVAILABLE;
            } else if (isRunning || connecting || disconnecting) {
                state = Tile.STATE_ACTIVE;
            } else {
                state = Tile.STATE_INACTIVE;
            }
            tile.setState(state);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                CharSequence subtitle;
                if (!hasProfiles) {
                    subtitle = getString(R.string.core_quick_tile_no_profile);
                } else if (connecting) {
                    subtitle = getString(R.string.core_quick_tile_subtitle_connecting);
                } else if (disconnecting) {
                    subtitle = getString(R.string.core_quick_tile_subtitle_disconnecting);
                } else if (isRunning && detailProfile != null) {
                    subtitle = getString(R.string.core_quick_tile_subtitle_connected, detailProfile.getDisplayName());
                } else if (detailProfile != null) {
                    subtitle = getString(R.string.core_quick_tile_subtitle_disconnected, detailProfile.getDisplayName());
                } else {
                    subtitle = getString(R.string.core_quick_tile_subtitle_disconnected_generic);
                }
                tile.setSubtitle(subtitle);
            }

            tile.updateTile();
        });
    }

    private void rememberPendingAction(int action) {
        SharedPreferences prefs = getSharedPreferences(PREFS_TILE, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_PENDING_ACTION, action).apply();
    }

    private void clearPendingAction() {
        SharedPreferences prefs = getSharedPreferences(PREFS_TILE, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_PENDING_ACTION).apply();
    }

    private void resumePendingActionIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS_TILE, Context.MODE_PRIVATE);
        int action = prefs.getInt(KEY_PENDING_ACTION, ACTION_NONE);
        if (action == ACTION_CONNECT) {
            Intent prepareIntent = GoBackend.VpnService.prepare(this);
            if (prepareIntent == null) {
                clearPendingAction();
                handleToggleRequest();
            }
        }
    }

    private void persistLastProfile(CoreProfile profile) {
        SharedPreferences prefs = getSharedPreferences(PREFS_CORE_CONNECTION, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_LAST_PROFILE, profile.getProfName()).apply();
    }

    @Nullable
    private CoreProfile selectPreferredProfile(List<CoreProfile> profiles, @Nullable CoreProfile runningProfile) {
        if (runningProfile != null) {
            return runningProfile;
        }
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_CORE_CONNECTION, Context.MODE_PRIVATE);
        String lastProfileName = prefs.getString(PREF_LAST_PROFILE, null);
        if (lastProfileName != null) {
            for (CoreProfile profile : profiles) {
                if (lastProfileName.equals(profile.getProfName())) {
                    return profile;
                }
            }
        }
        return profiles.get(0);
    }

    private void ensureBackend(PersistentConnectionProperties connectionProperties) {
        if (connectionProperties.getBackend() != null) {
            return;
        }
        try {
            connectionProperties.setBackend(new GoBackend(this));
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to initialise WireGuard backend", e);
            showToast(R.string.core_quick_tile_toggle_failed);
        }
    }

    private void showToast(int resId) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), getString(resId), Toast.LENGTH_SHORT).show());
    }

    private void launchCoreConnectionPage(boolean autoConnect) {
        Intent coreIntent = new Intent(this, CoreConnectionActivity.class);
        coreIntent.putExtra(EXTRA_OPEN_CORE_PAGE, true);
        coreIntent.putExtra(EXTRA_AUTO_CONNECT, autoConnect);
        if (autoConnect) {
            // CoreConnectionActivity must stay exported for the Quick Settings
            // long-press-to-open-app affordance (android.service.quicksettings.ACTIVITY), so
            // an external app can still start it directly. This token, generated fresh here and
            // never leaving this process, is the only thing that lets the activity trust that
            // an auto-connect request genuinely came from this tile and not a spoofed intent.
            final String token = UUID.randomUUID().toString();
            CoreConnectionActivity.registerPendingAutoConnectToken(token);
            coreIntent.putExtra(EXTRA_AUTO_CONNECT_TOKEN, token);
        }
        coreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    coreIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        }

        PendingIntent finalPendingIntent = pendingIntent;
        mainHandler.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && finalPendingIntent != null) {
                startActivityAndCollapse(finalPendingIntent);
            } else {
                startActivityAndCollapseLegacy(coreIntent);
            }
        });
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void startActivityAndCollapseLegacy(Intent intent) {
        startActivityAndCollapse(intent);
    }

    private void notifyUiStateChanged() {
        WireGuardStateMonitor.notifyStateChanged(() -> {
            Intent intent = new Intent(ACTION_CORE_CONNECTION_STATE_CHANGED);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        });
    }
}
