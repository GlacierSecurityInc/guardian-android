package com.glaciersecurity.glaciermessenger.entities;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link Conversation#removeMessages} drops expired disappearing messages from the in-memory list.
 *
 * <p>The reason it exists at all is a race. The expiry tick in ConversationFragment deletes the
 * expired rows on a background thread; it used to then replace the whole list with a fresh page
 * read back from the database. A message arriving between that read and the list swap is absent
 * from the snapshot, so reinstating the snapshot removed it from the conversation and the UI --
 * it stayed in the database, but stayed invisible until the chat was reopened. Removing exactly
 * the expired uuids touches nothing else, so there is no snapshot to go stale.
 *
 * <p>Robolectric rather than plain JUnit because Message's constructor reaches framework classes.
 */
@RunWith(RobolectricTestRunner.class)
public class ConversationRemoveMessagesTest {

    private static final Jid ALICE = Jid.of("alice@example.com");

    private static Conversation conversation() {
        final Conversation conversation =
                new Conversation(
                        "conversation-uuid",
                        "Alice",
                        "contact-uuid",
                        "account-uuid",
                        ALICE,
                        1_600_000_000_000L,
                        Conversation.STATUS_AVAILABLE,
                        Conversation.MODE_SINGLE,
                        Message.TIMER_NONE,
                        "");
        // Message's constructor reads the account's default timer.
        conversation.setAccount(new Account(Jid.of("me@example.com"), "no-password"));
        return conversation;
    }

    private static Message add(final Conversation conversation, final String body) {
        final Message message =
                new Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_SEND);
        conversation.add(message);
        return message;
    }

    private static List<String> bodies(final Conversation conversation) {
        final List<String> bodies = new java.util.ArrayList<>();
        for (final Message message : conversation.getMessages()) {
            bodies.add(message.getBody());
        }
        return bodies;
    }

    @Test
    public void removesOnlyTheNamedMessages() {
        final Conversation conversation = conversation();
        add(conversation, "keep me");
        final Message expired = add(conversation, "expired");
        add(conversation, "keep me too");

        conversation.removeMessages(Collections.singletonList(expired.getUuid()));

        assertThat(bodies(conversation)).containsExactly("keep me", "keep me too").inOrder();
    }

    @Test
    public void removesSeveralAtOnce() {
        final Conversation conversation = conversation();
        final Message first = add(conversation, "first");
        add(conversation, "survivor");
        final Message third = add(conversation, "third");

        conversation.removeMessages(Arrays.asList(first.getUuid(), third.getUuid()));

        assertThat(bodies(conversation)).containsExactly("survivor");
    }

    /**
     * The regression this method was introduced for: a message that arrives after the expiry tick
     * has decided what to delete must survive the removal.
     */
    @Test
    public void keepsAMessageThatArrivedAfterTheExpiredSetWasChosen() {
        final Conversation conversation = conversation();
        final Message expired = add(conversation, "expired");
        final List<String> expiredIds = Collections.singletonList(expired.getUuid());

        // Arrives between the tick choosing expiredIds and the removal running.
        add(conversation, "arrived late");

        conversation.removeMessages(expiredIds);

        assertThat(bodies(conversation)).containsExactly("arrived late");
    }

    @Test
    public void unknownUuidsAreIgnored() {
        final Conversation conversation = conversation();
        add(conversation, "only message");

        conversation.removeMessages(Arrays.asList("not-a-real-uuid", "nor-this-one"));

        assertThat(bodies(conversation)).containsExactly("only message");
    }

    @Test
    public void removingEverythingLeavesAnEmptyConversation() {
        final Conversation conversation = conversation();
        final Message first = add(conversation, "first");
        final Message second = add(conversation, "second");

        conversation.removeMessages(Arrays.asList(first.getUuid(), second.getUuid()));

        assertThat(conversation.getMessages()).isEmpty();
    }

    /**
     * {@link Message#prev} caches its answer, so a survivor that has already been drawn would go
     * on pointing at a message that is gone. Removal unties the chain to force a recompute.
     */
    @Test
    public void survivorsAreUntiedFromRemovedNeighbours() {
        final Conversation conversation = conversation();
        final Message expired = add(conversation, "expired");
        final Message survivor = add(conversation, "survivor");
        // Drawing the list populates the cached link.
        assertThat(survivor.prev()).isSameInstanceAs(expired);

        conversation.removeMessages(Collections.singletonList(expired.getUuid()));

        assertThat(survivor.prev()).isNull();
    }
}
