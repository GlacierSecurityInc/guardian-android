package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Looper;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.AudioDeviceInfoBuilder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Neither Telecom nor the Pixel's Bluetooth stack rings a headset for a self-managed call, so
 * Guardian rings it and answers on its button. These pin when it rings at all, which buttons
 * answer, and that a press answers exactly once. Where the audio actually comes out needs a
 * phone and a headset.
 */
@RunWith(RobolectricTestRunner.class)
public class HeadsetRingerTest {

    private Application context;
    private AudioManager audioManager;
    private final AtomicInteger answers = new AtomicInteger();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        audioManager = context.getSystemService(AudioManager.class);
        audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
    }

    @After
    public void tearDown() {
        HeadsetRinger.stop();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void connect(final int type) {
        shadowOf(audioManager)
                .setOutputDevices(List.of(AudioDeviceInfoBuilder.newBuilder().setType(type).build()));
    }

    private void ring() {
        HeadsetRinger.start(context, null, answers::incrementAndGet);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void ringsWhenABluetoothHeadsetIsConnected() {
        connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        ring();
        assertThat(HeadsetRinger.isRinging()).isTrue();

        HeadsetRinger.stop();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(HeadsetRinger.isRinging()).isFalse();
    }

    @Test
    public void leBluetoothHeadsetsCountToo() {
        connect(AudioDeviceInfo.TYPE_BLE_HEADSET);
        ring();
        assertThat(HeadsetRinger.isRinging()).isTrue();
    }

    @Test
    public void staysQuietWithoutAHeadset() {
        connect(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        ring();
        assertThat(HeadsetRinger.isRinging()).isFalse();
    }

    @Test
    public void vibrateStillRingsTheHeadset() {
        connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        audioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        ring();
        assertThat(HeadsetRinger.isRinging()).isTrue();
        assertThat(HeadsetRinger.isPlaying()).isTrue();
    }

    @Test
    public void silentKeepsTheButtonButPlaysNothing() {
        connect(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        ring();
        assertThat(HeadsetRinger.isRinging()).isTrue();
        assertThat(HeadsetRinger.isPlaying()).isFalse();
    }

    @Test
    public void playPauseAndHookAnswerButSkipDoesNot() {
        assertThat(HeadsetRinger.isAnswerKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isTrue();
        assertThat(HeadsetRinger.isAnswerKey(KeyEvent.KEYCODE_HEADSETHOOK)).isTrue();
        assertThat(HeadsetRinger.isAnswerKey(KeyEvent.KEYCODE_MEDIA_PLAY)).isTrue();
        assertThat(HeadsetRinger.isAnswerKey(KeyEvent.KEYCODE_MEDIA_PAUSE)).isTrue();
        assertThat(HeadsetRinger.isAnswerKey(KeyEvent.KEYCODE_MEDIA_NEXT)).isFalse();
        assertThat(HeadsetRinger.isAnswerKey(KeyEvent.KEYCODE_VOLUME_UP)).isFalse();
    }

    @Test
    public void aPressAnswersOnceOnKeyDown() {
        final var ringer = new HeadsetRinger(context, answers::incrementAndGet);
        final int key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;

        assertThat(ringer.handleMediaButton(new KeyEvent(KeyEvent.ACTION_DOWN, key))).isTrue();
        assertThat(ringer.handleMediaButton(new KeyEvent(KeyEvent.ACTION_UP, key))).isTrue();
        // a held button repeats ACTION_DOWN
        assertThat(
                        ringer.handleMediaButton(
                                new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 1)))
                .isTrue();

        assertThat(answers.get()).isEqualTo(1);
    }

    @Test
    public void otherButtonsAreNotConsumed() {
        final var ringer = new HeadsetRinger(context, answers::incrementAndGet);
        assertThat(
                        ringer.handleMediaButton(
                                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
                .isFalse();
        assertThat(ringer.handleMediaButton(null)).isFalse();
        assertThat(answers.get()).isEqualTo(0);
    }
}
