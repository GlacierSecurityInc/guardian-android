package com.glaciersecurity.glaciermessenger.testutil;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Measure, lay out and draw a view without an Activity.
 *
 * <p>Custom views are the part of {@code ui/} that a test can reach honestly without signing in:
 * they take their input through setters and produce pixels, and nothing about that needs a screen
 * or a bound service. What it does need is a real device -- {@code Canvas}, {@code Paint},
 * {@code LinearGradient} and font metrics are all native, and against the mockable android.jar
 * every one of them is a no-op, so a JVM version of these tests would draw nothing and assert
 * that it looked right.
 *
 * <p>Everything runs on the main thread. A detached view can be measured off it, but
 * {@code invalidate()} and {@code ValueAnimator} cannot, and those are exactly what the state
 * setters on these views do.
 */
public final class ViewHarness {

    private ViewHarness() {}

    /** The application context wrapped in the app's own theme, so {@code ?attr} lookups resolve. */
    public static Context themedContext() {
        return new android.view.ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.AppTheme);
    }

    /**
     * Run {@code work} on the main thread and surface anything it threw on the calling thread, so
     * a failed assertion inside is reported as a test failure rather than crashing the process.
     */
    public static void onMain(final Runnable work) {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                        () -> {
                            try {
                                work.run();
                            } catch (final Throwable t) {
                                thrown.set(t);
                            }
                        });
        final Throwable t = thrown.get();
        if (t instanceof Error) {
            throw (Error) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t != null) {
            throw new AssertionError(t);
        }
    }

    /** Measure and lay out {@code view} at an exact size, as a parent with fixed bounds would. */
    public static void layoutAt(final View view, final int widthPx, final int heightPx) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    /** Lay {@code view} out at the given size and draw it into a bitmap of that size. */
    public static Bitmap render(final View view, final int widthPx, final int heightPx) {
        layoutAt(view, widthPx, heightPx);
        final Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** True when every pixel is fully transparent -- i.e. the view drew nothing at all. */
    public static boolean isBlank(final Bitmap bitmap) {
        final int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(
                pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (final int pixel : pixels) {
            if (pixel != 0) {
                return false;
            }
        }
        return true;
    }

    /** A parent to inflate into, so a {@code <merge>} root has somewhere to go. */
    public static ViewGroup newParent(final Context context) {
        return new android.widget.FrameLayout(context);
    }
}
