package com.glaciersecurity.glaciermessenger.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class GifMovieView extends View {

    private static final int DEFAULT_DURATION_MS = 1000;

    private Movie movie;
    private long movieStart;
    private float scale = 1f;

    public GifMovieView(Context context) {
        super(context);
    }

    public GifMovieView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public GifMovieView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMovie(@Nullable Movie movie) {
        this.movie = movie;
        movieStart = 0L;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (movie != null && movie.width() > 0 && movie.height() > 0) {
            float scaleW = (float) width / movie.width();
            float scaleH = (float) height / movie.height();
            scale = Math.min(scaleW, scaleH);
        } else {
            scale = 1f;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (movie == null) {
            return;
        }

        final long now = SystemClock.uptimeMillis();
        if (movieStart == 0L) {
            movieStart = now;
        }

        int duration = movie.duration();
        if (duration <= 0) {
            duration = DEFAULT_DURATION_MS;
        }
        int relTime = (int) ((now - movieStart) % duration);
        movie.setTime(relTime);

        float movieWidth = movie.width() * scale;
        float movieHeight = movie.height() * scale;
        float dx = (getWidth() - movieWidth) / 2f;
        float dy = (getHeight() - movieHeight) / 2f;

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        movie.draw(canvas, 0f, 0f);
        canvas.restore();

        invalidate();
    }
}
