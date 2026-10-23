package com.glaciersecurity.glaciermessenger.ui.journey;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;
import com.glaciersecurity.glaciermessenger.ui.SecurityHubActivity;
import com.glaciersecurity.glaciermessenger.ui.SettingsActivity;
import com.glaciersecurity.glaciermessenger.ui.StartConversationActivity;
import com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyEnvironment;
import com.glaciersecurity.glaciermessenger.ui.journey.support.SeedFixtures;
import com.glaciersecurity.glaciermessenger.ui.journey.support.Wait;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The bottom navigation bar, which is how a user reaches most of the app.
 *
 * <p>Every destination except "chat" is a separate {@code Activity} started with
 * {@code startActivity}, so these are asserted with an {@link Instrumentation.ActivityMonitor}
 * rather than by looking for a view. That also avoids {@code espresso-intents}, which this build
 * does not declare.
 */
@RunWith(AndroidJUnit4.class)
public class NavigationJourneyTest {

    private static final long TIMEOUT_MS = 30_000L;

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private SeedFixtures seed;
    private ActivityScenario<ConversationsActivity> scenario;

    @Before
    public void setUp() {
        JourneyEnvironment.prepare();
        seed = new SeedFixtures(context);
        seed.seedConversation("alice");
        scenario = ActivityScenario.launch(ConversationsActivity.class);
        Wait.until(
                "the bottom navigation bar to be on screen",
                () -> {
                    onView(withId(R.id.navigation_conversations)).check(matches(isDisplayed()));
                    return true;
                });
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.close();
        }
        if (seed != null) {
            seed.cleanUp();
        }
    }

    /** Taps one bottom-nav item and asserts which activity it opens. */
    private void tapNavAndExpect(final int menuItemId, final Class<? extends Activity> expected) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(expected.getName(), null, false);
        try {
            onView(withId(menuItemId)).perform(click());
            final Activity opened = monitor.waitForActivityWithTimeout(TIMEOUT_MS);
            assertWithMessage(expected.getSimpleName() + " after tapping its bottom-nav item")
                    .that(opened)
                    .isNotNull();
            opened.finish();
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void contactsOpensStartConversation() {
        tapNavAndExpect(R.id.contacts_nav, StartConversationActivity.class);
    }

    @Test
    public void securityHubOpensSecurityHub() {
        tapNavAndExpect(R.id.security_hub_nav, SecurityHubActivity.class);
    }

    @Test
    public void settingsOpensSettings() {
        tapNavAndExpect(R.id.settings_nav, SettingsActivity.class);
    }

    /**
     * "Chat" is the tab we are already on, and its branch deliberately starts nothing. Asserted
     * because an accidental {@code startActivity} there would relaunch the screen under the user.
     */
    @Test
    public void chatStaysOnTheConversationList() {
        onView(withId(R.id.chat_nav)).perform(click());

        onView(withId(R.id.navigation_conversations)).check(matches(isDisplayed()));
    }
}
