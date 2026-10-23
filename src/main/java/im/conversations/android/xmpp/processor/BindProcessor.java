package im.conversations.android.xmpp.processor;

import android.text.TextUtils;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.generator.IqGenerator;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;

import im.conversations.android.xmpp.model.stanza.Iq;

public class BindProcessor implements Runnable {

    private final XmppConnectionService service;
    private final Account account;

    public BindProcessor(XmppConnectionService service, Account account) {
        this.service = service;
        this.account = account;
    }

    @Override
    public void run() {
        final XmppConnection connection = account.getXmppConnection();
        service.cancelAvatarFetches(account);
        final boolean loggedInSuccessfully =
                account.setOption(Account.OPTION_LOGGED_IN_SUCCESSFULLY, true);
        final boolean gainedFeature =
                account.setOption(
                        Account.OPTION_HTTP_UPLOAD_AVAILABLE,
                        connection.getFeatures().httpUpload(0));
        if (loggedInSuccessfully || gainedFeature) {
            service.databaseBackend.updateAccount(account);
        }

        if (loggedInSuccessfully) {
            if (!TextUtils.isEmpty(account.getDisplayName())) {
                service.publishDisplayName(account);
            }
        }

        account.getRoster().clearPresences();
        synchronized (account.inProgressConferenceJoins) {
            account.inProgressConferenceJoins.clear();
        }
        synchronized (account.inProgressConferencePings) {
            account.inProgressConferencePings.clear();
        }
        service.getJingleConnectionManager().notifyRebound(account);
        service.getQuickConversationsService().considerSyncBackground();

        connection.fetchRoster();

        if (connection.getFeatures().bookmarks2()) {
            service.fetchBookmarks2(account);
        } else if (!connection.getFeatures().bookmarksConversion()) {
            service.fetchBookmarks(account);
        }

        if (connection.getFeatures().mds()) {
            service.fetchMessageDisplayedSynchronization(account);
        }
        final var features = connection.getFeatures();
        final boolean bind2 = features.bind2();
        final boolean flexible = features.flexibleOfflineMessageRetrieval();
        final boolean catchup = service.getMessageArchiveService().inCatchup(account);
        final boolean trackOfflineMessageRetrieval;
        if (!bind2 && flexible && catchup && connection.isMamPreferenceAlways()) {
            trackOfflineMessageRetrieval = false;
            connection.sendIqPacket(
                    IqGenerator.purgeOfflineMessages(),
                    (packet) -> {
                        if (packet.getType() == Iq.Type.RESULT) {
                        }
                    });
        } else {
            trackOfflineMessageRetrieval = true;
        }
        service.sendPresence(account);
        connection.trackOfflineMessageRetrieval(trackOfflineMessageRetrieval);
        if (service.getPushManagementService().available(account)) {
            service.getPushManagementService().registerPushTokenOnServer(account);
        }
        service.connectMultiModeConversations(account);
        service.syncDirtyContacts(account);

        service.getUnifiedPushBroker().renewUnifiedPushEndpointsOnBind(account);

        // Org-controlled settings come from a lookup fired once at service start, which can land
        // before the network is usable -- notably right after an in-place upgrade. Retry it here,
        // where the connection is known good, so those settings are not stale until a manual
        // refresh. No-op unless the earlier attempt actually failed.
        service.retryGlacierOrganizationIfLastAttemptFailed(account);

        service.syncLiveLocationUpdates();
        service.syncAutomaticSecurityUpdates();
    }
}
