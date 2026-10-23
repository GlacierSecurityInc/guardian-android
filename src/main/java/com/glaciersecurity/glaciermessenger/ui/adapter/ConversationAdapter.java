package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.common.base.Optional;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ItemConversationBinding;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.ui.ConversationFragment;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.utils.IrregularUnicodeDetector;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.OngoingRtpSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ConversationAdapter
        extends RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder> {

    private final XmppActivity activity;
    private final List<Conversation> conversations;
    // Immutable snapshots of the content currently shown for each row. The service hands back the
    // same cached Conversation instances on every refresh, so the adapter's list and an incoming
    // new list share object references and the live objects are mutated in place. Diffing those
    // references against themselves always reports "unchanged"; we diff against these snapshots
    // instead so a new latest message (or unread/mute/pin change) actually triggers a rebind.
    private List<ConversationSnapshot> snapshots = new ArrayList<>();
    private OnConversationClickListener listener;

    public ConversationAdapter(XmppActivity activity, List<Conversation> conversations) {
        this.activity = activity;
        this.conversations = conversations;
        this.snapshots = snapshotsOf(conversations);
    }

    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ConversationViewHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(parent.getContext()),
                        R.layout.item_conversation,
                        parent,
                        false));
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder viewHolder, int position) {
        Conversation conversation = conversations.get(position);
        if (conversation == null) {
            return;
        }
        // Reset swipe button state so recycled holders don't bleed visibility, icons, or listeners from prior rows
        viewHolder.binding.btnDelete.setVisibility(View.VISIBLE);
        viewHolder.binding.btnDeleteLeft.setVisibility(View.VISIBLE);
        viewHolder.binding.btnMute.setImageResource(R.drawable.ic_notifications_off_24dp);
        viewHolder.binding.btnMuteLeft.setImageResource(R.drawable.ic_notifications_off_24dp);
        viewHolder.binding.btnPin.setImageResource(R.drawable.ic_push_pin_24dp);
        viewHolder.binding.btnPinLeft.setImageResource(R.drawable.ic_push_pin_24dp);
        viewHolder.binding.btnMute.setOnClickListener(null);
        viewHolder.binding.btnMute.setClickable(false);
        viewHolder.binding.btnPin.setOnClickListener(null);
        viewHolder.binding.btnPin.setClickable(false);
        viewHolder.binding.btnDelete.setOnClickListener(null);
        viewHolder.binding.btnDelete.setClickable(false);
        viewHolder.binding.btnMuteLeft.setOnClickListener(null);
        viewHolder.binding.btnMuteLeft.setClickable(false);
        viewHolder.binding.btnPinLeft.setOnClickListener(null);
        viewHolder.binding.btnPinLeft.setClickable(false);
        viewHolder.binding.btnDeleteLeft.setOnClickListener(null);
        viewHolder.binding.btnDeleteLeft.setClickable(false);
        viewHolder.binding.frame.setTranslationX(0f);
        CharSequence name = conversation.getName();
        if (name instanceof Jid) {
            viewHolder.binding.conversationName.setText(
                    IrregularUnicodeDetector.style(activity, (Jid) name));
        } else {
            viewHolder.binding.conversationName.setText(name);
        }

        final Message message = conversation.getLatestMessage();
        final int status = message.getStatus();
        final int unreadCount = conversation.unreadCount();

        // Unread rows get a subtle accent tint on the row background.
        if (conversation == ConversationFragment.getConversation(activity)) {
            viewHolder.binding.frame.setBackgroundResource(
                    R.drawable.background_selected_item_conversation);
        } else if (unreadCount > 0) {
            // glacier_accent_soft is intentionally translucent. Painting it directly
            // as the frame background leaves the frame ~90% transparent, which lets the
            // swipe-action buttons sitting behind the frame bleed through and appear on
            // top of unread rows. Composite the tint over the opaque surface color so the
            // frame stays opaque (and the accent still reads), matching read rows.
            final int surface = MaterialColors.getColor(
                    viewHolder.binding.frame,
                    com.google.android.material.R.attr.colorSurface);
            final int accent = ContextCompat.getColor(
                    viewHolder.binding.frame.getContext(),
                    R.color.glacier_accent_soft);
            viewHolder.binding.frame.setBackgroundColor(
                    androidx.core.graphics.ColorUtils.compositeColors(accent, surface));
        } else {
            viewHolder.binding.frame.setBackgroundColor(
                    MaterialColors.getColor(
                            viewHolder.binding.frame,
                            com.google.android.material.R.attr.colorSurface));
        }

        final boolean isRead = conversation.isRead();
        final @DrawableRes Integer messageStatusDrawable =
                MessageAdapter.getMessageStatusAsDrawable(message, status);
        if (message.getType() == Message.TYPE_RTP_SESSION) {
            viewHolder.binding.messageStatus.setVisibility(View.GONE);
        } else if (messageStatusDrawable == null) {
            if (status <= Message.STATUS_RECEIVED) {
                viewHolder.binding.messageStatus.setVisibility(View.GONE);
            } else {
                viewHolder.binding.messageStatus.setVisibility(View.INVISIBLE);
            }
        } else {
            viewHolder.binding.messageStatus.setImageResource(messageStatusDrawable);
            if (status == Message.STATUS_SEND_DISPLAYED) {
                viewHolder.binding.messageStatus.setImageResource(R.drawable.ic_done_all_bold_24dp);
                ImageViewCompat.setImageTintList(
                        viewHolder.binding.messageStatus,
                        ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        viewHolder.binding.messageStatus,
                                        android.R.attr.colorPrimary)));
            } else {
                ImageViewCompat.setImageTintList(
                        viewHolder.binding.messageStatus,
                        ColorStateList.valueOf(
                                MaterialColors.getColor(
                                        viewHolder.binding.messageStatus,
                                        android.R.attr.colorControlNormal)));
            }
            viewHolder.binding.messageStatus.setVisibility(View.VISIBLE);
        }
        final Conversation.Draft draft = isRead ? conversation.getDraft() : null;
        if (unreadCount > 0) {
            viewHolder.binding.unreadCount.setVisibility(View.VISIBLE);
            viewHolder.binding.unreadCount.setUnreadCount(unreadCount);
        } else {
            viewHolder.binding.unreadCount.setVisibility(View.GONE);
        }

        if (isRead) {
            viewHolder.binding.conversationName.setTypeface(null, Typeface.NORMAL);
        } else {
            viewHolder.binding.conversationName.setTypeface(null, Typeface.BOLD);
        }

        if (draft != null) {
            viewHolder.binding.conversationLastmsgImg.setVisibility(View.GONE);
            viewHolder.binding.conversationLastmsg.setText(draft.getMessage());
            viewHolder.binding.senderName.setText(R.string.draft);
            viewHolder.binding.senderName.setVisibility(View.VISIBLE);
            viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.NORMAL);
            viewHolder.binding.senderName.setTypeface(null, Typeface.ITALIC);
        } else {
            final boolean fileAvailable = !message.isDeleted();
            final boolean showPreviewText;
            if (fileAvailable
                    && (message.isFileOrImage()
                            || message.treatAsDownloadable()
                            || message.isGeoUri())) {
                final var attachment = Attachment.of(message);
                final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
                showPreviewText = false;
                viewHolder.binding.conversationLastmsgImg.setImageResource(imageResource);
                viewHolder.binding.conversationLastmsgImg.setVisibility(View.VISIBLE);
            } else {
                viewHolder.binding.conversationLastmsgImg.setVisibility(View.GONE);
                showPreviewText = true;
            }
            final Pair<CharSequence, Boolean> preview =
                    UIHelper.getMessagePreview(
                            activity,
                            message,
                            viewHolder.binding.conversationLastmsg.getCurrentTextColor());
            if (showPreviewText) {
                viewHolder.binding.conversationLastmsg.setText(UIHelper.shorten(preview.first));
            } else {
                viewHolder.binding.conversationLastmsgImg.setContentDescription(preview.first);
            }
            viewHolder.binding.conversationLastmsg.setVisibility(
                    showPreviewText ? View.VISIBLE : View.GONE);
            if (preview.second) {
                if (isRead) {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.ITALIC);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.NORMAL);
                } else {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.BOLD_ITALIC);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.BOLD);
                }
            } else {
                if (isRead) {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.NORMAL);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.NORMAL);
                } else {
                    viewHolder.binding.conversationLastmsg.setTypeface(null, Typeface.BOLD);
                    viewHolder.binding.senderName.setTypeface(null, Typeface.BOLD);
                }
            }
            if (status == Message.STATUS_RECEIVED) {
                if (conversation.getMode() == Conversation.MODE_MULTI) {
                    viewHolder.binding.senderName.setVisibility(View.VISIBLE);
                    final var displayName = UIHelper.getMessageDisplayName(message);
                    final var displayNameParts = displayName.split("\\s+");
                    // Skip when nickname only consists of blank chars
                    if (displayNameParts.length == 0) {
                        viewHolder.binding.senderName.setText(String.format("%s:", displayName));
                    } else {
                        viewHolder.binding.senderName.setText(
                                String.format("%s:", displayNameParts[0]));
                    }
                } else {
                    viewHolder.binding.senderName.setVisibility(View.GONE);
                }
            } else if (message.getType() != Message.TYPE_STATUS) {
                viewHolder.binding.senderName.setVisibility(View.VISIBLE);
                viewHolder.binding.senderName.setText(
                        String.format("%s:", activity.getString(R.string.me)));
            } else {
                viewHolder.binding.senderName.setVisibility(View.GONE);
            }
        }

        final Optional<OngoingRtpSession> ongoingCall;
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ongoingCall = Optional.absent();
        } else {
            ongoingCall =
                    activity.xmppConnectionService
                            .getJingleConnectionManager()
                            .getOngoingRtpConnection(conversation.getContact());
        }

        if (ongoingCall.isPresent()) {
            viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
            viewHolder.binding.notificationStatus.setImageResource(
                    R.drawable.ic_phone_in_talk_24dp);
        } else {
            final long muted_till =
                    conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
            if (muted_till == Long.MAX_VALUE) {
                viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
                viewHolder.binding.notificationStatus.setImageResource(
                        R.drawable.ic_notifications_off_24dp);
            } else if (muted_till >= System.currentTimeMillis()) {
                viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
                viewHolder.binding.notificationStatus.setImageResource(
                        R.drawable.ic_notifications_paused_24dp);
            } else if (conversation.alwaysNotify()) {
                viewHolder.binding.notificationStatus.setVisibility(View.GONE);
            } else {
                viewHolder.binding.notificationStatus.setVisibility(View.VISIBLE);
                viewHolder.binding.notificationStatus.setImageResource(
                        R.drawable.ic_notifications_none_24dp);
            }
        }

        long timestamp;
        if (draft != null) {
            timestamp = draft.getTimestamp();
        } else {
            timestamp = conversation.getLatestMessage().getTimeSent();
        }
        viewHolder.binding.pinnedOnTop.setVisibility(
                conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)
                        ? View.VISIBLE
                        : View.GONE);
        viewHolder.binding.conversationLastupdate.setText(
                UIHelper.readableTimeDifference(activity, timestamp));
        AvatarWorkerTask.loadAvatar(
                conversation,
                viewHolder.binding.conversationImage,
                R.dimen.avatar_on_conversation_overview);
        if (conversation.getMode() == Conversation.MODE_MULTI
                && conversation.getMucOptions().membersOnly()) {
            viewHolder.binding.privateGroupIndicator.setVisibility(View.VISIBLE);
        } else {
            viewHolder.binding.privateGroupIndicator.setVisibility(View.GONE);
        }
        setAvatarStatusBorder(viewHolder, conversation);
        viewHolder.itemView.setOnClickListener(v -> listener.onConversationClick(conversation));
    }

    private void setAvatarStatusBorder(
            @NonNull final ConversationViewHolder viewHolder,
            @NonNull final Conversation conversation) {
        if (conversation.getMode() != Conversation.MODE_SINGLE) {
            viewHolder.binding.conversationImage.setBorderWidth(0f);
            return;
        }
        final Presence.Status status =
                conversation.getContact() == null
                        ? Presence.Status.OFFLINE
                        : conversation.getContact().getShownStatus();
        if (status == Presence.Status.OFFLINE) {
            viewHolder.binding.conversationImage.setBorderWidth(0f);
            return;
        }
        final int borderColor =
                MaterialColors.harmonizeWithPrimary(
                        viewHolder.binding.conversationImage.getContext(),
                        ContextCompat.getColor(
                                viewHolder.binding.conversationImage.getContext(),
                                getStatusColor(status)));
        viewHolder.binding.conversationImage.setBorderColor(borderColor);
        viewHolder.binding.conversationImage.setBorderWidth(
                viewHolder.binding.conversationImage
                                .getContext()
                                .getResources()
                                .getDisplayMetrics()
                                .density
                        * 2f);
    }

    private int getStatusColor(@NonNull final Presence.Status status) {
        return switch (status) {
            case CHAT, ONLINE -> R.color.green_800;
            case AWAY -> R.color.amber_800;
            case XA -> R.color.orange_800;
            case DND -> R.color.red_800;
            case OFFLINE -> R.color.gray_800;
            default -> R.color.gray_800;
        };
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    public void setConversationClickListener(OnConversationClickListener listener) {
        this.listener = listener;
    }

    public void refreshConversations(final List<Conversation> newList) {
        final List<ConversationSnapshot> oldSnapshots = this.snapshots;
        final List<ConversationSnapshot> newSnapshots = snapshotsOf(newList);
        final DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldSnapshots.size(); }
            @Override public int getNewListSize() { return newSnapshots.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return Objects.equals(oldSnapshots.get(oldPos).uuid, newSnapshots.get(newPos).uuid);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return oldSnapshots.get(oldPos).sameContentAs(newSnapshots.get(newPos));
            }
        }, true);
        conversations.clear();
        conversations.addAll(newList);
        this.snapshots = newSnapshots;
        result.dispatchUpdatesTo(this);
    }

    private static List<ConversationSnapshot> snapshotsOf(final List<Conversation> source) {
        final List<ConversationSnapshot> result = new ArrayList<>(source.size());
        for (final Conversation conversation : source) {
            result.add(new ConversationSnapshot(conversation));
        }
        return result;
    }

    // Captures the content-bearing state of a Conversation at refresh time so a later refresh can
    // detect changes even though the underlying Conversation object is shared and mutated in place.
    private static final class ConversationSnapshot {
        final String uuid;
        final long mutedTill;
        final boolean pinned;
        final int unreadCount;
        final boolean read;
        final String latestMessageUuid;
        final int latestMessageStatus;
        final long latestMessageTimeSent;
        final String draftMessage;
        final long draftTimestamp;
        final Presence.Status presenceStatus;

        ConversationSnapshot(final Conversation conversation) {
            this.uuid = conversation.getUuid();
            this.mutedTill = conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0);
            this.pinned = conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false);
            this.unreadCount = conversation.unreadCount();
            this.read = conversation.isRead();
            final Message latest = conversation.getLatestMessage();
            this.latestMessageUuid = latest == null ? null : latest.getUuid();
            this.latestMessageStatus = latest == null ? Message.STATUS_UNSEND : latest.getStatus();
            this.latestMessageTimeSent = latest == null ? 0 : latest.getTimeSent();
            final Conversation.Draft draft = this.read ? conversation.getDraft() : null;
            this.draftMessage = draft == null ? null : draft.getMessage();
            this.draftTimestamp = draft == null ? 0 : draft.getTimestamp();
            // The avatar status ring is drawn from the contact's presence, which changes without
            // touching anything else on the row, so it has to take part in the diff as well.
            this.presenceStatus =
                    conversation.getMode() != Conversation.MODE_SINGLE
                                    || conversation.getContact() == null
                            ? Presence.Status.OFFLINE
                            : conversation.getContact().getShownStatus();
        }

        boolean sameContentAs(final ConversationSnapshot other) {
            return mutedTill == other.mutedTill
                    && pinned == other.pinned
                    && unreadCount == other.unreadCount
                    && read == other.read
                    && latestMessageStatus == other.latestMessageStatus
                    && latestMessageTimeSent == other.latestMessageTimeSent
                    && draftTimestamp == other.draftTimestamp
                    && presenceStatus == other.presenceStatus
                    && Objects.equals(latestMessageUuid, other.latestMessageUuid)
                    && Objects.equals(draftMessage, other.draftMessage);
        }
    }

    public void insert(Conversation c, int position) {
        conversations.add(position, c);
        snapshots.add(position, new ConversationSnapshot(c));
        notifyDataSetChanged();
    }

    public void remove(Conversation conversation, int position) {
        conversations.remove(conversation);
        if (position >= 0 && position < snapshots.size()) {
            snapshots.remove(position);
        }
        notifyItemRemoved(position);
    }

    public interface OnConversationClickListener {
        void onConversationClick(Conversation conversation);
    }

    public static class ConversationViewHolder extends RecyclerView.ViewHolder {
        public final ItemConversationBinding binding;

        private ConversationViewHolder(final ItemConversationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
