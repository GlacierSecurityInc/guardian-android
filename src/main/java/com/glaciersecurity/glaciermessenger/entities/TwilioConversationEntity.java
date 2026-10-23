package com.glaciersecurity.glaciermessenger.entities;

import android.database.Cursor;

import com.twilio.conversations.Conversation;

import java.util.Objects;

public class TwilioConversationEntity{
    public static final String TABLE_SMS_CONVERSATION = "SMSConversation";
    public static final String COLUMN_SID = "conversation_sid";
    public static final String COLUMN_SMS_PROFILE_ID = "sms_profile_id";
    public static final String COLUMN_FRIENDLY_NAME = "friendly_name";
    public static final String COLUMN_IDENTITY_NUMBER = "identity_number";
    public static final String COLUMN_UNIQUE_NAME = "unique_name";
    public static final String COLUMN_STATUS = "status";
    public static final String COLUMN_STATE = "state";
    public static final String COLUMN_DATE_CREATED = "date_created";
    public static final String COLUMN_CREATED_BY = "create_by";
    public static final String COLUMN_DATE_UPDATED = "date_updated";
    public static final String COLUMN_IS_NEW = "is_new";
    public static final String COLUMN_IS_UNREAD = "is_unread";
    public static final String COLUMN_UNREAD_COUNT = "unread_count";
    public static final String COLUMN_LATEST_MESSAGE = "latestMessage";
    public static final String COLUMN_LATEST_MESSAGE_IS_MEDIA = "latestMessageIsMedia";
    public static final String COLUMN_LATEST_MESSAGE_ID = "latestMessageId";
    public static final String COLUMN_LATEST_MESSAGE_DATE = "latestMessageDate";

    private String conv_sid;
    private String friendlyName;
    private String identityNumber;
    private String lastMessage;
    private String lastMessageDate;
    private Boolean lastMessageIsMedia;
    private int unreadCount;

    public TwilioConversationEntity(String sid, String friendlyName, String identityNumber,
                                    String lastMessage, String lastMessageDate, Boolean lastMessageIsMedia) {
        this.conv_sid = sid;
        this.friendlyName = friendlyName;
        this.identityNumber = identityNumber;
        this.lastMessage = lastMessage;
        this.lastMessageDate = lastMessageDate;
        this.lastMessageIsMedia = lastMessageIsMedia;
        this.unreadCount = 0;
    }

    public TwilioConversationEntity(String sid, String friendlyName, String identityNumber,
                                    String lastMessage, String lastMessageDate, Boolean lastMessageIsMedia,
                                    int unreadCount) {
        this.conv_sid = sid;
        this.friendlyName = friendlyName;
        this.identityNumber = identityNumber;
        this.lastMessage = lastMessage;
        this.lastMessageDate = lastMessageDate;
        this.lastMessageIsMedia = lastMessageIsMedia;
        this.unreadCount = unreadCount;
    }


    public TwilioConversationEntity(String sid) {
        this.conv_sid = sid;
        this.unreadCount = 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if ((o instanceof TwilioConversationEntity that)) {
            return Objects.equals(conv_sid, that.conv_sid);
        }
        if ((o instanceof Conversation that)) {
            return Objects.equals(conv_sid, that.getSid());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(conv_sid);
    }

    public static TwilioConversationEntity fromCursor(Cursor cursor) {
        int isMediaIndex = cursor.getColumnIndexOrThrow(COLUMN_LATEST_MESSAGE_IS_MEDIA);
        Boolean isMedia = cursor.isNull(isMediaIndex) ? null : cursor.getInt(isMediaIndex) != 0;
        int unreadIndex = cursor.getColumnIndex(COLUMN_UNREAD_COUNT);
        int unreadCount = unreadIndex >= 0 ? cursor.getInt(unreadIndex) : 0;
        return new TwilioConversationEntity(cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_FRIENDLY_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_IDENTITY_NUMBER)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LATEST_MESSAGE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_LATEST_MESSAGE_DATE)),
                isMedia,
                unreadCount);
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public Boolean getLastMessageIsMedia() {
        return lastMessageIsMedia;
    }

    public void setLastMessageIsMedia(Boolean lastMessageIsMedia) {
        this.lastMessageIsMedia = lastMessageIsMedia;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public void setFriendlyName(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    public String getConv_sid() {
        return conv_sid;
    }

    public void setConv_sid(String conv_sid) {
        this.conv_sid = conv_sid;
    }

    public String getIdentityNumber() {
        return identityNumber;
    }

    public void setIdentityNumber(String identityNumber) {
        this.identityNumber = identityNumber;
    }

    public String getLastMessageDate() {
        return lastMessageDate;
    }

    public void setLastMessageDate(String lastMessageDate) {
        this.lastMessageDate = lastMessageDate;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }
}
