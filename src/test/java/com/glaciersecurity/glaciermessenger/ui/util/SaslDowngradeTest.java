package com.glaciersecurity.glaciermessenger.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.glaciersecurity.glaciermessenger.crypto.sasl.ScramSha1;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;

/**
 * The predicate that decides whether an account is blocked on its pinned SASL mechanism.
 *
 * Getting it wrong is expensive in both directions: too strict and a blocked account has no way
 * back (the recovery it guards was unreachable in shipped builds, so this was the live behaviour);
 * too loose and the app offers to reset its own authentication protection when nothing is wrong.
 */
public class SaslDowngradeTest {

    private static Account account() {
        return new Account(Jid.ofEscaped("testuser@example.com"), "password");
    }

    private static Account pinnedToScram() {
        final Account account = account();
        account.setPinnedMechanism(new ScramSha1(account));
        return account;
    }

    @Test
    public void noAccountNeedsNothing() {
        assertFalse(SaslDowngrade.isBlocked(null));
    }

    @Test
    public void healthyAccountIsNotBlocked() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.ONLINE);
        assertFalse(SaslDowngrade.isBlocked(account));
    }

    @Test
    public void unrelatedFailureIsNotOffered() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.UNAUTHORIZED);
        assertFalse(SaslDowngrade.isBlocked(account));
    }

    @Test
    public void downgradeRejectionIsOffered() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        assertTrue(SaslDowngrade.isBlocked(account));
    }

    /**
     * The retry loop pushes the account back through CONNECTING between attempts, which is why the
     * predicate reads the last error rather than the current status.
     */
    @Test
    public void stillOfferedWhileRetrying() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        account.setStatus(Account.State.CONNECTING);
        assertTrue(SaslDowngrade.isBlocked(account));
    }

    @Test
    public void notOfferedWithNothingPinnedToClear() {
        final Account account = account();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        assertFalse(SaslDowngrade.isBlocked(account));
    }

    @Test
    public void acceptingClearsTheCondition() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        assertTrue(SaslDowngrade.isBlocked(account));

        account.resetPinnedMechanism();

        assertFalse(SaslDowngrade.isBlocked(account));
    }

    /** A login that succeeds on its own clears the offer without the user touching anything. */
    @Test
    public void reconnectingSuccessfullyClearsTheCondition() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        account.setStatus(Account.State.ONLINE);
        assertFalse(SaslDowngrade.isBlocked(account));
    }

    /**
     * The load-bearing property of the JWT migration: issuing a token clears the block on its own,
     * with the pin left untouched in the database. The mechanism factory's first branch is the
     * token branch, and it cannot rebuild a SCRAM mechanism, so the pinned priority resolves to
     * the -1 fallback and the account stops being blocked.
     *
     * If this ever stops holding, every SCRAM-pinned client wedges the moment a server stops
     * advertising SCRAM, and issuing tokens no longer rescues them.
     */
    @Test
    public void aTokenClearsTheConditionWithoutTouchingThePin() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        assertTrue(SaslDowngrade.isBlocked(account));

        account.setToken("header.payload.signature");

        assertFalse(SaslDowngrade.isBlocked(account));
        // The pin is bypassed, not cleared: the stored mechanism is still SCRAM-SHA-1, it just no
        // longer resolves to a priority. Anything checking the database to confirm a migration
        // would be misled by that -- it has to be checked behaviourally.
        assertEquals(-1, account.getPinnedMechanismPriority());
    }

    /** Losing the token re-arms the pin, which is what makes a partial rollback re-break clients. */
    @Test
    public void losingTheTokenRestoresTheCondition() {
        final Account account = pinnedToScram();
        account.setStatus(Account.State.DOWNGRADE_ATTACK);
        account.setToken("header.payload.signature");
        assertFalse(SaslDowngrade.isBlocked(account));

        account.setToken(null);

        assertTrue(SaslDowngrade.isBlocked(account));
    }
}
