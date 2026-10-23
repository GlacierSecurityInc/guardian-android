package com.glaciersecurity.glaciermessenger.ui.journey.support;

import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;

import android.view.View;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Message;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/** Matchers for the two lists this tier has to reason about. They are not the same kind of list. */
public final class JourneyMatchers {

    private JourneyMatchers() {}

    /**
     * For {@code onData} against {@code @id/messages_view}, a {@code ListView} over an
     * {@code ArrayAdapter<Message>}. Match on the body, never on {@code @id/message_body} (it is
     * included by both row layouts, so it matches once per visible row) and never on position
     * (status and date-separator rows move them).
     */
    public static Matcher<Object> withMessageBody(final String body) {
        return new TypeSafeMatcher<Object>() {
            @Override
            protected boolean matchesSafely(final Object item) {
                return item instanceof Message && body.equals(((Message) item).getBody());
            }

            @Override
            public void describeTo(final Description description) {
                description.appendText("a Message whose body is ").appendValue(body);
            }
        };
    }

    /**
     * For {@code onView} against {@code @id/list}, a {@code RecyclerView} -- the opposite case
     * from above. Matches {@code @id/frame} because the row root has no id;
     * {@code ConversationAdapter} listens on {@code itemView}, and a click on the non-clickable
     * frame bubbles up to it.
     */
    public static Matcher<View> conversationRow(final String name) {
        return allOf(
                withId(R.id.frame),
                hasDescendant(allOf(withId(R.id.conversation_name), withText(name))));
    }
}
