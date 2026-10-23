package com.glaciersecurity.glaciermessenger.ui.journey.support;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.testutil.DeviceTestFixtures;
import com.glaciersecurity.glaciermessenger.utils.CognitoClientMigrationManager;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;

import java.util.concurrent.TimeUnit;

/**
 * Everything a journey test needs done before it launches an activity.
 *
 * <p>All of this has to run per test method rather than once per class: the orchestrator runs
 * {@code pm clear} between methods, which removes the permission grants and the preferences
 * written here along with the rest of the app's data.
 *
 * <p>The point is to reach the screen under test without any of the interstitials the app shows
 * on a genuinely fresh install. Each one is suppressed at its source rather than dismissed
 * through the UI, because driving a system dialog is slow, device-dependent, and the thing most
 * likely to make an unattended run fail for a reason that is not the app's fault.
 */
public final class JourneyEnvironment {

    private JourneyEnvironment() {}

    /** Call first in every {@code @Before}. */
    public static void prepare() {
        DeviceTestFixtures.setUpStringprep();
        grantAllDangerousPermissions();
        neverAskAboutBatteryOptimization();
        snoozeTheReauthenticationPrompt();
        markTheCognitoMigrationDone();
    }

    /**
     * {@code ConversationsActivity} asks for these one at a time on every launch until granted,
     * and each prompt is outside the app's view hierarchy where Espresso cannot see it. Failures
     * are ignored: the set comes from the manifest, so it includes permissions that do not exist
     * on every API level here (ACCESS_LOCAL_NETWORK is 37+).
     */
    private static void grantAllDangerousPermissions() {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context target = instrumentation.getTargetContext();
        final PackageManager packageManager = target.getPackageManager();

        final PackageInfo info;
        try {
            info = packageManager.getPackageInfo(
                    target.getPackageName(), PackageManager.GET_PERMISSIONS);
        } catch (final PackageManager.NameNotFoundException impossible) {
            throw new AssertionError("the app under test is not installed", impossible);
        }
        if (info.requestedPermissions == null) {
            return;
        }

        for (final String permission : info.requestedPermissions) {
            if (!isDangerous(packageManager, permission)) {
                continue;
            }
            instrumentation
                    .getUiAutomation()
                    .executeShellCommand(
                            "pm grant " + target.getPackageName() + " " + permission);
        }
    }

    private static boolean isDangerous(final PackageManager pm, final String permission) {
        try {
            final PermissionInfo info = pm.getPermissionInfo(permission, 0);
            return (info.getProtection() & PermissionInfo.PROTECTION_DANGEROUS) != 0;
        } catch (final PackageManager.NameNotFoundException unknownOnThisApiLevel) {
            return false;
        }
    }

    /**
     * The battery dialog is {@code setCanceledOnTouchOutside(false)}, so it blocks everything
     * behind it until a button is clicked. The key is per-device, so it is rebuilt the same way
     * {@code ConversationsActivity.getBatteryOptimizationPreferenceKey()} builds it.
     */
    private static void neverAskAboutBatteryOptimization() {
        final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String device =
                Settings.Secure.getString(target.getContentResolver(), Settings.Secure.ANDROID_ID);
        PreferenceManager.getDefaultSharedPreferences(target)
                .edit()
                .putBoolean("show_battery_optimization" + (device == null ? "" : device), false)
                .commit();
    }

    /**
     * A hermetic test has no Cognito session, so the first JWT-bearing request broadcasts
     * {@code ACTION_REAUTHENTICATION_REQUIRED} and {@code XmppActivity} shows a dialog over
     * everything. Uses the app's own public back-off rather than anything test-only.
     */
    private static void snoozeTheReauthenticationPrompt() {
        OkHttpClientProvider.notifyReauthenticationSnoozed(1, TimeUnit.HOURS);
    }

    /**
     * The other dialog, and the one that actually fires: the migration notice is shown whenever
     * its preference has not been written, so on every freshly installed app, with no network
     * involved. It looks identical to the snooze case above, which is why suppressing only that
     * one changes nothing.
     */
    private static void markTheCognitoMigrationDone() {
        CognitoClientMigrationManager.markMigrationReauthV1Completed(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }
}
