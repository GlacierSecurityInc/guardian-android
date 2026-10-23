package com.glaciersecurity.glaciermessenger.xmpp.jingle;

import com.glaciersecurity.glaciermessenger.entities.Account;
import im.conversations.android.xmpp.model.stanza.Iq;

public interface OnJinglePacketReceived {
	void onJinglePacketReceived(Account account, Iq packet);
}
