package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.ui.OnSMSConversationClickListener;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.utils.DateParser;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.LogRedactor;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.SmsMessagePreviewUtils;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.twilio.conversations.Conversation;

import java.text.ParseException;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class SmsConversationsAdapter extends RecyclerView.Adapter<SmsConversationsAdapter.ConversationsRecyclerViewHolder> {


    public interface OnSmsConvAdapterLoadedListener {
        void onSmsConvAdapterLoaded();
    }
    public ArrayList<TwilioConversationEntity> twilioConversations;
    public OnSMSConversationClickListener conversationClickListener;
    public TwilioConversationEntity selectedConversation;
    public PhoneModuleActivity activity;
    public ViewGroup viewGroup;
    private OnSmsConvAdapterLoadedListener loadedListener;



    public SmsConversationsAdapter(PhoneModuleActivity activity, OnSMSConversationClickListener conversationClickListener, ArrayList<TwilioConversationEntity> conversations, OnSmsConvAdapterLoadedListener loadedListener) {
        this.twilioConversations = conversations;
        this.conversationClickListener = conversationClickListener;
        this.activity = activity;
        this.loadedListener = loadedListener;
    }

    public void setConversationsObjList(ArrayList<TwilioConversationEntity> conversations) {
        this.twilioConversations = conversations;
        notifyDataSetChanged();
        if (loadedListener != null) {
            loadedListener.onSmsConvAdapterLoaded();
        }
    }

    public void remove(TwilioConversationEntity conversationEntity, int position) {
        activity.conversationsManager.deleteExistingConversation(conversationEntity.getConv_sid());
        twilioConversations.remove(position);
        notifyItemRemoved(position);
    }

    public void removeAllForProxy(SmsProfile sp) {
        activity.conversationsManager.deleteAllExistingConversationForProxy(sp);
        activity.xmppConnectionService.deleteAllTwilioConversationForProxy(sp);
        twilioConversations.clear();
        notifyDataSetChanged();

    }




    public int getConversationPosition(Conversation conversation){

       return twilioConversations.indexOf(new TwilioConversationEntity(conversation.getSid()));
    }


    @NonNull
    @Override
    public ConversationsRecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.sms_list_row, parent, false);
        viewGroup = parent;
        return new ConversationsRecyclerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationsRecyclerViewHolder holder, @SuppressLint("RecyclerView") int position) {
        selectedConversation = twilioConversations.get(position);

        String selectedSid = selectedConversation.getConv_sid();
        String selectedFriendlyName = selectedConversation.getFriendlyName();
        CharSequence displayName = NativeContactsUtils.getContactNameIfExists(
                activity.xmppConnectionService,
                selectedConversation.getFriendlyName());
        if (displayName == null || displayName.toString().trim().isEmpty()) {
            displayName = selectedConversation.getFriendlyName();
        }
        holder.conversation_name.setText(displayName);

        String lastMessage = selectedConversation.getLastMessage();
        Boolean isMediaFlag = selectedConversation.getLastMessageIsMedia();
        if (lastMessage == null || lastMessage.trim().isEmpty()) {
            if (activity.xmppConnectionService != null
                    && activity.xmppConnectionService.getSmsInfo() != null) {
                lastMessage = activity.xmppConnectionService.getSmsInfo().conv_last_msg.get(selectedSid);
                if (isMediaFlag == null) {
                    isMediaFlag = activity.xmppConnectionService.getSmsInfo()
                            .conv_last_msg_is_media.get(selectedSid);
                }
            }
        }
        if (lastMessage == null || lastMessage.trim().isEmpty()) {
            lastMessage = "";
        }
        SmsMessagePreviewUtils.SmsPreview preview =
                SmsMessagePreviewUtils.fromStoredPreview(lastMessage, isMediaFlag);
        if (preview.isMedia()) {
            holder.conversation_lastmsg_img.setVisibility(View.VISIBLE);
        } else {
            holder.conversation_lastmsg_img.setVisibility(View.GONE);
        }
        holder.conversation_lastmsg.setText(SmsMessagePreviewUtils.formatPreviewText(preview));
        holder.dateText.setText(formatDate(selectedConversation.getLastMessageDate()));
        holder.avatar_circle.setBackgroundTintList(ColorStateList.valueOf(UIHelper.getColorForSMS(selectedFriendlyName)));
        String senderName = null;
        int unreadCount = selectedConversation.getUnreadCount();
        if (activity.xmppConnectionService != null
                && activity.xmppConnectionService.getSmsInfo() != null) {
            senderName =
                    activity.xmppConnectionService.getSmsInfo().conv_last_msg_sent.get(selectedSid);
        }
        if (isMessageFromMe(senderName)) {
            holder.sender_name.setText(String.format("%s: ", activity.getString(R.string.me)));
            holder.sender_name.setVisibility(View.VISIBLE);
        } else {
            holder.sender_name.setVisibility(View.GONE);
        }
        if (unreadCount > 0) {
            holder.unreadcount.setUnreadCount(unreadCount);
            holder.unreadcount.setVisibility(View.VISIBLE);
        } else {
            holder.unreadcount.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                conversationClickListener.OnSmsSidClick(selectedSid, selectedFriendlyName);
            }
        });

    }
    private boolean isMessageFromMe(String senderName) {
        if (senderName == null) {
            return false;
        }
        if (activity.account != null && senderName.equals(activity.account.getUsername())) {
            return true;
        }
        if (activity.selectedProfile != null) {
            return PhoneUtils.areSameNumber(senderName, activity.selectedProfile.getUnformattedNumber());
        }
        return false;
    }

    private String formatDate(String lastMessageDate)  {
                if (lastMessageDate == null) {
                    return "";
                }
                try {
                    Date date = DateParser.parse(lastMessageDate);

                    SimpleDateFormat formatter = new SimpleDateFormat("MMM d hh:mm a");
                    formatter.setTimeZone(TimeZone.getDefault());

                    return formatter.format(date);
                } catch (ParseException e) {
                    Log.e(Config.LOGTAG, "Parse exception " + e);
                    return "";
                }

        }

    @Override
    public int getItemCount() {
        if (twilioConversations == null){
            return 0;
        }
        return twilioConversations.size();
    }

    public static class ConversationsRecyclerViewHolder extends RecyclerView.ViewHolder {
        TextView conversation_name, sender_name, conversation_lastmsg, dateText, conv_Sid;
        RelativeLayout avatar_circle;
        ImageView conversation_lastmsg_img;
        com.glaciersecurity.glaciermessenger.ui.widget.UnreadCountCustomView unreadcount;
        public ConversationsRecyclerViewHolder(View view) {
            super(view);
            conversation_name = view.findViewById(R.id.conversation_name);
            avatar_circle = view.findViewById(R.id.avatar_circle);
            sender_name = view.findViewById(R.id.sender_name);
            conversation_lastmsg = view.findViewById(R.id.conversation_lastmsg);
            conversation_lastmsg_img = view.findViewById(R.id.conversation_lastmsg_img);
            dateText = view.findViewById(R.id.conversation_lastupdate);
            unreadcount = view.findViewById(R.id.conversation_unread_count);
        }
    }
}
