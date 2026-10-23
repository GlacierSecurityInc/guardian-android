package com.glaciersecurity.glaciermessenger.services;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.receiver.SystemEventReceiver;
import com.glaciersecurity.glaciermessenger.ui.ConversationsManager;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.twilio.conversations.ConversationsClient;
import com.twilio.conversations.NotificationPayload;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

import java.util.Map;

/**
 * FCM only delivers com.google.firebase.MESSAGING_EVENT to a single manifest-declared
 * FirebaseMessagingService; declaring one per consumer (Twilio Voice, Twilio Conversations,
 * the XMPP server push wake-up) means at most one of them ever actually runs. This service
 * is the single listener and fans each message/token out to whichever consumer it belongs to.
 */
public class FcmListenerService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull final RemoteMessage remoteMessage) {
        final Map<String, String> data = remoteMessage.getData();
        if (data.isEmpty()) {
            return;
        }
        if (data.containsKey("account")) {
            handleXmppPush(data);
            return;
        }
        if (handleVoicePush(data)) {
            return;
        }
        handleConversationsPush(remoteMessage);
    }

    private void handleXmppPush(final Map<String, String> data) {
        if (!SystemEventReceiver.hasEnabledAccounts(this)) {
            return;
        }
        final Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_FCM_MESSAGE_RECEIVED);
        intent.putExtra("account", data.get("account"));
        Compatibility.startService(this, intent);
    }

    private boolean handleVoicePush(final Map<String, String> data) {
        return Voice.handleMessage(this, data, new MessageListener() {
            @Override
            public void onCallInvite(@NonNull final CallInvite callInvite) {
                final int notificationId = (int) System.currentTimeMillis();
                handleInvite(callInvite, notificationId);
            }

            @Override
            public void onCancelledCallInvite(@NonNull final CancelledCallInvite cancelledCallInvite, @Nullable final CallException callException) {
                handleCanceledCallInvite(cancelledCallInvite);
            }
        });
    }

    private void handleConversationsPush(final RemoteMessage remoteMessage) {
        final ConversationsManager manager = ConversationsManager.getSharedInstance();
        if (manager == null) {
            Log.w(Config.LOGTAG, "No ConversationsManager instance available to handle Twilio push notification");
            return;
        }
        final ConversationsClient client = manager.getConversationsClient();
        if (client == null) {
            Log.w(Config.LOGTAG, "Twilio Conversations client not initialized; ignoring push notification");
            return;
        }
        try {
            client.handleNotification(new NotificationPayload(remoteMessage.getData()));
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "Twilio Conversations client failed to handle push notification", e);
        }
    }

    @Override
    public void onNewToken(@NonNull final String token) {
        if (SystemEventReceiver.hasEnabledAccounts(this)) {
            final Intent xmppIntent = new Intent(this, XmppConnectionService.class);
            xmppIntent.setAction(XmppConnectionService.ACTION_FCM_TOKEN_REFRESH);
            Compatibility.startService(this, xmppIntent);
        }

        final Intent conversationsIntent = new Intent(this, XmppConnectionService.class);
        conversationsIntent.setAction(XmppConnectionService.ACTION_CONVERSATIONS_FCM_TOKEN_REFRESH);
        conversationsIntent.putExtra(XmppConnectionService.EXTRA_CONVERSATIONS_FCM_TOKEN, token);
        Compatibility.startService(this, conversationsIntent);

        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(Config.ACTION_FCM_TOKEN));
    }

    private void handleInvite(final CallInvite callInvite, final int notificationId) {
        final Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Config.ACTION_INCOMING_CALL);
        intent.putExtra(Config.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Config.INCOMING_CALL_INVITE, callInvite);
        startIncomingCallNotificationService(intent);
    }

    private void handleCanceledCallInvite(final CancelledCallInvite cancelledCallInvite) {
        final Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Config.ACTION_CANCEL_CALL);
        intent.putExtra(Config.CANCELLED_CALL_INVITE, cancelledCallInvite);
        startIncomingCallNotificationService(intent);
    }

    /**
     * FCM pushes arrive while the app is fully backgrounded/killed, with no other foreground
     * service already running to grant an exemption (unlike Jingle calls, which piggyback on
     * XmppConnectionService's persistent connection). startForegroundService() can throw
     * ForegroundServiceStartNotAllowedException (API 31+) in that state; if it does, the whole
     * invite would otherwise be silently dropped with no notification and no call UI at all.
     * Fall back to a plain startService(), which has a longer-standing, separate FCM exemption
     * from the older Android 8+ "no background service starts" restriction.
     */
    private void startIncomingCallNotificationService(final Intent intent) {
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "startForegroundService rejected for incoming call, falling back to startService", e);
            try {
                startService(intent);
            } catch (final RuntimeException e2) {
                Log.e(Config.LOGTAG, "startService also failed for incoming call", e2);
            }
        }
    }
}
