package com.glaciersecurity.glaciermessenger.ui.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.R;

public final class PlaybackSpeedOptions {
    private static final float[] SPEEDS = {1.0f, 1.5f, 2.0f};
    private static final int[] LABEL_RES = {
            R.string.audio_speed_1x,
            R.string.audio_speed_1_5x,
            R.string.audio_speed_2x
    };

    private PlaybackSpeedOptions() {}

    public static int defaultIndex() {
        return 0;
    }

    public static float defaultSpeed() {
        return SPEEDS[0];
    }

    public static int cycleIndex(int currentIndex) {
        return normalizeIndex(currentIndex + 1);
    }

    public static int normalizeIndex(int index) {
        int normalized = index % SPEEDS.length;
        if (normalized < 0) {
            normalized += SPEEDS.length;
        }
        return normalized;
    }

    public static float speedForIndex(int index) {
        return SPEEDS[normalizeIndex(index)];
    }

    public static int labelResIdForIndex(int index) {
        return LABEL_RES[normalizeIndex(index)];
    }

    @NonNull
    public static String labelForIndex(@NonNull Context context, int index) {
        return context.getString(labelResIdForIndex(index));
    }

    @NonNull
    public static String contentDescriptionForIndex(@NonNull Context context, int index) {
        return context.getString(R.string.audio_speed_content_description, labelForIndex(context, index));
    }
}
