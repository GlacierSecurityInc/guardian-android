package com.glaciersecurity.glaciermessenger.ui;

import com.twilio.conversations.Conversation;

public interface OnSMSConversationClickListener {
    void OnSmsConversationClick(Conversation conversation);
    void OnSmsContactNameClick(String conv_name);
    void OnSmsSidClick(String conv_sid, String friendlyName);

}
