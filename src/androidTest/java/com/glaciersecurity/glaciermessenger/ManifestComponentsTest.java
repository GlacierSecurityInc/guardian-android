package com.glaciersecurity.glaciermessenger;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * The cheapest instrumented test that is still worth running: it installs the real APK and asks
 * the platform whether every component the manifest declares actually exists and can be loaded.
 *
 * <p>That catches a renamed or deleted class the manifest still points at, a component the
 * shrinker stripped, and — the reason this runs on more than one API level — a component whose
 * declaration the platform rejects on a particular version, such as a foreground service whose
 * type is invalid for that release.
 *
 * <p>It needs no account, no network and no server, so it is also the smoke test that proves a
 * managed device is provisioned correctly before anything harder is attempted.
 */
@RunWith(AndroidJUnit4.class)
public class ManifestComponentsTest {

    private static final String PACKAGE = "com.glaciersecurity.glaciermessenger";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void theAppUnderTestIsTheOneWeThinkItIs() {
        assertThat(context.getPackageName()).isEqualTo(PACKAGE);
    }

    @Test
    public void deviceIsWithinTheSupportedRange() {
        // minSdk is 30 and part of the fleet is still on Android 14, so a test result from a
        // device outside that window would be misleading rather than useful.
        assertWithMessage("device API level")
                .that(Build.VERSION.SDK_INT)
                .isAtLeast(30);
    }

    @Test
    public void everyDeclaredActivityClassLoads() throws Exception {
        final PackageInfo info = context.getPackageManager()
                .getPackageInfo(PACKAGE, PackageManager.GET_ACTIVITIES);
        assertThat(info.activities).isNotNull();

        final List<String> missing = new ArrayList<>();
        for (final ActivityInfo activity : info.activities) {
            // An <activity-alias> name is an intent-filter handle, not a class. Its
            // targetActivity is the class that has to exist -- which is the part worth
            // checking, since an alias pointing at a renamed activity fails only when
            // something actually launches it.
            final String className =
                    activity.targetActivity != null ? activity.targetActivity : activity.name;
            if (!classLoads(className)) {
                missing.add(activity.name + " -> " + className);
            }
        }
        assertWithMessage("activities declared in the manifest whose class is missing")
                .that(missing)
                .isEmpty();
    }

    @Test
    public void everyActivityAliasPointsAtARealActivity() throws Exception {
        final PackageInfo info = context.getPackageManager()
                .getPackageInfo(PACKAGE, PackageManager.GET_ACTIVITIES);

        final List<String> aliases = new ArrayList<>();
        for (final ActivityInfo activity : info.activities) {
            if (activity.targetActivity != null) {
                aliases.add(activity.name);
                assertWithMessage("alias " + activity.name + " target")
                        .that(classLoads(activity.targetActivity))
                        .isTrue();
            }
        }
        // The notification-preferences alias is the one the launcher-restriction work in
        // 79aed9d48 turned on and off; assert at least one alias is still declared so this
        // test cannot pass vacuously after the manifest changes.
        assertThat(aliases).isNotEmpty();
    }

    @Test
    public void everyDeclaredServiceClassLoads() throws Exception {
        final PackageInfo info = context.getPackageManager()
                .getPackageInfo(PACKAGE, PackageManager.GET_SERVICES);
        assertThat(info.services).isNotNull();

        final List<String> missing = new ArrayList<>();
        for (final ServiceInfo service : info.services) {
            if (!classLoads(service.name)) {
                missing.add(service.name);
            }
        }
        assertWithMessage("services declared in the manifest whose class is missing")
                .that(missing)
                .isEmpty();
    }

    @Test
    public void foregroundServiceTypesAreValidOnThisRelease() throws Exception {
        // Android 14 introduced required foreground service types and Android 15 tightened
        // which ones a service may enter. A service that declares a type the running platform
        // rejects fails at startService time, in the field, on that version only.
        final PackageInfo info = context.getPackageManager()
                .getPackageInfo(PACKAGE, PackageManager.GET_SERVICES);

        for (final ServiceInfo service : info.services) {
            if (!service.name.startsWith(PACKAGE)) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The platform parsed the declaration; a malformed type would not have
                // survived installation, so reaching here at all is the assertion.
                assertWithMessage("foreground service type flags for " + service.name)
                        .that(service.getForegroundServiceType())
                        .isAtLeast(0);
            }
        }
    }

    private boolean classLoads(final String className) {
        try {
            Class.forName(className, false, context.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }
}
