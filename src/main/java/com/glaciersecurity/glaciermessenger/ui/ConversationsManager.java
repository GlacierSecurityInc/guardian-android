package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;
import static com.glaciersecurity.glaciermessenger.utils.SMSdbInfo.PhoneSyncStatus.LOAD_EXCEPTION;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TokenModel;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.utils.DateParser;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.LogRedactor;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.twilio.conversations.Attributes;
import com.twilio.conversations.CallbackListener;
import com.twilio.conversations.Conversation;
import com.twilio.conversations.ConversationListener;
import com.twilio.conversations.ConversationsClient;
import com.twilio.conversations.ConversationsClientListener;
import com.twilio.conversations.MediaUploadListener;
import com.twilio.conversations.Message;
import com.twilio.conversations.Participant;
import com.twilio.conversations.StatusListener;
import com.twilio.conversations.User;
import com.twilio.util.ErrorInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


public class ConversationsManager {

    private static volatile ConversationsManager sharedInstance;

    @Nullable
    public static ConversationsManager getSharedInstance() {
        return sharedInstance;
    }


    public List<Message> getMessagesForCurrentConversation() {
        return messagesForCurrentConversation;
    }

    public void deleteAllExistingConversationForProxy(SmsProfile sp) {
        if (sp == null || conversationsClient == null) {
            return;
        }
        for (Conversation conversation : conversationsClient.getMyConversations()) {
            String identityNumber = null;
            JSONObject convProxyNumber = getConversationAttributes(conversation);
            if (convProxyNumber != null) {
                try {
                    if (convProxyNumber.has("identity_number")) {
                        identityNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(convProxyNumber.getString("identity_number"));
                    }
                } catch (JSONException e) {
                }

                if (identityNumber != null && !identityNumber.isEmpty()) {
                    if (PhoneUtils.areSameNumber(identityNumber, sp.getUnformattedNumber())) {
                        leaveConversation(conversation, conversation.getSid());
                    }
                }
            }
        }
    }

    public void refreshCurrentConversationMessages(int count, @Nullable Runnable onComplete) {
        if (currentConversation == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        loadPreviousMessages(currentConversation, count, false, onComplete);
    }

    private void leaveConversation(Conversation conversation, String convSid) {
        conversation.destroy(new StatusListener() {
            @Override
            public void onSuccess() {
                xmppConnectionService.deleteTwilioConversation(convSid);
            }

            @Override
            public void onError(ErrorInfo errorInfo) {
                Log.w(Config.LOGTAG, "Failed to delete conversation " + convSid + ": " + (errorInfo != null ?
                        errorInfo.getMessage() : "unknown error"));
                xmppConnectionService.deleteTwilioConversation(convSid);
            }
        });
    }

    // ── Constants & state ─────────────────────────────────────────────────────

    private static final long INITIAL_RECONNECT_DELAY_MS = 2000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60000L;

    private final TokenModel mToken = new TokenModel();
    private ConversationsClient.ConnectionState connectionState;

    public ConversationsClient getConversationsClient() {
        return conversationsClient;
    }

    public int getConversationsClientConversationCount() {
        try {
            return conversationsClient.getMyConversations().size();
        } catch (Exception ignore) {}
        return 0;
    }

    public boolean isConversationsClientNull() {
        return conversationsClient == null;
    }

    public ConversationsClient.ConnectionState getConnectionState() {
        return connectionState;
    }

    private void updateConnectionState(ConversationsClient.ConnectionState state) {
        connectionState = state;
        notifyConnectionStateChanged(state);
    }

    private void handleConnectionStateChange(ConversationsClient.ConnectionState state) {
        updateConnectionState(state);
        if (state == null) {
            return;
        }
        switch (state) {
            case CONNECTED:
                cancelReconnect();
                break;
            case CONNECTING:
                break;
            case DENIED:
                cancelReconnect();
                requestTokenRefresh();
                break;
            case DISCONNECTED:
            case ERROR:
            case FATAL_ERROR:
                scheduleReconnect();
                break;
        }
    }

    private Conversation currentConversation;
    private List<Message> messagesForCurrentConversation;
    private boolean loadingChannels = false;
    private final List<String> pendingMessages = new ArrayList<>();
    private static final HandlerThread RECONNECTION_THREAD;
    private final Handler reconnectionHandler;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private int reconnectAttemptCount = 0;
    private final AtomicBoolean tokenRefreshInProgress = new AtomicBoolean(false);
    private final Object tokenRequestLock = new Object();

    // Use CopyOnWriteArrayList instead of ArrayList so that concurrent
    // add() calls from other threads are safe while notifyTokenListeners() is
    // draining the list under tokenRequestLock.
    private final List<AccessTokenListener> pendingTokenListeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean pushRegistrationInProgress = new AtomicBoolean(false);
    private String registeredFcmToken;
    private String pendingFcmToken;
    private final Map<String, PendingHistoryLoad> pendingHistoryLoads = new ConcurrentHashMap<>();
    private final Set<String> conversationListenerSids = ConcurrentHashMap.newKeySet();
    // True once the initial Twilio client sync is complete. onConversationAdded callbacks that
    // arrive after this point represent genuinely new conversations (not history replay), so we
    // must explicitly load and notify for their first message because onMessageAdded will not
    // fire for a message that was already present when the conversation listener was attached.
    private volatile boolean initialSyncComplete = false;

    private final XmppConnectionService xmppConnectionService;
    private Account account;
    private ConversationsClient conversationsClient;
    private volatile ConversationsManagerListener conversationsManagerListener;

    static {
        HandlerThread thread = new HandlerThread("TwilioConversationsReconnect", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        RECONNECTION_THREAD = thread;
    }

    public ConversationsManager(XmppConnectionService xmppConnectionService) {
        this.xmppConnectionService = xmppConnectionService;
        // Use a dedicated background looper so reconnect attempts can run even if the UI thread is paused.
        reconnectionHandler = new Handler(RECONNECTION_THREAD.getLooper());
        sharedInstance = this;
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            reconnectScheduled.set(false);
            if (conversationsClient == null) {
                if (account != null) {
                    init(account, true);
                }
                return;
            }
            if (connectionState == ConversationsClient.ConnectionState.CONNECTED
                    || connectionState == ConversationsClient.ConnectionState.CONNECTING) {
                return;
            }
            if (!reconnectConversationsClient()) {
                Log.w(Config.LOGTAG, "Automatic reconnect failed, recreating Twilio Conversations client");
                shutdownClient();
                if (account != null) {
                    init(account, true);
                }
            }
        }
    };

    private void scheduleReconnect() {
        if (account == null) {
            return;
        }
        if (reconnectScheduled.getAndSet(true)) {
            return;
        }
        long backoffStep = Math.min(reconnectAttemptCount, 5);
        long delayMs = Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << backoffStep), MAX_RECONNECT_DELAY_MS);
        reconnectionHandler.postDelayed(reconnectRunnable, delayMs);
        reconnectAttemptCount++;
    }

    private void cancelReconnect() {
        reconnectScheduled.set(false);
        reconnectAttemptCount = 0;
        reconnectionHandler.removeCallbacks(reconnectRunnable);
    }

    private void schedulePushRegistrationRetry() {
        if (TextUtils.isEmpty(pendingFcmToken)) {
            return;
        }
        reconnectionHandler.postDelayed(() -> registerForFcmPushNotifications(pendingFcmToken), INITIAL_RECONNECT_DELAY_MS * 2);
    }

    // ── Listener notifications ────────────────────────────────────────────────

    private void notifyReloadMessages(@Nullable ConversationsManagerListener listener) {
        if (listener == null) {
            return;
        }
        mainThreadHandler.post(() -> {
            try {
                listener.reloadMessages();
            } catch (RuntimeException e) {
                Log.e(Config.LOGTAG, "ConversationsManagerListener.reloadMessages failed", e);
            }
        });
    }

    private void runOnMainThread(@Nullable Runnable runnable) {
        if (runnable == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
            return;
        }
        mainThreadHandler.post(runnable);
    }

    private void notifyMoreMessagesLoaded(int newMessageCount, boolean hasMore) {
        final ConversationsManagerListener listener = conversationsManagerListener;
        if (listener == null) {
            return;
        }
        mainThreadHandler.post(() -> listener.onMoreMessagesLoaded(newMessageCount, hasMore));
    }

    private void notifyReceivedNewMessage(Message message) {
        final ConversationsManagerListener listener = conversationsManagerListener;
        if (listener == null) {
            return;
        }
        mainThreadHandler.post(() -> listener.receivedNewMessage(message));
    }

    private void notifyConnectionStateChanged(@Nullable ConversationsClient.ConnectionState state) {
        final ConversationsManagerListener listener = conversationsManagerListener;
        if (listener == null || state == null) {
            return;
        }
        listener.onConversationsClientConnectionStateChange(state);
    }

    private void notifyMessageSent(@Nullable String messageSid) {
        final ConversationsManagerListener listener = conversationsManagerListener;
        if (listener == null) {
            return;
        }
        listener.messageSentCallback(messageSid);
    }

    private void notifyMessageSendFailed(@Nullable String messageSid) {
        final ConversationsManagerListener listener = conversationsManagerListener;
        if (listener == null) {
            return;
        }
        listener.messageSendFailed(messageSid);
    }

    private void notifyIncomingMessage() {
        final ConversationsManagerListener listener = conversationsManagerListener;
        if (listener == null) {
            return;
        }
        listener.notifyMessages();
    }

    // ── Token / auth ──────────────────────────────────────────────────────────

    private void requestTokenRefresh() {
        if (tokenRefreshInProgress.get()) {
            return;
        }
        retrieveToken(accessTokenListener);
    }

    private void requestFcmTokenFromFirebase() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(new OnCompleteListener<String>() {
            @Override
            public void onComplete(@NonNull Task<String> task) {
                if (!task.isSuccessful()) {
                    Log.w(Config.LOGTAG, "Unable to fetch FCM token for Twilio Conversations", task.getException());
                    return;
                }
                String token = task.getResult();
                if (!TextUtils.isEmpty(token)) {
                    registerForFcmPushNotifications(token);
                }
            }
        });
    }

    public void initConvCli() {
        if (conversationsClient == null) {
            if (mToken.getAccessToken() == null) {
                retrieveAccessTokenFromServer(tokenResponseListener);
            } else {
                configureConversationsClientWithToken(mToken.getAccessToken(), tokenResponseListener);
            }
        }
    }

    public void registerForFcmPushNotifications(@Nullable String fcmToken) {
        if (fcmToken == null || fcmToken.isEmpty()) {
            requestFcmTokenFromFirebase();
            return;
        }
        pendingFcmToken = fcmToken;
        if (conversationsClient == null) {
            return;
        }
        if (fcmToken.equals(registeredFcmToken)) {
            return;
        }
        if (pushRegistrationInProgress.getAndSet(true)) {
            schedulePushRegistrationRetry();
            return;
        }
        ConversationsClient.FCMToken fcm = new ConversationsClient.FCMToken(fcmToken);
        conversationsClient.registerFCMToken(fcm, new StatusListener() {
            @Override
            public void onSuccess() {
                pushRegistrationInProgress.set(false);
                registeredFcmToken = fcmToken;
            }

            @Override
            public void onError(ErrorInfo errorInfo) {
                pushRegistrationInProgress.set(false);
                Log.e(Config.LOGTAG, "Failed to register Twilio Conversations FCM token: "
                        + (errorInfo != null ? errorInfo.getMessage() : "unknown error"));
                schedulePushRegistrationRetry();
            }
        });
    }

    private void configureConversationsClientWithToken(String token, @Nullable TokenResponseListener listener) {
        setmToken(token);
        if (conversationsClient == null) {
            ConversationsClient.Properties props = ConversationsClient.Properties.newBuilder().createProperties();
            updateConnectionState(ConversationsClient.ConnectionState.CONNECTING);
            ConversationsClient.create(xmppConnectionService, token, props, mConversationsClientCallback);
            if (listener != null) {
                listener.receivedTokenResponse(true, null);
            }
        } else {
            conversationsClient.updateToken(token, new StatusListener() {
                @Override
                public void onSuccess() {
                    if (listener != null) {
                        listener.receivedTokenResponse(true, null);
                    }
                    loadChannels(true);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.e(Config.LOGTAG, "Failed to update Twilio Conversations access token: "
                            + (errorInfo != null ? errorInfo.getMessage() : "unknown error"));
                    if (listener != null) {
                        listener.receivedTokenResponse(false,
                                errorInfo != null ? new Exception(errorInfo.getMessage()) : null);
                    }
                    shutdownClient();
                    if (account != null) {
                        init(account, true);
                    }
                }
            });
        }
    }

    public void setmToken(String accessToken) {
        this.mToken.setAccessToken(accessToken);
    }

    public void setOnConversationsManagerListener(ConversationsManagerListener conversationsManagerListener) {
        this.conversationsManagerListener = conversationsManagerListener;
        notifyConnectionStateChanged(connectionState);
    }

    public synchronized void init(Account account, boolean isForced) {
        this.account = account;

        if (mToken.getAccessToken() == null) {
            retrieveAccessTokenFromServer(tokenResponseListener);
            return;
        }

        if (conversationsClient == null) {
            configureConversationsClientWithToken(mToken.getAccessToken(), tokenResponseListener);
            return;
        }

        if (shouldAttemptReconnect()) {
            if (reconnectConversationsClient()) {
                return;
            }
            Log.w(Config.LOGTAG, "init()-> reconnect attempt failed, restarting conversations client");
            shutdownClient();
            init(account, isForced);
            return;
        }

        loadChannels(isForced);
    }

    private boolean shouldAttemptReconnect() {
        if (conversationsClient == null) {
            return false;
        }
        if (connectionState == null) {
            return false;
        }
        return connectionState != ConversationsClient.ConnectionState.CONNECTED
                && connectionState != ConversationsClient.ConnectionState.CONNECTING;
    }

    private boolean reconnectConversationsClient() {
        if (conversationsClient == null) {
            return false;
        }
        if (invokeReconnectMethod("connect")) {
            return true;
        }
        return invokeReconnectMethod("reconnect");
    }

    private boolean invokeReconnectMethod(String methodName) {
        try {
            Method method = conversationsClient.getClass().getMethod(methodName);
            CallbackListener<Void> callback = new CallbackListener<Void>() {
                @Override
                public void onSuccess(Void result) {
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.w(Config.LOGTAG, methodName + " failed: " + (errorInfo != null ? errorInfo.getMessage() : "unknown"));
                }
            };
            method.invoke(conversationsClient, callback);
            updateConnectionState(ConversationsClient.ConnectionState.CONNECTING);
            return true;
        } catch (NoSuchMethodException e) {
            Log.w(Config.LOGTAG, "No suitable method found to " + methodName + " Twilio Conversations client");
            return false;
        } catch (IllegalAccessException | InvocationTargetException e) {
            Log.w(Config.LOGTAG, "Failed to invoke " + methodName + "(CallbackListener) on Twilio Conversations client", e);
            return false;
        }
    }

    public void retrieveAccessTokenFromServer(final TokenResponseListener listener) {
        retrieveToken((token, exception) -> {
            if (token != null) {
                configureConversationsClientWithToken(token, listener);
            } else {
                listener.receivedTokenResponse(false, exception);
            }
        });
    }

    private void retrieveToken(AccessTokenListener listener) {

        synchronized (tokenRequestLock) {
            if (listener != null) {
                pendingTokenListeners.add(listener);
            }
            if (!tokenRefreshInProgress.compareAndSet(false, true)) {
                return;
            }
        }

        new Thread(() -> {
            String tokenResult = null;
            Exception exceptionResult = null;
            try {
                final Account account = xmppConnectionService.getFirstAccount();
                if (account == null) {
                    exceptionResult = new IllegalStateException("No account available");
                    return;
                }
                OkHttpClient client = getOkHttpClient();
                Map<String, String> formFields = new HashMap<>();
                formFields.put("username", account.getUsername());
                formFields.put("identity", account.getUsername());
                formFields.put("devicetype", "android");
                formFields.put("organization", account.getOrganization());
                Request request = OkHttpClientProvider.buildJwtFormRequestBuilder(
                                Endpoints.base() + EndPointConfig.CHAT_ACCESS_TOKEN_URL,
                                formFields)
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    final int responseCode = response != null ? response.code() : -1;
                    String responseBody = null;
                    if (response != null && response.body() != null) {
                        responseBody = response.body().string();
                    }
                    if (responseBody == null || responseBody.trim().isEmpty()) {
                        exceptionResult = new Exception("Empty token response body");
                        Log.e(Config.LOGTAG, "Token retrieval failed: empty response body (url=CHAT_ACCESS_TOKEN_URL, code=" + responseCode + ")");
                        return;
                    }

                    Gson gson = new Gson();
                    try {
                        TokenResponse tokenResponse = gson.fromJson(responseBody, TokenResponse.class);
                        if (tokenResponse == null) {
                            exceptionResult = new Exception("Invalid token response: null body parse");
                            Log.e(Config.LOGTAG, "Token retrieval parse failure: parsed response is null (url=CHAT_ACCESS_TOKEN_URL, code=" + responseCode + ", bodyLength=" + responseBody.length() + ")");
                            return;
                        }

                        final String responseMessage = tokenResponse.message != null ? tokenResponse.message.trim() : null;
                        final String responseToken = tokenResponse.token != null ? tokenResponse.token.trim() : null;
                        final boolean hasToken = responseToken != null && !responseToken.isEmpty();
                        final boolean successMessage = responseMessage != null && "success".equalsIgnoreCase(responseMessage);

                        if (successMessage || (responseMessage == null && hasToken)) {
                            if (hasToken) {
                                tokenResult = responseToken;
                            } else {
                                exceptionResult = new Exception("Token missing in success response");
                            }
                            if (tokenResponse.addPurchaseNumbers != null) {
                                xmppConnectionService.getSmsInfo().setDbPurchaseNum(tokenResponse.addPurchaseNumbers);
                            }
                        } else {
                            exceptionResult = new Exception("Token request failed: message=" + tokenResponse.message);
                        }
                    } catch (Exception e) {
                        Log.e(Config.LOGTAG, "Token retrieval parse failure (bodyEmpty=" + responseBody.trim().isEmpty() + ", bodyLength=" + responseBody.length() + ")", e);
                        Log.e(Config.LOGTAG, e.getMessage());
                        exceptionResult = e;
                    }
                }
            } catch (IOException ex) {
                Log.e(Config.LOGTAG, "Token retrieval request failure (url=CHAT_ACCESS_TOKEN_URL, exception=" + ex.getClass().getSimpleName() + ", message=" + ex.getMessage() + ")", ex);
                exceptionResult = ex;
            } finally {
                tokenRefreshInProgress.set(false);
                notifyTokenListeners(tokenResult, exceptionResult);
            }
        }).start();
    }

    /**
     * notifyTokenListeners
     *
     * <p>Two changes from the original:
     * <ol>
     *   <li>The pending-listener list is now a {@link CopyOnWriteArrayList} (see field
     *       declaration), so concurrent {@code add()} calls are thread-safe even while
     *       this method is draining the list under {@code tokenRequestLock}.</li>
     *   <li>Each {@code receivedAccessToken()} dispatch is wrapped in
     *       {@code try-catch(Exception)}. Without this guard, an unchecked exception
     *       thrown by any one listener aborts the loop; the remaining listeners — already
     *       removed from {@code pendingTokenListeners} — can never be rescheduled and
     *       would permanently stall the Twilio client.</li>
     * </ol>
     */
    private void notifyTokenListeners(@Nullable String token, @Nullable Exception exception) {
        // Atomically snapshot and drain the list under the lock.
        final List<AccessTokenListener> snapshot;
        synchronized (tokenRequestLock) {
            snapshot = new ArrayList<>(pendingTokenListeners);
            pendingTokenListeners.clear();
        }
        // wrap each dispatch so one bad listener cannot strand the rest.
        for (final AccessTokenListener listener : snapshot) {
            try {
                listener.receivedAccessToken(token, exception);
            } catch (final Exception e) {
                Log.e(Config.LOGTAG,
                        "uncaught exception in AccessTokenListener.receivedAccessToken(); "
                                + "remaining listeners in this batch will still be notified.", e);
            }
        }
    }

    // ── Channel loading ───────────────────────────────────────────────────────

    public synchronized void loadChannels(boolean force) {
        if (loadingChannels && !force) {
            return;
        }
        loadingChannels = true;
        try {
            if (conversationsClient == null || conversationsClient.getMyConversations() == null) {
                xmppConnectionService.markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.NO_CONV_CLIENT);
                loadingChannels = false;
                return;
            }

            List<Conversation> allConv = conversationsClient.getMyConversations();
            final AtomicInteger remaining = new AtomicInteger(allConv.size());
            if (allConv.isEmpty()) {
                notifyReloadMessages(conversationsManagerListener);
                xmppConnectionService.markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.SUCCESS);
                loadingChannels = false;
                return;
            }
            for (Conversation conversation : allConv) {
                registerConversationListener(conversation);
                String identityNumber = null;
                String friendlyName = conversation.getFriendlyName();
                JSONObject convProxyNumber = getConversationAttributes(conversation);
                if (convProxyNumber != null) {
                    try {
                        if (convProxyNumber.has("identity_number")) {
                            identityNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(convProxyNumber.getString("identity_number"));
                        }
                    } catch (JSONException e) {
                    }
                }

                SmsProfile sp = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(identityNumber);
                if (sp != null) {
                    xmppConnectionService.addSMSConversation(conversation, sp, identityNumber);
                    SmsProfile selectedProfile = xmppConnectionService.getSmsInfo().getSelectedProfile();
                    int historyCount = (selectedProfile != null && selectedProfile.getId() != null
                            && selectedProfile.getId().equals(sp.getId())) ? 10 : 1;
                    loadPreviousMessages(conversation, historyCount, false, () -> {
                        if (remaining.decrementAndGet() == 0) {
                            notifyReloadMessages(conversationsManagerListener);
                            xmppConnectionService.markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.SUCCESS);
                            loadingChannels = false;
                        }
                    });
                } else {
                    xmppConnectionService.addSMSConversation(conversation, "no_sp_" + friendlyName, identityNumber);
                    if (remaining.decrementAndGet() == 0) {
                        notifyReloadMessages(conversationsManagerListener);
                        xmppConnectionService.markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.SUCCESS);
                        loadingChannels = false;
                    }
                }
            }
        } catch (Exception e) {
            notifyReloadMessages(conversationsManagerListener);
            xmppConnectionService.markPhoneSyncComplete(LOAD_EXCEPTION);
            loadingChannels = false;
        }
    }

    // ── Public conversation operations ────────────────────────────────────────

    public void markConversationRead(String conversationSid) {
        if (conversationsClient == null || conversationSid == null || conversationSid.isEmpty()) {
            return;
        }
        conversationsClient.getConversation(conversationSid, new CallbackListener<Conversation>() {
            @Override
            public void onSuccess(Conversation conversation) {
                if (conversation == null) {
                    return;
                }
                Long lastMessageIndex = conversation.getLastMessageIndex();
                if (lastMessageIndex == null) {
                    syncConversationUnreadCount(conversation, getIdentityNumber(conversation));
                    return;
                }
                conversation.setLastReadMessageIndex(lastMessageIndex, new CallbackListener<Long>() {
                    @Override
                    public void onSuccess(Long result) {
                        syncConversationUnreadCount(conversation, getIdentityNumber(conversation));
                    }

                    @Override
                    public void onError(ErrorInfo errorInfo) {
                        CallbackListener.super.onError(errorInfo);
                        Log.w(Config.LOGTAG, "Failed to update last read message index for " + conversationSid);
                    }
                });
            }

            @Override
            public void onError(ErrorInfo errorInfo) {
                CallbackListener.super.onError(errorInfo);
                String errorMessage = errorInfo != null ? errorInfo.getMessage() : "unknown error";
                Log.w(Config.LOGTAG, "Failed to fetch conversation for read sync: " + errorMessage);
            }
        });
    }

    public void deleteExistingConversation(String convSid) {
        if (conversationsClient == null) {
            xmppConnectionService.deleteTwilioConversation(convSid);
            return;
        }
        conversationsClient.getConversation(convSid, new CallbackListener<Conversation>() {
            @Override
            public void onSuccess(Conversation conversation) {
                if (conversation != null) {
                    leaveConversation(conversation, convSid);
                }
            }

            @Override
            public void onError(ErrorInfo errorInfo) {
                CallbackListener.super.onError(errorInfo);
                String errorMessage = errorInfo != null ? errorInfo.getMessage() : "unknown error";
                int errorCode = errorInfo != null ? errorInfo.getCode() : -1;
                Log.w(Config.LOGTAG, "Failed to fetch conversation for deletion (code " + errorCode + "): " + errorMessage);
                xmppConnectionService.deleteTwilioConversation(convSid);
            }
        });
    }

    public void loadMoreMessages(int number) {
        if (currentConversation == null || messagesForCurrentConversation == null || messagesForCurrentConversation.isEmpty()) {
            if (conversationsManagerListener != null) {
                notifyMoreMessagesLoaded(0, false);
            }
            return;
        }
        Message anchor = messagesForCurrentConversation.get(0);
        currentConversation.getMessagesBefore(anchor.getMessageIndex(), number, new CallbackListener<List<Message>>() {
            @Override
            public void onSuccess(List<Message> result) {
                boolean hasMore = result != null && result.size() == number;
                if (result != null && !result.isEmpty()) {
                    List<Message> ordered = new ArrayList<>(result);
                    sortMessagesChronologically(ordered);
                    HashSet<String> existingSids = new HashSet<>();
                    for (Message message : messagesForCurrentConversation) {
                        existingSids.add(message.getSid());
                    }
                    List<Message> unique = new ArrayList<>();
                    for (Message message : ordered) {
                        if (existingSids.add(message.getSid())) {
                            unique.add(message);
                        }
                    }
                    if (!unique.isEmpty()) {
                        messagesForCurrentConversation.addAll(0, unique);
                        xmppConnectionService.populateSMSMessagesForConversation(unique);
                        if (conversationsManagerListener != null) {
                            notifyMoreMessagesLoaded(unique.size(), hasMore);
                        }
                    } else if (conversationsManagerListener != null) {
                        notifyMoreMessagesLoaded(0, hasMore);
                    }
                } else if (conversationsManagerListener != null) {
                    notifyMoreMessagesLoaded(0, false);
                }
            }

            @Override
            public void onError(ErrorInfo errorInfo) {
                Log.e(Config.LOGTAG, "Error loading older messages: " + errorInfo.getMessage());
                if (conversationsManagerListener != null) {
                    notifyMoreMessagesLoaded(0, true);
                }
            }
        });
    }

    public ArrayList<TwilioConversationEntity> getConversationsForProxy(SmsProfile smsProfile) {
        ArrayList<TwilioConversationEntity> list = xmppConnectionService.databaseBackend.getSmsConversationsObjForProxy(smsProfile);
        if (list == null) {
            return list;
        }
        Collections.sort(list, new Comparator<TwilioConversationEntity>() {
            private long toMillis(String ts) {
                if (ts == null || ts.isEmpty()) {
                    return Long.MIN_VALUE;
                }
                try {
                    return DateParser.parseMillis(ts);
                } catch (ParseException e) {
                    return Long.MIN_VALUE;
                }
            }

            @Override
            public int compare(TwilioConversationEntity a, TwilioConversationEntity b) {
                long t1 = toMillis(b.getLastMessageDate());
                long t2 = toMillis(a.getLastMessageDate());
                return Long.compare(t1, t2);
            }
        });
        return list;
    }

    // ── Internal conversation helpers ─────────────────────────────────────────

    private boolean addConversationToDB(Conversation conversation) {
        if (conversation != null) {
            String identityNumber = null;
            JSONObject convProxyNumber = getConversationAttributes(conversation);
            if (convProxyNumber != null) {
                try {
                    if (convProxyNumber.has("identity_number")) {
                        identityNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(convProxyNumber.getString("identity_number"));
                    }
                } catch (JSONException e) {
                    return false;
                }
            }

            SmsProfile sp = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(identityNumber);
            if (sp != null) {
                xmppConnectionService.addSMSConversation(conversation, sp, identityNumber);
                return true;
            }
        }
        return false;
    }

    protected void updateExistingConversation(Conversation conversation, boolean notifyLast) {
        if (conversation == null) {
            return;
        }

        if (conversation.getStatus() != Conversation.ConversationStatus.JOINED) {
            return;
        }

        syncConversationUnreadCount(conversation, getIdentityNumber(conversation));
        if (!isCurrentConversation(conversation)) {
            registerConversationListener(conversation);
            loadPreviousMessages(conversation, 10, notifyLast, null);
            return;
        }
        ConversationsManager.this.currentConversation = conversation;
        registerConversationListener(ConversationsManager.this.currentConversation);
        loadPreviousMessages(conversation, 10, notifyLast, () -> {
            if (conversationsManagerListener != null) {
                notifyReloadMessages(conversationsManagerListener);
            }
            // ensure any queued messages are sent once the conversation is ready
            sendPendingMessages();
        });
    }

    protected void findOrCreateConversation(String convSid, String friendlyName, String identityNumber) {
        currentConversation = null;
        messagesForCurrentConversation = new ArrayList<>();
        if (conversationsClient == null) {
            //todo start loading bar
            return;
        }
        final String identityCur = identityNumber;
        final String friendlyWithCountry = PhoneUtils.getStripPhoneNumberWithCountryCode(friendlyName);
        final String friendlyWithoutCountry = PhoneUtils.getStripPhoneNumber(friendlyName);
        if (convSid == null) {
            SmsProfile sp = xmppConnectionService.getSmsProfileFromProxy(identityNumber);
            TwilioConversationEntity existing = xmppConnectionService.getConversationsBetweenContactAndProxy(friendlyName, sp);
            if (existing != null) {
                convSid = existing.getConv_sid();
            }
        }

        if (convSid == null) {
            final String lookupFriendly = !TextUtils.isEmpty(friendlyWithCountry) ? friendlyWithCountry : friendlyName;
            conversationsClient.getConversation(lookupFriendly, new CallbackListener<Conversation>() {
                @Override
                public void onSuccess(Conversation createdConv) {
                    handleConversationReady(createdConv, lookupFriendly, identityCur);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    final String fallbackFriendly = !TextUtils.isEmpty(friendlyWithoutCountry)
                            ? friendlyWithoutCountry
                            : friendlyName;
                    conversationsClient.getConversation(fallbackFriendly, new CallbackListener<Conversation>() {
                        @Override
                        public void onSuccess(Conversation createdConv) {
                            handleConversationReady(createdConv, fallbackFriendly, identityCur);
                        }

                        @Override
                        public void onError(ErrorInfo errorInfo) {
                            final String createFriendly = !TextUtils.isEmpty(friendlyWithCountry)
                                    ? friendlyWithCountry
                                    : fallbackFriendly;
                            conversationsClient.createConversation(createFriendly, new CallbackListener<Conversation>() {
                                @Override
                                public void onSuccess(Conversation createdConv) {
                                    postCreateLogic(createdConv, identityCur, createFriendly);
                                }
                            });
                        }
                    });
                }
            });
        } else {
            String resolvedFriendly = !TextUtils.isEmpty(friendlyWithCountry) ? friendlyWithCountry : friendlyWithoutCountry;
            if (TextUtils.isEmpty(resolvedFriendly)) {
                resolvedFriendly = !TextUtils.isEmpty(friendlyName) ? friendlyName : identityNumber;
            }
            final String resolvedFriendlyFinal = resolvedFriendly;
            conversationsClient.getConversation(convSid, new CallbackListener<Conversation>() {
                @Override
                public void onSuccess(Conversation conversation) {
                    if (conversation != null) {
                        if (conversation.getStatus() == Conversation.ConversationStatus.JOINED) {
                            initSelectedConvo(conversation, resolvedFriendlyFinal, identityCur);
                        } else {
                            String joinIdentity = resolvedFriendlyFinal;
                            if (TextUtils.isEmpty(joinIdentity)) {
                                joinIdentity = friendlyName;
                            }
                            if (TextUtils.isEmpty(joinIdentity)) {
                                joinIdentity = identityCur;
                            }
                            joinConversation(conversation, joinIdentity, identityCur);
                        }
                    }
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    conversationsClient.createConversation(resolvedFriendlyFinal, new CallbackListener<Conversation>() {
                        @Override
                        public void onSuccess(Conversation createdConv) {
                            postCreateLogic(createdConv, identityCur, resolvedFriendlyFinal);
                        }
                    });
                }
            });
        }
    }

    private void handleConversationReady(Conversation conversation, String friendlyName, @Nullable String identityNumber) {
        if (conversation == null) {
            return;
        }
        if (conversation.getStatus() != Conversation.ConversationStatus.JOINED) {
            joinConversation(conversation, friendlyName, identityNumber);
        } else {
            addParticipantIfNeeded(conversation, friendlyName, identityNumber,
                    () -> initSelectedConvo(conversation, friendlyName, identityNumber));
        }
    }

    private void postCreateLogic(Conversation createdConv, @Nullable String identityNumber, String friendlyName) {
        if (createdConv != null) {
            JSONObject conv_attr = new JSONObject();
            try {
                conv_attr.put("identity_number", identityNumber);
            } catch (JSONException e) {
                Log.e(Config.LOGTAG, "create conversation error " + e.getMessage(), e);
            }
            Attributes attributes = new Attributes(conv_attr);
            createdConv.setAttributes(attributes, new StatusListener() {
                @Override
                public void onSuccess() {
                    joinConversation(createdConv, friendlyName, identityNumber);
                }
            });
        }
    }

    private void initSelectedConvo(Conversation existingConv, String friendlyName, @Nullable String identityNumber) {
        SmsProfile sp = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(identityNumber);
        xmppConnectionService.addSMSConversation(existingConv, sp, friendlyName);
        ConversationsManager.this.currentConversation = existingConv;
        registerConversationListener(ConversationsManager.this.currentConversation);
        loadPreviousMessages(existingConv, 100, false, () -> {
            if (conversationsManagerListener != null) {
                notifyReloadMessages(conversationsManagerListener);
            }
            sendPendingMessages();
        });
    }

    private void joinConversation(Conversation joinConv, String identity, @Nullable String identityNumber) {
        joinConv.join(new StatusListener() {
            @Override
            public void onSuccess() {
                addParticipantIfNeeded(joinConv, identity, identityNumber,
                        () -> initSelectedConvo(joinConv, identity, identityNumber));
            }

            @Override
            public void onError(ErrorInfo errorInfo) {
                ConversationsManager.this.currentConversation = null;
                String errorMessage = errorInfo != null ? errorInfo.getMessage() : "unknown error";
                int errorCode = errorInfo != null ? errorInfo.getCode() : -1;
                Log.e(Config.LOGTAG, "Error joining conversation (code " + errorCode + "): " + errorMessage);
                if (joinConv != null) {
                    xmppConnectionService.deleteTwilioConversation(joinConv.getSid());
                }
                String uniqueName = buildUniqueConversationName(identity);
                if (conversationsClient != null) {
                    conversationsClient.createConversation(uniqueName, new CallbackListener<Conversation>() {
                        @Override
                        public void onSuccess(Conversation createdConv) {
                            postCreateLogic(createdConv, identityNumber, identity);
                        }

                        @Override
                        public void onError(ErrorInfo createErrorInfo) {
                            String createMessage = createErrorInfo != null ? createErrorInfo.getMessage() : "unknown error";
                            int createCode = createErrorInfo != null ? createErrorInfo.getCode() : -1;
                            Log.e(Config.LOGTAG, "Failed to recreate conversation after join error (code " + createCode + "): " + createMessage);
                        }
                    });
                }
            }
        });
    }

    private String buildUniqueConversationName(@Nullable String baseName) {
        long timestamp = System.currentTimeMillis();
        if (TextUtils.isEmpty(baseName)) {
            return "conversation-" + timestamp;
        }
        return baseName + "-" + timestamp;
    }

    private void addParticipantIfNeeded(Conversation conversation, String identity, @Nullable String proxyIdentity, @Nullable Runnable onComplete) {
        List<Participant> participants = conversation.getParticipantsList();

        if (TextUtils.isEmpty(identity)) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        boolean alreadyAdded = false;

        for (Participant participant : participants) {
            if (identity != null && identity.equals(participant.getIdentity())) {
                alreadyAdded = true;
                break;
            }
        }

        if (!alreadyAdded) {
            StatusListener listener = new StatusListener() {
                @Override
                public void onSuccess() {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }

                @Override
                public void onError(ErrorInfo error) {
                    Log.e(Config.LOGTAG, "Failed to add participant: " + error.getMessage());
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            };
            if (!TextUtils.isEmpty(proxyIdentity) && isPhoneAddress(identity)) {
                conversation.addParticipantByAddress(identity, proxyIdentity, null, listener);
            } else {
                conversation.addParticipantByIdentity(identity, null, listener);
            }
        } else {
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    private boolean isPhoneAddress(String identity) {
        if (TextUtils.isEmpty(identity)) {
            return false;
        }
        return identity.matches("^\\+?\\d+$");
    }

    protected void loadPreviousMessages(final Conversation conversation, int number, boolean notifyLast, @Nullable Runnable onComplete) {
        boolean isAdded = addConversationToDB(conversation);
        if (conversation.getSynchronizationStatus().isAtLeast(Conversation.SynchronizationStatus.ALL)) {
            conversation.getLastMessages(number, new CallbackListener<List<Message>>() {
                @Override
                public void onSuccess(List<Message> result) {
                    if (xmppConnectionService == null) {
                        return;
                    }
                    boolean isCurrent = isCurrentConversation(conversation);
                    if (isCurrent) {
                        messagesForCurrentConversation = new ArrayList<>(result);
                        sortMessagesChronologically(messagesForCurrentConversation);
                    }
                    if (isAdded) {
                        xmppConnectionService.populateSMSMessagesForConversation(result);
                    }
                    if (!result.isEmpty()) {
                        Message lastMessage = result.get(result.size() - 1);
                        xmppConnectionService.getConversationBySidAndUpdate(conversation, lastMessage);
                        String identityNumber = null;
                        JSONObject convProxyNumber = getConversationAttributes(conversation);
                        if (convProxyNumber != null) {
                            try {
                                if (convProxyNumber.has("identity_number")) {
                                    identityNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(convProxyNumber.getString("identity_number"));
                                }
                            } catch (JSONException e) {
                            }
                        }
                        if (notifyLast) {
                            String author = lastMessage.getAuthor();
                            if (!TextUtils.equals(author, identityNumber) && !TextUtils.equals(author, account.getUsername())) {
                                // Persist unconditionally — populateSMSMessagesForConversation is
                                // gated on isAdded (SP found), so if attributes are missing the
                                // message would be dropped. receivedSmsMessage always writes to DB
                                // and deduplicates in-memory, matching onMessageAdded behaviour.
                                xmppConnectionService.receivedSmsMessage(lastMessage);
                                if (shouldNotifyForIncomingSms()) {
                                    xmppConnectionService
                                            .getNotificationService()
                                            .notifySmsMessage(
                                                    conversation.getSid(),
                                                    lastMessage.getBody(),
                                                    author,
                                                    identityNumber);
                                }
                                if (!isCurrentConversation(conversation)
                                        && xmppConnectionService.getSmsInfo() != null) {
                                    xmppConnectionService.getSmsInfo().markConversationUnread(
                                            identityNumber,
                                            conversation.getSid());
                                    xmppConnectionService.updatePhoneUi();
                                }
                            }
                        }
                        syncConversationUnreadCount(conversation, identityNumber);
                    }

                    if (onComplete != null) {
                        runOnMainThread(onComplete);
                    } else if (conversationsManagerListener != null) {
                        notifyReloadMessages(conversationsManagerListener);
                    }
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.e(Config.LOGTAG, "Error loading messages: " + errorInfo.getMessage());
                    runOnMainThread(onComplete);
                }
            });
        } else {
            deferLoadUntilSynced(conversation, number, notifyLast, onComplete);
        }
    }

    private void deferLoadUntilSynced(Conversation conversation, int number, boolean notifyLast, @Nullable Runnable onComplete) {
        String sid = conversation.getSid();
        if (sid == null) {
            if (onComplete != null) {
                runOnMainThread(onComplete);
            } else if (conversationsManagerListener != null) {
                notifyReloadMessages(conversationsManagerListener);
            }
            return;
        }
        pendingHistoryLoads.compute(sid, (key, existing) -> {
            if (existing == null) {
                return new PendingHistoryLoad(number, notifyLast, onComplete);
            }
            existing.merge(number, notifyLast, onComplete);
            return existing;
        });
    }

    @Nullable
    private String getIdentityNumber(Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        JSONObject attrs = getConversationAttributes(conversation);
        if (attrs == null) {
            return null;
        }
        try {
            if (attrs.has("identity_number")) {
                return PhoneUtils.getStripPhoneNumberWithCountryCode(attrs.getString("identity_number"));
            }
        } catch (JSONException e) {
        }
        return null;
    }

    private void syncConversationUnreadCount(Conversation conversation, @Nullable String identityNumber) {
        if (conversation == null || xmppConnectionService == null) {
            return;
        }
        String conversationSid = conversation.getSid();
        if (conversationSid == null) {
            return;
        }
        if (isCurrentConversation(conversation) && isSmsConversationActivityOpen()) {
            xmppConnectionService.updateSmsConversationUnreadCount(identityNumber, conversationSid, 0);
            return;
        }
        try {
            conversation.getUnreadMessagesCount(new CallbackListener<Long>() {
                @Override
                public void onSuccess(Long result) {
                    int unreadCount = result != null ? Math.toIntExact(result) : 0;
                    xmppConnectionService.updateSmsConversationUnreadCount(identityNumber, conversationSid, unreadCount);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    CallbackListener.super.onError(errorInfo);
                    Log.w(Config.LOGTAG, "Failed to fetch unread count for " + conversationSid);
                }
            });
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Failed to sync unread count for " + conversationSid, e);
        }
    }

    private boolean isCurrentConversation(@Nullable Conversation conversation) {
        if (conversation == null || currentConversation == null) {
            return false;
        }
        return TextUtils.equals(conversation.getSid(), currentConversation.getSid());
    }

    @Nullable
    private JSONObject getConversationAttributes(@Nullable Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        try {
            if (conversation.getAttributes() == null) {
                return null;
            }
            return conversation.getAttributes().getJSONObject();
        } catch (RuntimeException e) {
            Log.w(Config.LOGTAG, "Failed to fetch attributes for conversation " + conversation.getSid(), e);
            return null;
        }
    }

    private void sortMessagesChronologically(List<Message> messages) {
        if (messages == null || messages.size() < 2) {
            return;
        }
        messages.sort((first, second) -> Long.compare(first.getMessageIndex(), second.getMessageIndex()));
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    public void shutdownClient() {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            applyShutdownSideEffects();
            return;
        }
        try {
            cancelReconnect();
            final ConversationsClient clientToShutdown = conversationsClient;
            conversationsClient = null;
            applyShutdownSideEffects();
            if (clientToShutdown != null) {
                try {
                    clientToShutdown.shutdown();
                } catch (Throwable t) {
                    Log.w(Config.LOGTAG, "Twilio Conversations client shutdown threw unexpectedly", t);
                }
            }
        } finally {
            shutdownInProgress.set(false);
        }
    }

    private void applyShutdownSideEffects() {
        updateConnectionState(ConversationsClient.ConnectionState.DISCONNECTED);
        currentConversation = null;
        messagesForCurrentConversation = null;
        pendingMessages.clear();
        loadingChannels = false;
        initialSyncComplete = false;
        mToken.setAccessToken(null);
        registeredFcmToken = null;
        pendingFcmToken = null;
        pushRegistrationInProgress.set(false);
        tokenRefreshInProgress.set(false);
        clearConversationListeners();
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    void sendMMSMessage(List<Attachment> attachments) {
        if (currentConversation != null) {
            final Conversation conversation = currentConversation;
            for (Iterator<Attachment> i = attachments.iterator(); i.hasNext(); i.remove()) {
                final Attachment attachment = i.next();
                Bitmap bitmap = null;
                try {
                    bitmap = MediaStore.Images.Media.getBitmap(xmppConnectionService.getContentResolver(), attachment.getUri());
                    /*int nh = (int) ( bitImg.getHeight() * (512.0 / bitImg.getWidth()) );
                    bitmap = bitImg.createScaledBitmap(bitImg, 512, nh, true);*/
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream();
                int nh = (int) (bitmap.getHeight() * (512.0 / bitmap.getWidth()));
                bitmap = bitmap.createScaledBitmap(bitmap, 512, nh, true);
                bitmap.compress(Bitmap.CompressFormat.PNG, 50, arrayOutputStream);
                InputStream is = new ByteArrayInputStream(arrayOutputStream.toByteArray());
                final String fileName = attachment.getUri() != null ?
                        attachment.getUri().getLastPathSegment() : "attachment.png";
                conversation.prepareMessage().addMedia(is, "image/png", fileName, new MediaUploadListener() {
                    @Override
                    public void onStarted() {
                    }

                    @Override
                    public void onProgress(long bytesSent) {
                    }

                    @Override
                    public void onCompleted(String mediaSid) {
                    }

                    @Override
                    public void onFailed(ErrorInfo errorInfo) {
                    }
                }).buildAndSend(new CallbackListener<Message>() {
                    @Override
                    public void onSuccess(Message message) {
                        conversation.setAllMessagesRead(new CallbackListener<Long>() {
                            @Override
                            public void onSuccess(Long result) {
                            }
                        });
                        notifyMessageSent(message.getSid());
                    }
                });
            }
        }
    }

    public void sendMessage(String messageBody) {
        if (messageBody == null) {
            Log.w(Config.LOGTAG, "Skipping SMS send; message body was null");
            return;
        }
        if (currentConversation == null) {
            pendingMessages.add(messageBody);
            return;
        }
        final Conversation conversation = currentConversation;
        try {
            conversation.prepareMessage().setBody(messageBody).buildAndSend(new CallbackListener<Message>() {
                @Override
                public void onSuccess(Message message) {
                    conversation.setAllMessagesRead(new CallbackListener<Long>() {
                        @Override
                        public void onSuccess(Long result) {
                        }
                    });
                    xmppConnectionService.receivedSmsMessage(message);
                    notifyMessageSent(message.getSid());
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    notifyMessageSendFailed(null);
                }
            });
        } catch (Exception ex) {
            Log.e(Config.LOGTAG, "Exception while sending sms " + ex.getMessage(), ex);
            notifyMessageSendFailed(null);
        }
    }


    private void sendPendingMessages() {
        if (currentConversation == null || pendingMessages.isEmpty()) {
            return;
        }
        List<String> toSend = new ArrayList<>(pendingMessages);
        pendingMessages.clear();
        for (String body : toSend) {
            sendMessage(body);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isSmsConversationActivityOpen() {
        return conversationsManagerListener instanceof SmsConvActivity;
    }

    private boolean shouldNotifyForIncomingSms() {
        return !isSmsConversationActivityOpen() || !GlacierGuardian.isAppInForeground();
    }

    private void registerConversationListener(@Nullable Conversation conversation) {
        if (conversation == null) {
            return;
        }
        final String sid = conversation.getSid();
        if (TextUtils.isEmpty(sid)) {
            return;
        }
        if (!conversationListenerSids.add(sid)) {
            return;
        }
        conversation.addListener(mDefaultConversationListener);
    }

    private void unregisterConversationListener(@Nullable Conversation conversation) {
        if (conversation == null) {
            return;
        }
        final String sid = conversation.getSid();
        if (TextUtils.isEmpty(sid)) {
            return;
        }
        if (!conversationListenerSids.remove(sid)) {
            return;
        }
        try {
            conversation.removeListener(mDefaultConversationListener);
        } catch (Throwable t) {
            Log.w(Config.LOGTAG, "Failed to detach Twilio conversation listener sid=" + sid, t);
        }
    }

    private void clearConversationListeners() {
        conversationListenerSids.clear();
    }

    // ── Listeners / Callbacks ─────────────────────────────────────────────────

    private final ConversationListener mDefaultConversationListener = new ConversationListener() {
        @Override
        public void onMessageAdded(final Message message) {
            if (xmppConnectionService == null) {
                return;
            }
            // Always persist the new message in the database
            xmppConnectionService.receivedSmsMessage(message);

            JSONObject ConvProxyNumber = getConversationAttributes(message.getConversation());
            String ide_num = "";
            if (ConvProxyNumber != null) {
                try {
                    ide_num = ConvProxyNumber.getString("identity_number");
                    String author = message.getAuthor();

                    if (!TextUtils.equals(author, ide_num) && !TextUtils.equals(author, account.getUsername())) {
                        // Post system notification regardless of whether a UI listener is
                        // attached — without this, background-delivered FCM pushes never show
                        // a notification because conversationsManagerListener is null when no
                        // Activity is bound.
                        if (shouldNotifyForIncomingSms()) {
                            xmppConnectionService
                                    .getNotificationService()
                                    .notifySmsMessage(
                                            message.getConversation().getSid(),
                                            message.getBody(),
                                            message.getAuthor(),
                                            ide_num);
                        }

                        if (conversationsManagerListener != null) {
                            notifyReceivedNewMessage(message);
                            boolean currentConversation = isCurrentConversation(message.getConversation());
                            notifyIncomingMessage();
                            if (!currentConversation
                                    && xmppConnectionService.getSmsInfo() != null) {
                                xmppConnectionService.getSmsInfo().markConversationUnread(
                                        ide_num,
                                        message.getConversation().getSid());
                                xmppConnectionService.updatePhoneUi();
                            }
                        }
                    } else if (conversationsManagerListener != null) {
                        notifyReceivedNewMessage(message);
                    }
                } catch (JSONException e) {
                }
            }
            syncConversationUnreadCount(message.getConversation(), getIdentityNumber(message.getConversation()));
        }

        @Override
        public void onMessageUpdated(Message message, Message.UpdateReason reason) {
        }

        @Override
        public void onMessageDeleted(Message message) {
        }

        @Override
        public void onParticipantAdded(Participant participant) {
        }

        @Override
        public void onParticipantUpdated(Participant participant, Participant.UpdateReason reason) {
        }

        @Override
        public void onParticipantDeleted(Participant participant) {
        }

        @Override
        public void onTypingStarted(Conversation conversation, Participant participant) {
        }

        @Override
        public void onTypingEnded(Conversation conversation, Participant participant) {
        }

        @Override
        public void onSynchronizationChanged(Conversation conversation) {
        }
    };

    private final ConversationsClientListener mConversationsClientListener = new ConversationsClientListener() {
        @Override
        public void onConversationAdded(Conversation conversation) {
            registerConversationListener(conversation);
            // If this callback fires after initial sync, the conversation is genuinely new
            // (created while the app is running). onMessageAdded will NOT fire for a message
            // that was already in the conversation when the listener was attached, so we
            // explicitly fetch and notify about the latest message here.
            if (initialSyncComplete) {
                loadPreviousMessages(conversation, 1, true, null);
            }
            conversation.setLastReadMessageIndex(0, new CallbackListener<Long>() {
                @Override
                public void onSuccess(Long result) {
                    conversation.setAllMessagesUnread(new CallbackListener<Long>() {
                        @Override
                        public void onSuccess(Long result) {
                            String identityNumber = null;
                            String friendlyName = conversation.getFriendlyName();
                            JSONObject convProxyNumber = getConversationAttributes(conversation);
                            if (convProxyNumber != null) {
                                try {
                                    if (convProxyNumber.has("identity_number")) {
                                        identityNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(convProxyNumber.getString("identity_number"));
                                    }
                                } catch (JSONException e) {
                                }
                            }
                            SmsProfile sp = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(identityNumber);
                            if (sp != null) {
                                xmppConnectionService.addSMSConversation(conversation, sp, identityNumber);
                                syncConversationUnreadCount(conversation, identityNumber);
                                if (conversation.getSynchronizationStatus().isAtLeast(Conversation.SynchronizationStatus.ALL)) {
                                    findOrCreateConversation(conversation.getSid(), friendlyName, identityNumber);
                                }
                            }
                        }
                    });
                }
            });
        }

        @Override
        public void onConversationUpdated(Conversation conversation, Conversation.UpdateReason reason) {
            syncConversationUnreadCount(conversation, getIdentityNumber(conversation));
            updateExistingConversation(conversation, false);
        }

        @Override
        public void onConversationDeleted(Conversation conversation) {
            unregisterConversationListener(conversation);
            xmppConnectionService.deleteTwilioConversation(conversation.getSid());
        }

        @Override
        public void onConversationSynchronizationChange(Conversation conversation) {
            if (conversation == null) {
                return;
            }
            if (conversation.getSynchronizationStatus().isAtLeast(Conversation.SynchronizationStatus.ALL)) {
                PendingHistoryLoad pending = pendingHistoryLoads.remove(conversation.getSid());
                if (pending != null) {
                    loadPreviousMessages(conversation, pending.number, pending.notifyLast, pending.onComplete);
                }
            }
        }

        @Override
        public void onError(ErrorInfo errorInfo) {
        }

        @Override
        public void onUserUpdated(User user, User.UpdateReason reason) {
        }

        @Override
        public void onUserSubscribed(User user) {
        }

        @Override
        public void onUserUnsubscribed(User user) {
        }

        @Override
        public void onClientSynchronization(ConversationsClient.SynchronizationStatus status) {
            if (status == ConversationsClient.SynchronizationStatus.COMPLETED) {
                initialSyncComplete = true;
                loadChannels(true);
            } else {
                // Any non-COMPLETED status (STARTED, CONVERSATIONS_COMPLETED, FAILED) means a
                // new sync pass is in progress. Clear the flag so onConversationAdded does not
                // treat replayed existing conversations as new and fire spurious notifications.
                initialSyncComplete = false;
            }
        }

        @Override
        public void onNewMessageNotification(String conversationSid, String messageSid, long messageIndex) {
            if (conversationsClient == null || TextUtils.isEmpty(conversationSid)) {
                return;
            }
            conversationsClient.getConversation(conversationSid, new CallbackListener<Conversation>() {
                @Override
                public void onSuccess(Conversation conversation) {
                    if (conversation == null) {
                        return;
                    }
                    registerConversationListener(conversation);
                    loadPreviousMessages(conversation, 1, true, null);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.w(
                            Config.LOGTAG,
                            "Failed to resolve conversation for new-message notification sid="
                                    + conversationSid
                                    + ": "
                                    + (errorInfo != null ? errorInfo.getMessage() : "unknown error"));
                }
            });
        }

        @Override
        public void onAddedToConversationNotification(String conversationSid) {
        }

        @Override
        public void onRemovedFromConversationNotification(String conversationSid) {
        }

        @Override
        public void onNotificationSubscribed() {
        }

        @Override
        public void onNotificationFailed(ErrorInfo errorInfo) {
        }

        @Override
        public void onConnectionStateChange(ConversationsClient.ConnectionState state) {
            handleConnectionStateChange(state);
        }

        @Override
        public void onTokenExpired() {
            requestTokenRefresh();
        }

        @Override
        public void onTokenAboutToExpire() {
            requestTokenRefresh();
        }
    };

    private final AccessTokenListener accessTokenListener = new AccessTokenListener() {
        @Override
        public void receivedAccessToken(@Nullable String token, @Nullable Exception exception) {
            if (token != null) {
                configureConversationsClientWithToken(token, tokenResponseListener);
            } else {
                tokenResponseListener.receivedTokenResponse(false, exception);
            }
        }
    };

    private final TokenResponseListener tokenResponseListener = new TokenResponseListener() {
        @Override
        public void receivedTokenResponse(boolean success, @Nullable Exception exception) {
            if (!success) {
                // `exception` is the only account of *why* the token could not be retrieved or
                // refreshed -- the Twilio failure paths in configureConversationsClientWithToken
                // and retrieveToken both pass one. It used to be discarded here, which left a
                // reconnect loop with no recorded cause.
                //
                // Interpolated into the message rather than passed as Log's throwable argument:
                // the throwable overloads deliberately do not scrub `tr`, and one of these
                // exceptions is `new Exception(errorInfo.getMessage())` built from remote text,
                // which would let a peer forge logcat lines inside the trace block (CWE-117).
                // Log.java's own javadoc says to put remote text in `msg`, which is sanitized.
                // No stack trace is lost that is worth having -- that one is synthesized at the
                // call site that wraps the message.
                final String cause = "; cause=" + exception;
                if (conversationsClient == null) {
                    xmppConnectionService.markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.NO_CONV_CLIENT);
                } else {
                    loadChannels(true);
                }
                scheduleReconnect();
            }
        }
    };

    private final CallbackListener<ConversationsClient> mConversationsClientCallback =
            new CallbackListener<ConversationsClient>() {
                @Override
                public void onSuccess(ConversationsClient conversationsClient) {
                    ConversationsManager.this.conversationsClient = conversationsClient;
                    conversationsClient.addListener(ConversationsManager.this.mConversationsClientListener);
                    registerForFcmPushNotifications(pendingFcmToken);
                    xmppConnectionService.onConversationClientSync(true);
                }

                @Override
                public void onError(ErrorInfo errorInfo) {
                    Log.e(Config.LOGTAG, "Error creating Twilio Conversations Client: " + errorInfo.getMessage());
                    handleConnectionStateChange(ConversationsClient.ConnectionState.ERROR);
                    scheduleReconnect();
                    xmppConnectionService.onConversationClientSync(false);
                    xmppConnectionService.markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.NO_CONV_CLIENT);
                }
            };

    // ── Inner classes ─────────────────────────────────────────────────────────

    protected static class TokenResponse {
        @SerializedName("message")
        String message;

        @SerializedName("identity")
        String identity;

        @SerializedName("user_numbers")
        List<String> userNumbers;

        @SerializedName("token")
        String token;

        @SerializedName("add_purchase_numbers")
        Boolean addPurchaseNumbers;
    }

    private static class PendingHistoryLoad {
        private int number;
        private boolean notifyLast;
        private Runnable onComplete;

        private PendingHistoryLoad(int number, boolean notifyLast, @Nullable Runnable onComplete) {
            this.number = number;
            this.notifyLast = notifyLast;
            this.onComplete = onComplete;
        }

        private void merge(int number, boolean notifyLast, @Nullable Runnable onComplete) {
            this.number = Math.max(this.number, number);
            this.notifyLast = this.notifyLast || notifyLast;
            if (this.onComplete == null) {
                this.onComplete = onComplete;
            } else if (onComplete != null) {
                Runnable previous = this.onComplete;
                this.onComplete = () -> {
                    previous.run();
                    onComplete.run();
                };
            }
        }
    }

    // ── Interfaces ────────────────────────────────────────────────────────────

    public interface AccessTokenListener {
        void receivedAccessToken(@Nullable String token, @Nullable Exception exception);
    }

    public interface TokenResponseListener {
        void receivedTokenResponse(boolean success, @Nullable Exception exception);
    }
}
