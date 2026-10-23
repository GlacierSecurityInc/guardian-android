package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.app.Activity;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ItemChannelDiscoveryBinding;
import com.glaciersecurity.glaciermessenger.entities.Room;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;


public class ChannelSearchResultAdapter extends ListAdapter<Room, ChannelSearchResultAdapter.ChannelSearchResultViewHolder> implements View.OnCreateContextMenuListener {

    private static final DiffUtil.ItemCallback<Room> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull Room a, @NonNull Room b) {
            return a.address != null && a.address.equals(b.address);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Room a, @NonNull Room b) {
            return a.equals(b);
        }
    };
    private OnChannelSearchResultSelected listener;
    private Room current;

    public ChannelSearchResultAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public ChannelSearchResultViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        return new ChannelSearchResultViewHolder(DataBindingUtil.inflate(LayoutInflater.from(viewGroup.getContext()), R.layout.item_channel_discovery, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelSearchResultViewHolder viewHolder, int position) {
        final Room searchResult = getItem(position);
        viewHolder.binding.name.setText(searchResult.getName());
        final String description = searchResult.getDescription();
        if (TextUtils.isEmpty(description)) {
            viewHolder.binding.description.setVisibility(View.GONE);
        } else {
            viewHolder.binding.description.setText(description);
            viewHolder.binding.description.setVisibility(View.VISIBLE);
        }
        viewHolder.binding.language.setVisibility(View.GONE);
        final Jid room = searchResult.getRoom();
        viewHolder.binding.room.setText(room != null ? "#" + room.asBareJid().getEscapedLocal() : "");
        if (searchResult.isMembersOnly()) {
            viewHolder.binding.privateIndicator.setVisibility(View.VISIBLE);
        } else {
            viewHolder.binding.privateIndicator.setVisibility(View.GONE);
        }
        AvatarWorkerTask.loadAvatar(searchResult, viewHolder.binding.avatar, R.dimen.avatar);
        final View root = viewHolder.binding.getRoot();
        root.setTag(searchResult);
        root.setOnClickListener(v -> listener.onChannelSearchResult(searchResult));
        root.setOnCreateContextMenuListener(this);
    }

    public void setOnChannelSearchResultSelectedListener(OnChannelSearchResultSelected listener) {
        this.listener = listener;
    }

    public Room getCurrent() {
        return this.current;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        final Activity activity = XmppActivity.find(v);
        final Object tag = v.getTag();
        if (activity != null && tag instanceof Room) {
            activity.getMenuInflater().inflate(R.menu.channel_item_context, menu);
            this.current = (Room) tag;
        }
    }

    public interface OnChannelSearchResultSelected {
        void onChannelSearchResult(Room result);
    }

    public static class ChannelSearchResultViewHolder extends RecyclerView.ViewHolder {

        private final ItemChannelDiscoveryBinding binding;

        private ChannelSearchResultViewHolder(final ItemChannelDiscoveryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
