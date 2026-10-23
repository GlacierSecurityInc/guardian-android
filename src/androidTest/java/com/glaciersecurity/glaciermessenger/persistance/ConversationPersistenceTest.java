package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.testutil.DeviceTestFixtures;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Conversations and messages through the real store: writes, reads, cascade deletes and the FTS4
 * search index.
 *
 * <p>The search assertions are the point of doing this on a device. {@code messages_index} is an
 * external-content FTS4 table, so the text it matches against lives in {@code messages} and the
 * index only holds row ids -- kept in step by three triggers. Nothing about that arrangement is
 * visible from the Java side: a test that inserts through {@code createMessage} and searches
 * through {@code getMessageSearchCursor} is the only thing that notices when a trigger stops
 * firing, and the symptom in the field is simply that search quietly returns nothing.
 *
 * <p><strong>Every test here states its own preconditions rather than relying on
 * {@code clearPackageData}.</strong> The orchestrator's {@code pm clear} does hand each method an
 * empty database, but the setting is not always on: a coverage run has to turn it off, because
 * the JaCoCo agent writes into the very data directory {@code pm clear} removes (see
 * {@code src/androidTest/README.md}). The conversation-scoped assertions were already safe --
 * {@link #seed} makes a fresh account and conversation per test and the queries filter on them.
 * The search assertions were not, because {@code messages_index} is global: they are scoped by
 * {@link #marker} instead, and {@link #removeWhatThisTestWrote} puts the store back afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class ConversationPersistenceTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private DatabaseBackend db;
    private Account account;
    private Conversation conversation;

    @BeforeClass
    public static void setUpStringprep() {
        DeviceTestFixtures.setUpStringprep();
    }

    /**
     * A token unique to one test method, mixed into the body of every message that test expects
     * to find again and ANDed into every query {@link #search} runs, so a search assertion means
     * "of the messages <em>this</em> test wrote" on a database that may already hold rows from
     * earlier ones. Alphanumeric on purpose: the index is built with {@code tokenize='unicode61'},
     * so punctuation would split it into several tokens, and {@code FtsUtils.toMatchString}
     * appends {@code *} to every term -- 122 random bits keeps one marker's prefix from matching
     * another.
     */
    private String marker;

    @Before
    public void seed() {
        db = DatabaseBackend.getInstance(context);
        marker = "mark" + UUID.randomUUID().toString().replace("-", "");
        account = DeviceTestFixtures.newAccount();
        db.createAccount(account);
        conversation = DeviceTestFixtures.newConversation(account, "alice");
        db.createConversation(conversation);
    }

    /** Leaves the store as this test found it, whether or not {@code clearPackageData} is on. */
    @After
    public void removeWhatThisTestWrote() {
        if (db == null) {
            return;
        }
        db.deleteMessagesInConversation(conversation);
        db.deleteAccount(account);
    }

    private Message persistMessage(final String body) {
        final Message message = DeviceTestFixtures.newMessage(conversation, body);
        db.createMessage(message);
        return message;
    }

    /** Has FTS4 check messages_index against messages; a stale posting makes this throw. */
    private void assertSearchIndexIsIntact() {
        try {
            db.getWritableDatabase()
                    .execSQL("INSERT INTO messages_index(messages_index) VALUES('integrity-check')");
        } catch (final SQLiteException e) {
            throw new AssertionError(
                    "messages_index failed FTS4 integrity-check -- a delete left stale postings"
                            + " behind",
                    e);
        }
    }

    /**
     * A message this test can find again: {@code body} with {@link #marker} appended. Returns the
     * {@link Message}, whose {@code getBody()} is the stored text a search hands back.
     */
    private Message persistSearchable(final String body) {
        return persistMessage(body + " " + marker);
    }

    /**
     * Searches for {@code term} among the messages <em>this test</em> wrote.
     *
     * <p>{@code getMessageSearchCursor} takes a list of terms and {@code FtsUtils.toMatchString}
     * joins them with a space, which FTS4 reads as AND -- so adding the marker narrows the query
     * without changing what {@code term} itself has to match. The scoping lives here rather than
     * in each test so a search assertion added later cannot forget it.
     */
    private List<String> search(final String term) {
        return runSearch(Arrays.asList(term, marker));
    }

    /** Searches the whole store, ignoring {@link #marker}. Only for asserting global emptiness. */
    private List<String> searchUnscoped(final String term) {
        return runSearch(Collections.singletonList(term));
    }

    private List<String> runSearch(final List<String> terms) {
        final List<String> bodies = new ArrayList<>();
        try (Cursor cursor = db.getMessageSearchCursor(terms, null)) {
            final int bodyIndex = cursor.getColumnIndex(Message.BODY);
            while (cursor.moveToNext()) {
                bodies.add(cursor.getString(bodyIndex));
            }
        }
        return bodies;
    }

    @Test
    public void aConversationIsFoundBackByAccountAndJid() {
        final Conversation found = db.findConversation(account, conversation.getJid().asBareJid());

        assertThat(found).isNotNull();
        assertThat(found.getUuid()).isEqualTo(conversation.getUuid());
    }

    @Test
    public void messagesComeBackInTheOrderTheyWereSent() {
        persistMessage("first");
        persistMessage("second");
        persistMessage("third");

        final List<Message> messages = db.getMessages(conversation, 50);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getBody()).isEqualTo("first");
        assertThat(messages.get(2).getBody()).isEqualTo("third");
    }

    @Test
    public void theLimitArgumentKeepsTheMostRecentMessages() {
        persistMessage("first");
        persistMessage("second");
        persistMessage("third");

        final List<Message> messages = db.getMessages(conversation, 2);

        // The query orders by time DESC and then reverses, so a limit takes the newest.
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getBody()).isEqualTo("third");
    }

    @Test
    public void deletingAnAccountCascadesToItsConversationsAndMessages() {
        persistMessage("first");

        db.deleteAccount(account);

        assertWithMessage("conversations left behind after the account was deleted")
                .that(db.findConversation(account, conversation.getJid().asBareJid()))
                .isNull();
        try (Cursor cursor =
                db.getReadableDatabase()
                        .rawQuery(
                                "SELECT count(*) FROM messages WHERE conversationUuid=?",
                                new String[] {conversation.getUuid()})) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertWithMessage("messages orphaned by the account delete")
                    .that(cursor.getInt(0))
                    .isEqualTo(0);
        }
    }

    @Test
    public void deletingMessagesInAConversationLeavesTheConversation() {
        persistMessage("first");

        db.deleteMessagesInConversation(conversation);

        assertThat(db.getMessages(conversation, 50)).isEmpty();
        assertThat(db.findConversation(account, conversation.getJid().asBareJid())).isNotNull();
    }

    // ---- full text search --------------------------------------------------------------

    @Test
    public void aMessageIsSearchableAsSoonAsItIsInserted() {
        final Message message = persistSearchable("the quick brown fox");

        assertThat(search("brown")).containsExactly(message.getBody());
    }

    @Test
    public void searchDoesNotMatchAMessageInAnotherWord() {
        persistSearchable("the quick brown fox");

        assertThat(search("row")).isEmpty();
    }

    @Test
    public void searchIsCaseInsensitive() {
        final Message message = persistSearchable("the quick brown fox");

        assertThat(search("BROWN")).containsExactly(message.getBody());
    }

    @Test
    public void aDeletedMessageDropsOutOfTheSearchIndex() {
        final Message message = persistSearchable("the quick brown fox");
        assertThat(search("brown")).containsExactly(message.getBody());

        db.deleteMessagesInConversation(conversation);

        assertWithMessage("message delete trigger did not clean the FTS index")
                .that(search("brown"))
                .isEmpty();
        // The marker is unique to this test, so once its one message is gone nothing in the
        // store carries it -- which an unscoped search can state outright.
        assertWithMessage("the deleted message is still reachable without the marker")
                .that(searchUnscoped(marker))
                .isEmpty();
        // The search joins on rowid, so while nothing holds the freed rowid a stale posting
        // matches nothing and the assertions above pass either way. These two catch it.
        assertSearchIndexIsIntact();
        persistSearchable("a slow green turtle");
        assertWithMessage("a stale posting resurfaced under a new message's rowid")
                .that(search("brown"))
                .isEmpty();
    }

    @Test
    public void deletingAnAccountDropsItsMessagesOutOfTheSearchIndex() {
        final Message message = persistSearchable("the quick brown fox");
        assertThat(search("brown")).containsExactly(message.getBody());

        db.deleteAccount(account);

        // A second account to insert under. Its message takes the rowid the deleted one freed
        // (SQLite assigns max(rowid)+1), so a stale posting now points at this body. It carries
        // the same marker, so the scoped search below still reaches it.
        account = DeviceTestFixtures.newAccount("second");
        db.createAccount(account);
        conversation = DeviceTestFixtures.newConversation(account, "bob");
        db.createConversation(conversation);
        final Message replacement = persistSearchable("a slow green turtle");

        assertWithMessage("stale messages_index postings survived the account delete")
                .that(search("brown"))
                .isEmpty();
        assertThat(search("turtle")).containsExactly(replacement.getBody());
        assertSearchIndexIsIntact();
    }

    @Test
    public void rebuildMessagesIndexLeavesEveryMessageSearchable() {
        // Nothing in the tree calls this -- it is a manual repair hook -- so without a test a
        // rename or a broken rebuild statement would rot here unnoticed.
        final Message message = persistSearchable("the quick brown fox");

        db.rebuildMessagesIndex();

        assertThat(search("brown")).containsExactly(message.getBody());
        assertSearchIndexIsIntact();
    }

    @Test
    public void editingAMessageBodyUpdatesTheSearchIndex() {
        final Message message = persistSearchable("the quick brown fox");
        final String edited = "a slow green turtle " + marker;
        message.setBody(edited);

        db.updateMessage(message, true);

        assertWithMessage("after_message_update trigger did not reindex the new body")
                .that(search("turtle"))
                .containsExactly(edited);
        assertThat(search("brown")).isEmpty();
    }

    @Test
    public void expireOldMessagesRemovesOnlyMessagesOlderThanTheCutoff() {
        final Message old = DeviceTestFixtures.newMessage(conversation, "ancient");
        old.setTime(1_000L);
        db.createMessage(old);
        persistMessage("recent");

        db.expireOldMessages(2_000L);

        final List<Message> remaining = db.getMessages(conversation, 50);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getBody()).isEqualTo("recent");
    }
}
