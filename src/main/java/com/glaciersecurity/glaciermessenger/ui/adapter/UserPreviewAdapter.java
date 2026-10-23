package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ItemUserPreviewBinding;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.ui.util.MucDetailsContextMenuHelper;

public class UserPreviewAdapter extends ListAdapter<MucOptions.User, UserPreviewAdapter.UserPreviewViewHolder>
        implements View.OnCreateContextMenuListener {

    private MucOptions.User selectedUser = null;

    public UserPreviewAdapter() {
        super(UserAdapter.DIFF);
    }

    @NonNull
    @Override
    public UserPreviewViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int position) {
        return new UserPreviewViewHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(viewGroup.getContext()),
                        R.layout.item_user_preview,
                        viewGroup,
                        false));
    }

    @Override
    public void onBindViewHolder(@NonNull UserPreviewViewHolder viewHolder, int position) {
        final MucOptions.User user = getItem(position);
        AvatarWorkerTask.loadAvatar(user, viewHolder.binding.avatar, R.dimen.media_size);
        viewHolder
                .binding
                .getRoot()
                .setOnClickListener(
                        v -> {
                            final XmppActivity activity = XmppActivity.find(v);
                            if (activity == null) {
                                return;
                            }
                            final var contact = user.getContact();
                            if (contact != null) {
                                activity.switchToContactDetails(contact);
                            } else {
                                activity.highlightInMuc(user.getConversation(), user.getName());
                            }
                        });
        viewHolder.binding.getRoot().setOnCreateContextMenuListener(this);
        viewHolder.binding.getRoot().setTag(user);
        viewHolder
                .binding
                .getRoot()
                .setOnLongClickListener(
                        v -> {
                            selectedUser = user;
                            return false;
                        });
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MucDetailsContextMenuHelper.onCreateContextMenu(menu, v);
    }

    public MucOptions.User getSelectedUser() {
        return selectedUser;
    }

    static class UserPreviewViewHolder extends RecyclerView.ViewHolder {

        private final ItemUserPreviewBinding binding;

        private UserPreviewViewHolder(final ItemUserPreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
