package com.glaciersecurity.glaciermessenger.ui.journey;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.SettingsActivity;
import com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyEnvironment;
import com.glaciersecurity.glaciermessenger.ui.journey.support.Launchers;
import com.glaciersecurity.glaciermessenger.ui.journey.support.SeedFixtures;
import com.glaciersecurity.glaciermessenger.ui.journey.support.Wait;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The settings screen renders and its preference fragment inflates.
 *
 * <p>Deliberately shallow. The preference tree under {@code ui/fragment/settings/} has eleven
 * screens, and most of what they do is write a key that a unit test can assert far more cheaply
 * than a device can. What only a device can show is that the host activity starts and the
 * fragment inflates at all -- a broken preference XML or a missing resource fails exactly here
 * and nowhere else, and it takes the whole screen down.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsJourneyTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private SeedFixtures seed;
    private Activity settings;

    @Before
    public void setUp() {
        JourneyEnvironment.prepare();
        seed = new SeedFixtures(context);
        seed.seedDisabledAccount();
    }

    @After
    public void tearDown() {
        if (settings != null) {
            settings.finish();
        }
        if (seed != null) {
            seed.cleanUp();
        }
    }

    @Test
    public void theSettingsScreenOpensAndInflatesItsFragment() {
        settings =
                Launchers.startAndWaitFor(
                        new Intent(context, SettingsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        SettingsActivity.class);

        Wait.until(
                "the settings toolbar to be on screen",
                () -> {
                    onView(withId(R.id.materialToolbar)).check(matches(isDisplayed()));
                    return true;
                });

        // The fragment is added into this container at runtime; if the preference XML fails to
        // inflate, the activity is still up and this container is empty.
        Wait.until(
                "the preference fragment to be added",
                () -> {
                    final android.view.View container =
                            settings.findViewById(R.id.fragment_container);
                    return container instanceof android.view.ViewGroup
                            && ((android.view.ViewGroup) container).getChildCount() > 0;
                });

        assertWithMessage("SettingsActivity is still up after inflating its fragment")
                .that(settings.isFinishing())
                .isFalse();
    }
}
