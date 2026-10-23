package com.glaciersecurity.glaciermessenger.ui.service;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.PowerManager;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.common.primitives.Ints;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.services.GlacierMediaPlayer;
import com.glaciersecurity.glaciermessenger.ui.adapter.MessageAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.PlaybackSpeedOptions;
import com.glaciersecurity.glaciermessenger.ui.widget.WaveformProgressDrawable;
import com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils;
import com.glaciersecurity.glaciermessenger.utils.WeakReferenceSet;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioPlayer
        implements View.OnClickListener,
                android.media.MediaPlayer.OnCompletionListener,
                SeekBar.OnSeekBarChangeListener,
                Runnable,
                SensorEventListener {

    private static final int REFRESH_INTERVAL = 250;
    private static final Object LOCK = new Object();
    private static GlacierMediaPlayer player = null;
    private static Message currentlyPlayingMessage = null;
    private static PowerManager.WakeLock wakeLock;
    private static int playbackSpeedIndex = PlaybackSpeedOptions.defaultIndex();
    private final MessageAdapter messageAdapter;
    private final WeakReferenceSet<RelativeLayout> audioPlayerLayouts = new WeakReferenceSet<>();
    private final SensorManager sensorManager;
    private final Sensor proximitySensor;
    private final PendingItem<WeakReference<ImageButton>> pendingOnClickView = new PendingItem<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler();

    public AudioPlayer(MessageAdapter adapter) {
        final Context context = adapter.getContext();
        this.messageAdapter = adapter;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.proximitySensor =
                this.sensorManager == null
                        ? null
                        : this.sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        initializeProximityWakeLock(context);
        synchronized (AudioPlayer.LOCK) {
            if (AudioPlayer.player != null) {
                AudioPlayer.player.setOnCompletionListener(this);
                if (AudioPlayer.player.isPlaying() && sensorManager != null) {
                    sensorManager.registerListener(
                            this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        }
    }

    private static String formatTime(final int ms) {
        return TimeFrameUtils.formatElapsedTime(ms,false);
    }

    private void initializeProximityWakeLock(Context context) {
        synchronized (AudioPlayer.LOCK) {
            if (AudioPlayer.wakeLock == null) {
                final PowerManager powerManager =
                        (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                AudioPlayer.wakeLock =
                        powerManager == null
                                ? null
                                : powerManager.newWakeLock(
                                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                                        AudioPlayer.class.getSimpleName());
                if (AudioPlayer.wakeLock != null) {
                    AudioPlayer.wakeLock.setReferenceCounted(false);
                }
            }
        }
    }

    public void init(RelativeLayout audioPlayer, Message message) {
        synchronized (AudioPlayer.LOCK) {
            audioPlayer.setTag(message);
            if (init(ViewHolder.get(audioPlayer), message)) {
                this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
                executor.execute(() -> this.stopRefresher(true));
            } else {
                this.audioPlayerLayouts.removeWeakReferenceTo(audioPlayer);
            }
        }
    }

    private boolean init(final ViewHolder viewHolder, final Message message) {
        MessageAdapter.setTextColor(viewHolder.runtime, viewHolder.bubbleColor);
        MessageAdapter.setTextColor(viewHolder.playbackSpeed, viewHolder.bubbleColor);
        viewHolder.progress.setOnSeekBarChangeListener(this);
        applyBubbleColors(viewHolder);
        viewHolder.playPause.setOnClickListener(this);
        viewHolder.playbackSpeed.setOnClickListener(v -> cyclePlaybackSpeed(viewHolder));
        updatePlaybackSpeedButton(viewHolder);
        final Context context = viewHolder.playPause.getContext();
        if (message == currentlyPlayingMessage) {
            if (AudioPlayer.player != null && AudioPlayer.player.isPlaying()) {
                viewHolder.playPause.setImageResource(R.drawable.ic_pause_24dp);
                tintPlayIcon(viewHolder);
                viewHolder.playPause.setContentDescription(context.getString(R.string.pause_audio));
                viewHolder.progress.setEnabled(true);
            } else {
                viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
                viewHolder.playPause.setImageResource(R.drawable.ic_play_arrow_24dp);
                tintPlayIcon(viewHolder);
                viewHolder.progress.setEnabled(false);
            }
            return true;
        } else {
            viewHolder.playPause.setImageResource(R.drawable.ic_play_arrow_24dp);
            tintPlayIcon(viewHolder);
            viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
            viewHolder.runtime.setText(formatTime(message.getFileParams().runtime));
            viewHolder.progress.setProgress(0);
            viewHolder.progress.setEnabled(false);
            return false;
        }
    }

    /** The circular play button and the waveform's played bars share one accent color, swapped
     *  with its "on accent" counterpart depending on which side of the conversation the bubble
     *  is on -- mirroring how the bubble's own background/foreground pair is chosen. */
    private static void applyBubbleColors(final ViewHolder viewHolder) {
        final boolean sent = viewHolder.bubbleColor == MessageAdapter.BubbleColor.SENT;
        final int accent =
                MaterialColors.getColor(
                        viewHolder.playPause, androidx.appcompat.R.attr.colorPrimary);
        final int onAccent =
                MaterialColors.getColor(
                        viewHolder.playPause, com.google.android.material.R.attr.colorOnPrimary);
        final int buttonFill = sent ? onAccent : accent;
        viewHolder.playPause.setBackgroundTintList(ColorStateList.valueOf(buttonFill));
        tintPlayIcon(viewHolder);
        if (viewHolder.waveformDrawable != null) {
            // Played bars share the play button's fill color; unplayed bars are the same color
            // faded out, so the split reads clearly against either bubble background.
            viewHolder.waveformDrawable.setColors(
                    buttonFill, ColorUtils.setAlphaComponent(buttonFill, 90));
        }
    }

    private static void tintPlayIcon(final ViewHolder viewHolder) {
        final boolean sent = viewHolder.bubbleColor == MessageAdapter.BubbleColor.SENT;
        final int accent =
                MaterialColors.getColor(
                        viewHolder.playPause, androidx.appcompat.R.attr.colorPrimary);
        final int onAccent =
                MaterialColors.getColor(
                        viewHolder.playPause, com.google.android.material.R.attr.colorOnPrimary);
        final int icon = sent ? accent : onAccent;
        ImageViewCompat.setImageTintList(viewHolder.playPause, ColorStateList.valueOf(icon));
    }

    @Override
    public synchronized void onClick(View v) {
        if (v.getId() == R.id.play_pause) {
            synchronized (LOCK) {
                startStop((ImageButton) v);
            }
        }
    }

    /** Walks up from a view inside the audio_player controls row (play_pause / progress /
     *  playback_speed are nested a level deeper than they used to be) to the RelativeLayout
     *  container itself, so callers don't have to assume a fixed nesting depth. */
    private static RelativeLayout findAudioPlayer(final View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getId() == R.id.audio_player) {
                return (RelativeLayout) parent;
            }
        }
        throw new IllegalStateException("view is not nested inside R.id.audio_player");
    }

    private void startStop(ImageButton playPause) {
        initializeProximityWakeLock(playPause.getContext());
        final RelativeLayout audioPlayer = findAudioPlayer(playPause);
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        final Message message = (Message) audioPlayer.getTag();
        if (startStop(viewHolder, message)) {
            this.audioPlayerLayouts.clear();
            this.audioPlayerLayouts.addWeakReferenceTo(audioPlayer);
            stopRefresher(true);
        }
    }

    private boolean playPauseCurrent(final ViewHolder viewHolder) {
        final Context context = viewHolder.playPause.getContext();
        if (player.isPlaying()) {
            viewHolder.progress.setEnabled(false);
            player.pause();
            messageAdapter.flagScreenOff();
            releaseProximityWakeLock();
            viewHolder.playPause.setImageResource(R.drawable.ic_play_arrow_24dp);
            tintPlayIcon(viewHolder);
            viewHolder.playPause.setContentDescription(context.getString(R.string.play_audio));
        } else {
            viewHolder.progress.setEnabled(true);
            player.start();
            messageAdapter.flagScreenOn();
            acquireProximityWakeLock();
            this.stopRefresher(true);
            viewHolder.playPause.setImageResource(R.drawable.ic_pause_24dp);
            tintPlayIcon(viewHolder);
            viewHolder.playPause.setContentDescription(context.getString(R.string.pause_audio));
        }
        return false;
    }

    private void play(ViewHolder viewHolder, Message message, boolean earpiece, double progress) {
        if (play(viewHolder, message, earpiece)) {
            AudioPlayer.player.seekTo((int) (AudioPlayer.player.getDuration() * progress));
        }
    }

    private boolean play(ViewHolder viewHolder, Message message, boolean earpiece) {
        AudioPlayer.player = new GlacierMediaPlayer();
        try {
            AudioPlayer.currentlyPlayingMessage = message;
            AudioPlayer.player.setAudioStreamType(
                    earpiece ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
            AudioPlayer.player.setDataSource(
                    messageAdapter.getFileBackend().getFile(message).getAbsolutePath());
            AudioPlayer.player.setOnCompletionListener(this);
            AudioPlayer.player.prepare();
            applyPlaybackSpeed();
            AudioPlayer.player.start();
            messageAdapter.flagScreenOn();
            acquireProximityWakeLock();
            viewHolder.progress.setEnabled(true);
            viewHolder.playPause.setImageResource(R.drawable.ic_pause_24dp);
            tintPlayIcon(viewHolder);
            viewHolder.playPause.setContentDescription(
                    viewHolder.playPause.getContext().getString(R.string.pause_audio));
            sensorManager.registerListener(
                    this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            return true;
        } catch (final Exception e) {
            messageAdapter.flagScreenOff();
            releaseProximityWakeLock();
            AudioPlayer.currentlyPlayingMessage = null;
            sensorManager.unregisterListener(this);
            return false;
        }
    }

    public void startStopPending() {
        WeakReference<ImageButton> reference = pendingOnClickView.pop();
        if (reference != null) {
            ImageButton imageButton = reference.get();
            if (imageButton != null) {
                startStop(imageButton);
            }
        }
    }

    private boolean startStop(ViewHolder viewHolder, Message message) {
        if (message == currentlyPlayingMessage && player != null) {
            return playPauseCurrent(viewHolder);
        }
        if (AudioPlayer.player != null) {
            stopCurrent();
        }
        return play(viewHolder, message, false);
    }

    private void stopCurrent() {
        if (AudioPlayer.player.isPlaying()) {
            AudioPlayer.player.stop();
        }
        AudioPlayer.player.release();
        messageAdapter.flagScreenOff();
        releaseProximityWakeLock();
        AudioPlayer.player = null;
        resetPlayerUi();
    }

    private void resetPlayerUi() {
        for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
            resetPlayerUi(audioPlayer.get());
        }
    }

    private void resetPlayerUi(final RelativeLayout audioPlayer) {
        if (audioPlayer == null) {
            return;
        }
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        final Message message = (Message) audioPlayer.getTag();
        updatePlaybackSpeedButton(viewHolder);
        viewHolder.playPause.setContentDescription(
                viewHolder.playPause.getContext().getString(R.string.play_audio));
        viewHolder.playPause.setImageResource(R.drawable.ic_play_arrow_24dp);
        tintPlayIcon(viewHolder);
        if (message != null) {
            viewHolder.runtime.setText(formatTime(message.getFileParams().runtime));
        }
        viewHolder.progress.setProgress(0);
        viewHolder.progress.setEnabled(false);
    }

    @Override
    public void onCompletion(android.media.MediaPlayer mediaPlayer) {
        synchronized (AudioPlayer.LOCK) {
            this.stopRefresher(false);
            if (AudioPlayer.player == mediaPlayer) {
                AudioPlayer.currentlyPlayingMessage = null;
                AudioPlayer.player = null;
            }
            mediaPlayer.release();
            messageAdapter.flagScreenOff();
            releaseProximityWakeLock();
            resetPlayerUi();
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        synchronized (AudioPlayer.LOCK) {
            final RelativeLayout audioPlayer = findAudioPlayer(seekBar);
            final Message message = (Message) audioPlayer.getTag();
            if (fromUser && message == AudioPlayer.currentlyPlayingMessage) {
                float percent = progress / 100f;
                int duration = AudioPlayer.player.getDuration();
                int seekTo = Math.round(duration * percent);
                AudioPlayer.player.seekTo(seekTo);
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {}

    public void stop() {
        synchronized (AudioPlayer.LOCK) {
            stopRefresher(false);
            if (AudioPlayer.player != null) {
                stopCurrent();
            }
            AudioPlayer.currentlyPlayingMessage = null;
            sensorManager.unregisterListener(this);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }

    private void stopRefresher(boolean runOnceMore) {
        this.handler.removeCallbacks(this);
        if (runOnceMore) {
            this.handler.post(this);
        }
    }

    public void unregisterListener() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void run() {
        synchronized (AudioPlayer.LOCK) {
            if (AudioPlayer.player != null) {
                boolean renew = false;
                final int current = player.getCurrentPosition();
                final int duration = player.getDuration();
                for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
                    renew |= refreshAudioPlayer(audioPlayer.get(), current, duration);
                }
                if (renew && AudioPlayer.player.isPlaying()) {
                    handler.postDelayed(this, REFRESH_INTERVAL);
                }
            }
        }
    }

    private boolean refreshAudioPlayer(RelativeLayout audioPlayer, int current, int duration) {
        if (audioPlayer == null || audioPlayer.getVisibility() != View.VISIBLE) {
            return false;
        }
        final ViewHolder viewHolder = ViewHolder.get(audioPlayer);
        if (duration <= 0) {
            viewHolder.progress.setProgress(100);
        } else {
            final var progress = current * 100L / duration;
            viewHolder.progress.setProgress(Math.min(Ints.saturatedCast(progress), 100));
        }
        viewHolder.runtime.setText(
                String.format("%s / %s", formatTime(current), formatTime(duration)));
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) {
            return;
        }
        if (AudioPlayer.player == null || !AudioPlayer.player.isPlaying()) {
            return;
        }
        final int streamType;
        if (event.values[0] < 5f && event.values[0] != proximitySensor.getMaximumRange()) {
            streamType = AudioManager.STREAM_VOICE_CALL;
        } else {
            streamType = AudioManager.STREAM_MUSIC;
        }
        messageAdapter.setVolumeControl(streamType);
        double position = AudioPlayer.player.getCurrentPosition();
        double duration = AudioPlayer.player.getDuration();
        double progress = position / duration;
        if (AudioPlayer.player.getAudioStreamType() != streamType) {
            synchronized (AudioPlayer.LOCK) {
                AudioPlayer.player.stop();
                AudioPlayer.player.release();
                AudioPlayer.player = null;
                try {
                    ViewHolder currentViewHolder = getCurrentViewHolder();
                    if (currentViewHolder != null) {
                        play(
                                currentViewHolder,
                                currentlyPlayingMessage,
                                streamType == AudioManager.STREAM_VOICE_CALL,
                                progress);
                    }
                } catch (Exception e) {
                    Log.w(Config.LOGTAG, "onSensorChanged exception", e);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}

    private void acquireProximityWakeLock() {
        synchronized (AudioPlayer.LOCK) {
            if (wakeLock != null) {
                wakeLock.acquire();
            }
        }
    }

    private void releaseProximityWakeLock() {
        synchronized (AudioPlayer.LOCK) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
        messageAdapter.setVolumeControl(AudioManager.STREAM_MUSIC);
    }

    private ViewHolder getCurrentViewHolder() {
        for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
            final Message message = (Message) audioPlayer.get().getTag();
            if (message == currentlyPlayingMessage) {
                return ViewHolder.get(audioPlayer.get());
            }
        }
        return null;
    }

    private static float getCurrentPlaybackSpeed() {
        return PlaybackSpeedOptions.speedForIndex(playbackSpeedIndex);
    }

    private void cyclePlaybackSpeed(ViewHolder sourceViewHolder) {
        playbackSpeedIndex = PlaybackSpeedOptions.cycleIndex(playbackSpeedIndex);
        updatePlaybackSpeedButton(sourceViewHolder);
        updatePlaybackSpeedButtons();
        applyPlaybackSpeed();
    }

    private void updatePlaybackSpeedButtons() {
        for (WeakReference<RelativeLayout> audioPlayer : audioPlayerLayouts) {
            final RelativeLayout layout = audioPlayer.get();
            if (layout != null) {
                updatePlaybackSpeedButton(ViewHolder.get(layout));
            }
        }
    }

    private void updatePlaybackSpeedButton(ViewHolder viewHolder) {
        if (viewHolder == null || viewHolder.playbackSpeed == null) {
            return;
        }
        final Context context = viewHolder.playbackSpeed.getContext();
        viewHolder.playbackSpeed.setText(
                PlaybackSpeedOptions.labelForIndex(context, playbackSpeedIndex));
        viewHolder.playbackSpeed.setContentDescription(
                PlaybackSpeedOptions.contentDescriptionForIndex(context, playbackSpeedIndex));
    }

    private void applyPlaybackSpeed() {
        synchronized (AudioPlayer.LOCK) {
            if (AudioPlayer.player == null) {
                return;
            }
            try {
                PlaybackParams params = new PlaybackParams();
                params.setSpeed(getCurrentPlaybackSpeed());
                params.setPitch(1.0f);
                AudioPlayer.player.setPlaybackParams(params);
            } catch (Exception e) {
                Log.w(Config.LOGTAG, "Unable to set audio message playback speed", e);
            }
        }
    }

    public static class ViewHolder {
        private TextView runtime;
        private SeekBar progress;
        private ImageButton playPause;
        private TextView playbackSpeed;
        private WaveformProgressDrawable waveformDrawable;
        private MessageAdapter.BubbleColor bubbleColor = MessageAdapter.BubbleColor.SURFACE;

        public static ViewHolder get(final RelativeLayout audioPlayer) {
            final var existingViewHolder =
                    (ViewHolder) audioPlayer.getTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER);
            if (existingViewHolder != null) {
                return existingViewHolder;
            }
            final ViewHolder viewHolder = new ViewHolder();
            viewHolder.runtime = audioPlayer.findViewById(R.id.runtime);
            viewHolder.progress = audioPlayer.findViewById(R.id.progress);
            viewHolder.playPause = audioPlayer.findViewById(R.id.play_pause);
            viewHolder.playbackSpeed = audioPlayer.findViewById(R.id.playback_speed);
            viewHolder.waveformDrawable =
                    new WaveformProgressDrawable(audioPlayer.getResources().getDisplayMetrics().density);
            viewHolder.progress.setProgressDrawable(viewHolder.waveformDrawable);
            audioPlayer.setTag(R.id.TAG_AUDIO_PLAYER_VIEW_HOLDER, viewHolder);
            return viewHolder;
        }

        public void setBubbleColor(final MessageAdapter.BubbleColor bubbleColor) {
            this.bubbleColor = bubbleColor;
        }
    }
}
