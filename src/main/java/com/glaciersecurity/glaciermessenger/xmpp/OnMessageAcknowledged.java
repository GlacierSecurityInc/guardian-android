package com.glaciersecurity.glaciermessenger.xmpp;

import com.glaciersecurity.glaciermessenger.entities.Account;

public interface OnMessageAcknowledged {
    boolean onMessageAcknowledged(Account account, Jid to, String id);
}
