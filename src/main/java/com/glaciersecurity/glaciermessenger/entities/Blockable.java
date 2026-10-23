package com.glaciersecurity.glaciermessenger.entities;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

public interface Blockable {
	boolean isBlocked();
	boolean isDomainBlocked();
	Jid getBlockedJid();
	Jid getJid();
	Account getAccount();
}
