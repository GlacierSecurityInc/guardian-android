package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.helper.widget.Flow;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.makeramen.roundedimageview.RoundedImageView;
import com.google.android.material.color.MaterialColors;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ItemContactBinding;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.ListItem;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.utils.XEP0392Helper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import java.util.List;

public class ListItemAdapter extends ArrayAdapter<ListItem> {

	protected XmppActivity activity;
	private boolean showDynamicTags = false;
	private OnTagClickedListener mOnTagClickedListener = null;
	private final View.OnClickListener onTagTvClick = view -> {
		if (view instanceof TextView tv && mOnTagClickedListener != null) {
			final String tag = tv.getText().toString();
			mOnTagClickedListener.onTagClicked(tag);
		}
	};

	public ListItemAdapter(XmppActivity activity, List<ListItem> objects) {
		super(activity, 0, objects);
		this.activity = activity;
	}


	public void refreshSettings() {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		this.showDynamicTags = preferences.getBoolean(AppSettings.SHOW_DYNAMIC_TAGS, false);
	}

	@NonNull
	@Override
	public View getView(int position, View view, @NonNull ViewGroup parent) {
		LayoutInflater inflater = activity.getLayoutInflater();
		final ListItem item = getItem(position);
		ViewHolder viewHolder;
		if (view == null) {
			final ItemContactBinding binding = DataBindingUtil.inflate(inflater,R.layout.item_contact,parent,false);
			viewHolder = ViewHolder.get(binding);
			view = binding.getRoot();
		} else {
			viewHolder = (ViewHolder) view.getTag();
		}
		if (view.isActivated()) {
		}
		final List<ListItem.Tag> tags = item.getTags();
		final boolean hasMetaTags;
		if (item instanceof Contact contact) {
			hasMetaTags = contact.isBlocked() || contact.getShownStatus() != Presence.Status.OFFLINE;
		} else {
			hasMetaTags = false;
		}
		if ((tags.isEmpty() && !hasMetaTags) || !this.showDynamicTags) {
			viewHolder.tags.setVisibility(View.GONE);
		} else {
			viewHolder.tags.setVisibility(View.VISIBLE);
			viewHolder.tags.removeViews(1, viewHolder.tags.getChildCount() - 1);
			final ImmutableList.Builder<Integer> viewIdBuilder = new ImmutableList.Builder<>();
			for (final ListItem.Tag tag : tags) {
				final String name = tag.getName();
				final TextView tv = (TextView) inflater.inflate(R.layout.item_tag, viewHolder.tags, false);
				tv.setText(name);
				tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(getContext(),XEP0392Helper.rgbFromNick(name))));
				tv.setOnClickListener(this.onTagTvClick);
				final int id = ViewCompat.generateViewId();
				tv.setId(id);
				viewIdBuilder.add(id);
				viewHolder.tags.addView(tv);
			}
			if (item instanceof Contact contact) {
				if (contact.isBlocked()) {
					final TextView tv =
							(TextView)
									inflater.inflate(
											R.layout.item_tag, viewHolder.tags, false);
					tv.setText(R.string.blocked);
					tv.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.harmonizeWithPrimary(tv.getContext(),ContextCompat.getColor(tv.getContext(),R.color.gray_800))));
					final int id = ViewCompat.generateViewId();
					tv.setId(id);
					viewIdBuilder.add(id);
					viewHolder.tags.addView(tv);
				} else {
                    final Presence.Status status = contact.getShownStatus();
                    if (status != Presence.Status.OFFLINE) {
					final TextView tv =
							(TextView)
									inflater.inflate(
											R.layout.item_tag, viewHolder.tags, false);
						UIHelper.setStatus(tv, status);
						final int id = ViewCompat.generateViewId();
						tv.setId(id);
						viewIdBuilder.add(id);
						viewHolder.tags.addView(tv);
                    }
                }
			}
			viewHolder.flowWidget.setReferencedIds(Ints.toArray(viewIdBuilder.build()));
		}
		final Jid jid = item.getJid();
		if (jid != null) {
			viewHolder.jid.setVisibility(View.VISIBLE);
			viewHolder.jid.setText("@"+jid.getEscapedLocal());
		} else {
			viewHolder.jid.setVisibility(View.GONE);
		}
		viewHolder.name.setText(item.getDisplayName());
		AvatarWorkerTask.loadAvatar(item, viewHolder.avatar, R.dimen.avatar);
		if (item instanceof Contact contact) {
			setAvatarStatusBorder(viewHolder.avatar, contact.getShownStatus());
		} else {
			setAvatarStatusBorder(viewHolder.avatar, Presence.Status.OFFLINE);
		}
		return view;
	}

	private void setAvatarStatusBorder(final RoundedImageView avatar, final Presence.Status status) {
		if (status == null || status == Presence.Status.OFFLINE) {
			avatar.setBorderWidth(0f);
			return;
		}
		final int borderColor =
				MaterialColors.harmonizeWithPrimary(
						avatar.getContext(),
						ContextCompat.getColor(avatar.getContext(), getStatusColor(status)));
		avatar.setBorderColor(borderColor);
		avatar.setBorderWidth(avatar.getContext().getResources().getDisplayMetrics().density * 3f);
	}

	private int getStatusColor(final Presence.Status status) {
		return switch (status) {
			case CHAT, ONLINE -> R.color.green_800;
			case AWAY -> R.color.amber_800;
			case XA -> R.color.orange_800;
			case DND -> R.color.red_800;
			case OFFLINE -> R.color.gray_800;
			default -> R.color.gray_800;
		};
	}
	public void setOnTagClickedListener(OnTagClickedListener listener) {
		this.mOnTagClickedListener = listener;
	}


	public interface OnTagClickedListener {
		void onTagClicked(String tag);
	}

	private static class ViewHolder {
		private TextView name;
		private TextView jid;
		private RoundedImageView avatar;
		private ConstraintLayout tags;
		private Flow flowWidget;
		private View privateGroupIndicator;

		private ViewHolder() {

		}

		public static ViewHolder get(final ItemContactBinding binding) {
			ViewHolder viewHolder = new ViewHolder();
			viewHolder.name = binding.contactDisplayName;
			viewHolder.jid = binding.contactJid;
			viewHolder.avatar = binding.contactPhoto;
			viewHolder.tags = binding.tags;
			viewHolder.flowWidget = binding.flowWidget;
			viewHolder.privateGroupIndicator = binding.privateGroupIndicator;
			binding.getRoot().setTag(viewHolder);
			return viewHolder;
		}
	}

}
