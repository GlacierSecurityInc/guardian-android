package com.glaciersecurity.glaciermessenger.ui.journey;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyMatchers.conversationRow;
import static org.hamcrest.Matchers.allOf;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;
import com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyEnvironment;
import com.glaciersecurity.glaciermessenger.ui.journey.support.SeedFixtures;
import com.glaciersecurity.glaciermessenger.ui.journey.support.Wait;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The conversation list: the screen a signed-in user sees every time they open the app.
 *
 * <p>Seeded state only -- no sign-in and no network. See {@code SeedFixtures} for why the account
 * is seeded disabled and what that buys.
 */
@RunWith(AndroidJUnit4.class)
public class ConversationListJourneyTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private SeedFixtures seed;
    private ActivityScenario<ConversationsActivity> scenario;

    @Before
    public void setUp() {
        JourneyEnvironment.prepare();
        seed = new SeedFixtures(context);
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

    /**
     * Opens the list and waits for it to actually be populated.
     *
     * <p>The wait is not decoration. The activity binds {@code XmppConnectionService} and the
     * service restores conversations on a background executor, so the main looper is idle -- which
     * is all Espresso waits for -- several times before there is anything on screen.
     */
    private void openTheList() {
        scenario = ActivityScenario.launch(ConversationsActivity.class);
        Wait.until(
                "the conversation list to be on screen",
                () -> {
                    onView(withId(R.id.list)).check(matches(isDisplayed()));
                    return true;
                });
    }

    @Test
    public void aSeededConversationIsListedByName() {
        seed.seedConversation("alice");

        openTheList();

        onView(conversationRow("alice")).check(matches(isDisplayed()));
    }

    @Test
    public void theLatestMessageIsShownAsThePreview() {
        seed.seedConversation("alice");
        final String[] stored = seed.seedMessages("first one", "the latest one");
        final String newest = stored[stored.length - 1];

        openTheList();

        Wait.until(
                "the newest message to appear as the row's preview",
                () -> {
                    onView(allOf(withId(R.id.conversation_lastmsg), withText(newest)))
                            .check(matches(isDisplayed()));
                    return true;
                });
    }

    @Test
    public void severalConversationsAreAllListed() {
        seed.seedDisabledAccount();
        seed.seedConversation("alice");
        seed.seedConversation("bob");

        openTheList();

        onView(conversationRow("bob")).check(matches(isDisplayed()));
    }
}
