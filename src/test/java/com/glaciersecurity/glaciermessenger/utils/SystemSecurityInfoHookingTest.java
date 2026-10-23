package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Before;
import org.junit.Test;

/**
 * {@link SystemSecurityInfo#isHookingPackageInstalled(Context)}, the Xposed/Magisk/Frida
 * installed-package scan.
 *
 * <p>This is the Mockito tier rather than the Robolectric one, and the difference is the point:
 * the method's whole job is to react to packages being present, and the only way to say "this
 * device has LSPosed installed" is to control what {@link PackageManager#getPackageInfo} does.
 * Robolectric would boot a whole Android environment and still leave us installing fake packages
 * into it; a stubbed PackageManager states the precondition directly and runs in milliseconds.
 *
 * <p>Note what is <em>not</em> asserted here: the method's second half looks for the
 * {@code de.robv.android.xposed.XposedBridge} class on the classpath. That class is genuinely
 * absent in a unit test and there is no honest way to conjure it, so these tests pin down the
 * package-scan branch only and the negative case below is really "no hooking package AND no
 * XposedBridge on the test classpath".
 */
public class SystemSecurityInfoHookingTest {

    private Context context;
    private PackageManager packageManager;

    @Before
    public void setUp() throws Exception {
        context = mock(Context.class);
        packageManager = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(packageManager);
        // Default: nothing is installed. Individual tests then make one package resolve.
        when(packageManager.getPackageInfo(anyString(), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
    }

    @Test
    public void aCleanDeviceReportsNoHookingFramework() throws Exception {
        assertThat(SystemSecurityInfo.isHookingPackageInstalled(context)).isFalse();
    }

    @Test
    public void lsposedManagerIsDetected() throws Exception {
        installed("org.lsposed.manager");
        assertThat(SystemSecurityInfo.isHookingPackageInstalled(context)).isTrue();
    }

    @Test
    public void magiskIsDetected() throws Exception {
        installed("com.topjohnwu.magisk");
        assertThat(SystemSecurityInfo.isHookingPackageInstalled(context)).isTrue();
    }

    @Test
    public void theOriginalXposedInstallerIsDetected() throws Exception {
        installed("de.robv.android.xposed.installer");
        assertThat(SystemSecurityInfo.isHookingPackageInstalled(context)).isTrue();
    }

    @Test
    public void aPackageTheScanDoesNotKnowAboutIsIgnored() throws Exception {
        // Guards against the scan degenerating into "any installed package trips it".
        installed("com.example.ordinary.app");
        assertThat(SystemSecurityInfo.isHookingPackageInstalled(context)).isFalse();
    }

    /** Makes exactly {@code pkg} resolve; every other query keeps throwing NameNotFoundException. */
    private void installed(String pkg) throws Exception {
        when(packageManager.getPackageInfo(eq(pkg), anyInt())).thenReturn(new PackageInfo());
    }
}
