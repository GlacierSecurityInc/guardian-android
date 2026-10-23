package com.glaciersecurity.glaciermessenger.xmpp.jingle;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import java.util.Set;

public interface OngoingRtpSession {
    Account getAccount();

    Jid getWith();

    String getSessionId();

    CallIntegration getCallIntegration();

    Set<Media> getMedia();
}
