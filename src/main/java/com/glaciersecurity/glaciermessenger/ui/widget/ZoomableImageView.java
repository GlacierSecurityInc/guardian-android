package com.glaciersecurity.glaciermessenger.ui.widget;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 4.0f;

    private final Matrix baseMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    private float currentScale = MIN_SCALE;
    private float lastX = 0f;
    private float lastY = 0f;
    private boolean isDragging = false;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return performClick();
            }
        });
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            resetMatrix();
        }
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        resetMatrix();
    }

    @Override
    public void setImageURI(@Nullable android.net.Uri uri) {
        super.setImageURI(uri);
        resetMatrix();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }

        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                isDragging = true;
                // When zoomed in, keep gestures for ourselves so the surrounding pager
                // does not swipe to the next item while the user is panning the image.
                if (currentScale > MIN_SCALE && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && isDragging && currentScale > MIN_SCALE) {
                    final float dx = event.getX() - lastX;
                    final float dy = event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    drawMatrix.postTranslate(dx, dy);
                    fixTranslation();
                    setImageMatrix(drawMatrix);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                isDragging = false;
                // At normal zoom, let the pager intercept horizontal swipes again.
                if (currentScale <= MIN_SCALE && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
            default:
                break;
        }

        return true;
    }

    private void resetMatrix() {
        final Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }

        final int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        final int viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }

        final float scale = Math.min((float) viewWidth / drawableWidth, (float) viewHeight / drawableHeight);
        baseMatrix.reset();
        baseMatrix.setScale(scale, scale);
        final float translateX = (viewWidth - drawableWidth * scale) / 2f;
        final float translateY = (viewHeight - drawableHeight * scale) / 2f;
        baseMatrix.postTranslate(translateX, translateY);

        drawMatrix.set(baseMatrix);
        currentScale = MIN_SCALE;
        setImageMatrix(drawMatrix);
    }

    private void applyScale(float scaleFactor, float focusX, float focusY) {
        final float newScale = clamp(currentScale * scaleFactor, MIN_SCALE, MAX_SCALE);
        final float deltaScale = newScale / currentScale;
        currentScale = newScale;
        drawMatrix.postScale(deltaScale, deltaScale, focusX, focusY);
        fixTranslation();
        setImageMatrix(drawMatrix);
    }

    private void fixTranslation() {
        final RectF rect = getDisplayRect(drawMatrix);
        if (rect == null) {
            return;
        }

        float deltaX = 0f;
        float deltaY = 0f;
        final float viewWidth = getWidth();
        final float viewHeight = getHeight();

        if (rect.width() <= viewWidth) {
            deltaX = (viewWidth - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        }

        if (rect.height() <= viewHeight) {
            deltaY = (viewHeight - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        drawMatrix.postTranslate(deltaX, deltaY);
    }

    @Nullable
    private RectF getDisplayRect(Matrix matrix) {
        final Drawable drawable = getDrawable();
        if (drawable == null) {
            return null;
        }

        final RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(rect);
        return rect;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            applyScale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            return true;
        }
    }
}
