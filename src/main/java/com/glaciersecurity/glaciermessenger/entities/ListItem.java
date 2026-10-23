package com.glaciersecurity.glaciermessenger.entities;

import android.content.Context;

import java.util.List;

import com.glaciersecurity.glaciermessenger.services.AvatarService;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;


public interface ListItem extends Comparable<ListItem>, AvatarService.Avatarable {
	String getDisplayName();

	Jid getJid();

	List<Tag> getTags();

	final class Tag {
		private final String name;

		public Tag(final String name) {
			this.name = name;
		}

		public String getName() {
			return this.name;
		}
	}

	boolean match(Context context, final String needle);
}
