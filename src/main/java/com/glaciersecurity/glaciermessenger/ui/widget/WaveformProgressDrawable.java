package com.glaciersecurity.glaciermessenger.ui.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Progress drawable for a voice-message {@link android.widget.SeekBar} that renders a fixed
 * amplitude waveform (played bars vs. unplayed bars) instead of a plain line, driven by the
 * host view's level (0..10000, set automatically from progress/max).
 */
public class WaveformProgressDrawable extends Drawable {

    private static final int MAX_BARS = 46;
    private static final float MIN_BAR_HEIGHT_FRACTION = 0.24f;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final float barWidthPx;
    private final float barGapPx;

    private int playedColor = Color.WHITE;
    private int unplayedColor = Color.GRAY;
    private float playedFraction = 0f;
    private int barCount = 0;
    private int alpha = 255;

    public WaveformProgressDrawable(final float density) {
        this.barWidthPx = 3f * density;
        this.barGapPx = 3f * density;
        barPaint.setStyle(Paint.Style.FILL);
    }

    public void setColors(final int playedColor, final int unplayedColor) {
        this.playedColor = playedColor;
        this.unplayedColor = unplayedColor;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(@NonNull final Rect bounds) {
        super.onBoundsChange(bounds);
        final float step = barWidthPx + barGapPx;
        final int count = step > 0 ? (int) (bounds.width() / step) : 0;
        this.barCount = Math.max(1, Math.min(MAX_BARS, count));
    }

    @Override
    protected boolean onLevelChange(final int level) {
        this.playedFraction = Math.max(0f, Math.min(1f, level / 10000f));
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(@NonNull final Canvas canvas) {
        final Rect bounds = getBounds();
        if (barCount <= 0 || bounds.isEmpty()) {
            return;
        }
        final float centerY = bounds.exactCenterY();
        final float contentHeight = bounds.height();
        final float minHeight = contentHeight * MIN_BAR_HEIGHT_FRACTION;
        final float maxHeight = contentHeight;
        final float totalSpan = barCount * barWidthPx + Math.max(0, barCount - 1) * barGapPx;
        float x = bounds.left + Math.max(0f, (bounds.width() - totalSpan) / 2f);
        final int cut = Math.round(barCount * playedFraction);
        for (int i = 0; i < barCount; i++) {
            final float amplitude = amplitudeFor(i);
            final float barHeight = Math.max(2f, minHeight + amplitude * (maxHeight - minHeight));
            barRect.set(x, centerY - barHeight / 2f, x + barWidthPx, centerY + barHeight / 2f);
            setPaintColor(i < cut ? playedColor : unplayedColor);
            canvas.drawRoundRect(barRect, barWidthPx / 2f, barWidthPx / 2f, barPaint);
            x += barWidthPx + barGapPx;
        }
    }

    private void setPaintColor(final int color) {
        barPaint.setColor(color);
        barPaint.setAlpha(Color.alpha(color) * alpha / 255);
    }

    // Deterministic pseudo-amplitude trace (no real sample data is stored per-message); this
    // mirrors the design mock's waveform so playback bars read as a real trace rather than a
    // flat line.
    private static float amplitudeFor(final int index) {
        final double v =
                Math.abs(
                        Math.sin(index * 1.3) * 0.55
                                + Math.sin(index * 0.5 + 1) * 0.4
                                + Math.sin(index * 2.7) * 0.18);
        return (float) Math.min(1.0, v);
    }

    @Override
    public void setAlpha(final int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable final ColorFilter colorFilter) {
        barPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
