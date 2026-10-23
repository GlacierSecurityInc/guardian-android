package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ConnectivityReceiver#isConnected} gates outbound work app-wide, so a false negative does
 * not surface as an error -- requests are simply never made and the app looks idle.
 *
 * <p>The clause worth the most here is TRANSPORT_VPN. Core is a VPN, so while the tunnel is up it
 * *is* the active network, and Android only folds the underlying WIFI/CELLULAR transport into its
 * capabilities when the VPN declares an underlying network. Without that clause a device on Core
 * reads as offline. Deleting it should turn {@link #aVpnTransportCountsAsConnected} red and nothing
 * else.
 *
 * <p>The second half of the class is the stability stamp {@code hasBeenConnectedFor} reads, which
 * exists so reconnect logic can wait out a flapping link. It is elapsed-realtime based with no
 * clock seam, so these tests drive Robolectric's clock rather than sleeping.
 *
 * <p>Robolectric rather than plain JUnit: every branch is a ConnectivityManager answer, and against
 * the mockable android.jar they are all null -- the whole class would pass vacuously as "offline".
 */
@RunWith(RobolectricTestRunner.class)
public class ConnectivityReceiverTest {

    private Context context;
    private ConnectivityManager connectivityManager;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        resetStaticState();
    }

    /**
     * The three stamps are private statics with no reset hook, and Robolectric keeps one sandbox
     * per class, so without this a test inherits the previous one's connectivity history.
     */
    private static void resetStaticState() throws Exception {
        booleanField("LAST_CONNECTED_STATE").set(false);
        longField("LAST_RESTORE_ELAPSED_MS").set(-1L);
        longField("STABLE_CONNECTED_SINCE_ELAPSED_MS").set(-1L);
    }

    @SuppressWarnings("unchecked")
    private static AtomicBoolean booleanField(final String name) throws Exception {
        final Field field = ConnectivityReceiver.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicBoolean) field.get(null);
    }

    private static AtomicLong longField(final String name) throws Exception {
        final Field field = ConnectivityReceiver.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicLong) field.get(null);
    }

    private void activeNetworkWithTransport(final int transport) {
        final NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(transport);
        shadowOf(connectivityManager)
                .setNetworkCapabilities(connectivityManager.getActiveNetwork(), capabilities);
    }

    /** Take the network away entirely, so {@code getActiveNetwork()} answers null. */
    private void disconnect() {
        shadowOf(connectivityManager).setDefaultNetworkActive(false);
    }

    // ---- which transports count as online -------------------------------------------------

    @Test
    public void aWifiTransportCountsAsConnected() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        assertThat(ConnectivityReceiver.isConnected(context)).isTrue();
    }

    @Test
    public void aCellularTransportCountsAsConnected() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        assertThat(ConnectivityReceiver.isConnected(context)).isTrue();
    }

    @Test
    public void anEthernetTransportCountsAsConnected() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        assertThat(ConnectivityReceiver.isConnected(context)).isTrue();
    }

    /** Core is a VPN; without this clause every device on Core reads as offline. */
    @Test
    public void aVpnTransportCountsAsConnected() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_VPN);
        assertThat(ConnectivityReceiver.isConnected(context)).isTrue();
    }

    @Test
    public void aTransportWeDoNotRecogniseDoesNotCountAsConnected() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH);
        assertThat(ConnectivityReceiver.isConnected(context)).isFalse();
    }

    @Test
    public void noActiveNetworkIsNotConnected() {
        disconnect();
        // Asserted rather than assumed: this is the branch where getActiveNetwork() itself is
        // null, and it has to stay distinct from the capabilities-are-null case below.
        assertThat(connectivityManager.getActiveNetwork()).isNull();
        assertThat(ConnectivityReceiver.isConnected(context)).isFalse();
    }

    @Test
    public void anActiveNetworkWithNoCapabilitiesIsNotConnected() {
        final Network network = connectivityManager.getActiveNetwork();
        shadowOf(connectivityManager).setNetworkCapabilities(network, null);
        assertThat(ConnectivityReceiver.isConnected(context)).isFalse();
    }

    // ---- the stability stamp --------------------------------------------------------------

    @Test
    public void connectingStampsBothTheRestoreAndStableTimes() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);

        assertThat(ConnectivityReceiver.getLastRestoreElapsedRealtimeMs()).isGreaterThan(0L);
        assertThat(ConnectivityReceiver.getStableConnectedSinceElapsedRealtimeMs())
                .isEqualTo(ConnectivityReceiver.getLastRestoreElapsedRealtimeMs());
    }

    @Test
    public void aZeroMinimumIsSatisfiedAsSoonAsWeAreConnected() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        assertThat(ConnectivityReceiver.hasBeenConnectedFor(context, 0L)).isTrue();
    }

    @Test
    public void aFreshConnectionHasNotYetBeenStableForLong() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);
        assertThat(ConnectivityReceiver.hasBeenConnectedFor(context, 30_000L)).isFalse();
    }

    @Test
    public void theSameConnectionBecomesStableOnceEnoughTimeHasPassed() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));

        assertThat(ConnectivityReceiver.hasBeenConnectedFor(context, 30_000L)).isTrue();
    }

    /** Staying connected must not keep pushing the stamp forward, or nothing is ever stable. */
    @Test
    public void stayingConnectedDoesNotResetTheStamp() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);
        final long stampedAt = ConnectivityReceiver.getStableConnectedSinceElapsedRealtimeMs();

        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        ConnectivityReceiver.isConnected(context);

        assertThat(ConnectivityReceiver.getStableConnectedSinceElapsedRealtimeMs())
                .isEqualTo(stampedAt);
        assertThat(ConnectivityReceiver.getLastRestoreElapsedRealtimeMs()).isEqualTo(stampedAt);
    }

    @Test
    public void droppingTheConnectionClearsTheStableStamp() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);

        disconnect();
        ConnectivityReceiver.isConnected(context);

        assertThat(ConnectivityReceiver.getStableConnectedSinceElapsedRealtimeMs()).isEqualTo(-1L);
        assertThat(ConnectivityReceiver.hasBeenConnectedFor(context, 1L)).isFalse();
    }

    /** A reconnect is a new stability window, so the countdown starts over. */
    @Test
    public void reconnectingRestampsFromTheReconnect() {
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);
        final long firstStamp = ConnectivityReceiver.getLastRestoreElapsedRealtimeMs();

        disconnect();
        assertThat(connectivityManager.getActiveNetwork()).isNull();
        ConnectivityReceiver.isConnected(context);

        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        shadowOf(connectivityManager).setDefaultNetworkActive(true);
        activeNetworkWithTransport(NetworkCapabilities.TRANSPORT_WIFI);
        ConnectivityReceiver.isConnected(context);

        assertThat(ConnectivityReceiver.getLastRestoreElapsedRealtimeMs())
                .isGreaterThan(firstStamp);
        assertThat(ConnectivityReceiver.hasBeenConnectedFor(context, 4_000L)).isFalse();
    }
}
