package com.glaciersecurity.glaciermessenger.wireguard;

import static com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties.CoreConnectionState.CONNECTED;
import static com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties.CoreConnectionState.CONNECTING;
import static com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties.CoreConnectionState.DISCONNECTED;
import static com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties.CoreConnectionState.DISCONNECTING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Covers: "Core/VPN", "Core Connection", "Connect / Disconnect / Reconnect after restart",
 * "VPN (connected/disconnected)".
 *
 * <p>This is the single source of truth every UI consumer reads for core connection state, and
 * its hard cases are all timing: the backend briefly reporting a tunnel as UP just after an
 * explicit disconnect, and an empty backend read during startup that must not be mistaken for a
 * real disconnect. None of that is reproducible by hand on a device with any reliability.
 *
 * <p>Runs headless because {@code GlacierGuardian.getContext()} is null in a unit test: every
 * persistence call is guarded by a null-Context check, so the whole state machine runs in memory
 * with no DatabaseBackend. {@code new PersistentConnectionProperties()} gives a fresh instance,
 * so the class's {@code getInstance()} singleton is not in the way.
 */
public class PersistentConnectionPropertiesTest {

    private static final String PROFILE = "us-east_user.conf";
    private static final String OTHER_PROFILE = "eu-west_user.conf";

    /** Mirrors CONNECTION_TRANSITION_TIMEOUT_MS. */
    private static final long TRANSITION_TIMEOUT_MS = 15_000L;

    private PersistentConnectionProperties properties;

    @Before
    public void setUp() {
        properties = new PersistentConnectionProperties();
    }

    private static Set<String> names(final String... values) {
        return new HashSet<>(java.util.Arrays.asList(values));
    }

    /**
     * The transition deadline and the stale-UP suppression window are read straight off
     * {@code System.currentTimeMillis()} with no clock seam, so a test that needs an expired
     * deadline backdates the field rather than sleeping.
     */
    private void backdate(final String field, final long millisAgo) throws Exception {
        final Field declared = PersistentConnectionProperties.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.setLong(properties, System.currentTimeMillis() - millisAgo);
    }

    // ── Baseline ──────────────────────────────────────────────────────────────

    @Test
    public void startsDisconnectedWithNothingRunning() {
        assertEquals(DISCONNECTED, properties.getConnectionState());
        assertFalse(properties.isRunningTunnel());
        assertFalse(properties.isConnectionTransitioning());
        assertTrue(properties.getRunningProfileNames().isEmpty());
        assertNull(properties.getPrimaryRunningProfileName());
        assertFalse(properties.isProfileRunning(PROFILE));
        assertFalse(properties.isProfileRunning(null));
    }

    // ── Explicit user intent ──────────────────────────────────────────────────

    @Test
    public void anExplicitConnectIsReflectedOnTheFirstTap() {
        properties.markProfileConnected(PROFILE);

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isRunningTunnel());
        assertTrue(properties.isProfileRunning(PROFILE));
        assertEquals(PROFILE, properties.getPrimaryRunningProfileName());
        assertEquals(Collections.singleton(PROFILE), properties.getRunningProfileNames());
    }

    /** The app runs a single core profile at a time, so a connect replaces the previous one. */
    @Test
    public void connectingASecondProfileReplacesTheFirst() {
        properties.markProfileConnected(PROFILE);

        properties.markProfileConnected(OTHER_PROFILE);

        assertEquals(Collections.singleton(OTHER_PROFILE), properties.getRunningProfileNames());
        assertFalse(properties.isProfileRunning(PROFILE));
    }

    @Test
    public void aNullProfileNameIsIgnoredOnConnect() {
        properties.markProfileConnected(null);
        properties.markProfileConnecting(null);

        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    @Test
    public void aPendingConnectShowsAsConnectingAndNotYetRunning() {
        properties.markProfileConnecting(PROFILE);

        assertEquals(CONNECTING, properties.getConnectionState());
        assertTrue(properties.isConnectionTransitioning());
        assertFalse("not running until the backend confirms", properties.isProfileRunning(PROFILE));
        assertFalse(properties.isRunningTunnel());
    }

    @Test
    public void disconnectingARunningProfileWaitsForBackendConfirmation() {
        properties.markProfileConnected(PROFILE);

        properties.markProfileDisconnected(PROFILE);

        assertEquals(DISCONNECTING, properties.getConnectionState());
        assertTrue(properties.isConnectionTransitioning());
        assertFalse(properties.isProfileRunning(PROFILE));
    }

    @Test
    public void disconnectingAProfileThatWasNotRunningChangesNothing() {
        properties.markProfileConnected(PROFILE);

        properties.markProfileDisconnected(OTHER_PROFILE);

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isProfileRunning(PROFILE));
    }

    @Test
    public void disconnectingWithNoNameDisconnectsEverything() {
        properties.markProfileConnected(PROFILE);

        properties.markProfileDisconnected(null);

        assertEquals(DISCONNECTING, properties.getConnectionState());
    }

    @Test
    public void disconnectingWhenNothingRunsSettlesStraightToDisconnected() {
        properties.markAllDisconnected();

        assertEquals(DISCONNECTED, properties.getConnectionState());
        assertFalse(properties.isConnectionTransitioning());
    }

    @Test
    public void markAllDisconnectedNowClearsWithoutWaiting() {
        properties.markProfileConnected(PROFILE);

        properties.markAllDisconnectedNow();

        assertEquals(DISCONNECTED, properties.getConnectionState());
        assertTrue(properties.getRunningProfileNames().isEmpty());
    }

    @Test
    public void aFailedBackendOperationClearsThePendingTransition() {
        properties.markProfileConnecting(PROFILE);

        properties.markConnectionTransitionFailed();

        assertEquals(DISCONNECTED, properties.getConnectionState());
        assertFalse(properties.isConnectionTransitioning());
    }

    // ── Reconciliation: the disconnect path ───────────────────────────────────

    @Test
    public void anEmptyBackendReadConfirmsAPendingDisconnect() {
        properties.markProfileConnected(PROFILE);
        properties.markProfileDisconnected(PROFILE);

        assertTrue(properties.reconcileRunningProfiles(Collections.emptySet(), false));
        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    /**
     * The regression the stale-UP suppression exists for: GoBackend can still report the tunnel
     * as UP for a moment after an explicit disconnect, and applying that would undo the user's
     * tap before the authoritative "lost" callback arrives.
     */
    @Test
    public void aStaleBackendUpDoesNotUndoAnExplicitDisconnect() {
        properties.markProfileConnected(PROFILE);
        properties.markProfileDisconnected(PROFILE);

        assertFalse(properties.reconcileRunningProfiles(names(PROFILE), false));

        assertEquals(DISCONNECTING, properties.getConnectionState());
        assertFalse(properties.isProfileRunning(PROFILE));
    }

    /** ...but it cannot hold the UI in "disconnecting" forever if the callback never comes. */
    @Test
    public void aStaleBackendUpStopsWinningOnceTheTransitionTimesOut() throws Exception {
        properties.markProfileConnected(PROFILE);
        properties.markProfileDisconnected(PROFILE);
        backdate("transitionStartedAtMs", TRANSITION_TIMEOUT_MS + 1_000L);

        assertTrue(properties.reconcileRunningProfiles(names(PROFILE), false));

        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    /**
     * Once the suppression window has passed, a backend "UP" is taken at face value again. The
     * running set was never actually cleared during the pending disconnect -- only the transition
     * masked it -- so the state goes back to CONNECTED while the call reports no change.
     */
    @Test
    public void suppressionLapsesWhenItsWindowExpires() throws Exception {
        properties.markProfileConnected(PROFILE);
        properties.markProfileDisconnected(PROFILE);
        backdate("staleBackendUpSuppressionUntilMs", 1_000L);

        assertFalse(properties.reconcileRunningProfiles(names(PROFILE), false));

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isProfileRunning(PROFILE));
    }

    @Test
    public void aDifferentProfileComingUpDuringADisconnectIsApplied() {
        properties.markProfileConnected(PROFILE);
        properties.markProfileDisconnected(PROFILE);

        assertTrue(properties.reconcileRunningProfiles(names(OTHER_PROFILE), false));

        assertEquals(CONNECTED, properties.getConnectionState());
        assertEquals(Collections.singleton(OTHER_PROFILE), properties.getRunningProfileNames());
    }

    /**
     * Reconnecting the same profile lifts its suppression, so the next backend "UP" is no longer
     * filtered out as stale.
     */
    @Test
    public void reconnectingClearsTheSuppressionForThatProfile() {
        properties.markProfileConnected(PROFILE);
        properties.markProfileDisconnected(PROFILE);

        properties.markProfileConnected(PROFILE);

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isProfileRunning(PROFILE));

        properties.reconcileRunningProfiles(names(PROFILE), false);

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isProfileRunning(PROFILE));
    }

    // ── Reconciliation: the connect path ──────────────────────────────────────

    @Test
    public void aBackendReadConfirmsAPendingConnect() {
        properties.markProfileConnecting(PROFILE);

        assertTrue(properties.reconcileRunningProfiles(names(PROFILE), false));

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isProfileRunning(PROFILE));
    }

    /** During warmup the backend legitimately reports nothing; that is not a failed connect. */
    @Test
    public void anEmptyQueryDoesNotCancelAPendingConnect() {
        properties.markProfileConnecting(PROFILE);

        assertFalse(properties.reconcileRunningProfiles(Collections.emptySet(), false));

        assertEquals(CONNECTING, properties.getConnectionState());
    }

    @Test
    public void anObservedDisconnectDoesCancelAPendingConnect() {
        properties.markProfileConnecting(PROFILE);

        assertFalse(properties.reconcileRunningProfiles(Collections.emptySet(), true));

        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    @Test
    public void aPendingConnectGivesUpOnceItTimesOut() throws Exception {
        properties.markProfileConnecting(PROFILE);
        backdate("transitionStartedAtMs", TRANSITION_TIMEOUT_MS + 1_000L);

        assertFalse(properties.reconcileRunningProfiles(Collections.emptySet(), false));

        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    // ── Reconciliation: the stable state ──────────────────────────────────────

    /**
     * The asymmetry that makes reboot/resume work: an empty backend read during startup must not
     * tear down the state (and its persisted row) that the boot restore is driven from.
     */
    @Test
    public void anEmptyQueryDoesNotTearDownAStableConnection() {
        properties.markProfileConnected(PROFILE);

        assertFalse(properties.reconcileRunningProfiles(Collections.emptySet(), false));

        assertEquals(CONNECTED, properties.getConnectionState());
        assertTrue(properties.isProfileRunning(PROFILE));
    }

    @Test
    public void anObservedDisconnectDoesTearDownAStableConnection() {
        properties.markProfileConnected(PROFILE);

        assertTrue(properties.reconcileRunningProfiles(Collections.emptySet(), true));

        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    @Test
    public void aNullBackendResultIsTreatedAsEmpty() {
        properties.markProfileConnected(PROFILE);

        assertFalse(properties.reconcileRunningProfiles(null, false));
        assertTrue(properties.reconcileRunningProfiles(null, true));

        assertEquals(DISCONNECTED, properties.getConnectionState());
    }

    @Test
    public void reconcilingToTheSameStateReportsNoChange() {
        properties.markProfileConnected(PROFILE);

        assertFalse(properties.reconcileRunningProfiles(names(PROFILE), false));
        assertEquals(CONNECTED, properties.getConnectionState());
    }

    @Test
    public void aBackendReadIsAppliedFromAStableDisconnectedState() {
        assertTrue(properties.reconcileRunningProfiles(names(PROFILE), false));

        assertEquals(CONNECTED, properties.getConnectionState());
    }

    /** The caller's set is copied, not adopted -- mutating it afterwards must not leak in. */
    @Test
    public void theCallersSetIsNotAliased() {
        final Set<String> backend = names(PROFILE);
        properties.reconcileRunningProfiles(backend, false);

        backend.add(OTHER_PROFILE);

        assertEquals(Collections.singleton(PROFILE), properties.getRunningProfileNames());
    }

    @Test
    public void theReturnedSnapshotIsNotLive() {
        properties.markProfileConnected(PROFILE);

        properties.getRunningProfileNames().add(OTHER_PROFILE);

        assertEquals(Collections.singleton(PROFILE), properties.getRunningProfileNames());
    }

    // ── Tunnels ───────────────────────────────────────────────────────────────

    @Test
    public void tunnelsAreCreatedOnceAndReusedByName() {
        final WgTunnel first = properties.getTunnel(PROFILE);
        final WgTunnel again = properties.getTunnel(PROFILE);

        assertEquals(first, again);
        assertEquals(1, properties.getTunnels().size());
        assertEquals(PROFILE, first.getName());
    }

    @Test
    public void theCurrentTunnelFollowsTheLastLookup() {
        properties.getTunnel(PROFILE);
        assertEquals(PROFILE, properties.getCurrentTunnelName());
        assertTrue(properties.isCurrentTunnel(PROFILE));

        properties.getTunnel(OTHER_PROFILE);

        assertEquals(OTHER_PROFILE, properties.getCurrentTunnelName());
        assertFalse(properties.isCurrentTunnel(PROFILE));
    }

    @Test
    public void thereIsNoCurrentTunnelBeforeAnyLookup() {
        assertNull(properties.getCurrentTunnelName());
        assertFalse(properties.isCurrentTunnel(PROFILE));
        assertFalse(properties.isCurrentTunnel(null));
    }

    @Test
    public void theTunnelListIsHandedOutAsASnapshot() {
        properties.getTunnel(PROFILE);

        properties.getTunnels().clear();

        assertEquals(1, properties.getTunnels().size());
    }
}
