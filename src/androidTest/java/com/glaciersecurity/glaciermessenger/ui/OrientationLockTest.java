package com.glaciersecurity.glaciermessenger.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.util.WindowFlagsProbeActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@code OrientationLock} pins every activity to portrait on phones and leaves tablets alone.
 *
 * <p>The behaviour is decided by {@code R.bool.portrait_only}, which is {@code true} in
 * {@code values/} and {@code false} in {@code values-sw600dp/} -- so the correct answer is
 * different on different hardware, and a test can only assert it by asking the device it is
 * running on. That is the reason this class exists in the instrumented tier rather than as a unit
 * test with a fake resource: one assertion checks the phone behaviour on a phone and the tablet
 * behaviour on a tablet.
 *
 * <p>The tablet half only actually runs on the {@code matrix} group, which is the only one with
 * a device wide enough to resolve {@code values-sw600dp} ({@code tablet34}). A {@code fleet} run
 * is all phones, so it exercises the locked branch alone -- green there says nothing about
 * whether tablets are still free to rotate.
 *
 * <p>It also covers the part that is easy to lose in a refactor. The lock is installed as an
 * {@code Application.ActivityLifecycleCallbacks} rather than declared in the manifest, because the
 * manifest attribute cannot express the sw600dp exception and does not reach activities the app
 * does not declare itself (the image cropper). A callback that stops being registered fails
 * silently -- every screen simply starts rotating again.
 */
@RunWith(AndroidJUnit4.class)
public class OrientationLockTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private boolean isPhone() {
        return context.getResources().getBoolean(R.bool.portrait_only);
    }

    @Test
    public void theDeviceClassificationAgreesWithItsActualWidth() {
        // Every other assertion in this class branches on portrait_only, so a device that is
        // filed under "tablet" but is really a phone would make the tablet cases pass by never
        // running. This is what stops that: portrait_only comes from values-sw600dp, so it has
        // to agree with the width the device actually reports.
        final int smallestWidthDp =
                context.getResources().getConfiguration().smallestScreenWidthDp;

        assertWithMessage(
                        "portrait_only is %s but the device reports smallestScreenWidthDp=%s;"
                            + " values-sw600dp should have decided this",
                        isPhone(), smallestWidthDp)
                .that(isPhone())
                .isEqualTo(smallestWidthDp < 600);
    }

    @Test
    public void anActivityIsLockedToPortraitOnPhonesAndFreeOnTablets() {
        try (ActivityScenario<WindowFlagsProbeActivity> scenario =
                ActivityScenario.launch(WindowFlagsProbeActivity.class)) {
            scenario.onActivity(
                    activity -> {
                        final int requested = activity.getRequestedOrientation();
                        if (isPhone()) {
                            assertWithMessage(
                                            "requested orientation on a device where"
                                                + " portrait_only is true")
                                    .that(requested)
                                    .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                        } else {
                            assertWithMessage(
                                            "a tablet must keep rotating: the two-pane"
                                                + " conversations layout is w945dp and only"
                                                + " reachable in landscape")
                                    .that(requested)
                                    .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                        }
                    });
        }
    }

    @Test
    public void aLockedActivityIsActuallyInPortrait() {
        // The requested orientation is what the app asked for; this is what the window manager
        // did with it. They can differ -- a freeform or split-screen window keeps its own shape.
        if (!isPhone()) {
            return;
        }
        try (ActivityScenario<WindowFlagsProbeActivity> scenario =
                ActivityScenario.launch(WindowFlagsProbeActivity.class)) {
            scenario.onActivity(
                    activity ->
                            assertThat(activity.getResources().getConfiguration().orientation)
                                    .isEqualTo(Configuration.ORIENTATION_PORTRAIT));
        }
    }

    @Test
    public void theLockIsInstalledForTheWholeProcessNotJustOneActivity() {
        // Two launches in a row: the callback is registered once on the Application, so a second
        // activity has to come up locked as well. A lock applied in a base-activity onCreate --
        // the obvious-looking refactor -- would pass the first test and fail this one for any
        // activity that does not extend that base, including the image cropper.
        for (int i = 0; i < 2; i++) {
            try (ActivityScenario<WindowFlagsProbeActivity> scenario =
                    ActivityScenario.launch(WindowFlagsProbeActivity.class)) {
                final int expected =
                        isPhone()
                                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                scenario.onActivity(
                        activity ->
                                assertThat(activity.getRequestedOrientation()).isEqualTo(expected));
            }
        }
    }
}
