package com.glaciersecurity.glaciermessenger.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.SystemClock;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.color.MaterialColors;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityRecordingBinding;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;

import com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils;

public class RecordingActivity extends BaseActivity implements View.OnClickListener {

    private ActivityRecordingBinding binding;

    private int sheetBasePaddingBottom;

    private MediaRecorder mRecorder;
    private long mStartTime = 0;

    private final CountDownLatch outputFileWrittenLatch = new CountDownLatch(1);

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    tick();
                    mHandler.postDelayed(mTickExecutor, 100);
                }
            };

    private File mOutputFile;

    private FileObserver mFileObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_recording);
        this.binding.cancelButton.setOnClickListener(this);
        this.binding.deleteButton.setOnClickListener(this);
        this.binding.shareButton.setOnClickListener(this);
        this.binding.waveform.setColors(
                MaterialColors.getColor(this.binding.waveform, com.google.android.material.R.attr.colorOutlineVariant),
                MaterialColors.getColor(this.binding.waveform, androidx.appcompat.R.attr.colorPrimary),
                MaterialColors.getColor(this.binding.waveform, R.attr.errorColor, 0xFFE07B6E));
        this.setFinishOnTouchOutside(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        // Extend the window behind the navigation bar so the sheet's background can reach
        // the true bottom of the screen, the same as Material's BottomSheetDialog. A
        // floating/dialog window otherwise avoids the nav bar entirely regardless of
        // setDecorFitsSystemWindows, so it must also opt out via setFitInsetsTypes.
        getWindow().setDecorFitsSystemWindows(false);
        final WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.setFitInsetsTypes(WindowInsets.Type.statusBars());
        getWindow().setAttributes(attributes);
        this.sheetBasePaddingBottom = this.binding.getRoot().getPaddingBottom();
        applySheetInsets();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            getWindow().setBackgroundBlurRadius(
                    (int) (24 * getResources().getDisplayMetrics().density));
        }
    }

    /**
     * The sheet's window deliberately extends behind the navigation bar, so nothing else pads
     * the content away from it. A hardcoded bottom padding is wrong in gesture navigation (too
     * much) and wrong in landscape, where a three-button bar sits on the side and used to cover
     * the "attach" button, so pad by the real navigation-bar / cutout insets on top of the
     * layout's own bottom padding. The insets have to come from the window metrics: this is a
     * floating window, and those are never dispatched WindowInsets of their own.
     */
    private void applySheetInsets() {
        final Insets bars =
                getWindowManager()
                        .getCurrentWindowMetrics()
                        .getWindowInsets()
                        .getInsets(
                                WindowInsets.Type.navigationBars()
                                        | WindowInsets.Type.displayCutout());
        final View root = this.binding.getRoot();
        root.setPadding(
                bars.left,
                root.getPaddingTop(),
                bars.right,
                this.sheetBasePaddingBottom + bars.bottom);
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // the activity handles orientation changes itself, so re-read the bars: in landscape a
        // three-button bar moves to the side and the bottom inset disappears
        this.binding.getRoot().post(this::applySheetInsets);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (startRecording()) {
            this.binding.waveform.setRecording(true);
        } else {
            this.binding.shareButton.setEnabled(false);
            this.binding.timer.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            this.binding.timer.setText(R.string.unable_to_start_recording);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        this.binding.waveform.setRecording(false);
        if (mRecorder != null) {
            mHandler.removeCallbacks(mTickExecutor);
            stopRecording(false);
        }
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
        }
    }

    private static final Set<String> AAC_SENSITIVE_DEVICES =
            new ImmutableSet.Builder<String>()
                    .add("FP4")             // Fairphone 4 https://codeberg.org/monocles/monocles_chat/issues/133
                    .add("ONEPLUS A6000")   // OnePlus 6 https://github.com/iNPUTmice/Conversations/issues/4329
                    .add("ONEPLUS A6003")   // OnePlus 6 https://github.com/iNPUTmice/Conversations/issues/4329
                    .add("ONEPLUS A6010")   // OnePlus 6T https://codeberg.org/monocles/monocles_chat/issues/133
                    .add("ONEPLUS A6013")   // OnePlus 6T https://codeberg.org/monocles/monocles_chat/issues/133
                    .add("Pixel 4a")        // Pixel 4a https://github.com/iNPUTmice/Conversations/issues/4223
                    .add("WP12 Pro")        // Oukitel WP 12 Pro https://github.com/iNPUTmice/Conversations/issues/4223
                    .add("Volla Phone X")   // Volla Phone X https://github.com/iNPUTmice/Conversations/issues/4223
                    .build();

    private boolean startRecording() {
        mRecorder = new MediaRecorder();
        try {
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG,"could not set audio source", e);
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mRecorder.setPrivacySensitive(true);
        }
        final int outputFormat;
        if (Config.USE_OPUS_VOICE_MESSAGES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputFormat = MediaRecorder.OutputFormat.OGG;
            mRecorder.setOutputFormat(outputFormat);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            mRecorder.setAudioEncodingBitRate(32_000);
        } else {
            outputFormat = MediaRecorder.OutputFormat.MPEG_4;
            mRecorder.setOutputFormat(outputFormat);
            if (AAC_SENSITIVE_DEVICES.contains(Build.MODEL) && Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
                // Changing these three settings for AAC sensitive devices for Android<=13 might lead to sporadically truncated (cut-off) voice messages.
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
                mRecorder.setAudioSamplingRate(24_000);
                mRecorder.setAudioEncodingBitRate(28_000);
            } else {
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mRecorder.setAudioSamplingRate(44_100);
                mRecorder.setAudioEncodingBitRate(64_000);
            }
        }
        setupOutputFile(outputFormat);
        mRecorder.setOutputFile(mOutputFile.getAbsolutePath());

        try {
            mRecorder.prepare();
            mRecorder.start();
            mStartTime = SystemClock.elapsedRealtime();
            mHandler.postDelayed(mTickExecutor, 100);
            return true;
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "prepare() failed ", e);
            return false;
        }
    }

    protected void stopRecording(final boolean saveFile) {
        try {
            mRecorder.stop();
            mRecorder.release();
            // Ensure the finisher does not wait longer than necessary
            outputFileWrittenLatch.countDown();
        } catch (Exception e) {
            if (saveFile) {
                Toast.makeText(this, R.string.unable_to_save_recording, Toast.LENGTH_SHORT).show();
                return;
            }
        } finally {
            mRecorder = null;
            mStartTime = 0;
        }
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
            mFileObserver = null;
        }
        if (!saveFile && mOutputFile != null) {
            if (mOutputFile.delete()) {
            }
        }
        if (saveFile) {
            new Thread(new Finisher(outputFileWrittenLatch, mOutputFile, this)).start();
        }
    }

    private static class Finisher implements Runnable {

        private final CountDownLatch latch;
        private final File outputFile;
        private final WeakReference<Activity> activityReference;

        private Finisher(CountDownLatch latch, File outputFile, Activity activity) {
            this.latch = latch;
            this.outputFile = outputFile;
            this.activityReference = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) {
                }
            } catch (final InterruptedException e) {
            }
            final Activity activity = activityReference.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(
                    () -> {
                        activity.setResult(
                                Activity.RESULT_OK, new Intent().setData(Uri.fromFile(outputFile)));
                        activity.finish();
                    });
        }
    }

    private File generateOutputFilename(final int outputFormat) {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US);
        final String extension;
        if (outputFormat == MediaRecorder.OutputFormat.MPEG_4) {
            extension = "m4a";
        } else if (outputFormat == MediaRecorder.OutputFormat.OGG) {
            extension = "oga";
        } else {
            throw new IllegalStateException("Unrecognized output format");
        }
        final String filename =
                String.format("RECORDING_%s.%s", dateFormat.format(new Date()), extension);
        final String type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            type = Environment.DIRECTORY_RECORDINGS;
        } else {
            type = Environment.DIRECTORY_DOWNLOADS;
        }
        final File conversationsDirectory =
                FileBackend.getConversationsMediaDirectory(this, type);
        return new File(conversationsDirectory, filename);
    }

    private void setupOutputFile(final int outputFormat) {
        mOutputFile = generateOutputFilename(outputFormat);
        final File parentDirectory = mOutputFile.getParentFile();
        if (Objects.requireNonNull(parentDirectory).mkdirs()) {
        }
        setupFileObserver(parentDirectory);
    }

    private void setupFileObserver(final File directory) {
        mFileObserver =
                new FileObserver(directory.getAbsolutePath()) {
                    @Override
                    public void onEvent(int event, String s) {
                        if (s != null
                                && s.equals(mOutputFile.getName())
                                && event == FileObserver.CLOSE_WRITE) {
                            outputFileWrittenLatch.countDown();
                        }
                    }
                };
        mFileObserver.startWatching();
    }

    private void tick() {
        this.binding.timer.setText(TimeFrameUtils.formatTimePassed(mStartTime, true));
        this.binding.waveform.pushAmplitude(currentAmplitude());
    }

    private float currentAmplitude() {
        if (mRecorder == null) {
            return 0f;
        }
        try {
            return (float) Math.sqrt(mRecorder.getMaxAmplitude() / 32767f);
        } catch (final IllegalStateException e) {
            return 0f;
        }
    }

    @Override
    public void onClick(final View view) {
        final int id = view.getId();
        if (id == R.id.cancel_button || id == R.id.delete_button) {
            mHandler.removeCallbacks(mTickExecutor);
            stopRecording(false);
            setResult(RESULT_CANCELED);
            finish();
        } else if (id == R.id.share_button) {
            this.binding.shareButton.setEnabled(false);
            this.binding.shareButton.setText(R.string.please_wait);
            this.binding.cancelButton.setEnabled(false);
            this.binding.deleteButton.setEnabled(false);
            this.binding.waveform.setRecording(false);
            mHandler.removeCallbacks(mTickExecutor);
            mHandler.postDelayed(() -> stopRecording(true), 500);
        }
    }
}
