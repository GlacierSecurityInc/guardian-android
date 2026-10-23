package com.glaciersecurity.glaciermessenger.ui.widget;

import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.isBlank;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.layoutAt;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.onMain;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.render;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.themedContext;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The custom views under {@code ui/widget}, measured and drawn for real.
 *
 * <p>These are the part of the UI layer a test can reach without an account: they take their input
 * through setters and produce pixels. They are also the part where a break is hardest to notice --
 * a view that silently draws nothing looks like a view whose data has not arrived, so the Core
 * connection screen with a dead orb, or a voice message with a flat waveform, reads as "not
 * connected yet" rather than as a bug.
 *
 * <p>This tier rather than Robolectric because the whole subject is native: {@code Canvas},
 * {@code Paint}, {@code LinearGradient}, {@code RadialGradient} and text metrics. Against the
 * mockable android.jar every draw call is a no-op and every one of these assertions would pass
 * against a view that drew nothing.
 *
 * <p>The assertions are deliberately about *difference* rather than about exact pixels: that the
 * connected orb does not look like the disconnected one, that a played waveform does not look like
 * an unplayed one. A golden-image test on a device fleet with different densities and font
 * renderers would be noise.
 */
@RunWith(AndroidJUnit4.class)
public class CustomViewRenderingTest {

    private static final int W = 240;
    private static final int H = 240;

    private Context context() {
        return themedContext();
    }

    // ---- UnreadCountCustomView -------------------------------------------------------------

    @Test
    public void anUnreadBadgeDrawsSomething() {
        onMain(
                () -> {
                    final UnreadCountCustomView view = new UnreadCountCustomView(context());
                    view.setUnreadCount(3);
                    assertThat(isBlank(render(view, 64, 64))).isFalse();
                });
    }

    /**
     * Above 999 the badge switches to an infinity glyph rather than growing. The number and the
     * glyph have to actually render differently -- if the switch were dropped, a four-digit count
     * would be drawn at the same text size and overflow the circle silently.
     */
    @Test
    public void aCountOverNineHundredNinetyNineDrawsTheInfinityGlyphInstead() {
        onMain(
                () -> {
                    final UnreadCountCustomView atLimit = new UnreadCountCustomView(context());
                    atLimit.setUnreadCount(999);
                    final Bitmap limit = render(atLimit, 64, 64);

                    final UnreadCountCustomView over = new UnreadCountCustomView(context());
                    over.setUnreadCount(1000);
                    final Bitmap infinity = render(over, 64, 64);

                    assertThat(infinity.sameAs(limit)).isFalse();

                    final UnreadCountCustomView wayOver = new UnreadCountCustomView(context());
                    wayOver.setUnreadCount(123456);
                    assertThat(render(wayOver, 64, 64).sameAs(infinity)).isTrue();
                });
    }

    @Test
    public void differentCountsDrawDifferently() {
        onMain(
                () -> {
                    final UnreadCountCustomView one = new UnreadCountCustomView(context());
                    one.setUnreadCount(1);
                    final UnreadCountCustomView two = new UnreadCountCustomView(context());
                    two.setUnreadCount(2);
                    assertThat(render(one, 64, 64).sameAs(render(two, 64, 64))).isFalse();
                });
    }

    // ---- ConnectionOrbView ------------------------------------------------------------------

    @Test
    public void theOrbStartsOffAndRemembersTheStateItIsGiven() {
        onMain(
                () -> {
                    final ConnectionOrbView orb = new ConnectionOrbView(context());
                    assertThat(orb.getState()).isEqualTo(ConnectionOrbView.STATE_OFF);
                    orb.setState(ConnectionOrbView.STATE_CONNECTING);
                    assertThat(orb.getState()).isEqualTo(ConnectionOrbView.STATE_CONNECTING);
                    orb.setState(ConnectionOrbView.STATE_CONNECTED);
                    assertThat(orb.getState()).isEqualTo(ConnectionOrbView.STATE_CONNECTED);
                });
    }

    @Test
    public void everyOrbStateDrawsSomething() {
        onMain(
                () -> {
                    for (final int state :
                            new int[] {
                                ConnectionOrbView.STATE_OFF,
                                ConnectionOrbView.STATE_CONNECTING,
                                ConnectionOrbView.STATE_CONNECTED
                            }) {
                        final ConnectionOrbView orb = new ConnectionOrbView(context());
                        orb.setState(state);
                        assertThat(isBlank(render(orb, W, H))).isFalse();
                    }
                });
    }

    /** Connected and disconnected must be distinguishable at a glance; that is the whole screen. */
    @Test
    public void theConnectedOrbLooksDifferentFromTheDisconnectedOne() {
        onMain(
                () -> {
                    final ConnectionOrbView off = new ConnectionOrbView(context());
                    off.setState(ConnectionOrbView.STATE_OFF);
                    final ConnectionOrbView on = new ConnectionOrbView(context());
                    on.setState(ConnectionOrbView.STATE_CONNECTED);
                    assertThat(render(on, W, H).sameAs(render(off, W, H))).isFalse();
                });
    }

    @Test
    public void anOrbWithNoSizeDoesNotThrow() {
        onMain(
                () -> {
                    final ConnectionOrbView orb = new ConnectionOrbView(context());
                    orb.setState(ConnectionOrbView.STATE_CONNECTED);
                    layoutAt(orb, 0, 0);
                    orb.draw(new Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
                });
    }

    // ---- ThroughputSparklineView -------------------------------------------------------------

    @Test
    public void aSparklineWithSamplesLooksDifferentFromAResetOne() {
        onMain(
                () -> {
                    final ThroughputSparklineView flat = new ThroughputSparklineView(context());
                    flat.reset();
                    final Bitmap baseline = render(flat, 200, 60);

                    final ThroughputSparklineView busy = new ThroughputSparklineView(context());
                    busy.reset();
                    for (int i = 0; i < 20; i++) {
                        busy.push(i * 100f);
                    }
                    assertThat(render(busy, 200, 60).sameAs(baseline)).isFalse();
                });
    }

    /**
     * The window is fixed-size and shifts left on every push, so pushing far more samples than it
     * holds has to be ordinary behaviour rather than an index error.
     */
    @Test
    public void pushingFarMoreSamplesThanTheWindowHoldsIsFine() {
        onMain(
                () -> {
                    final ThroughputSparklineView view = new ThroughputSparklineView(context());
                    for (int i = 0; i < 500; i++) {
                        view.push(i);
                    }
                    assertThat(isBlank(render(view, 200, 60))).isFalse();
                });
    }

    @Test
    public void aNegativeSampleIsClampedRatherThanInverted() {
        onMain(
                () -> {
                    final ThroughputSparklineView negative = new ThroughputSparklineView(context());
                    negative.reset();
                    negative.push(-500f);
                    final ThroughputSparklineView zero = new ThroughputSparklineView(context());
                    zero.reset();
                    zero.push(0f);
                    assertThat(render(negative, 200, 60).sameAs(render(zero, 200, 60))).isTrue();
                });
    }

    @Test
    public void aSparklineWithNoSizeDrawsNothingRatherThanThrowing() {
        onMain(
                () -> {
                    final ThroughputSparklineView view = new ThroughputSparklineView(context());
                    view.push(10f);
                    layoutAt(view, 0, 0);
                    view.draw(new Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
                });
    }

    // ---- VoiceWaveformView --------------------------------------------------------------------

    @Test
    public void aRecordedWaveformLooksDifferentFromAnEmptyOne() {
        onMain(
                () -> {
                    final VoiceWaveformView empty = new VoiceWaveformView(context());
                    empty.reset();
                    final Bitmap baseline = render(empty, 300, 80);

                    final VoiceWaveformView recorded = new VoiceWaveformView(context());
                    // Laid out before the samples go in: see the test below -- the view drops
                    // anything pushed while it has no width.
                    layoutAt(recorded, 300, 80);
                    recorded.reset();
                    for (int i = 0; i < 40; i++) {
                        recorded.pushAmplitude((i % 10) / 10f);
                    }
                    assertThat(render(recorded, 300, 80).sameAs(baseline)).isFalse();
                });
    }

    @Test
    public void resettingAWaveformReturnsItToTheEmptyPicture() {
        onMain(
                () -> {
                    final VoiceWaveformView view = new VoiceWaveformView(context());
                    view.reset();
                    final Bitmap empty = render(view, 300, 80);
                    for (int i = 0; i < 30; i++) {
                        view.pushAmplitude(0.8f);
                    }
                    assertThat(render(view, 300, 80).sameAs(empty)).isFalse();
                    view.reset();
                    assertThat(render(view, 300, 80).sameAs(empty)).isTrue();
                });
    }

    /**
     * Pinned as observed behaviour, not endorsed: the rolling window is sized from the view's
     * measured width, so while that is still zero every sample pushed is immediately trimmed away.
     * A recording that starts before the sheet has been laid out therefore loses its opening
     * amplitudes -- silently, since the bars simply start later.
     */
    @Test
    public void samplesPushedBeforeTheViewIsMeasuredAreDiscarded() {
        onMain(
                () -> {
                    final VoiceWaveformView view = new VoiceWaveformView(context());
                    for (int i = 0; i < 40; i++) {
                        view.pushAmplitude(0.9f);
                    }
                    final VoiceWaveformView untouched = new VoiceWaveformView(context());
                    assertThat(render(view, 300, 80).sameAs(render(untouched, 300, 80))).isTrue();
                });
    }

    /**
     * The blinking dot is a {@code ValueAnimator} started and stopped by {@code setRecording}. It
     * is started on a view that is never attached here, so the thing being checked is that
     * stopping it is safe in that order -- a leaked infinite animator on the recording sheet keeps
     * a frame callback alive for the life of the process.
     */
    @Test
    public void startingAndStoppingRecordingIsSafeOnADetachedView() {
        onMain(
                () -> {
                    final VoiceWaveformView view = new VoiceWaveformView(context());
                    view.setRecording(true);
                    view.pushAmplitude(0.5f);
                    assertThat(isBlank(render(view, 300, 80))).isFalse();
                    view.setRecording(false);
                    view.setRecording(false);
                    render(view, 300, 80);
                });
    }

    // ---- WaveformProgressDrawable --------------------------------------------------------------

    @Test
    public void aPartlyPlayedWaveformLooksDifferentFromAnUnplayedOne() {
        onMain(
                () -> {
                    final float density =
                            context().getResources().getDisplayMetrics().density;
                    final Bitmap unplayed = renderDrawable(density, 0);
                    final Bitmap half = renderDrawable(density, 5000);
                    final Bitmap played = renderDrawable(density, 10000);

                    assertThat(half.sameAs(unplayed)).isFalse();
                    assertThat(played.sameAs(half)).isFalse();
                    assertThat(played.sameAs(unplayed)).isFalse();
                });
    }

    @Test
    public void aLevelOutsideTheRangeIsClampedToTheEnds() {
        onMain(
                () -> {
                    final float density = context().getResources().getDisplayMetrics().density;
                    assertThat(renderDrawable(density, -1).sameAs(renderDrawable(density, 0)))
                            .isTrue();
                    assertThat(
                                    renderDrawable(density, 99999)
                                            .sameAs(renderDrawable(density, 10000)))
                            .isTrue();
                });
    }

    @Test
    public void aWaveformWithEmptyBoundsDrawsNothingRatherThanThrowing() {
        onMain(
                () -> {
                    final WaveformProgressDrawable drawable = new WaveformProgressDrawable(1f);
                    drawable.setColors(Color.WHITE, Color.GRAY);
                    drawable.setBounds(new Rect(0, 0, 0, 0));
                    final Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
                    drawable.draw(new Canvas(bitmap));
                    assertThat(isBlank(bitmap)).isTrue();
                });
    }

    private static Bitmap renderDrawable(final float density, final int level) {
        final WaveformProgressDrawable drawable = new WaveformProgressDrawable(density);
        drawable.setColors(Color.WHITE, Color.argb(255, 40, 40, 40));
        drawable.setBounds(new Rect(0, 0, 300, 48));
        drawable.setLevel(level);
        final Bitmap bitmap = Bitmap.createBitmap(300, 48, Bitmap.Config.ARGB_8888);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    // ---- SquareFrameLayout ----------------------------------------------------------------------

    /**
     * The layout ignores the height it is given and uses the width for both. That one-line
     * override is what keeps media thumbnails square in a grid; without it they take the row
     * height and the grid goes ragged.
     */
    @Test
    public void aSquareFrameLayoutTakesItsHeightFromItsWidth() {
        onMain(
                () -> {
                    final SquareFrameLayout layout = new SquareFrameLayout(context());
                    layout.measure(
                            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
                    assertThat(layout.getMeasuredWidth()).isEqualTo(150);
                    assertThat(layout.getMeasuredHeight()).isEqualTo(150);
                });
    }

    // ---- SignatureView ----------------------------------------------------------------------------

    @Test
    public void aSignatureStartsEmptyAndRecordsStrokesUntilCleared() {
        onMain(
                () -> {
                    final SignatureView view = new SignatureView(context());
                    layoutAt(view, 300, 150);
                    assertThat(view.isEmpty()).isTrue();

                    stroke(view, 20f, 20f, 200f, 120f);
                    assertThat(view.isEmpty()).isFalse();
                    assertThat(isBlank(render(view, 300, 150))).isFalse();

                    view.clear();
                    assertThat(view.isEmpty()).isTrue();
                });
    }

    @Test
    public void aSignatureCanBeExportedAsABitmap() {
        onMain(
                () -> {
                    final SignatureView view = new SignatureView(context());
                    layoutAt(view, 300, 150);
                    stroke(view, 10f, 10f, 280f, 140f);
                    final Bitmap exported = view.getSignatureBitmap();
                    assertThat(exported).isNotNull();
                    assertThat(exported.getWidth()).isGreaterThan(0);
                    assertThat(exported.getHeight()).isGreaterThan(0);
                });
    }

    private static void stroke(
            final View view, final float x1, final float y1, final float x2, final float y2) {
        final long now = SystemClock.uptimeMillis();
        dispatch(view, MotionEvent.ACTION_DOWN, now, x1, y1);
        dispatch(view, MotionEvent.ACTION_MOVE, now + 10, (x1 + x2) / 2f, (y1 + y2) / 2f);
        dispatch(view, MotionEvent.ACTION_MOVE, now + 20, x2, y2);
        dispatch(view, MotionEvent.ACTION_UP, now + 30, x2, y2);
    }

    private static void dispatch(
            final View view, final int action, final long time, final float x, final float y) {
        final MotionEvent event =
                MotionEvent.obtain(SystemClock.uptimeMillis(), time, action, x, y, 0);
        try {
            view.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
