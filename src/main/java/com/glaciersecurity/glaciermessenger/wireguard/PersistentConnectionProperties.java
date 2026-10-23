package com.glaciersecurity.glaciermessenger.wireguard;


import static com.wireguard.android.backend.Tunnel.State.DOWN;

import android.content.Context;

import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.wireguard.android.backend.GoBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds the WireGuard tunnels and is the single source of truth for which core
 * profiles are currently connected.
 *
 * <p>The connected state lives in memory ({@link #runningProfileNames}) and is
 * persisted to the {@code wireguard_config} table for cold starts. Every UI
 * consumer reads it from here instead of querying the Go backend directly: the
 * backend can report a stale state for a short window right after a state change
 * (e.g. still "up" immediately after {@code setState(DOWN)}), which used to leave
 * the UI out of sync until a later refresh.
 *
 * <p>State changes arrive from two kinds of callers:
 * <ul>
 *   <li><b>Intent</b> — explicit user connect/disconnect ({@link #markProfileConnected},
 *       {@link #markProfileDisconnected}, {@link #markAllDisconnected}). Applied
 *       immediately so the first tap is reflected without waiting on the backend.</li>
 *   <li><b>Reconciliation</b> — the WireGuard/OS monitors fold the real backend
 *       state back in ({@link #reconcileRunningProfiles}). This is the only path
 *       that should derive state from the backend.</li>
 * </ul>
 */
public class PersistentConnectionProperties {

    public enum CoreConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private final ArrayList<WgTunnel> tunnels = new ArrayList<>();
    // Guards tunnels + currentTunnel. These are touched from several threads — the
    // UI's AsyncTasks, the VPN network callbacks, the WireGuard state monitor, and
    // the boot receiver — so all access goes through this lock. getTunnels() also
    // hands callers a snapshot so they can iterate without risking a
    // ConcurrentModificationException against a concurrent getTunnel() insert.
    private final Object tunnelsLock = new Object();
    private static PersistentConnectionProperties mInstance= null;
    private WgTunnel currentTunnel;
    private GoBackend backend;

    // ── Single source of truth for core connection state ──────────────────────
    private final Object stateLock = new Object();
    private final Set<String> runningProfileNames = new HashSet<>(); // guarded by stateLock
    private boolean stateInitialized = false;                        // guarded by stateLock
    private final Set<String> staleBackendUpSuppressedProfileNames = new HashSet<>(); // guarded by stateLock
    private final Set<String> pendingTransitionProfileNames = new HashSet<>(); // guarded by stateLock
    private CoreConnectionState transitionState = null;               // guarded by stateLock
    private long staleBackendUpSuppressionUntilMs = 0L;               // guarded by stateLock
    private long transitionStartedAtMs = 0L;                          // guarded by stateLock
    private static final long STALE_BACKEND_UP_SUPPRESSION_MS = 5_000L;
    private static final long CONNECTION_TRANSITION_TIMEOUT_MS = 15_000L;
    // When the app last asked for a teardown; a DOWN without one is a revoke.
    private volatile long expectedTeardownAtMs;
    private final ExecutorService stateWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CoreConnectionStateWriter");
        thread.setDaemon(true);
        return thread;
    });


    public WgTunnel getTunnel(String profName) {
        synchronized (tunnelsLock) {
            for (WgTunnel existingWgTunnel : tunnels) {
                if (existingWgTunnel.getName() != null && existingWgTunnel.getName().equals(profName)) {
                    currentTunnel = existingWgTunnel;
                    return existingWgTunnel;
                }
            }
            WgTunnel tunnel = new WgTunnel();
            tunnel.setName(profName);
            currentTunnel = tunnel;
            tunnels.add(tunnel);
            return tunnel;
        }
    }

    /** @return true when any core profile is connected, per the source of truth. */
    public boolean isRunningTunnel(){
        return getConnectionState() == CoreConnectionState.CONNECTED;
    }

    /** @return the current stable or pending core connection state. */
    public CoreConnectionState getConnectionState() {
        ensureStateInitialized();
        synchronized (stateLock) {
            if (transitionState != null) {
                return transitionState;
            }
            return runningProfileNames.isEmpty()
                    ? CoreConnectionState.DISCONNECTED : CoreConnectionState.CONNECTED;
        }
    }

    public boolean isConnectionTransitioning() {
        CoreConnectionState state = getConnectionState();
        return state == CoreConnectionState.CONNECTING
                || state == CoreConnectionState.DISCONNECTING;
    }

    /** @return true when the named profile is connected, per the source of truth. */
    public boolean isProfileRunning(@Nullable String profName) {
        if (profName == null) {
            return false;
        }
        ensureStateInitialized();
        synchronized (stateLock) {
            return transitionState == null
                    && !runningProfileNames.isEmpty()
                    && runningProfileNames.contains(profName);
        }
    }

    /** @return a snapshot of the connected profile names. */
    public Set<String> getRunningProfileNames() {
        ensureStateInitialized();
        synchronized (stateLock) {
            return new HashSet<>(runningProfileNames);
        }
    }

    /** @return one connected profile name, or null when nothing is connected. */
    @Nullable
    public String getPrimaryRunningProfileName() {
        ensureStateInitialized();
        synchronized (stateLock) {
            return runningProfileNames.isEmpty() ? null : runningProfileNames.iterator().next();
        }
    }

    /**
     * Record an explicit user connect. Applied immediately so the UI reflects the
     * action on the first tap. The app runs a single core profile at a time, so a
     * connect replaces whatever was connected before.
     */
    public void markProfileConnected(@Nullable String profName) {
        if (profName == null) {
            return;
        }
        synchronized (stateLock) {
            staleBackendUpSuppressedProfileNames.remove(profName);
            if (staleBackendUpSuppressedProfileNames.isEmpty()) {
                staleBackendUpSuppressionUntilMs = 0L;
            }
            clearTransitionLocked();
        }
        applyRunningProfiles(Collections.singleton(profName));
    }

    /** Record that a user-requested connect is pending backend confirmation. */
    public void markProfileConnecting(@Nullable String profName) {
        if (profName == null) {
            return;
        }
        ensureStateInitialized();
        synchronized (stateLock) {
            transitionState = CoreConnectionState.CONNECTING;
            transitionStartedAtMs = System.currentTimeMillis();
            pendingTransitionProfileNames.clear();
            pendingTransitionProfileNames.add(profName);
            staleBackendUpSuppressedProfileNames.remove(profName);
            if (staleBackendUpSuppressedProfileNames.isEmpty()) {
                staleBackendUpSuppressionUntilMs = 0L;
            }
        }
    }

    /** Record an explicit user disconnect of a specific profile. */
    public void markProfileDisconnected(@Nullable String profName) {
        if (profName == null) {
            markAllDisconnected();
            return;
        }
        ensureStateInitialized();
        final Set<String> next;
        synchronized (stateLock) {
            next = new HashSet<>(runningProfileNames);
        }
        if (next.remove(profName)) {
            markProfilesDisconnecting(Collections.singleton(profName));
        } else {
            applyRunningProfiles(next);
        }
    }

    /** Record an explicit disconnect of everything pending backend confirmation. */
    public void markAllDisconnected() {
        ensureStateInitialized();
        final Set<String> disconnectedNames;
        synchronized (stateLock) {
            disconnectedNames = new HashSet<>(runningProfileNames);
        }
        markProfilesDisconnecting(disconnectedNames);
    }

    /** Authoritatively clear all running state without waiting for backend confirmation. */
    public void markAllDisconnectedNow() {
        clearStaleBackendUpSuppression();
        clearTransition();
        applyRunningProfiles(Collections.emptySet());
    }

    /** Clear a pending transition after a failed backend operation. */
    public void markConnectionTransitionFailed() {
        clearTransition();
    }

    private void markProfilesDisconnecting(Set<String> profileNames) {
        if (profileNames == null || profileNames.isEmpty()) {
            markAllDisconnectedNow();
            return;
        }
        suppressStaleBackendUpFor(profileNames);
        synchronized (stateLock) {
            transitionState = CoreConnectionState.DISCONNECTING;
            transitionStartedAtMs = System.currentTimeMillis();
            pendingTransitionProfileNames.clear();
            pendingTransitionProfileNames.addAll(profileNames);
        }
    }

    /**
     * Fold the real backend state back into the source of truth. This is the only
     * entry point that callers should drive from a backend query; everyone else
     * reads {@link #isRunningTunnel()} and friends.
     *
     * <p>{@code allowClearOnEmpty} guards the asymmetry that makes reboot/resume
     * work: a non-empty result is normally applied, but profiles that were just
     * explicitly disconnected are briefly filtered out so a stale backend "UP"
     * read cannot undo the user action before the OS emits the later lost
     * callback. An <em>empty</em> result is only authoritative when it comes from a real
     * "VPN lost" transition. During
     * startup, warmup, or a pending boot-restore the backend legitimately reports
     * no tunnels for a moment, and tearing the state (and its persisted row) down
     * on that would defeat the restore that {@code GlacierGuardian}/{@code
     * BootCompletedReceiver} drive from that row. Pass {@code false} for queries
     * (startup/onAvailable) and {@code true} only for an observed disconnect.
     *
     * @return true when the state actually changed.
     */
    public boolean reconcileRunningProfiles(@Nullable Set<String> backendRunningNames,
                                            boolean allowClearOnEmpty) {
        Set<String> next =
                backendRunningNames == null ? Collections.emptySet() : new HashSet<>(backendRunningNames);

        CoreConnectionState pendingState;
        synchronized (stateLock) {
            pendingState = transitionState;
        }

        if (pendingState == CoreConnectionState.DISCONNECTING) {
            if (next.isEmpty()) {
                clearStaleBackendUpSuppression();
                clearTransition();
                return applyRunningProfiles(Collections.emptySet());
            }
            if (removeSuppressedStaleBackendUps(next)) {
                if (next.isEmpty()) {
                    if (isTransitionTimedOut()) {
                        clearStaleBackendUpSuppression();
                        clearTransition();
                        return applyRunningProfiles(Collections.emptySet());
                    }
                    return false;
                }
            }
            clearTransition();
            return applyRunningProfiles(next);
        }

        if (pendingState == CoreConnectionState.CONNECTING) {
            if (next.isEmpty()) {
                if (allowClearOnEmpty || isTransitionTimedOut()) {
                    clearTransition();
                    return applyRunningProfiles(Collections.emptySet());
                }
                return false;
            }
            clearTransition();
            return applyRunningProfiles(next);
        }

        if (next.isEmpty() && !allowClearOnEmpty) {
            return false;
        }
        if (allowClearOnEmpty && next.isEmpty()) {
            clearStaleBackendUpSuppression();
        }
        return applyRunningProfiles(next);
    }

    private void suppressStaleBackendUpFor(Set<String> profileNames) {
        // GoBackend can briefly report a tunnel as UP immediately after an
        // explicit disconnect. Keep those just-disconnected names from being
        // re-applied by query-style reconciliations until the backend catches up
        // or the authoritative onLost clear arrives.
        if (profileNames == null || profileNames.isEmpty()) {
            return;
        }
        synchronized (stateLock) {
            staleBackendUpSuppressedProfileNames.addAll(profileNames);
            staleBackendUpSuppressionUntilMs =
                    System.currentTimeMillis() + STALE_BACKEND_UP_SUPPRESSION_MS;
        }
    }

    private boolean removeSuppressedStaleBackendUps(Set<String> backendRunningNames) {
        synchronized (stateLock) {
            if (staleBackendUpSuppressedProfileNames.isEmpty()) {
                return false;
            }
            if (System.currentTimeMillis() > staleBackendUpSuppressionUntilMs) {
                clearStaleBackendUpSuppressionLocked();
                return false;
            }
            boolean removed = false;
            for (Iterator<String> iterator = backendRunningNames.iterator(); iterator.hasNext(); ) {
                if (staleBackendUpSuppressedProfileNames.contains(iterator.next())) {
                    iterator.remove();
                    removed = true;
                }
            }
            return removed;
        }
    }

    private boolean isTransitionTimedOut() {
        synchronized (stateLock) {
            return transitionState != null
                    && transitionStartedAtMs > 0L
                    && System.currentTimeMillis() - transitionStartedAtMs
                    >= CONNECTION_TRANSITION_TIMEOUT_MS;
        }
    }

    private void clearTransition() {
        synchronized (stateLock) {
            clearTransitionLocked();
        }
    }

    private void clearTransitionLocked() {
        transitionState = null;
        transitionStartedAtMs = 0L;
        pendingTransitionProfileNames.clear();
    }

    private void clearStaleBackendUpSuppression() {
        synchronized (stateLock) {
            clearStaleBackendUpSuppressionLocked();
        }
    }

    private void clearStaleBackendUpSuppressionLocked() {
        staleBackendUpSuppressedProfileNames.clear();
        staleBackendUpSuppressionUntilMs = 0L;
    }

    private boolean applyRunningProfiles(Set<String> next) {
        ensureStateInitialized();
        final Set<String> snapshot;
        synchronized (stateLock) {
            if (runningProfileNames.equals(next)) {
                return false;
            }
            runningProfileNames.clear();
            runningProfileNames.addAll(next);
            snapshot = new HashSet<>(runningProfileNames);
        }
        persistRunningProfiles(snapshot);
        return true;
    }

    private void persistRunningProfiles(final Set<String> snapshot) {
        final Context context = GlacierGuardian.getContext();
        if (context == null) {
            return;
        }
        stateWriter.execute(() -> {
            try {
                DatabaseBackend.getInstance(context).setCoreProfilesRunning(snapshot);
            } catch (Exception ignored) {
                // Best-effort persistence; the in-memory state stays authoritative.
            }
        });
    }

    private void ensureStateInitialized() {
        synchronized (stateLock) {
            if (stateInitialized) {
                return;
            }
            // Seed once from the persisted running rows. Done under the lock (a
            // single indexed query) so concurrent first callers can't observe an
            // empty set before seeding completes.
            Context context = GlacierGuardian.getContext();
            if (context != null) {
                try {
                    Set<String> seeded =
                            DatabaseBackend.getInstance(context).getRunningCoreProfileNames();
                    if (seeded != null) {
                        runningProfileNames.addAll(seeded);
                    }
                } catch (Exception ignored) {
                }
            }
            stateInitialized = true;
        }
    }

    public String getCurrentTunnelName(){
        synchronized (tunnelsLock) {
            return currentTunnel != null ? currentTunnel.getName() : null;
        }
    }

    public boolean isCurrentTunnel(String name) {
        synchronized (tunnelsLock) {
            return currentTunnel != null
                    && currentTunnel.getName() != null
                    && currentTunnel.getName().equals(name);
        }
    }

    public GoBackend getBackend() {
        ensureBackendInitialized();
        return backend;
    }

    public void setBackend(GoBackend backend) {
        this.backend = backend;
    }


    public static synchronized PersistentConnectionProperties getInstance() {
        if(null == mInstance){
            mInstance = new PersistentConnectionProperties();
        }
        return mInstance;
    }

    private synchronized void ensureBackendInitialized() {
        if (backend != null) {
            return;
        }

        Context context = GlacierGuardian.getContext();
        if (context == null) {
            return;
        }

        try {
            backend = new GoBackend(context);
        } catch (Exception ignored) {
        }
    }


    /**
     * Call right before an app-initiated {@code setState(DOWN)}, so the DOWN that GoBackend
     * reports is not mistaken for Android revoking the tunnel.
     */
    public void expectTeardown() {
        expectedTeardownAtMs = android.os.SystemClock.elapsedRealtime();
    }

    /** @return whether a DOWN arriving now was asked for by the app; clears the expectation. */
    boolean consumeExpectedTeardown() {
        final long at = expectedTeardownAtMs;
        expectedTeardownAtMs = 0L;
        return at != 0L
                && android.os.SystemClock.elapsedRealtime() - at < CONNECTION_TRANSITION_TIMEOUT_MS;
    }

    public void disconnectVPNs() {
        // Snapshot under the lock, then do the (potentially blocking) backend calls
        // outside it so we neither hold the lock during I/O nor iterate the live
        // list while another thread might be adding a tunnel.
        final List<WgTunnel> snapshot;
        synchronized (tunnelsLock) {
            snapshot = new ArrayList<>(tunnels);
        }
        for (WgTunnel existingWgTunnel : snapshot) {
            try {
                expectTeardown();
                backend.setState(existingWgTunnel, DOWN, null);
            } catch (Exception e){

            }
        }
        markAllDisconnected();
    }

    public List<WgTunnel> getTunnels() {
        synchronized (tunnelsLock) {
            return new ArrayList<>(tunnels);
        }
    }
}
