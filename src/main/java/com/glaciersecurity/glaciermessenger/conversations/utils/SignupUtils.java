package com.glaciersecurity.glaciermessenger.conversations.utils;

import android.content.Intent;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;
import com.glaciersecurity.glaciermessenger.ui.LoginAccountActivity;
import com.glaciersecurity.glaciermessenger.conversations.ui.ManageAccountActivity;
import com.glaciersecurity.glaciermessenger.ui.StartConversationActivity;

import com.glaciersecurity.glaciermessenger.ui.StepperWizard;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;

public class SignupUtils {

    public static boolean isSupportTokenRegistry() {
        return true;
    }

    public static Intent getRedirectionIntent(final ConversationsActivity activity) {
        final XmppConnectionService service = activity.xmppConnectionService;
        Account pendingAccount = AccountUtils.getPendingAccount(service);
        Intent intent;
        if (pendingAccount != null) {
            intent = new Intent(activity, LoginAccountActivity.class);
            intent.putExtra("jid", pendingAccount.getJid().asBareJid().toString());
            if (!pendingAccount.isOptionSet(Account.OPTION_MAGIC_CREATE)) {
                intent.putExtra(LoginAccountActivity.EXTRA_FORCE_REGISTER, pendingAccount.isOptionSet(Account.OPTION_REGISTER));
            }
        } else {
            if (service.getAccounts().isEmpty()) {
                    if (Config.X509_VERIFICATION) {
                        intent = new Intent(activity, ManageAccountActivity.class);
                    }
                    else {
                        intent = new Intent(activity, StepperWizard.class);
                    }
                } else {
                    intent = new Intent(activity, StartConversationActivity.class);
                }
        }
        intent.putExtra("init", true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }
}