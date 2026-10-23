package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.location.LocationManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link LocationScanner#start(long, String, String)} registration, across the API level boundary
 * it branches on.
 *
 * <p>These run under Robolectric at a pinned SDK because the interesting case is unreachable any
 * other way: {@code minSdk} is 30, but every attached device and emulator is API 33 or newer, so
 * the pre-S branch never executes on real hardware here. It is also unreachable from the UI
 * without a signed-in account -- {@code LocationService} is not exported, and the only path that
 * starts it requires {@code getAccounts()} to be non-empty.
 *
 * <p>The regression being guarded: a misplaced brace once put the whole registration block inside
 * an {@code if (SDK_INT >= S)} gate, so on Android 11 no provider was registered and no wakelock
 * taken -- while {@code mRegistered} was set to true regardless, leaving the scanner reporting
 * itself as running.
 */
@RunWith(RobolectricTestRunner.class)
public class LocationScannerRegistrationTest {

    private static final long INTERVAL_MS = 120_000L;

    private Application application;
    private LocationManager locationManager;
    private LocationScanner scanner;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        locationManager = (LocationManager) application.getSystemService(Context.LOCATION_SERVICE);
        // fuse = false: LocationService constructs it that way, so this is the live branch.
        scanner = new LocationScanner(application, false);
    }

    private java.util.List<android.location.LocationListener> listenersFor(final String provider) {
        return shadowOf(locationManager).getLocationUpdateListeners(provider);
    }

    private void grantFineLocation() {
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Test
    @Config(sdk = 30)
    public void registersNetworkAndGpsOnAndroid11() {
        grantFineLocation();

        scanner.start(INTERVAL_MS, "user", "org");

        assertThat(listenersFor(LocationManager.NETWORK_PROVIDER)).contains(scanner);
        assertThat(listenersFor(LocationManager.GPS_PROVIDER)).contains(scanner);
    }

    /**
     * The post-S branch, at the suite's default SDK. Not pinned to a level of its own: the branch
     * is the same for every API >= 31, and each pinned level costs another staged android-all jar.
     */
    @Test
    public void registersNetworkAndGpsOnModernAndroid() {
        grantFineLocation();

        scanner.start(INTERVAL_MS, "user", "org");

        assertThat(listenersFor(LocationManager.NETWORK_PROVIDER)).contains(scanner);
        assertThat(listenersFor(LocationManager.GPS_PROVIDER)).contains(scanner);
    }

    /**
     * stop() only deregisters when mRegistered is set, so a clean teardown on Android 11 is the
     * observable proof that start() set it -- the flag itself is private.
     */
    @Test
    @Config(sdk = 30)
    public void stopDeregistersOnAndroid11() {
        grantFineLocation();
        scanner.start(INTERVAL_MS, "user", "org");
        // assert the precondition: without it "nothing is registered after stop()" is vacuously
        // true on the very regression this class exists to catch.
        assertThat(listenersFor(LocationManager.NETWORK_PROVIDER)).contains(scanner);
        assertThat(listenersFor(LocationManager.GPS_PROVIDER)).contains(scanner);

        scanner.stop();

        assertThat(listenersFor(LocationManager.NETWORK_PROVIDER)).doesNotContain(scanner);
        assertThat(listenersFor(LocationManager.GPS_PROVIDER)).doesNotContain(scanner);
    }

    /**
     * Without the permission nothing may be registered, and mRegistered must stay false -- which
     * shows up as a later start() (once granted) still being able to register.
     */
    @Test
    @Config(sdk = 30)
    public void doesNotRegisterWithoutPermissionOnAndroid11() {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);

        scanner.start(INTERVAL_MS, "user", "org");

        assertThat(listenersFor(LocationManager.NETWORK_PROVIDER)).isEmpty();
        assertThat(listenersFor(LocationManager.GPS_PROVIDER)).isEmpty();

        grantFineLocation();
        scanner.start(INTERVAL_MS, "user", "org");

        assertThat(listenersFor(LocationManager.NETWORK_PROVIDER)).contains(scanner);
        assertThat(listenersFor(LocationManager.GPS_PROVIDER)).contains(scanner);
    }
}
