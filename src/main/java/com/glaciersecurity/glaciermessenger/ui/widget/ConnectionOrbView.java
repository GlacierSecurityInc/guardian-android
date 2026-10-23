package com.glaciersecurity.glaciermessenger.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.R;

/**
 * Core Connection — modernized. The centerpiece power orb.
 *
 * Draws a gradient disc wrapped by a track + progress ring. State drives the
 * treatment:
 *   • {@link #STATE_OFF}        — dim disc, neutral track, no motion.
 *   • {@link #STATE_CONNECTING} — accent sweep arc spinning around the track.
 *   • {@link #STATE_CONNECTED}  — green disc, full ring, a soft breathing
 *     scale, a glowing radial halo and staggered pulse rings radiating out.
 *
 * The centered power glyph is supplied separately (an ImageView overlaid in the
 * layout) so it can be tinted independently. All colours come from the cc_*
 * tokens, so the orb switches cleanly between light and dark.
 */
public class ConnectionOrbView extends View {

    public static final int STATE_OFF = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    // Pulse cycle slowed 25% from the original 3000ms so the connected state
    // reads as calm/steady rather than urgent.
    private static final long PULSE_PERIOD_MS = 3750L;
    // Stagger for the three pulse rings — one third of the cycle apart so they
    // stay evenly spaced as the period changes.
    private static final long PULSE_STAGGER_MS = PULSE_PERIOD_MS / 3L;
    private static final long SPIN_PERIOD_MS = 1100L;
    private static final long BREATHE_PERIOD_MS = 3600L;
    private static final long GLOW_PERIOD_MS = 3400L;

    private int state = STATE_OFF;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF ringRect = new RectF();

    private ValueAnimator animator;
    private long startTime;

    public ConnectionOrbView(Context context) {
        super(context);
        init();
    }

    public ConnectionOrbView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConnectionOrbView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeCap(Paint.Cap.ROUND);
        pulsePaint.setStyle(Paint.Style.STROKE);
        discBorderPaint.setStyle(Paint.Style.STROKE);
        startTime = SystemClock.elapsedRealtime();
    }

    /** Set the orb state. Triggers/stops the looping animation as needed. */
    public void setState(int state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        startTime = SystemClock.elapsedRealtime();
        updateAnimation();
        invalidate();
    }

    public int getState() {
        return state;
    }

    private int color(int res) {
        return ContextCompat.getColor(getContext(), res);
    }

    private void updateAnimation() {
        boolean shouldAnimate = state == STATE_CONNECTED || state == STATE_CONNECTING;
        if (shouldAnimate) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(PULSE_PERIOD_MS);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(a -> invalidate());
            }
            if (!animator.isStarted()) {
                animator.start();
            }
        } else if (animator != null && animator.isStarted()) {
            animator.cancel();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        // Nothing to draw, and the radial halo below builds a RadialGradient of radius size/2 --
        // which throws outright at zero. Same guard ThroughputSparklineView already carries.
        if (w <= 0 || h <= 0) {
            return;
        }
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float size = Math.min(w, h);

        final float strokeW = size * 0.032f;          // ~6dp on a 188px orb
        final float ringRadius = (size - strokeW * 3.6f) / 2f;
        final float discRadius = size * 0.351f;        // 132/188 of the orb
        final long elapsed = SystemClock.elapsedRealtime() - startTime;

        final boolean connected = state == STATE_CONNECTED;
        final boolean connecting = state == STATE_CONNECTING;

        final int main = connected ? color(R.color.cc_green)
                : connecting ? color(R.color.cc_accent) : color(R.color.cc_muted);

        // Glowing halo — connected only, gently pulsing opacity.
        if (connected) {
            float glowPhase = (elapsed % GLOW_PERIOD_MS) / (float) GLOW_PERIOD_MS;
            float glowAlpha = 0.5f + 0.45f * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * glowPhase));
            int glow = color(R.color.cc_green_glow);
            glowPaint.setShader(new RadialGradient(cx, cy, size / 2f,
                    glow, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            glowPaint.setAlpha((int) (glowAlpha * 255));
            canvas.drawCircle(cx, cy, size / 2f, glowPaint);
        }

        // Pulse rings — three staggered expanding circles.
        if (connected) {
            for (int i = 0; i < 3; i++) {
                long delay = i * PULSE_STAGGER_MS;
                float phase = ((elapsed - delay) % PULSE_PERIOD_MS) / (float) PULSE_PERIOD_MS;
                if (phase < 0) {
                    continue;
                }
                float scale = 0.62f + (1.85f - 0.62f) * phase;
                float alpha = phase < 0.16f ? (phase / 0.16f) * 0.5f : 0.5f * (1f - (phase - 0.16f) / 0.84f);
                pulsePaint.setColor(main);
                pulsePaint.setAlpha((int) (Math.max(0f, alpha) * 255));
                pulsePaint.setStrokeWidth(strokeW * 0.25f);
                canvas.drawCircle(cx, cy, ringRadius * scale, pulsePaint);
            }
        }

        // Base track ring.
        ringRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        trackPaint.setStrokeWidth(strokeW);
        trackPaint.setColor(connected ? color(R.color.cc_green_soft) : color(R.color.cc_border_strong));
        canvas.drawCircle(cx, cy, ringRadius, trackPaint);

        // Progress ring (full when connected) / spinning sweep (connecting).
        if (connected) {
            ringPaint.setStrokeWidth(strokeW);
            ringPaint.setColor(main);
            canvas.drawArc(ringRect, -90f, 360f, false, ringPaint);
        } else if (connecting) {
            float spin = (elapsed % SPIN_PERIOD_MS) / (float) SPIN_PERIOD_MS;
            sweepPaint.setStrokeWidth(strokeW);
            sweepPaint.setColor(main);
            canvas.drawArc(ringRect, spin * 360f - 90f, 100f, false, sweepPaint);
        }

        // Breathing scale on the disc when connected.
        float breathe = 1f;
        if (connected) {
            float bp = (elapsed % BREATHE_PERIOD_MS) / (float) BREATHE_PERIOD_MS;
            breathe = 1f + 0.035f * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * bp));
        }
        float r = discRadius * breathe;

        // Gradient disc.
        int top = connected ? color(R.color.cc_disc_on_top) : color(R.color.cc_disc_off_top);
        int bottom = connected ? color(R.color.cc_disc_on_bottom) : color(R.color.cc_disc_off_bottom);
        discPaint.setShader(new LinearGradient(cx, cy - r, cx, cy + r, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, discPaint);

        discBorderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
        discBorderPaint.setColor(connected ? main : color(R.color.cc_border_strong));
        canvas.drawCircle(cx, cy, r, discBorderPaint);
    }
}
