package com.glaciersecurity.glaciermessenger.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.R;

/**
 * Core Connection — modernized. Live throughput sparkline for the session card.
 *
 * Plots a rolling window of recent download-rate samples as a smooth line with
 * a soft gradient fill underneath, matching the design's session card.
 */
public class ThroughputSparklineView extends View {

    private static final int WINDOW = 36;

    private final float[] values = new float[WINDOW];
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public ThroughputSparklineView(Context context) {
        super(context);
        init();
    }

    public ThroughputSparklineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ThroughputSparklineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        linePaint.setColor(ContextCompat.getColor(getContext(), R.color.cc_green));
    }

    /** Push a new sample onto the rolling window. */
    public void push(float value) {
        System.arraycopy(values, 1, values, 0, WINDOW - 1);
        values[WINDOW - 1] = Math.max(0f, value);
        invalidate();
    }

    /** Reset the window to a calm baseline. */
    public void reset() {
        for (int i = 0; i < WINDOW; i++) {
            values[i] = 0f;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int green = ContextCompat.getColor(getContext(), R.color.cc_green);
        int top = (green & 0x00FFFFFF) | 0x59000000;   // ~0.35 alpha
        fillPaint.setShader(new LinearGradient(0, 0, 0, h, top, Color.TRANSPARENT, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float max = 1f;
        for (float v : values) {
            if (v > max) {
                max = v;
            }
        }
        final float step = w / (WINDOW - 1);
        final float pad = getResources().getDisplayMetrics().density * 2f;

        linePath.reset();
        fillPath.reset();
        for (int i = 0; i < WINDOW; i++) {
            float x = i * step;
            float y = h - (values[i] / max) * (h - pad * 2) - pad;
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(w, h);
        fillPath.lineTo(0, h);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
