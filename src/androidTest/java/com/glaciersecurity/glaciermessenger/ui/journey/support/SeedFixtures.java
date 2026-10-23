package com.glaciersecurity.glaciermessenger.ui.journey.support;

import android.content.Context;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.testutil.DeviceTestFixtures;

import java.util.UUID;

/**
 * The state a hermetic journey test starts from, and the means to take it away again.
 *
 * <p>The account is seeded <b>disabled</b>: that is what makes the tier hermetic rather than
 * merely offline, and the launcher routes to the conversation list anyway. See "Journey tests" in
 * {@code src/androidTest/README.md}.
 */
public final class SeedFixtures {

    private final Context context;
    private final DatabaseBackend db;

    /**
     * Under {@code -PcoverageReport} the orchestrator stops clearing data between methods, so the
     * database carries rows from earlier tests and an assertion could be satisfied by one of
     * them. Same device, same reasoning as {@code ConversationPersistenceTest}'s marker.
     */
    private final String marker;

    private Account account;
    private Conversation conversation;

    public SeedFixtures(final Context context) {
        this.context = context;
        this.db = DatabaseBackend.getInstance(context);
        this.marker = "mark" + UUID.randomUUID().toString().replace("-", "");
    }

    public String marker() {
        return marker;
    }

    public Account account() {
        return account;
    }

    public Conversation conversation() {
        return conversation;
    }

    /** One disabled account, enough to route the launcher at the conversation list. */
    public Account seedDisabledAccount() {
        account = DeviceTestFixtures.newAccount();
        // The service's first pass queries with this; it is null-safe, but seeding it keeps a
        // hermetic test from depending on that.
        account.setOrganization("example-org");
        account.setOption(Account.OPTION_DISABLED, true);
        db.createAccount(account);
        return account;
    }

    /** A one-to-one conversation on the seeded account. Seeds the account if it is not there yet. */
    public Conversation seedConversation(final String with) {
        if (account == null) {
            seedDisabledAccount();
        }
        conversation = DeviceTestFixtures.newConversation(account, with);
        db.createConversation(conversation);
        return conversation;
    }

    /** Messages carrying this test's marker, oldest first. Returns the bodies as stored. */
    public String[] seedMessages(final String... bodies) {
        final String[] stored = new String[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            stored[i] = bodies[i] + " " + marker;
            final Message message = DeviceTestFixtures.newMessage(conversation, stored[i]);
            db.createMessage(message);
        }
        return stored;
    }

    /** Leaves the store as this test found it, whether or not {@code clearPackageData} is on. */
    public void cleanUp() {
        if (conversation != null) {
            db.deleteMessagesInConversation(conversation);
        }
        if (account != null) {
            db.deleteAccount(account);
        }
    }
}
