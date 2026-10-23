package com.glaciersecurity.glaciermessenger.ui.journey;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyMatchers.conversationRow;
import static com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyMatchers.withMessageBody;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;
import com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyEnvironment;
import com.glaciersecurity.glaciermessenger.ui.journey.support.Launchers;
import com.glaciersecurity.glaciermessenger.ui.journey.support.SeedFixtures;
import com.glaciersecurity.glaciermessenger.ui.journey.support.Wait;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opening a conversation and reading it. The chat screen is a <em>fragment</em> inside
 * {@code ConversationsActivity}, so waiting for a "ConversationActivity" times out on a screen
 * that is already open -- assert on views, not on the top activity.
 */
@RunWith(AndroidJUnit4.class)
public class OpenConversationJourneyTest {

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

    private void waitForTheChatScreen() {
        Wait.until(
                "the chat screen's composer to be on screen",
                () -> {
                    onView(withId(R.id.textinput)).check(matches(isDisplayed()));
                    return true;
                });
    }

    /** The deterministic route in, so a broken list does not fail every test that needs a chat. */
    @Test
    public void aConversationOpensFromAViewIntent() {
        seed.seedConversation("alice");
        final String[] stored = seed.seedMessages("the body of a seeded message");

        Launchers.startAndWaitFor(
                Launchers.conversationIntent(seed.conversation().getUuid()),
                ConversationsActivity.class);
        waitForTheChatScreen();

        Wait.until(
                "the seeded message to be in the message list",
                () -> {
                    onData(withMessageBody(stored[0]))
                            .inAdapterView(withId(R.id.messages_view))
                            .check(matches(isDisplayed()));
                    return true;
                });
    }

    /**
     * The route a person takes, which proves something the intent route does not: that the row is
     * hit-testable. The listener is on the un-id'd {@code itemView}, so this relies on bubbling.
     */
    @Test
    public void tappingAListRowOpensThatConversation() {
        seed.seedConversation("alice");
        final String[] stored = seed.seedMessages("opened by tapping the row");

        scenario = ActivityScenario.launch(ConversationsActivity.class);
        Wait.until(
                "the conversation list to be on screen",
                () -> {
                    onView(conversationRow("alice")).check(matches(isDisplayed()));
                    return true;
                });

        onView(conversationRow("alice")).perform(click());

        waitForTheChatScreen();
        Wait.until(
                "that conversation's message to be in the list",
                () -> {
                    onData(withMessageBody(stored[0]))
                            .inAdapterView(withId(R.id.messages_view))
                            .check(matches(isDisplayed()));
                    return true;
                });
    }
}
