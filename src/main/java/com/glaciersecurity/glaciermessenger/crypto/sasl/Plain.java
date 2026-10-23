package com.glaciersecurity.glaciermessenger.crypto.sasl;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;

import com.glaciersecurity.glaciermessenger.entities.Account;

public class Plain extends SaslMechanism {

    public static final String MECHANISM = "PLAIN";

    public Plain(final Account account) {
        super(account);
    }

    public static String getMessage(String username, String password) {
        final String message = '\u0000' + username + '\u0000' + password;
        return Base64.encodeToString(message.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }

    @Override
    public String getClientFirstMessage(final SSLSocket sslSocket) {
        // ejabberd's JWT auth rides on SASL PLAIN: JID local part as username, JWT as the
        // "password". Prefer the token when present; otherwise fall back to the real password.
        final String secret = account.hasToken() ? account.getToken() : account.getPassword();
        return getMessage(account.getUsername(), secret);
    }
}
