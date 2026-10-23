package com.glaciersecurity.glaciermessenger.ui.journey;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.pressImeActionButton;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.glaciersecurity.glaciermessenger.ui.journey.support.JourneyMatchers.withMessageBody;

import android.content.Context;

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
 * Composing and sending, as far as one device can observe it: nothing is delivered here, so what
 * is asserted is everything up to the wire; delivery is out of scope here.
 */
@RunWith(AndroidJUnit4.class)
public class ComposeMessageJourneyTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private SeedFixtures seed;

    @Before
    public void setUp() {
        JourneyEnvironment.prepare();
        seed = new SeedFixtures(context);
        seed.seedConversation("alice");
        Launchers.startAndWaitFor(
                Launchers.conversationIntent(seed.conversation().getUuid()),
                ConversationsActivity.class);
        Wait.until(
                "the composer to be on screen",
                () -> {
                    onView(withId(R.id.textinput)).check(matches(isDisplayed()));
                    return true;
                });
    }

    @After
    public void tearDown() {
        if (seed != null) {
            seed.cleanUp();
        }
    }

    @Test
    public void theComposerAcceptsText() {
        onView(withId(R.id.textinput)).perform(replaceText("typed into the composer"));

        onView(withId(R.id.textinput)).check(matches(withText("typed into the composer")));
    }

    /**
     * Pinned because the shell tooling records the opposite -- that {@code textSendButton} "only
     * EXISTS once the input is non-empty". That holds for the accessibility tree, not the view
     * hierarchy, so no workaround for it belongs here.
     */
    @Test
    public void theSendButtonIsPresentAndEnabledBeforeAnythingIsTyped() {
        onView(withId(R.id.textSendButton)).check(matches(isDisplayed()));
        onView(withId(R.id.textSendButton)).check(matches(isEnabled()));
    }

    /**
     * {@code IME_ACTION_SEND} reaches the same {@code sendMessage()} a tap does, without the soft
     * keyboard -- which is the largest source of flake in the adb-driven tooling.
     */
    @Test
    public void sendingWithTheImeActionStoresAndRendersTheMessage() {
        final String body = "sent with the ime action " + seed.marker();

        onView(withId(R.id.textinput)).perform(replaceText(body), pressImeActionButton());

        Wait.until(
                "the sent message to render in the conversation",
                () -> {
                    onData(withMessageBody(body))
                            .inAdapterView(withId(R.id.messages_view))
                            .check(matches(isDisplayed()));
                    return true;
                });
    }

    /** The same thing by tapping, with the keyboard dismissed first so the button is hittable. */
    @Test
    public void sendingByTappingTheButtonStoresAndRendersTheMessage() {
        final String body = "sent by tapping " + seed.marker();

        onView(withId(R.id.textinput)).perform(replaceText(body), closeSoftKeyboard());
        onView(withId(R.id.textSendButton)).perform(click());

        Wait.until(
                "the sent message to render in the conversation",
                () -> {
                    onData(withMessageBody(body))
                            .inAdapterView(withId(R.id.messages_view))
                            .check(matches(isDisplayed()));
                    return true;
                });
    }

    /** After sending, the composer is empty again -- a small thing users notice immediately. */
    @Test
    public void theComposerIsClearedAfterSending() {
        onView(withId(R.id.textinput))
                .perform(replaceText("cleared afterwards " + seed.marker()), pressImeActionButton());

        Wait.until(
                "the composer to be cleared",
                () -> {
                    onView(withId(R.id.textinput)).check(matches(withText("")));
                    return true;
                });
    }
}
