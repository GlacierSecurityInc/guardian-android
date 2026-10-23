package com.glaciersecurity.glaciermessenger.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Live scrolling amplitude bar-graph for an in-progress voice message recording, with a
 * blinking "recording" dot. New samples enter on the right and age towards the left; the
 * most recent bars are tinted with {@link #setAccentColor(int)}, older ones fade to
 * {@link #setBaseColor(int)}.
 */
public class VoiceWaveformView extends View {

    private static final float BAR_WIDTH_DP = 3f;
    private static final float BAR_GAP_DP = 3f;
    private static final float MIN_BAR_HEIGHT_FRACTION = 0.12f;
    private static final int RECENT_BAR_HIGHLIGHT_COUNT = 10;

    private final Deque<Float> amplitudes = new ArrayDeque<>();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private int baseColor = Color.GRAY;
    private int accentColor = Color.BLUE;
    private int dotColor = Color.RED;

    private float barWidthPx;
    private float barGapPx;
    private float dotRadiusPx;
    private float dotAreaWidthPx;

    private boolean recording = false;
    private ValueAnimator dotAnimator;
    private float dotAlpha = 1f;

    public VoiceWaveformView(Context context) {
        super(context);
        init();
    }

    public VoiceWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VoiceWaveformView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        final float density = getResources().getDisplayMetrics().density;
        barWidthPx = BAR_WIDTH_DP * density;
        barGapPx = BAR_GAP_DP * density;
        dotRadiusPx = 4f * density;
        dotAreaWidthPx = 20f * density;
        barPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setColors(int baseColor, int accentColor, int dotColor) {
        this.baseColor = baseColor;
        this.accentColor = accentColor;
        this.dotColor = dotColor;
        invalidate();
    }

    public void setRecording(boolean recording) {
        if (this.recording == recording) {
            return;
        }
        this.recording = recording;
        if (recording) {
            startDotBlink();
        } else {
            stopDotBlink();
        }
    }

    private void startDotBlink() {
        stopDotBlink();
        dotAnimator = ValueAnimator.ofFloat(1f, 0.25f, 1f);
        dotAnimator.setDuration(1000);
        dotAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotAnimator.setInterpolator(new LinearInterpolator());
        dotAnimator.addUpdateListener(animation -> {
            dotAlpha = (float) animation.getAnimatedValue();
            invalidate();
        });
        dotAnimator.start();
    }

    private void stopDotBlink() {
        if (dotAnimator != null) {
            dotAnimator.cancel();
            dotAnimator = null;
        }
        dotAlpha = 1f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopDotBlink();
        super.onDetachedFromWindow();
    }

    /** Adds one new amplitude sample, normalized to [0, 1]. */
    public void pushAmplitude(float normalizedAmplitude) {
        final float clamped = Math.max(0f, Math.min(1f, normalizedAmplitude));
        amplitudes.addLast(clamped);
        final int maxBars = maxVisibleBars();
        while (amplitudes.size() > maxBars) {
            amplitudes.removeFirst();
        }
        invalidate();
    }

    public void reset() {
        amplitudes.clear();
        invalidate();
    }

    private int maxVisibleBars() {
        final float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        final float availableWidth = contentWidth - dotAreaWidthPx;
        if (availableWidth <= 0 || barWidthPx + barGapPx <= 0) {
            return 0;
        }
        return Math.max(0, (int) (availableWidth / (barWidthPx + barGapPx)));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final float contentTop = getPaddingTop();
        final float contentHeight = getHeight() - contentTop - getPaddingBottom();
        final float centerY = contentTop + contentHeight / 2f;
        final float contentLeft = getPaddingLeft();

        if (recording) {
            dotPaint.setColor(dotColor);
            dotPaint.setAlpha(Math.round(255 * dotAlpha));
            canvas.drawCircle(contentLeft + dotAreaWidthPx / 2f, centerY, dotRadiusPx, dotPaint);
        }

        final int count = amplitudes.size();
        if (count == 0) {
            return;
        }
        final float minHeight = contentHeight * MIN_BAR_HEIGHT_FRACTION;
        final float maxHeight = contentHeight * 0.9f;

        int i = 0;
        for (final float amplitude : amplitudes) {
            final float x = contentLeft + dotAreaWidthPx + i * (barWidthPx + barGapPx);
            final float barHeight = minHeight + amplitude * (maxHeight - minHeight);
            barRect.set(x, centerY - barHeight / 2f, x + barWidthPx, centerY + barHeight / 2f);
            final int distanceFromNewest = count - 1 - i;
            barPaint.setColor(colorForBar(distanceFromNewest));
            canvas.drawRoundRect(barRect, barWidthPx / 2f, barWidthPx / 2f, barPaint);
            i++;
        }
    }

    private int colorForBar(int distanceFromNewest) {
        if (distanceFromNewest >= RECENT_BAR_HIGHLIGHT_COUNT) {
            return baseColor;
        }
        final float fraction = 1f - (distanceFromNewest / (float) RECENT_BAR_HIGHLIGHT_COUNT);
        return blendColors(baseColor, accentColor, fraction);
    }

    private static int blendColors(int from, int to, float fraction) {
        final float inverse = 1f - fraction;
        final float a = Color.alpha(from) * inverse + Color.alpha(to) * fraction;
        final float r = Color.red(from) * inverse + Color.red(to) * fraction;
        final float g = Color.green(from) * inverse + Color.green(to) * fraction;
        final float b = Color.blue(from) * inverse + Color.blue(to) * fraction;
        return Color.argb(Math.round(a), Math.round(r), Math.round(g), Math.round(b));
    }
}
