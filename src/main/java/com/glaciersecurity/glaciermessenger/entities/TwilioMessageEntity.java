package com.glaciersecurity.glaciermessenger.entities;

import android.database.Cursor;

public class TwilioMessageEntity{
    public static final String TABLE_SMS_MESSAGE = "SMSMessage";
    public static final String COLUMN_MESSAGE_SID = "message_sid";
    public static final String COLUMN_CONVERSATION_ID = "conversationId";
    public static final String COLUMN_TWILIO_SID = "twilioSid";
    public static final String COLUMN_MESSAGE_DATE = "messageDate";
    public static final String COLUMN_IS_READ = "isRead";
    public static final String COLUMN_IS_INCOMING = "isIncoming";
    public static final String COLUMN_MEDIA_ITEM_UNIQUE_ID = "mediaItemUniqueId";
    public static final String COLUMN_TRANSFER_PROGRESS = "transferProgress";
    public static final String COLUMN_MSG_TEXT = "msgText";
    public static final String COLUMN_MSG_SENDER_ID = "msgSenderId";
    public static final String COLUMN_MSG_SENDER_DISPLAY_NAME = "msgSenderDisplayName";
    public static final String COLUMN_MODIFIED = "modified";

    public final String messageSid;
    public final String conversationSid;
    public final String messageDate;
    public final String body;
    public final String author;

    public TwilioMessageEntity(String messageSid, String conversationSid,
                               String messageDate, String body, String author) {
        this.messageSid = messageSid;
        this.conversationSid = conversationSid;
        this.messageDate = messageDate;
        this.body = body;
        this.author = author;
    }

    public static TwilioMessageEntity fromCursor(Cursor cursor) {
        return new TwilioMessageEntity(
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_SID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_DATE)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MSG_TEXT)),
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MSG_SENDER_DISPLAY_NAME))
        );
    }
}
