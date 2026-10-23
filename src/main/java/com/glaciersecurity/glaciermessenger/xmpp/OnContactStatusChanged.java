package com.glaciersecurity.glaciermessenger.xmpp;

import com.glaciersecurity.glaciermessenger.entities.Contact;

public interface OnContactStatusChanged {
	void onContactStatusChanged(final Contact contact, final boolean online);
}
