package com.glaciersecurity.glaciermessenger.testutil;

import android.content.Context;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import java.util.ArrayList;

import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep;

/**
 * Fixtures for the instrumented suite.
 *
 * <p>Every value here comes from a reserved range -- {@code example.com} (RFC 2606),
 * {@code 202-555-01xx}, {@code 192.0.2.0/24}. Nothing in this package may name a real Glacier
 * account, org or host: these fixtures end up in CI logs and in anything that scrapes the repo,
 * and a real JID in a fixture is also an invitation to point a test at the real backend. The
 * same rule and table are written up in {@code src/test/README.md} for the JVM tier.
 */
public final class DeviceTestFixtures {

    public static final String DOMAIN = "example.com";

    private DeviceTestFixtures() {}

    /**
     * jxmpp performs no stringprep until an implementation registers itself, and in the app that
     * happens in {@code XmppConnectionService.onCreate} -- which an instrumented test does not
     * run any more than a JVM one does. Without this, {@code Jid.of} accepts addresses it should
     * reject and any validation assertion passes vacuously.
     */
    public static void setUpStringprep() {
        LibIdnXmppStringprep.setup();
    }

    /**
     * An account with a fresh UUID, so two calls never collide in the accounts table.
     *
     * <p>The core region has to be set explicitly. {@code new Account(jid, password)} leaves it
     * null -- only the constructor that takes a {@code GlacierUserEntity} defaults it to an empty
     * list -- and {@code Account.getContentValues} calls {@code coreRegion.toString()}
     * unconditionally, so persisting an account built the short way throws NPE. Real sign-in goes
     * through {@code LoginAccountActivity}, which uses the long constructor; the short one is
     * still reachable from {@code XmppConnectionService}'s client-certificate path, which does
     * call {@code createAccount} on it.
     */
    public static Account newAccount(final String local) {
        final Account account = new Account(Jid.of(local, DOMAIN, null), "not-a-real-password");
        account.setCoreRegion(new ArrayList<>());
        return account;
    }

    public static Account newAccount() {
        return newAccount("testuser");
    }

    /** A one-to-one conversation belonging to {@code account}. */
    public static Conversation newConversation(final Account account, final String with) {
        return new Conversation(with, account, Jid.of(with, DOMAIN, null), Conversational.MODE_SINGLE);
    }

    public static Message newMessage(final Conversation conversation, final String body) {
        return new Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
    }

    /**
     * An account and conversation already written to the database, which is what most persistence
     * assertions need before they can say anything.
     */
    public static Conversation persistedConversation(final Context context, final String with) {
        final DatabaseBackend db = DatabaseBackend.getInstance(context);
        final Account account = newAccount();
        db.createAccount(account);
        final Conversation conversation = newConversation(account, with);
        db.createConversation(conversation);
        return conversation;
    }
}
