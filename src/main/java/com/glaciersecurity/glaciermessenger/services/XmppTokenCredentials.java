package com.glaciersecurity.glaciermessenger.services;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.GlacierUserEntity;
import com.google.common.base.Strings;

/**
 * The Glacier backend is JWT-only: {@code xmpp_token} from glacier-users is the one XMPP credential
 * the client accepts from it. There is deliberately no password fallback -- an account without a
 * token fails auth and goes through the normal login-failure / reconnect-error handling.
 */
public final class XmppTokenCredentials {

    public enum LoginCheck {
        OK,
        MISSING_JID,
        MISSING_TOKEN
    }

    private XmppTokenCredentials() {}

    public static LoginCheck checkLogin(final GlacierUserEntity user) {
        if (user == null
                || Strings.isNullOrEmpty(user.getMessengerId())
                || Strings.isNullOrEmpty(user.getOrganization())) {
            return LoginCheck.MISSING_JID;
        }
        if (Strings.isNullOrEmpty(user.getXmppToken())) {
            return LoginCheck.MISSING_TOKEN;
        }
        return LoginCheck.OK;
    }

    /**
     * Stores {@code token} on the account if there is one. Without one the account is left exactly
     * as it was: neither the stored token nor the password is touched.
     *
     * @return true if a token was applied
     */
    public static boolean applyToken(final Account account, final String token) {
        if (Strings.isNullOrEmpty(token)) {
            return false;
        }
        account.setToken(token);
        return true;
    }
}
