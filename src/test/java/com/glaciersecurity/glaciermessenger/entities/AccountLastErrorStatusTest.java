package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;

/**
 * lastErrorStatus survives the CONNECTING churn between retries, but losing the network must
 * replace it: otherwise an old UNAUTHORIZED keeps the password prompt and error notification up
 * while the actual problem is airplane mode (upstream 12b9814c2).
 */
public class AccountLastErrorStatusTest {

    private static Account account() {
        return new Account(Jid.ofEscaped("testuser@example.com"), "password");
    }

    @Test
    public void errorSurvivesConnecting() {
        final Account account = account();
        account.setStatus(Account.State.UNAUTHORIZED);
        account.setStatus(Account.State.CONNECTING);
        assertEquals(Account.State.UNAUTHORIZED, account.getLastErrorStatus());
        assertTrue(account.unauthorized());
    }

    @Test
    public void noInternetReplacesError() {
        final Account account = account();
        account.setStatus(Account.State.UNAUTHORIZED);
        account.setStatus(Account.State.NO_INTERNET);
        assertEquals(Account.State.NO_INTERNET, account.getLastErrorStatus());
        assertFalse(account.unauthorized());
    }

    @Test
    public void airplaneModeReplacesError() {
        final Account account = account();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        account.setStatus(Account.State.AIRPLANE_MODE);
        assertEquals(Account.State.AIRPLANE_MODE, account.getLastErrorStatus());
    }

    @Test
    public void networkStatesAreNotErrors() {
        assertFalse(Account.State.NO_INTERNET.isError());
        assertFalse(Account.State.AIRPLANE_MODE.isError());
        assertTrue(Account.State.AIRPLANE_MODE.isAttemptReconnect());
    }
}
