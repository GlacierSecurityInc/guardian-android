package com.glaciersecurity.glaciermessenger.crypto.sasl;

import javax.net.ssl.SSLSocket;

import com.glaciersecurity.glaciermessenger.entities.Account;

public abstract class ScramPlusMechanism extends ScramMechanism implements ChannelBindingMechanism {

    ScramPlusMechanism(Account account, ChannelBinding channelBinding) {
        super(account, channelBinding);
    }

    @Override
    protected byte[] getChannelBindingData(final SSLSocket sslSocket)
            throws AuthenticationException {
        return ChannelBindingMechanism.getChannelBindingData(sslSocket, this.channelBinding);
    }

    @Override
    public ChannelBinding getChannelBinding() {
        return this.channelBinding;
    }
}
