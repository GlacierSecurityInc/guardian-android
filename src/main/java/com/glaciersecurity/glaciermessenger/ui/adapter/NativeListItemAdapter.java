package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.NativeContact;
import com.glaciersecurity.glaciermessenger.ui.StartSmsOrCallDialog;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.makeramen.roundedimageview.RoundedImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class NativeListItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_CONTACT = 1;

	protected XmppActivity activity;
	private boolean showDynamicTags = false;
	// Flattened list: String entries are section headers, NativeContact entries are rows.
	private final List<Object> items = new ArrayList<>();
	private OnTagClickedListener mOnTagClickedListener = null;
	private OnContactActionListener mOnContactActionListener = null;

	public NativeListItemAdapter(XmppActivity activity, List<NativeContact> nativeContacts) {
		this.activity = activity;
		rebuild(nativeContacts);
	}

	@Override
	public int getItemViewType(int position) {
		return items.get(position) instanceof String ? TYPE_HEADER : TYPE_CONTACT;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == TYPE_HEADER) {
			View view = inflater.inflate(R.layout.item_contact_section_header, parent, false);
			return new HeaderViewHolder(view);
		}
		View view = inflater.inflate(R.layout.item_native_contact, parent, false);
		return new ContactViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		Object entry = items.get(position);
		if (holder instanceof HeaderViewHolder) {
			((HeaderViewHolder) holder).letter.setText((String) entry);
			return;
		}
		final NativeContact item = (NativeContact) entry;
		ContactViewHolder viewHolder = (ContactViewHolder) holder;
		viewHolder.name.setText(item.getDisplayName());
		viewHolder.number.setText(item.getPrimaryNumber());
		AvatarWorkerTask.loadAvatar(item, viewHolder.avatar, R.dimen.avatar_on_details_screen_size);
		viewHolder.itemView.setOnClickListener(view -> {
			if (mOnContactActionListener != null) {
				mOnContactActionListener.onOpenContact(item);
			} else {
				StartSmsOrCallDialog.show(activity, item);
			}
		});
		viewHolder.message.setOnClickListener(view -> {
			if (mOnContactActionListener != null) {
				mOnContactActionListener.onMessageContact(item);
			} else {
				StartSmsOrCallDialog.show(activity, item);
			}
		});
		viewHolder.call.setOnClickListener(view -> {
			if (mOnContactActionListener != null) {
				mOnContactActionListener.onCallContact(item);
			} else {
				StartSmsOrCallDialog.show(activity, item);
			}
		});
	}

	/**
	 * Rebuilds the flattened list (A–Z section headers + contacts) from a list of
	 * contacts that is expected to already be sorted by display name.
	 */
	public void updateContactList(List<NativeContact> nativeContacts) {
		rebuild(nativeContacts);
		notifyDataSetChanged();
	}

	private void rebuild(List<NativeContact> nativeContacts) {
		items.clear();
		if (nativeContacts == null) {
			return;
		}
		String currentLetter = null;
		for (NativeContact contact : nativeContacts) {
			String letter = sectionLetter(contact);
			if (!letter.equals(currentLetter)) {
				currentLetter = letter;
				items.add(letter);
			}
			items.add(contact);
		}
	}

	private String sectionLetter(NativeContact contact) {
		String name = contact.getDisplayName();
		if (name == null || name.trim().isEmpty()) {
			return "#";
		}
		char c = Character.toUpperCase(name.trim().charAt(0));
		return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	public void refreshSettings() {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		this.showDynamicTags = preferences.getBoolean(AppSettings.SHOW_DYNAMIC_TAGS, false);
	}

	public void setOnTagClickedListener(OnTagClickedListener listener) {
		this.mOnTagClickedListener = listener;
	}

	public void setOnContactActionListener(OnContactActionListener listener) {
		this.mOnContactActionListener = listener;
	}

	public interface OnTagClickedListener {
		void onTagClicked(String tag);
	}

	public interface OnContactActionListener {
		void onOpenContact(NativeContact contact);

		void onCallContact(NativeContact contact);

		void onMessageContact(NativeContact contact);
	}

	static class HeaderViewHolder extends RecyclerView.ViewHolder {
		private final TextView letter;

		HeaderViewHolder(View view) {
			super(view);
			letter = view.findViewById(R.id.section_letter);
		}
	}

	static class ContactViewHolder extends RecyclerView.ViewHolder {
		private final TextView name;
		private final RoundedImageView avatar;
		private final TextView number;
		private final ImageView message;
		private final ImageView call;

		ContactViewHolder(View view) {
			super(view);
			name = view.findViewById(R.id.contact_display_name);
			number = view.findViewById(R.id.contact_phone_number);
			avatar = view.findViewById(R.id.contact_photo);
			message = view.findViewById(R.id.action_message);
			call = view.findViewById(R.id.action_call);
		}
	}
}
