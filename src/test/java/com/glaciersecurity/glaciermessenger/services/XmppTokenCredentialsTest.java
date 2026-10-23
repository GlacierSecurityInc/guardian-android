package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.GlacierUserEntity;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiResponses;
import com.glaciersecurity.glaciermessenger.services.XmppTokenCredentials.LoginCheck;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.google.gson.Gson;

import org.junit.Test;

/**
 * The backend is JWT-only. These pin that xmpp_token is the one XMPP credential taken from
 * glacier-users, and that a response without one never turns into password auth -- the old
 * fallback cleared the JWT and reconnected expecting a password that did not exist.
 */
public class XmppTokenCredentialsTest {

    private static final String TOKEN = "header.payload.signature";

    private static Account account() {
        // GlacierGuardian.getContext() is null here, so set/clearToken only touch the field.
        return spy(new Account(Jid.ofEscaped("testuser@example.com"), ""));
    }

    private static GlacierUserEntity user(final String messengerId, final String token) {
        return GlacierUserEntity.builder()
                .organization("acme")
                .username("testuser")
                // The entity unboxes these two; a null one is an NPE in build().
                .addToCoreVpn(false)
                .addUserToPurchaseNumbers(false)
                .messengerId(messengerId)
                .xmppToken(token)
                .build();
    }

    // ---- refresh (profile refresh and SASL-failure re-auth) -----------------------------

    @Test
    public void aRefreshWithATokenStoresIt() {
        final Account account = account();

        assertThat(XmppTokenCredentials.applyToken(account, TOKEN)).isTrue();

        verify(account).setToken(TOKEN);
        assertThat(account.getToken()).isEqualTo(TOKEN);
        verify(account, never()).setPassword(any());
        verify(account, never()).clearToken();
    }

    @Test
    public void aRefreshWithoutATokenLeavesTheAccountAlone() {
        final Account account = account();
        account.setToken("previous.jwt.value");
        clearInvocations(account);

        assertThat(XmppTokenCredentials.applyToken(account, null)).isFalse();
        assertThat(XmppTokenCredentials.applyToken(account, "")).isFalse();

        verify(account, never()).setPassword(any());
        verify(account, never()).clearToken();
        verify(account, never()).setToken(any());
        assertThat(account.getToken()).isEqualTo("previous.jwt.value");
    }

    @Test
    public void aRetiredPasswordFieldInTheResponseIsIgnored() {
        // An old or misconfigured backend may still send the retired field; it must not be read.
        final MobileApiResponses.GlacierUserResponse response =
                new Gson().fromJson(
                        "{\"user\":{\"organization\":\"acme\",\"username\":\"testuser\","
                                + "\"messenger_id\":\"testuser@example.com\","
                                + "\"glacierpwd\":\"not-a-credential\"}}",
                        MobileApiResponses.GlacierUserResponse.class);
        final Account account = account();

        assertThat(XmppTokenCredentials.applyToken(account, response.user.xmppToken)).isFalse();

        verify(account, never()).setPassword(any());
        verify(account, never()).clearToken();
    }

    // ---- login ----------------------------------------------------------------------------

    @Test
    public void aTokenOnlyLoginSucceeds() {
        assertThat(XmppTokenCredentials.checkLogin(user("testuser@example.com", TOKEN)))
                .isEqualTo(LoginCheck.OK);
    }

    @Test
    public void aLoginWithNoTokenFails() {
        assertThat(XmppTokenCredentials.checkLogin(user("testuser@example.com", null)))
                .isEqualTo(LoginCheck.MISSING_TOKEN);
        assertThat(XmppTokenCredentials.checkLogin(user("testuser@example.com", "")))
                .isEqualTo(LoginCheck.MISSING_TOKEN);
    }

    @Test
    public void aLoginWithNoJidFailsEvenWithAToken() {
        assertThat(XmppTokenCredentials.checkLogin(user(null, TOKEN)))
                .isEqualTo(LoginCheck.MISSING_JID);
        assertThat(XmppTokenCredentials.checkLogin(null)).isEqualTo(LoginCheck.MISSING_JID);
    }
}
