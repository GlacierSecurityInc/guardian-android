package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The developer/debugging checks in {@link SystemSecurityInfo}, which read {@code Settings.Global}
 * through a {@link ContentResolver}.
 *
 * <p>These need the Robolectric tier. Against the mockable android.jar every
 * {@code Settings.Global.getInt} call returns the {@code returnDefaultValues} zero no matter what
 * the device state is, so a plain JUnit test can only ever observe the "off" branch and would
 * pass just as happily if the method body were {@code return false;}.
 */
@RunWith(RobolectricTestRunner.class)
public class SystemSecurityInfoSettingsTest {

    private Context context;
    private ContentResolver resolver;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        resolver = context.getContentResolver();
    }

    @Test
    public void developerToolsAreReportedOffOnAnUntouchedDevice() {
        assertThat(SystemSecurityInfo.isDeveloperToolsEnabled(context)).isFalse();
    }

    @Test
    public void developerToolsAreDetectedWhenEnabled() {
        Settings.Global.putInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
        assertThat(SystemSecurityInfo.isDeveloperToolsEnabled(context)).isTrue();
    }

    @Test
    public void anyNonZeroDeveloperToolsValueCounts() {
        // The check is "!= 0", not "== 1" -- unlike the two ADB checks below.
        Settings.Global.putInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 2);
        assertThat(SystemSecurityInfo.isDeveloperToolsEnabled(context)).isTrue();
    }

    @Test
    public void usbDebuggingIsReportedOffOnAnUntouchedDevice() {
        assertThat(SystemSecurityInfo.isUSBDebuggingEnabled(context)).isFalse();
    }

    @Test
    public void usbDebuggingIsDetectedWhenEnabled() {
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1);
        assertThat(SystemSecurityInfo.isUSBDebuggingEnabled(context)).isTrue();
    }

    @Test
    public void wifiDebuggingIsReportedOffOnAnUntouchedDevice() {
        assertThat(SystemSecurityInfo.isWifiDebuggingEnabled(context)).isFalse();
    }

    @Test
    public void wifiDebuggingIsDetectedWhenEnabled() {
        // "adb_wifi_enabled" is a string literal in the app: there is no Settings.Global
        // constant for it. Spelling it out here too is deliberate -- if the app's literal is
        // ever mistyped, this test still reads the key Android actually uses.
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1);
        assertThat(SystemSecurityInfo.isWifiDebuggingEnabled(context)).isTrue();
    }

    @Test
    public void settingsChangesDoNotLeakBetweenTests() {
        // Each Robolectric test gets a fresh environment. If this ever fails, the tests above
        // are no longer independent and their "untouched device" cases are meaningless.
        assertThat(Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0)).isEqualTo(0);
        assertThat(Settings.Global.getInt(
                resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)).isEqualTo(0);
    }
}
