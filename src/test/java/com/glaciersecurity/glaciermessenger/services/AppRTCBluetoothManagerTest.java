package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.time.Duration;
import java.util.List;

/**
 * App-managed G2G calls (Android 15+) move audio to a headset with Bluetooth SCO, which takes a
 * few seconds to come up. The manager waits for the headset's "audio connected" broadcast and
 * otherwise gives up after a 4 s timeout, calling stopBluetoothSco() -- which pulls the call off
 * the headset and back onto the phone.
 *
 * <p>Both halves of that broke in the field ("answered on Bluetooth, back on the phone a few
 * seconds later"): the receiver was registered NOT_EXPORTED, so the broadcast from the Bluetooth
 * app (uid 1002) never arrived, and the timeout fallback then only checked the first connected
 * headset. {@link #headsetReceiverIsExported} and {@link #timeoutKeepsScoWhenALaterHeadsetCarriesTheAudio}
 * pin those two. Robolectric does not enforce export flags, so the flag has to be asserted directly.
 */
@RunWith(RobolectricTestRunner.class)
public class AppRTCBluetoothManagerTest {

    private static final Duration SCO_TIMEOUT = Duration.ofMillis(4000);

    private Application context;
    private AudioManager audioManager;
    private BluetoothHeadset headset;
    private BluetoothDevice watch;
    private BluetoothDevice earbuds;
    private BluetoothProfile.ServiceListener profileListener;
    private AppRTCBluetoothManager manager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        audioManager = mock(AudioManager.class);
        when(audioManager.isBluetoothScoAvailableOffCall()).thenReturn(true);
        headset = mock(BluetoothHeadset.class);
        watch = mock(BluetoothDevice.class);
        earbuds = mock(BluetoothDevice.class);
        final AudioManager stubbedAudioManager = audioManager;
        manager =
                new AppRTCBluetoothManager(context, mock(AppRTCAudioManager.class)) {
                    @Override
                    protected AudioManager getAudioManager(final Context context) {
                        return stubbedAudioManager;
                    }

                    @Override
                    protected boolean getBluetoothProfileProxy(
                            final Context context,
                            final BluetoothProfile.ServiceListener listener,
                            final int profile) {
                        profileListener = listener;
                        return true;
                    }

                    @Override
                    protected boolean hasBluetoothConnectPermission() {
                        return true;
                    }
                };
    }

    @After
    public void tearDown() {
        // drop the mock headset first: stop() would hand it to the real closeProfileProxy()
        if (profileListener != null) {
            profileListener.onServiceDisconnected(BluetoothProfile.HEADSET);
        }
        manager.stop();
    }

    /** start(), the HEADSET proxy connecting, and a headset list -- leaves the manager in SCO_CONNECTING. */
    private void startScoWith(final BluetoothDevice... connected) {
        when(headset.getConnectedDevices()).thenReturn(List.of(connected));
        manager.start();
        profileListener.onServiceConnected(BluetoothProfile.HEADSET, headset);
        manager.updateDevice();
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.HEADSET_AVAILABLE);
        assertThat(manager.startScoAudio()).isTrue();
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTING);
    }

    private void sendAudioState(final int state) {
        context.sendBroadcast(
                new Intent(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                        .putExtra(BluetoothHeadset.EXTRA_STATE, state));
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** A watch and earbuds both connected; Android's active calls device is the earbuds. */
    private void connectWatchAndEarbudsWithEarbudsActive() {
        when(watch.getAddress()).thenReturn("02:00:00:00:00:01");
        when(earbuds.getAddress()).thenReturn("02:00:00:00:00:02");
        final AudioDeviceInfo sco = mock(AudioDeviceInfo.class);
        when(sco.getType()).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        when(sco.getAddress()).thenReturn("02:00:00:00:00:02");
        when(audioManager.getAvailableCommunicationDevices()).thenReturn(List.of(sco));
        when(headset.getConnectedDevices()).thenReturn(List.of(watch, earbuds));
        manager.start();
        profileListener.onServiceConnected(BluetoothProfile.HEADSET, headset);
        manager.updateDevice();
    }

    @Test
    public void routesToTheActiveHeadsetNotTheFirstConnected() {
        connectWatchAndEarbudsWithEarbudsActive();

        assertThat(manager.getDevice()).isSameInstanceAs(earbuds);
    }

    @Test
    public void listsEveryConnectedHeadset() {
        connectWatchAndEarbudsWithEarbudsActive();

        assertThat(manager.getConnectedDevices()).containsExactly(watch, earbuds).inOrder();
    }

    @Test
    public void followsAnActiveHeadsetChangedInSettings() {
        connectWatchAndEarbudsWithEarbudsActive();
        final AudioDeviceInfo sco = mock(AudioDeviceInfo.class);
        when(sco.getType()).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        when(sco.getAddress()).thenReturn("02:00:00:00:00:01");
        when(audioManager.getAvailableCommunicationDevices()).thenReturn(List.of(sco));

        assertThat(manager.getDevice()).isSameInstanceAs(watch);
    }

    @Test
    @Config(sdk = 30)
    public void findsTheActiveHeadsetBeforeAndroid12() {
        when(watch.getAddress()).thenReturn("02:00:00:00:00:01");
        when(earbuds.getAddress()).thenReturn("02:00:00:00:00:02");
        final AudioDeviceInfo sco = mock(AudioDeviceInfo.class);
        when(sco.getType()).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        when(sco.getAddress()).thenReturn("02:00:00:00:00:02");
        when(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .thenReturn(new AudioDeviceInfo[] {sco});
        when(headset.getConnectedDevices()).thenReturn(List.of(watch, earbuds));
        manager.start();
        profileListener.onServiceConnected(BluetoothProfile.HEADSET, headset);
        manager.updateDevice();

        assertThat(manager.getDevice()).isSameInstanceAs(earbuds);
    }

    @Test
    public void fallsBackToTheFirstHeadsetWhenNoneIsAnnouncedActive() {
        when(headset.getConnectedDevices()).thenReturn(List.of(watch, earbuds));
        manager.start();
        profileListener.onServiceConnected(BluetoothProfile.HEADSET, headset);
        manager.updateDevice();

        assertThat(manager.getDevice()).isSameInstanceAs(watch);
    }

    @Test
    public void headsetReceiverIsExported() {
        manager.start();

        final List<ShadowApplication.Wrapper> headsetReceivers =
                shadowOf(context).getRegisteredReceivers().stream()
                        .filter(
                                w ->
                                        w.getIntentFilter()
                                                .hasAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED))
                        .toList();
        assertThat(headsetReceivers).hasSize(1);
        assertThat(headsetReceivers.get(0).flags & ContextCompat.RECEIVER_EXPORTED)
                .isEqualTo(ContextCompat.RECEIVER_EXPORTED);
        assertThat(headsetReceivers.get(0).flags & ContextCompat.RECEIVER_NOT_EXPORTED).isEqualTo(0);
    }

    @Test
    public void audioConnectedBroadcastCompletesScoBeforeTheTimeout() {
        startScoWith(earbuds);

        sendAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTED);
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTED);

        // the broadcast cancelled the timer, so it must not fire later and tear SCO down
        shadowOf(Looper.getMainLooper()).idleFor(SCO_TIMEOUT.multipliedBy(2));
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTED);
        verify(audioManager, never()).stopBluetoothSco();
    }

    @Test
    public void timeoutKeepsScoWhenTheOnlyHeadsetCarriesTheAudio() {
        startScoWith(earbuds);
        when(headset.isAudioConnected(earbuds)).thenReturn(true);

        shadowOf(Looper.getMainLooper()).idleFor(SCO_TIMEOUT);

        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTED);
        verify(audioManager, never()).stopBluetoothSco();
    }

    @Test
    public void timeoutKeepsScoWhenALaterHeadsetCarriesTheAudio() {
        startScoWith(watch, earbuds);
        when(headset.isAudioConnected(watch)).thenReturn(false);
        when(headset.isAudioConnected(earbuds)).thenReturn(true);

        shadowOf(Looper.getMainLooper()).idleFor(SCO_TIMEOUT);

        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTED);
        verify(audioManager, never()).stopBluetoothSco();
    }

    @Test
    public void timeoutStopsScoWhenNoHeadsetCarriesTheAudio() {
        startScoWith(watch, earbuds);

        shadowOf(Looper.getMainLooper()).idleFor(SCO_TIMEOUT);

        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_DISCONNECTING);
        verify(audioManager).stopBluetoothSco();
    }

    @Test
    public void slowHeadsetThatReportsConnectingIsNotCutOffAtFourSeconds() {
        startScoWith(earbuds);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        sendAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTING);

        // 7 s after startScoAudio(): the old 4 s timer would already have torn SCO down
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTING);
        verify(audioManager, never()).stopBluetoothSco();

        sendAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTED);
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTED);
        verify(audioManager, never()).stopBluetoothSco();
    }

    @Test
    public void headsetStuckConnectingIsGivenUpTenSecondsAfterItSaidSo() {
        startScoWith(earbuds);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        sendAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTING);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9999));
        verify(audioManager, never()).stopBluetoothSco();

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_DISCONNECTING);
        verify(audioManager).stopBluetoothSco();
    }

    @Test
    public void noTimeoutDecisionBeforeFourSeconds() {
        startScoWith(earbuds);

        shadowOf(Looper.getMainLooper()).idleFor(SCO_TIMEOUT.minusMillis(1));

        assertThat(manager.getState()).isEqualTo(AppRTCBluetoothManager.State.SCO_CONNECTING);
        verify(audioManager, never()).stopBluetoothSco();
    }
}
