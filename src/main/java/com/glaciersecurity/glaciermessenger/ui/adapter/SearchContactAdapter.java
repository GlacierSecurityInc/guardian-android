package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.makeramen.roundedimageview.RoundedImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders contact search results for the modernized {@code SearchActivity}.
 * Highlights the matched term in accent and exposes a per-row "message" action.
 * A display cap lets the "All" scope show only a few contacts inline while the
 * "Contacts" scope shows the full list.
 */
public class SearchContactAdapter extends RecyclerView.Adapter<SearchContactAdapter.SearchContactViewHolder> {

	public interface OnContactClicked {
		void onContactClicked(Contact contact);
	}

	private final List<Contact> contacts = new ArrayList<>();
	private String highlight = "";
	private int displayLimit = Integer.MAX_VALUE;
	private final OnContactClicked listener;

	public SearchContactAdapter(final OnContactClicked listener) {
		this.listener = listener;
	}

	public void update(final List<Contact> items, final String highlight, final int displayLimit) {
		this.contacts.clear();
		if (items != null) {
			this.contacts.addAll(items);
		}
		this.highlight = highlight == null ? "" : highlight.trim();
		this.displayLimit = Math.max(0, displayLimit);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public SearchContactViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
		final View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_search_contact, parent, false);
		return new SearchContactViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull final SearchContactViewHolder holder, final int position) {
		final Contact contact = contacts.get(position);
		final Context context = holder.itemView.getContext();

		AvatarWorkerTask.loadAvatar(contact, holder.photo, R.dimen.avatar);
		holder.name.setText(highlighted(context, contact.getDisplayName()));

		final String jid = localPart(contact);
		holder.jid.setText(highlighted(context, jid));
		holder.shield.setVisibility(View.VISIBLE);

		final View.OnClickListener open = v -> {
			if (listener != null) {
				listener.onContactClicked(contact);
			}
		};
		holder.itemView.setOnClickListener(open);
		holder.message.setOnClickListener(open);
	}

	@Override
	public int getItemCount() {
		return Math.min(contacts.size(), displayLimit);
	}

	/** Show only the local part of the JID (no domain). */
	private String localPart(final Contact contact) {
		if (contact.getJid() == null) {
			return "";
		}
		final String local = contact.getJid().getLocal();
		return local == null || local.isEmpty()
				? contact.getJid().asBareJid().toString()
				: local;
	}

	private SpannableString highlighted(final Context context, final String text) {
		final SpannableString spannable = new SpannableString(text == null ? "" : text);
		if (highlight.isEmpty() || text == null || text.isEmpty()) {
			return spannable;
		}
		final int accent = ContextCompat.getColor(context, R.color.glacier_button);
		final String haystack = text.toLowerCase(Locale.US);
		final String needle = highlight.toLowerCase(Locale.US);
		int index = haystack.indexOf(needle);
		while (index >= 0) {
			final int end = index + needle.length();
			spannable.setSpan(new ForegroundColorSpan(accent), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			spannable.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			index = haystack.indexOf(needle, end);
		}
		return spannable;
	}

	static class SearchContactViewHolder extends RecyclerView.ViewHolder {
		final RoundedImageView photo;
		final TextView name;
		final TextView jid;
		final View shield;
		final ImageButton message;

		SearchContactViewHolder(@NonNull final View itemView) {
			super(itemView);
			photo = itemView.findViewById(R.id.contact_photo);
			name = itemView.findViewById(R.id.contact_display_name);
			jid = itemView.findViewById(R.id.contact_jid);
			shield = itemView.findViewById(R.id.contact_shield);
			message = itemView.findViewById(R.id.contact_message);
		}
	}
}
