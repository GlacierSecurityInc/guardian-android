package com.glaciersecurity.glaciermessenger.crypto.sasl;

import javax.net.ssl.SSLSocket;

import com.glaciersecurity.glaciermessenger.entities.Account;

public class Anonymous extends SaslMechanism {

    public static final String MECHANISM = "ANONYMOUS";

    public Anonymous(final Account account) {
        super(account);
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }

    @Override
    public String getClientFirstMessage(final SSLSocket sslSocket) {
        return "";
    }
}
