package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.app.PendingIntent;
import android.content.IntentSender;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.ContextCompat;

import com.makeramen.roundedimageview.RoundedImageView;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;

import org.openintents.openpgp.util.OpenPgpUtils;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.PgpEngine;
import com.glaciersecurity.glaciermessenger.databinding.ItemContactBinding;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.ConferenceDetailsActivity;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.ui.util.MucDetailsContextMenuHelper;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

public class UserAdapter extends ListAdapter<MucOptions.User, UserAdapter.UserViewHolder> implements View.OnCreateContextMenuListener {

    static final DiffUtil.ItemCallback<MucOptions.User> DIFF = new DiffUtil.ItemCallback<MucOptions.User>() {
        @Override
        public boolean areItemsTheSame(@NonNull MucOptions.User a, @NonNull MucOptions.User b) {
            final Jid fullA = a.getFullJid();
            final Jid fullB = b.getFullJid();
            final Jid realA = a.getRealJid();
            final Jid realB = b.getRealJid();
            if (fullA != null && fullB != null) {
                return fullA.equals(fullB);
            } else if (realA != null && realB != null) {
                return realA.equals(realB);
            } else {
                return false;
            }
        }

        @Override
        public boolean areContentsTheSame(@NonNull MucOptions.User a, @NonNull MucOptions.User b) {
            return a.equals(b);
        }
    };
    private final boolean advancedMode;
    private MucOptions.User selectedUser = null;

    public UserAdapter(final boolean advancedMode) {
        super(DIFF);
        this.advancedMode = advancedMode;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int position) {
        return new UserViewHolder(DataBindingUtil.inflate(LayoutInflater.from(viewGroup.getContext()), R.layout.item_contact, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder viewHolder, int position) {
        final MucOptions.User user = getItem(position);
        AvatarWorkerTask.loadAvatar(user, viewHolder.binding.contactPhoto, R.dimen.avatar);
        viewHolder.binding.getRoot().setOnClickListener(
                v -> {
                    final XmppActivity activity = XmppActivity.find(v);
                    if (activity == null) {
                        return;
                    }
                    final Contact contact = user.getContact();
                    if (contact != null) {
                        activity.switchToContactDetails(contact);
                    } else {
                        activity.highlightInMuc(user.getConversation(), user.getName());
                    }
                });
        viewHolder.binding.getRoot().setTag(user);
        viewHolder.binding.getRoot().setOnCreateContextMenuListener(this);
        viewHolder.binding.getRoot().setOnLongClickListener(v -> {
            selectedUser = user;
            return false;
        });
        final String name = user.getName();
        final Contact contact = user.getContact();
        if (contact != null) {
            final String displayName = contact.getDisplayName();
            viewHolder.binding.contactDisplayName.setText(displayName);
            if (name != null && !name.equals(displayName)) {
                viewHolder.binding.contactJid.setText(String.format("%s \u2022 %s", name, ConferenceDetailsActivity.getStatus(viewHolder.binding.getRoot().getContext(), user, advancedMode)));
            } else {
                viewHolder.binding.contactJid.setText(ConferenceDetailsActivity.getStatus(viewHolder.binding.getRoot().getContext(), user, advancedMode));
            }
            setAvatarStatusBorder(viewHolder.binding.contactPhoto, contact.getShownStatus());
        } else {
            viewHolder.binding.contactDisplayName.setText(Strings.nullToEmpty(name));
            viewHolder.binding.contactJid.setText(ConferenceDetailsActivity.getStatus(viewHolder.binding.getRoot().getContext(), user, advancedMode));
            setAvatarStatusBorder(viewHolder.binding.contactPhoto, Presence.Status.OFFLINE);
        }
        if (advancedMode && user.getPgpKeyId() != 0) {
            viewHolder.binding.key.setVisibility(View.VISIBLE);
            viewHolder.binding.key.setOnClickListener(v -> {
                final XmppActivity activity = XmppActivity.find(v);
                final XmppConnectionService service = activity == null ? null : activity.xmppConnectionService;
                final PgpEngine pgpEngine = service == null ? null : service.getPgpEngine();
                if (pgpEngine != null) {
                    PendingIntent intent = pgpEngine.getIntentForKey(user.getPgpKeyId());
                    if (intent != null) {
                        try {
                            activity.startIntentSenderForResult(intent.getIntentSender(), 0, null, 0, 0, 0, Compatibility.pgpStartIntentSenderOptions());
                        } catch (IntentSender.SendIntentException ignored) {

                        }
                    }
                }
            });
            viewHolder.binding.key.setText(OpenPgpUtils.convertKeyIdToHex(user.getPgpKeyId()));
        } else {
            viewHolder.binding.key.setVisibility(View.GONE);
        }


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


    public MucOptions.User getSelectedUser() {
        return selectedUser;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MucDetailsContextMenuHelper.onCreateContextMenu(menu,v);
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {

        private final ItemContactBinding binding;

        private UserViewHolder(ItemContactBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
