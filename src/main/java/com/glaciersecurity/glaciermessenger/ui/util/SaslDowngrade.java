package com.glaciersecurity.glaciermessenger.ui.util;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Recovery for an account whose pinned SASL mechanism the server no longer offers.
 *
 * The client pins the mechanism it last authenticated with and refuses anything weaker on the next
 * handshake, which is what stops an attacker stripping SCRAM to harvest a password over PLAIN.
 * When a server legitimately stops offering the pinned mechanism -- moving to JWT auth does exactly
 * that -- the same check blocks every reconnect. Because SASL runs only at connect time, the
 * session in progress keeps working and the account fails later, at a moment unrelated to the
 * change.
 *
 * There are two ways out, and they are not equivalent. Renewing the credential is the normal one
 * and needs no ceremony: a stored JWT resolves the pin to -1 by itself, so
 * XmppConnection.validate() fetches one automatically and the status-bar tap asks for the same
 * thing, behaving like any other reconnect. {@link #promptToClearPin} is the other -- it discards
 * the pin, after which the account authenticates with its password over whatever the server
 * offers. That is the protection itself being given up, so it is a last resort behind a dialog
 * that says so.
 */
public final class SaslDowngrade {

    private SaslDowngrade() {}

    /**
     * True when the account cannot connect because its pinned mechanism is no longer on offer.
     *
     * Reads the last <em>error</em> status rather than the current one, because the account cycles
     * back through CONNECTING on every retry; a successful login resets it. The priority test is
     * also what makes this self-clearing: a token-bearing account reports -1, so an account that
     * has just been issued a JWT stops reporting as blocked without anything else happening.
     */
    public static boolean isBlocked(@Nullable final Account account) {
        return account != null
                && account.getLastErrorStatus() == Account.State.DOWNGRADE_ATTACK
                && account.getPinnedMechanismPriority() >= 0;
    }

    /**
     * Last resort for a server that has legitimately changed mechanism but issues no token, so
     * {@link #promptToResync} has nothing to fetch. Clearing the pin is a real reduction in
     * protection and the dialog says so.
     */
    public static void promptToClearPin(
            final Activity activity,
            @Nullable final XmppConnectionService service,
            @Nullable final Account account) {
        if (!isBlocked(account)) {
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sasl_reset_pin_title)
                .setMessage(R.string.sasl_reset_pin_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.sasl_reset_pin_confirm,
                        (dialog, which) -> clearPin(activity, service, account))
                .create()
                .show();
    }

    private static void clearPin(
            final Activity activity,
            @Nullable final XmppConnectionService service,
            final Account account) {
        account.resetPinnedMechanism();
        // updateAccount() persists the cleared pin and reconnects in the background.
        if (service == null || !service.updateAccount(account)) {
            Toast.makeText(activity, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }
}
