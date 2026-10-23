package com.glaciersecurity.glaciermessenger.testutil;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards the Robolectric wiring itself rather than any app behaviour.
 *
 * <p>Robolectric has several failure modes that are invisible until a test runs and then look
 * like a bug in whatever test happened to run first: the wrong android-all jar, a missing
 * {@code --add-opens}, {@code includeAndroidResources} switched off, or the app's
 * conscrypt-android artifact shadowing the OpenJDK one. Each check here fails with a message
 * pointing at the build configuration instead, so a broken environment is diagnosed once.
 *
 * <p>If this class fails, read the Robolectric comments in {@code build.gradle} and
 * {@code src/test/resources/robolectric.properties} before touching any other test.
 */
@RunWith(RobolectricTestRunner.class)
public class RobolectricEnvironmentTest {

    @Test
    public void runsAgainstTheSdkLevelThatBuildGradleStages() {
        // build.gradle stages exactly one android-all jar and robolectric.properties pins the
        // level to match. If these two ever drift, Robolectric asks for a jar that was never
        // staged and -- because it is in offline mode -- fails instead of downloading it.
        assertThat(Build.VERSION.SDK_INT).isEqualTo(37);
    }

    @Test
    public void bootsTheStockApplicationNotTheAppsOwn() {
        // robolectric.properties deliberately substitutes android.app.Application. Booting the
        // real GlacierGuardian would run Amplify/Firebase/WireGuard startup and die on
        // conscrypt's missing JVM native library.
        Context context = ApplicationProvider.getApplicationContext();
        assertThat(context.getApplicationContext().getClass().getName())
                .isEqualTo("android.app.Application");
    }

    @Test
    public void resolvesTheAppsOwnResources() {
        // Proves testOptions.unitTests.includeAndroidResources is on: without it Robolectric has
        // no merged resource table and cannot resolve a string the app actually declares.
        Context context = ApplicationProvider.getApplicationContext();
        assertThat(context.getString(com.glaciersecurity.glaciermessenger.R.string.app_name))
                .isEqualTo("Guardian");
    }

    @Test
    public void providesRealSharedPreferencesNotStubs() {
        // The whole point of the Robolectric tier: a store that can tell "stored false" apart
        // from "never stored", which the mockable android.jar cannot. See FakeSharedPreferences
        // for the hand-written equivalent the non-Robolectric tests still use.
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("env-check", Context.MODE_PRIVATE);

        assertThat(prefs.contains("flag")).isFalse();
        prefs.edit().putBoolean("flag", false).apply();
        assertThat(prefs.contains("flag")).isTrue();
        assertThat(prefs.getBoolean("flag", true)).isFalse();
    }
}
