package com.glaciersecurity.glaciermessenger.ui.util;

import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.glaciersecurity.glaciermessenger.R;

import static android.content.Context.AUDIO_SERVICE;
import static android.content.Context.VIBRATOR_SERVICE;

public class SoundPoolManager {

    private boolean playing = false;
    private boolean loaded = false;
    private boolean playingCalled = false;
    private boolean outgoingCalled = false;
    private float volume;
    private SoundPool soundPool;
    private int ringingSoundId;
    private int ringingStreamId;
    private int outgoingSoundId;
    private int disconnectSoundId;
    private int joingSoundId;

    Ringtone ringtone;
    Ringtone outgoingRingtone;

    private static SoundPoolManager instance;

    private AudioManager audioManager;
    private Vibrator vibrator;

    private SoundPoolManager(Context context) {
        // AudioManager audio settings for adjusting the volume
        audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        float actualVolume = (float) audioManager.getStreamVolume(audioManager.getMode());
        float maxVolume = (float) audioManager.getStreamMaxVolume(audioManager.getMode());
        volume = actualVolume / maxVolume;

        vibrator = (Vibrator) context.getSystemService(VIBRATOR_SERVICE);

        Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"+ context.getPackageName() + "/" + R.raw.incoming_ring);
        ringtone = RingtoneManager.getRingtone(context,soundUri);


        Uri outgoingUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"+ context.getPackageName() + "/" + R.raw.outgoing);
        outgoingRingtone = RingtoneManager.getRingtone(context,outgoingUri);

        // Load the sounds
        int maxStreams = 2;
        soundPool = new SoundPool.Builder()
                .setMaxStreams(maxStreams)
                .build();

        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                loaded = true;
                if (playingCalled) {
                    playRinging();
                    playingCalled = false;
                }

                if(outgoingCalled){
                    playOutgoing();
                    outgoingCalled = false;
                }
            }

        });
        ringingSoundId = soundPool.load(context, R.raw.incoming_ring, 1);
        outgoingSoundId = soundPool.load(context, R.raw.outgoing, 1);
        disconnectSoundId = soundPool.load(context, R.raw.disconnect_end_call, 1);
        joingSoundId = soundPool.load(context, R.raw.join_call, 1);
    }

    private float getVolume(){
        float actualVolume = (float) audioManager.getStreamVolume(audioManager.getMode());
        float maxVolume = (float) audioManager.getStreamMaxVolume(audioManager.getMode());
        volume = actualVolume / maxVolume;
        return volume;
    }
    public static SoundPoolManager getInstance(Context context) {
        if (instance == null) {
            instance = new SoundPoolManager(context);
        }
        return instance;
    }

    public void playRinging() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build();
        ringtone.setAudioAttributes(attrs);
        audioManager.setMode(AudioManager.MODE_RINGTONE);
        if (loaded && !playing) {
            ringtone.play();
            vibrateIfNeeded();

            playing = true;
        } else {
            playingCalled = true;
        }
    }

    public void playOutgoing() {
        AudioAttributes attr = new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                .build();

        outgoingRingtone.setAudioAttributes(attr);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (loaded && !playing) {
            outgoingRingtone.play();
            playing = true;
        } else {
            outgoingCalled = true;
        }
    }


    public static boolean isBluetoothHeadsetEnabled() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();
    }

    public void vibrateIfNeeded() {
        if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE ||
                audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            // Start without a delay, Vibrate for 1000 milliseconds, Sleep for 1000 milliseconds
            long[] pattern = {0, 1000, 1000};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    public void stopRinging() {
        vibrator.cancel();
        if (playing) {
            soundPool.stop(ringingStreamId);
            if (ringtone != null) { // probably don't need above line anymore
                ringtone.stop();
            }
            soundPool.stop(outgoingSoundId);
            if (outgoingRingtone != null) {
                outgoingRingtone.stop();
            }
            playing = false;
        }

    }

    public void playDisconnect() {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (loaded && !playing) {
            soundPool.play(disconnectSoundId, getVolume(), getVolume(), 1, 0, 1f);
            playing = false;
        }
    }

    public void playJoin() {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (loaded && !playing) {
            soundPool.play(joingSoundId, getVolume(), getVolume(), 1, 0, 1f);
            playing = false;
        }
    }

    public void release() {
        if (soundPool != null) {
            soundPool.unload(ringingSoundId);
            soundPool.unload(disconnectSoundId);
            soundPool.unload(joingSoundId);
            soundPool.unload(outgoingSoundId);
            soundPool.release();
            soundPool = null;
        }
        instance = null;
    }
}
