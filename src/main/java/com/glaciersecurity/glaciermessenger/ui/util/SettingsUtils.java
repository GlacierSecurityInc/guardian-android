package com.glaciersecurity.glaciermessenger.ui.util;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.BuildConfig;

public class SettingsUtils {

    /**
     * Whether an instrumentation is running in this process. Computed once: the answer cannot
     * change for the life of the process, and this is called on every activity resume.
     */
    private static volatile Boolean instrumented = null;

    public static void applyScreenshotSetting(final Activity activity) {
        final Window activityWindow = activity.getWindow();
        if (allowScreenshots(activity)) {
            activityWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activityWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private static boolean allowScreenshots(final Activity activity) {
        if (isInstrumentedTest()) {
            return true;
        }
        return new AppSettings(activity).isAllowScreenshots();
    }

    /**
     * True only while an instrumented test is driving this process, and only in a debug build.
     *
     * <p>FLAG_SECURE blanks {@code screencap} and makes {@code uiautomator dump} return nothing,
     * so with it set a failing instrumented test produces a black screenshot and no view
     * hierarchy — and the system dialogs the app depends on (VPN consent, Telecom, permission
     * prompts) can only be driven through UI Automator. Seeding the user's "Allow screenshots"
     * preference instead would work, but it writes a real privacy setting on a shared test
     * device and leaves it flipped afterwards. Detecting the test run leaves the setting
     * alone.
     *
     * <p>The androidx test classes ship in the *test* APK, which is loaded into this process only
     * for an instrumented run; a debug build installed by hand cannot find them, and a release
     * build fails the {@link BuildConfig#DEBUG} check first. Both have to be true, so neither a
     * hand-installed debug build nor a store build ever clears the flag.
     */
    private static boolean isInstrumentedTest() {
        if (!BuildConfig.DEBUG) {
            return false;
        }
        Boolean cached = instrumented;
        if (cached == null) {
            cached = detectInstrumentation();
            instrumented = cached;
        }
        return cached;
    }

    private static boolean detectInstrumentation() {
        try {
            Class.forName("androidx.test.platform.app.InstrumentationRegistry");
            return true;
        } catch (final ClassNotFoundException notUnderTest) {
            return false;
        }
    }
}
