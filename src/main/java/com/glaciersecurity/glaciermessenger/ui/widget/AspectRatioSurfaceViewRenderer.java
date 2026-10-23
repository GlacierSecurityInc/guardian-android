package com.glaciersecurity.glaciermessenger.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Rational;

import com.glaciersecurity.glaciermessenger.Config;

public class AspectRatioSurfaceViewRenderer extends org.webrtc.SurfaceViewRenderer {

    private Rational aspectRatio = new Rational(1,1);

    private OnAspectRatioChanged onAspectRatioChanged;

    public AspectRatioSurfaceViewRenderer(Context context) {
        super(context);
    }

    public AspectRatioSurfaceViewRenderer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
        super.onFrameResolutionChanged(videoWidth, videoHeight, rotation);
        final int rotatedWidth = rotation != 0 && rotation != 180 ? videoHeight : videoWidth;
        final int rotatedHeight = rotation != 0 && rotation != 180 ? videoWidth : videoHeight;
        final Rational currentRational = this.aspectRatio;
        this.aspectRatio = new Rational(rotatedWidth, rotatedHeight);
        if (currentRational.equals(this.aspectRatio) || onAspectRatioChanged == null) {
            return;
        }
        onAspectRatioChanged.onAspectRatioChanged(this.aspectRatio);
    }

    public void setOnAspectRatioChanged(final OnAspectRatioChanged onAspectRatioChanged) {
        this.onAspectRatioChanged = onAspectRatioChanged;
    }

    public Rational getAspectRatio() {
        return this.aspectRatio;
    }

    public interface OnAspectRatioChanged {
        void onAspectRatioChanged(final Rational rational);
    }
}
