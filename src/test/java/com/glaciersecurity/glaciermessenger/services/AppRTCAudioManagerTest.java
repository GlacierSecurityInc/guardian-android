package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;

import androidx.test.core.app.ApplicationProvider;

import com.glaciersecurity.glaciermessenger.services.AppRTCBluetoothManager.State;
import com.glaciersecurity.glaciermessenger.services.CallIntegration.AudioDevice;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * The in-call audio picker on Android 15+ goes through {@link
 * AppRTCAudioManager#selectAudioDeviceByUser}. Before it existed the call screen could only set
 * the *default* device, which loses to a connected headset every time, so the button was made
 * inert on Bluetooth and the user could not get the call back onto the phone.
 *
 * <p>The Bluetooth manager is replaced by a fake whose state moves the way the real one does
 * (stopScoAudio -> SCO_DISCONNECTING -> updateDevice -> HEADSET_AVAILABLE), so these exercise the
 * real {@code updateAudioDeviceState()} decisions.
 */
@RunWith(RobolectricTestRunner.class)
public class AppRTCAudioManagerTest {

    private AppRTCAudioManager manager;
    private AppRTCBluetoothManager bluetooth;
    private AudioManager audioManager;
    private State state;
    private boolean headsetConnected;

    @Before
    public void setUp() throws Exception {
        final Application context = ApplicationProvider.getApplicationContext();
        shadowOf(context.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_TELEPHONY, true);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        manager = new AppRTCAudioManager(context);

        bluetooth = mock(AppRTCBluetoothManager.class);
        when(bluetooth.getState()).thenAnswer(i -> state);
        doAnswer(i -> state = State.SCO_DISCONNECTING).when(bluetooth).stopScoAudio();
        doAnswer(i -> {
                    if (state == State.SCO_DISCONNECTING
                            || state == State.HEADSET_UNAVAILABLE
                            || state == State.HEADSET_AVAILABLE) {
                        state = headsetConnected ? State.HEADSET_AVAILABLE : State.HEADSET_UNAVAILABLE;
                    }
                    return null;
                })
                .when(bluetooth)
                .updateDevice();
        when(bluetooth.startScoAudio()).thenAnswer(i -> {
            state = State.SCO_CONNECTING;
            return true;
        });
        final Field field = AppRTCAudioManager.class.getDeclaredField("bluetoothManager");
        field.setAccessible(true);
        field.set(manager, bluetooth);
        // as at the start of every call: resets the user's choice to NONE
        state = State.HEADSET_UNAVAILABLE;
        manager.start();
    }

    private void onBluetooth() {
        headsetConnected = true;
        state = State.SCO_CONNECTED;
        manager.updateAudioDeviceState();
        assertThat(manager.getSelectedAudioDevice()).isEqualTo(AudioDevice.BLUETOOTH);
        clearInvocations(bluetooth);
    }

    @Test
    public void pickingPhoneWhileOnBluetoothLeavesTheHeadset() {
        onBluetooth();

        manager.selectAudioDeviceByUser(AudioDevice.EARPIECE);

        verify(bluetooth).stopScoAudio();
        assertThat(manager.getSelectedAudioDevice()).isEqualTo(AudioDevice.EARPIECE);
        assertThat(audioManager.isSpeakerphoneOn()).isFalse();
    }

    @Test
    public void pickingSpeakerWhileOnBluetoothTurnsTheSpeakerOn() {
        onBluetooth();

        manager.selectAudioDeviceByUser(AudioDevice.SPEAKER_PHONE);

        verify(bluetooth).stopScoAudio();
        assertThat(manager.getSelectedAudioDevice()).isEqualTo(AudioDevice.SPEAKER_PHONE);
        assertThat(audioManager.isSpeakerphoneOn()).isTrue();
    }

    @Test
    public void thePhonePickSticksWhileTheHeadsetStaysConnected() {
        onBluetooth();
        manager.selectAudioDeviceByUser(AudioDevice.EARPIECE);

        // any later headset broadcast re-runs the decision; it must not pull the call back
        manager.updateAudioDeviceState();
        manager.updateAudioDeviceState();

        verify(bluetooth, never()).startScoAudio();
        assertThat(manager.getSelectedAudioDevice()).isEqualTo(AudioDevice.EARPIECE);
    }

    @Test
    public void pickingBluetoothAgainRestartsScoWithFreshAttempts() {
        onBluetooth();
        manager.selectAudioDeviceByUser(AudioDevice.EARPIECE);
        bluetooth.scoConnectionAttempts = 2;

        manager.selectAudioDeviceByUser(AudioDevice.BLUETOOTH);

        assertThat(bluetooth.scoConnectionAttempts).isEqualTo(0);
        verify(bluetooth).startScoAudio();
        state = State.SCO_CONNECTED;
        manager.updateAudioDeviceState();
        assertThat(manager.getSelectedAudioDevice()).isEqualTo(AudioDevice.BLUETOOTH);
    }

    @Test
    public void theAppsOwnDefaultStillLosesToTheHeadset() {
        // CallIntegration's automatic choices go through setDefaultAudioDevice(); a headset that
        // connects mid-call must still win over those
        onBluetooth();

        manager.setDefaultAudioDevice(AudioDevice.EARPIECE);

        verify(bluetooth, never()).stopScoAudio();
        assertThat(manager.getSelectedAudioDevice()).isEqualTo(AudioDevice.BLUETOOTH);
    }

    @Test
    public void aHeadsetConnectingAfterAnAutomaticChoiceIsTakenUp() {
        state = State.HEADSET_UNAVAILABLE;
        manager.updateAudioDeviceState();
        manager.setDefaultAudioDevice(AudioDevice.EARPIECE);

        headsetConnected = true;
        manager.updateAudioDeviceState();

        verify(bluetooth).startScoAudio();
    }
}
