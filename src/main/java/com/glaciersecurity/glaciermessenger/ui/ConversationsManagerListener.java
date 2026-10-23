package com.glaciersecurity.glaciermessenger.ui;

import com.twilio.conversations.ConversationsClient;
import com.twilio.conversations.Message;

public interface ConversationsManagerListener {
    void receivedNewMessage(Message message);
    void messageSentCallback(String sid);
    void messageSendFailed(String sid);
    void reloadMessages();
    void showList();
    void notifyMessages();
    void onConversationsClientConnectionStateChange(ConversationsClient.ConnectionState state);
    void onMoreMessagesLoaded(int newMessageCount, boolean hasMore);
}
