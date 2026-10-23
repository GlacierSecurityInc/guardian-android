package com.glaciersecurity.glaciermessenger.services;

import android.content.Intent;

import com.glaciersecurity.glaciermessenger.Config;

public class QuickConversationsService extends AbstractQuickConversationsService {

    QuickConversationsService(XmppConnectionService xmppConnectionService) {
        super(xmppConnectionService);
    }

    @Override
    public void considerSync() {

    }

    @Override
    public void signalAccountStateChange() {

    }

    @Override
    public boolean isSynchronizing() {
        return false;
    }

    @Override
    public void considerSyncBackground() {

    }

    @Override
    public void handleSmsReceived() {
    }
}