package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.view.WindowManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.AppSettings;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * FLAG_SECURE is what makes a failing instrumented test undebuggable: {@code screencap} comes
 * back black and {@code uiautomator dump} returns nothing, so there is no failure screenshot and
 * no view hierarchy. It also blocks UI Automator from driving the system dialogs this app depends
 * on — VPN consent, Telecom, permission prompts.
 *
 * <p>These tests hold the two halves of the arrangement in place: under instrumentation the flag
 * is off, and the user's own privacy setting is left exactly as it was.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenshotSettingTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void underInstrumentationTheWindowIsNotSecure() {
        try (ActivityScenario<WindowFlagsProbeActivity> scenario =
                     ActivityScenario.launch(WindowFlagsProbeActivity.class)) {
            scenario.onActivity(activity -> {
                // Start from the shipping default so the test proves the gate, not a leftover.
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

                SettingsUtils.applyScreenshotSetting(activity);

                final int flags = activity.getWindow().getAttributes().flags;
                assertWithMessage("FLAG_SECURE after applyScreenshotSetting under instrumentation")
                        .that(flags & WindowManager.LayoutParams.FLAG_SECURE)
                        .isEqualTo(0);
            });
        }
    }

    @Test
    public void theUsersPrivacySettingIsNotTouched() {
        // The alternative fix was to seed "Allow screenshots" to true before launching. That
        // works, but it writes a real privacy setting on a shared test device and
        // leaves it flipped afterwards. Detecting the test run instead leaves it alone, and this
        // is the assertion that keeps it that way.
        final boolean before = new AppSettings(context).isAllowScreenshots();

        try (ActivityScenario<WindowFlagsProbeActivity> scenario =
                     ActivityScenario.launch(WindowFlagsProbeActivity.class)) {
            scenario.onActivity(SettingsUtils::applyScreenshotSetting);
        }

        assertThat(new AppSettings(context).isAllowScreenshots()).isEqualTo(before);
    }

}
