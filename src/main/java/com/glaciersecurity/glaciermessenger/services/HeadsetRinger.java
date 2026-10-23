package com.glaciersecurity.glaciermessenger.services;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

/**
 * Rings a connected Bluetooth headset for an incoming G2G call, and lets its button answer.
 *
 * <p>Android does neither for us. Telecom does not ring self-managed calls, and on a Pixel 9a
 * (API 37) the Bluetooth stack did not tell the headset about a ringing self-managed call until it
 * was active, so there was no HFP ring and no answer button. The notification ringtone plays on
 * the phone speaker only. So while a call rings: play the ringtone pinned to the headset's media
 * output, and hold an active media session, because with no HFP call a headset button press
 * arrives as play/pause.
 */
public final class HeadsetRinger {

    public interface OnAnswer {
        void answer();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Nullable private static HeadsetRinger current;

    private final Context context;
    private final OnAnswer onAnswer;
    @Nullable private MediaPlayer player;
    @Nullable private MediaSession session;

    @VisibleForTesting
    HeadsetRinger(final Context context, final OnAnswer onAnswer) {
        this.context = context.getApplicationContext();
        this.onAnswer = onAnswer;
    }

    /**
     * Starts ringing a connected headset. Its button answers in any ringer mode; the ringtone
     * plays unless the phone is on silent -- vibrate keeps the phone quiet, not the wearer's ears.
     */
    public static void start(
            final Context context, @Nullable final Uri ringtone, final OnAnswer onAnswer) {
        MAIN.post(
                () -> {
                    stopOnMain();
                    final var audioManager = context.getSystemService(AudioManager.class);
                    if (audioManager == null) {
                        return;
                    }
                    final AudioDeviceInfo headset = findHeadsetOutput(audioManager);
                    if (headset == null) {
                        return;
                    }
                    final var ringer = new HeadsetRinger(context, onAnswer);
                    current = ringer;
                    ringer.begin(
                            headset,
                            audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT
                                    ? null
                                    : ringtone == null
                                            ? RingtoneManager.getDefaultUri(
                                                    RingtoneManager.TYPE_RINGTONE)
                                            : ringtone);
                });
    }

    @VisibleForTesting
    static boolean isRinging() {
        return current != null;
    }

    @VisibleForTesting
    static boolean isPlaying() {
        final var ringer = current;
        return ringer != null && ringer.player != null;
    }

    public static void stop() {
        MAIN.post(HeadsetRinger::stopOnMain);
    }

    private static void stopOnMain() {
        final var ringer = current;
        current = null;
        if (ringer != null) {
            ringer.release();
        }
    }

    @VisibleForTesting
    @Nullable
    static AudioDeviceInfo findHeadsetOutput(final AudioManager audioManager) {
        for (final AudioDeviceInfo device :
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    return device;
                }
                default -> {}
            }
        }
        return null;
    }

    /** {@code uri} null: answer on the button but play nothing. */
    private void begin(final AudioDeviceInfo headset, @Nullable final Uri uri) {
        startSession();
        if (uri == null) {
            return;
        }
        final var player = new MediaPlayer();
        this.player = player;
        try {
            // media, not ringtone: the policy keeps ringtone usage on the speaker and ignores the
            // preferred device (measured on a Pixel 9a, API 37); media goes where it is pinned
            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            player.setDataSource(context, uri);
            player.setLooping(true);
            player.setPreferredDevice(headset);
            player.setOnPreparedListener(MediaPlayer::start);
            player.prepareAsync();
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "could not ring the headset", e);
        }
    }

    private void startSession() {
        final var session = new MediaSession(context, "glacier_incoming_call");
        this.session = session;
        session.setCallback(
                new MediaSession.Callback() {
                    @Override
                    public boolean onMediaButtonEvent(@NonNull final Intent intent) {
                        return handleMediaButton(intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT));
                    }
                },
                MAIN);
        // a playing session is where the system sends a headset's play/pause
        session.setPlaybackState(
                new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build());
        session.setActive(true);
    }

    @VisibleForTesting
    boolean handleMediaButton(@Nullable final KeyEvent event) {
        if (event == null || !isAnswerKey(event.getKeyCode())) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            stopOnMain();
            onAnswer.answer();
        }
        return true;
    }

    @VisibleForTesting
    static boolean isAnswerKey(final int keyCode) {
        return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    private void release() {
        final var player = this.player;
        this.player = null;
        if (player != null) {
            try {
                player.stop();
            } catch (final IllegalStateException e) {
                // not started yet
            }
            player.release();
        }
        final var session = this.session;
        this.session = null;
        if (session != null) {
            session.setActive(false);
            session.release();
        }
    }
}
