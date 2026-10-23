package com.glaciersecurity.glaciermessenger.services;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.ui.StartConversationActivity;
import com.glaciersecurity.glaciermessenger.utils.ReplacingSerialSingleThreadExecutor;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

public class ShortcutService {

    // Must match the <category> in res/xml/shortcuts.xml's <share-target>, which is how
    // these dynamic conversation shortcuts surface as Direct Share targets.
    public static final String SHARE_TARGET_CATEGORY =
            "com.glaciersecurity.glaciermessenger.category.TEXT_SHARE_TARGET";

    private final XmppConnectionService xmppConnectionService;
    private final ReplacingSerialSingleThreadExecutor replacingSerialSingleThreadExecutor = new ReplacingSerialSingleThreadExecutor(ShortcutService.class.getSimpleName());

    public ShortcutService(XmppConnectionService xmppConnectionService) {
        this.xmppConnectionService = xmppConnectionService;
    }

    public void refresh() {
        refresh(false);
    }

    public void refresh(final boolean forceUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            final Runnable r = new Runnable() {
                @Override
                public void run() {
                    refreshImpl(forceUpdate);
                }
            };
            replacingSerialSingleThreadExecutor.execute(r);
        }
    }

    @TargetApi(25)
    public void report(Contact contact) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            ShortcutManager shortcutManager = xmppConnectionService.getSystemService(ShortcutManager.class);
            shortcutManager.reportShortcutUsed(getShortcutId(contact));
        }
    }

    @TargetApi(25)
    private void refreshImpl(boolean forceUpdate) {
        List<FrequentContact> frequentContacts = xmppConnectionService.databaseBackend.getFrequentContacts(30);
        HashMap<String,Account> accounts = new HashMap<>();
        for(Account account : xmppConnectionService.getAccounts()) {
            accounts.put(account.getUuid(),account);
        }
        List<Contact> contacts = new ArrayList<>();
        for(FrequentContact frequentContact : frequentContacts) {
            Account account = accounts.get(frequentContact.account);
            if (account != null) {
                contacts.add(account.getRoster().getContact(frequentContact.contact));
            }
        }
        ShortcutManager shortcutManager = xmppConnectionService.getSystemService(ShortcutManager.class);
        boolean needsUpdate = forceUpdate || contactsChanged(contacts,shortcutManager.getDynamicShortcuts());
        if (!needsUpdate) {
            return;
        }
        List<ShortcutInfo> newDynamicShortCuts = new ArrayList<>();
        for (Contact contact : contacts) {
            ShortcutInfo shortcut = getShortcutInfo(contact);
            newDynamicShortCuts.add(shortcut);
        }
        if (shortcutManager.setDynamicShortcuts(newDynamicShortCuts)) {
        }
    }

    public ShortcutInfoCompat getShortcutInfoCompat(final Contact contact) {
        final ShortcutInfoCompat.Builder builder =
                new ShortcutInfoCompat.Builder(xmppConnectionService, getShortcutId(contact))
                        .setShortLabel(contact.getDisplayName())
                        .setIntent(getShortcutIntent(contact))
                        .setCategories(Collections.singleton(SHARE_TARGET_CATEGORY))
                        .setIsConversation();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setIcon(
                    IconCompat.createFromIcon(
                            xmppConnectionService,
                            Icon.createWithBitmap(
                                    xmppConnectionService
                                            .getAvatarService()
                                            .getRoundedShortcut(contact))));
        }
        return builder.build();
    }

    public ShortcutInfoCompat getShortcutInfoCompat(final MucOptions mucOptions) {
        final ShortcutInfoCompat.Builder builder =
                new ShortcutInfoCompat.Builder(xmppConnectionService, getShortcutId(mucOptions))
                        .setShortLabel(mucOptions.getConversation().getName())
                        .setIntent(getShortcutIntent(mucOptions))
                        .setCategories(Collections.singleton(SHARE_TARGET_CATEGORY))
                        .setIsConversation();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setIcon(
                    IconCompat.createFromIcon(
                            xmppConnectionService,
                            Icon.createWithBitmap(
                                    xmppConnectionService
                                            .getAvatarService()
                                            .getRoundedShortcut(mucOptions))));
        }
        return builder.build();
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private ShortcutInfo getShortcutInfo(final Contact contact) {
        return getShortcutInfoCompat(contact).toShortcutInfo();
    }

    private static boolean contactsChanged(List<Contact> needles, List<ShortcutInfo> haystack) {
        for(Contact needle : needles) {
            if(!contactExists(needle,haystack)) {
                return true;
            }
        }
        return needles.size() != haystack.size();
    }

    @TargetApi(25)
    private static boolean contactExists(Contact needle, List<ShortcutInfo> haystack) {
        for(ShortcutInfo shortcutInfo : haystack) {
            if (getShortcutId(needle).equals(shortcutInfo.getId()) && needle.getDisplayName().equals(shortcutInfo.getShortLabel())) {
                return true;
            }
        }
        return false;
    }

    private static String getShortcutId(Contact contact) {
        return contact.getAccount().getJid().asBareJid().toEscapedString()+"#"+contact.getJid().asBareJid().toEscapedString();
    }

    /**
     * Resolves a Direct Share shortcut ID (Intent.EXTRA_SHORTCUT_ID, in the "accountJid#targetJid"
     * format built by getShortcutId) back to the conversation it represents.
     */
    public static Conversation findConversationForShortcut(
            final XmppConnectionService service, final String shortcutId) {
        final int separator = shortcutId.indexOf('#');
        if (separator < 0) {
            return null;
        }
        try {
            final Jid accountJid = Jid.ofEscaped(shortcutId.substring(0, separator));
            final Jid conversationJid = Jid.ofEscaped(shortcutId.substring(separator + 1));
            final Account account = service.findAccountByJid(accountJid);
            if (account == null) {
                return null;
            }
            return service.find(account, conversationJid);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static String getShortcutId(final MucOptions mucOptions) {
        final Account account = mucOptions.getAccount();
        final Jid jid = mucOptions.getConversation().getJid();
        return account.getJid().asBareJid().toEscapedString()
                + "#"
                + jid.asBareJid().toEscapedString();
    }

    private Intent getShortcutIntent(final MucOptions mucOptions) {
        final Account account = mucOptions.getAccount();
        return getShortcutIntent(
                account,
                Uri.parse(
                        String.format(
                                "xmpp:%s?join",
                                mucOptions
                                        .getConversation()
                                        .getJid()
                                        .asBareJid()
                                        .toEscapedString())));
    }

    private Intent getShortcutIntent(final Contact contact) {
        return getShortcutIntent(
                contact.getAccount(),
                Uri.parse("xmpp:" + contact.getJid().asBareJid().toEscapedString()));
    }

    private Intent getShortcutIntent(final Account account, final Uri uri) {
        Intent intent = new Intent(xmppConnectionService, StartConversationActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        intent.putExtra("account", account.getJid().asBareJid().toString());
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    @NonNull
    public Intent createShortcut(Contact contact, boolean legacy) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !legacy) {
            ShortcutInfo shortcut = getShortcutInfo(contact);
            ShortcutManager shortcutManager = xmppConnectionService.getSystemService(ShortcutManager.class);
            intent = shortcutManager.createShortcutResultIntent(shortcut);
        } else {
            intent = createShortcutResultIntent(contact);
        }
        return intent;
    }

    @NonNull
    private Intent createShortcutResultIntent(Contact contact) {
        AvatarService avatarService = xmppConnectionService.getAvatarService();
        Bitmap icon = avatarService.getRoundedShortcutWithIcon(contact);
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, contact.getDisplayName());
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, getShortcutIntent(contact));
        return intent;
    }

    public static class FrequentContact {
        private final String account;
        private final Jid contact;

        public FrequentContact(String account, Jid contact) {
            this.account = account;
            this.contact = contact;
        }
    }

}
