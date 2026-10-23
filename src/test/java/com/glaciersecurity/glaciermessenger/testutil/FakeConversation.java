package com.glaciersecurity.glaciermessenger.testutil;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

/**
 * The six methods {@link com.glaciersecurity.glaciermessenger.entities.Message} reaches through,
 * so a test can build a Message without a real Conversation/Account/XmppConnectionService graph.
 *
 * <p>The account is real rather than null: Message's constructor asks it for a disappearing-message
 * timer, so a null one fails before the first assertion. Message's constructor also defaults the
 * counterpart from the conversation, so a "no counterpart" case has to be built and then cleared.
 */
public class FakeConversation implements Conversational {

    private final Jid jid;
    private final int mode;
    private final Account account = new Account(Jid.of("self@example.com"), "");

    public FakeConversation(final Jid jid) {
        this(jid, MODE_SINGLE);
    }

    public FakeConversation(final Jid jid, final int mode) {
        this.jid = jid;
        this.mode = mode;
    }

    @Override
    public Account getAccount() {
        return account;
    }

    @Override
    public Contact getContact() {
        return null;
    }

    @Override
    public Jid getJid() {
        return jid;
    }

    @Override
    public int getMode() {
        return mode;
    }

    @Override
    public String getUuid() {
        return "a-conversation-uuid";
    }

    @Override
    public int getTimer() {
        return 0;
    }
}
