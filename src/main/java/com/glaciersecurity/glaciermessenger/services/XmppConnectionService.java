package com.glaciersecurity.glaciermessenger.services;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.s;
import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;
import static com.glaciersecurity.glaciermessenger.utils.Random.SECURE_RANDOM;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Messenger;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.security.KeyChain;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.entities.GlacierUserEntity;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiClient;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiResponses;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;
import com.glaciersecurity.glaciermessenger.entities.LatestVersionEntity;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.entities.TwilioMessageEntity;
import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.ui.ConversationsManager;
import com.glaciersecurity.glaciermessenger.ui.LocationPermissionActivity;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.LogRedactor;
import com.glaciersecurity.glaciermessenger.utils.NetworkManager;
import android.util.LruCache;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.BoolRes;
import androidx.annotation.IntegerRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.CognitoAccount;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.glaciersecurity.glaciermessenger.utils.OnSMSCompleteCallbackListener;
import com.glaciersecurity.glaciermessenger.utils.SmsMessagePreviewUtils;
import com.glaciersecurity.glaciermessenger.utils.SystemSecurityInfo;
import com.glaciersecurity.glaciermessenger.utils.SecurityHubReportStatus;
import com.glaciersecurity.glaciermessenger.utils.SecurityInfoApiClient;
import com.glaciersecurity.glaciermessenger.utils.TwilioAccessTokenParser;
import com.glaciersecurity.glaciermessenger.utils.VoiceRegistrationStatus;
import com.glaciersecurity.glaciermessenger.wireguard.WgTunnel;
import com.glaciersecurity.glaciermessenger.wireguard.WireGuardStateMonitor;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;

import org.conscrypt.Conscrypt;
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;
import org.json.JSONObject;

import android.media.MediaPlayer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.android.JabberIdContact;
import com.glaciersecurity.glaciermessenger.crypto.OmemoSetting;
import com.glaciersecurity.glaciermessenger.crypto.PgpDecryptionService;
import com.glaciersecurity.glaciermessenger.crypto.PgpEngine;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.XmppAxolotlMessage;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Blockable;
import com.glaciersecurity.glaciermessenger.entities.Bookmark;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.entities.MucOptions.OnRenameListener;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.entities.PresenceTemplate;
import com.glaciersecurity.glaciermessenger.entities.Reaction;
import com.glaciersecurity.glaciermessenger.entities.Roster;
import com.glaciersecurity.glaciermessenger.entities.ServiceDiscoveryResult;
import com.glaciersecurity.glaciermessenger.generator.AbstractGenerator;
import com.glaciersecurity.glaciermessenger.generator.IqGenerator;
import com.glaciersecurity.glaciermessenger.generator.MessageGenerator;
import com.glaciersecurity.glaciermessenger.generator.PresenceGenerator;
import com.glaciersecurity.glaciermessenger.http.HttpConnectionManager;
import com.glaciersecurity.glaciermessenger.parser.AbstractParser;
import com.glaciersecurity.glaciermessenger.parser.IqParser;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.persistance.UnifiedPushDatabase;
import com.glaciersecurity.glaciermessenger.receiver.SystemEventReceiver;
import com.glaciersecurity.glaciermessenger.ui.ChooseAccountForProfilePictureActivity;
import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;
import com.glaciersecurity.glaciermessenger.ui.RtpSessionActivity;
import com.glaciersecurity.glaciermessenger.ui.UiCallback;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnAvatarPublication;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnMediaLoaded;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSearchResultsAvailable;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.worker.VoicemailRefreshWorker;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.ConversationsFileObserver;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.utils.MimeUtils;
import com.glaciersecurity.glaciermessenger.utils.PhoneHelper;
import com.glaciersecurity.glaciermessenger.utils.QuickLoader;
import com.glaciersecurity.glaciermessenger.utils.ReplacingSerialSingleThreadExecutor;
import com.glaciersecurity.glaciermessenger.utils.ReplacingTaskManager;
import com.glaciersecurity.glaciermessenger.utils.Resolver;
import com.glaciersecurity.glaciermessenger.utils.SerialSingleThreadExecutor;
import com.glaciersecurity.glaciermessenger.utils.StringUtils;
import com.glaciersecurity.glaciermessenger.utils.WakeLockHelper;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.LocalizedContent;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.InvalidJid;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnContactStatusChanged;
import com.glaciersecurity.glaciermessenger.xmpp.OnKeyStatusUpdated;
import com.glaciersecurity.glaciermessenger.xmpp.OnMessageAcknowledged;
import com.glaciersecurity.glaciermessenger.xmpp.OnStatusChanged;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.chatstate.ChatState;
import com.glaciersecurity.glaciermessenger.xmpp.forms.Data;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.AbstractJingleConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.Media;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;
import com.glaciersecurity.glaciermessenger.xmpp.mam.MamReference;
import com.glaciersecurity.glaciermessenger.xmpp.pep.Avatar;
import com.glaciersecurity.glaciermessenger.xmpp.pep.PublishOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.twilio.voice.LogLevel;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.Voice;

import im.conversations.android.xmpp.model.stanza.Iq;
import me.leolin.shortcutbadger.ShortcutBadger;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;

public class XmppConnectionService extends Service {

    public static final String ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations";
    public static final String ACTION_MARK_AS_READ = "mark_as_read";
    public static final String ACTION_SNOOZE = "snooze";
    public static final String ACTION_CLEAR_MESSAGE_NOTIFICATION = "clear_message_notification";
    public static final String ACTION_CLEAR_MISSED_CALL_NOTIFICATION = "clear_missed_call_notification";
    public static final String ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error";
    public static final String ACTION_TRY_AGAIN = "try_again";

    public static final String ACTION_TEMPORARILY_DISABLE = "temporarily_disable";
    public static final String ACTION_RESTORED_BACKUP = "restored_backup";
    public static final String ACTION_REFRESH_VOICEMAILS = "refresh_voicemails";
    public static final String ACTION_PING = "ping";
    public static final String ACTION_IDLE_PING = "idle_ping";
    public static final String ACTION_INTERNAL_PING = "internal_ping";
    public static final String ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh";
    public static final String ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received";
    public static final String ACTION_CONVERSATIONS_FCM_TOKEN_REFRESH = "conversations_fcm_token_refresh";
    public static final String EXTRA_CONVERSATIONS_FCM_TOKEN = "extra_conversations_fcm_token";
    public static final String ACTION_DISMISS_CALL = "dismiss_call";
    public static final String ACTION_END_CALL = "end_call";
    public static final String ACTION_CALL_INTEGRATION_SERVICE_STARTED = "call_integration_service_started";
    private static final String ACTION_POST_CONNECTIVITY_CHANGE = "com.glaciersecurity.glaciermessenger.POST_CONNECTIVITY_CHANGE";
    public static final String ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS = "com.glaciersecurity.glaciermessenger.UNIFIED_PUSH_RENEW";
    public static final String ACTION_QUICK_LOG = "com.glaciersecurity.glaciermessenger.QUICK_LOG";

    private static final String SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp";

    public final CountDownLatch restoredFromDatabaseLatch = new CountDownLatch(1);
    private final AtomicBoolean fileAvailabilityCheckRunning = new AtomicBoolean(false);
    private final Set<OnMediaAvailabilityChanged> mOnMediaAvailabilityChanged =
            new CopyOnWriteArraySet<>();
    private final DefaultLifecycleObserver foregroundObserver =
            new DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull final LifecycleOwner owner) {
                    refreshFileAvailability();
                }
            };
    private final static Executor FILE_OBSERVER_EXECUTOR = Executors.newSingleThreadExecutor();
    private final static Executor FILE_ATTACHMENT_EXECUTOR = Executors.newSingleThreadExecutor();

    private final ScheduledExecutorService internalPingExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService voicemailRefreshExecutor = Executors.newSingleThreadScheduledExecutor();
    private final static SerialSingleThreadExecutor VIDEO_COMPRESSION_EXECUTOR = new SerialSingleThreadExecutor("VideoCompression");
    private final SerialSingleThreadExecutor mDatabaseWriterExecutor = new SerialSingleThreadExecutor("DatabaseWriter");
    private final SerialSingleThreadExecutor mDatabaseReaderExecutor = new SerialSingleThreadExecutor("DatabaseReader");
    private final SerialSingleThreadExecutor mNotificationExecutor = new SerialSingleThreadExecutor("NotificationExecutor");
    // The security sweep queries the keyguard, the biometric service and the package manager,
    // and shells out for system properties, so it never runs on the caller's thread. A serial
    // executor also keeps the cached SecurityInfo single-writer.
    private final SerialSingleThreadExecutor mSecurityInfoExecutor = new SerialSingleThreadExecutor("SecurityInfo");
    private final ReplacingTaskManager mRosterSyncTaskManager = new ReplacingTaskManager();
    private final IBinder mBinder = new XmppConnectionBinder();
    private final List<Conversation> conversations = new CopyOnWriteArrayList<>();
    private final IqGenerator mIqGenerator = new IqGenerator(this);
    private final Set<String> mInProgressAvatarFetches = new HashSet<>();
    private final Set<String> mOmittedPepAvatarFetches = new HashSet<>();
    private final HashSet<Jid> mLowPingTimeoutMode = new HashSet<>();
    private final Consumer<Iq> mDefaultIqHandler = (packet) -> {
        if (packet.getType() != Iq.Type.RESULT) {
            final var error = packet.getError();
            String text = error != null ? error.findChildContent("text") : null;
        }
    };
    public DatabaseBackend databaseBackend;
    private final ReplacingSerialSingleThreadExecutor mContactMergerExecutor = new ReplacingSerialSingleThreadExecutor("ContactMerger");
    private long mLastActivity = 0;

    private final AppSettings appSettings = new AppSettings(this);
    private final FileBackend fileBackend = new FileBackend(this);
    private MemorizingTrustManager mMemorizingTrustManager;
    private final NotificationService mNotificationService = new NotificationService(this);
    private final UnifiedPushBroker unifiedPushBroker = new UnifiedPushBroker(this);
    private final ChannelDiscoveryService mChannelDiscoveryService = new ChannelDiscoveryService(this);
    private final ShortcutService mShortcutService = new ShortcutService(this);
    private final AtomicBoolean mInitialAddressbookSyncCompleted = new AtomicBoolean(false);
    private final AtomicBoolean mOngoingVideoTranscoding = new AtomicBoolean(false);
    private final AtomicBoolean mForceDuringOnCreate = new AtomicBoolean(false);
    private final AtomicBoolean phoneSyncInProgress = new AtomicBoolean(false);
    // True once a phone sync has completed and determined the account has no assigned
    // numbers (or SMS is disabled). In that case the Twilio Conversations client is never
    // initialized, so this flag lets hasPhoneLoaded() report a terminal "loaded" state
    // instead of leaving the Phone UI stuck showing a loading/connecting spinner forever.
    private final AtomicBoolean phoneSyncCompletedNoNumbers = new AtomicBoolean(false);
    private final AtomicBoolean phonePrefetchTriggered = new AtomicBoolean(false);
    private final AtomicBoolean phonePrefetchInProgress = new AtomicBoolean(false);
    private final Handler phoneSyncTimeoutHandler = new Handler(Looper.getMainLooper());
    private static final long INITIAL_PHONE_SYNC_TIMEOUT_MS = 20000L; // 20 seconds
    private static final long MAX_PHONE_SYNC_TIMEOUT_MS = 120000L; // 2 minutes
    private static final int MAX_PHONE_SYNC_RETRIES = 3;
    private int phoneSyncRetryCount = 0;
    private final AtomicBoolean phoneSyncPrefetchStarted = new AtomicBoolean(false);
    private final Runnable phoneSyncTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (phoneSyncInProgress.get()) {
                if (phoneSyncRetryCount < MAX_PHONE_SYNC_RETRIES) {
                    phoneSyncRetryCount++;
                    Log.w(Config.LOGTAG, "Phone sync timeout, retrying forced sync (" + phoneSyncRetryCount + ")");
                    syncPhoneInfo(true);
                } else {
                    Log.e(Config.LOGTAG, "Phone sync failed after " + phoneSyncRetryCount + " attempts");
                    phoneSyncInProgress.set(false);
                    phoneSyncTimeoutHandler.removeCallbacks(this);
                    updateConsoleUi(false);
                }
            }
        }
    };
    private final AtomicReference<OngoingCall> ongoingCall = new AtomicReference<>();
    private final MessageGenerator mMessageGenerator = new MessageGenerator(this);
    public OnContactStatusChanged onContactStatusChanged = (contact, online) -> {
        Conversation conversation = find(getConversations(), contact);
        if (conversation != null) {
            if (online) {
                if (contact.getPresences().size() == 1) {
                    sendUnsentMessages(conversation);
                }
            }
        }
    };
    private final PresenceGenerator mPresenceGenerator = new PresenceGenerator(this);
    private List<Account> accounts;
    private List<GlacierOrgEntity> organizations;
    private final JingleConnectionManager mJingleConnectionManager = new JingleConnectionManager(this);
    private final HttpConnectionManager mHttpConnectionManager = new HttpConnectionManager(this);
    private final AvatarService mAvatarService = new AvatarService(this);
    private final ExternalCallManager externalCallManager = new ExternalCallManager(this);
    private final MessageArchiveService mMessageArchiveService = new MessageArchiveService(this);
    private final PushManagementService mPushManagementService = new PushManagementService(this);
    private final QuickConversationsService mQuickConversationsService = new QuickConversationsService(this);
    private final ConversationsManager conversationsManager = new ConversationsManager(this);

    private final ConversationsFileObserver fileObserver = new ConversationsFileObserver(
            Environment.getExternalStorageDirectory().getAbsolutePath()
    ) {
        @Override
        public void onEvent(final File file) {
            markFileDeleted(file);
        }
    };
    private final OnMessageAcknowledged mOnMessageAcknowledgedListener = new OnMessageAcknowledged() {

        @Override
        public boolean onMessageAcknowledged(final Account account, final Jid to, final String id) {
            if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                final String sessionId = id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                mJingleConnectionManager.updateProposedSessionDiscovered(
                        account,
                        to,
                        sessionId,
                        JingleConnectionManager.DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED
                );
            }


            final Jid bare = to.asBareJid();

            for (final Conversation conversation : getConversations()) {
                if (conversation.getAccount() == account && conversation.getJid().asBareJid().equals(bare)) {
                    final Message message = conversation.findUnsentMessageWithUuid(id);
                    if (message != null) {
                        message.setStatus(Message.STATUS_SEND);
                        message.setErrorMessage(null);
                        databaseBackend.updateMessage(message, false);
                        return true;
                    }
                }
            }
            return false;
        }
    };

    private boolean destroyed = false;

    private int unreadCount = -1;
    private int unreadVoicemailCount = 0;

    //Ui callback listeners
    private final Set<OnConversationUpdate> mOnConversationUpdates = Collections.newSetFromMap(new WeakHashMap<OnConversationUpdate, Boolean>());
    private final Set<OnShowErrorToast> mOnShowErrorToasts = Collections.newSetFromMap(new WeakHashMap<OnShowErrorToast, Boolean>());
    private final Set<OnAccountUpdate> mOnAccountUpdates = Collections.newSetFromMap(new WeakHashMap<OnAccountUpdate, Boolean>());
    private final Set<OnOrganizationUpdate> mOnOrganizationUpdates = Collections.newSetFromMap(new WeakHashMap<OnOrganizationUpdate, Boolean>());
    private final Set<OnAmplifySync> mOnAmplifySync = Collections.newSetFromMap(new WeakHashMap<OnAmplifySync, Boolean>());
    private final Set<OnCoreStatusUpdate> mOnCoreStatusUpdates = Collections.newSetFromMap(new WeakHashMap<OnCoreStatusUpdate, Boolean>());
    private final Set<OnPhoneUpdate> mOnPhoneUpdates = Collections.newSetFromMap(new WeakHashMap<OnPhoneUpdate, Boolean>());
    private final Set<OnCaptchaRequested> mOnCaptchaRequested = Collections.newSetFromMap(new WeakHashMap<OnCaptchaRequested, Boolean>());
    private final Set<OnRosterUpdate> mOnRosterUpdates = Collections.newSetFromMap(new WeakHashMap<OnRosterUpdate, Boolean>());
    private final Set<OnUpdateBlocklist> mOnUpdateBlocklist = Collections.newSetFromMap(new WeakHashMap<OnUpdateBlocklist, Boolean>());
    private final Set<OnMucRosterUpdate> mOnMucRosterUpdate = Collections.newSetFromMap(new WeakHashMap<OnMucRosterUpdate, Boolean>());
    private final Set<OnKeyStatusUpdated> mOnKeyStatusUpdated = Collections.newSetFromMap(new WeakHashMap<OnKeyStatusUpdated, Boolean>());
    private final Set<OnJingleRtpConnectionUpdate> onJingleRtpConnectionUpdate = Collections.newSetFromMap(new WeakHashMap<OnJingleRtpConnectionUpdate, Boolean>());
    private final Set<OnNativeContactUpdate> mOnNativeContactUpdates = Collections.newSetFromMap(new WeakHashMap<OnNativeContactUpdate, Boolean>());
    private final Set<OnConsoleStatus> mOnConsoleStatus = Collections.newSetFromMap(new WeakHashMap<OnConsoleStatus, Boolean>());


    private final Object LISTENER_LOCK = new Object();

    private final Handler disappearingMessagesHandler = new Handler(Looper.getMainLooper());
    private Runnable disappearingMessagesRunnable;


    public final Set<String> FILENAMES_TO_IGNORE_DELETION = new HashSet<>();

    private final AtomicBoolean pendingPackageReplaceCoreProfileImport = new AtomicBoolean(false);
    private final AtomicBoolean glacierUserDataRefreshed = new AtomicBoolean(false);

    /**
     * Set when a Glacier organization lookup fails. The lookup is fired once from the database
     * restore at service start and is otherwise fire-and-forget, so an attempt that lands before
     * the network or auth session is ready -- which is exactly what an in-place upgrade does,
     * since the service restarts immediately -- used to leave the org settings stale until the
     * user pulled to refresh. {@link #retryGlacierOrganizationIfLastAttemptFailed} re-runs it on
     * the next bind instead.
     */
    private final AtomicBoolean glacierOrgFetchFailed = new AtomicBoolean(false);


    private final AtomicLong mLastExpiryRun = new AtomicLong(0);
    private final LruCache<Pair<String, String>, ServiceDiscoveryResult> discoCache = new LruCache<>(20);
    private final OnStatusChanged statusListener = new OnStatusChanged() {

        @Override
        public void onStatusChanged(final Account account) {
            XmppConnection connection = account.getXmppConnection();
            updateAccountUi();

            if (account.getStatus() == Account.State.ONLINE || account.getStatus().isError()) {
                mQuickConversationsService.signalAccountStateChange();
            }

            if (account.getStatus() == Account.State.ONLINE) {
                synchronized (mLowPingTimeoutMode) {
                    if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                    }
                }
                if (account.setShowErrorNotification(true)) {
                    databaseBackend.updateAccount(account);
                }
                mMessageArchiveService.executePendingQueries(account);
                if (connection != null && connection.getFeatures().csi()) {
                    if (checkListeners()) {
                        connection.sendInactive();
                    } else {
                        connection.sendActive();
                    }
                }
                List<Conversation> conversations = getConversations();
                for (Conversation conversation : conversations) {
                    final boolean inProgressJoin;
                    synchronized (account.inProgressConferenceJoins) {
                        inProgressJoin = account.inProgressConferenceJoins.contains(conversation);
                    }
                    final boolean pendingJoin;
                    synchronized (account.pendingConferenceJoins) {
                        pendingJoin = account.pendingConferenceJoins.contains(conversation);
                    }
                    if (conversation.getAccount() == account
                            && !pendingJoin
                            && !inProgressJoin) {
                        sendUnsentMessages(conversation);
                    }
                }
                final List<Conversation> pendingLeaves;
                synchronized (account.pendingConferenceLeaves) {
                    pendingLeaves = new ArrayList<>(account.pendingConferenceLeaves);
                    account.pendingConferenceLeaves.clear();

                }
                for (Conversation conversation : pendingLeaves) {
                    leaveMuc(conversation);
                }
                final List<Conversation> pendingJoins;
                synchronized (account.pendingConferenceJoins) {
                    pendingJoins = new ArrayList<>(account.pendingConferenceJoins);
                    account.pendingConferenceJoins.clear();
                }
                for (Conversation conversation : pendingJoins) {
                    joinMuc(conversation);
                }
                scheduleWakeUpCall(Config.PING_MAX_INTERVAL, account.getUuid().hashCode());
                maybePrefetchPhoneInfo(account);
            } else if (account.getStatus() == Account.State.OFFLINE || account.getStatus() == Account.State.DISABLED || account.getStatus() == Account.State.LOGGED_OUT) {
                resetSendingToWaiting(account);
                if (account.isConnectionEnabled() && isInLowPingTimeoutMode(account)) {
                    reconnectAccount(account, true, false);
                } else {
                    final int timeToReconnect = SECURE_RANDOM.nextInt(10) + 2;
                    scheduleWakeUpCall(timeToReconnect, account.getUuid().hashCode());
                }
            } else if (account.getStatus() == Account.State.REGISTRATION_SUCCESSFUL) {
                databaseBackend.updateAccount(account);
                reconnectAccount(account, true, false);
            } else if (account.getStatus() != Account.State.CONNECTING) {
                resetSendingToWaiting(account);
                if (connection != null && account.getStatus().isAttemptReconnect()) {
                    final boolean aggressive = account.getStatus() == Account.State.SEE_OTHER_HOST
                            || hasJingleRtpConnection(account);
                    final int next = connection.getTimeToNextAttempt(aggressive);
                    final boolean lowPingTimeoutMode = isInLowPingTimeoutMode(account);
                    if (next <= 0) {
                        reconnectAccount(account, true, false);
                    } else {
                        final int attempt = connection.getAttempt() + 1;
                        scheduleWakeUpCall(next, account.getUuid().hashCode());
                        if (aggressive) {
                            internalPingExecutor.schedule(
                                    XmppConnectionService.this::manageAccountConnectionStatesInternal,
                                    (next * 1000L) + 50,
                                    TimeUnit.MILLISECONDS
                            );
                        }
                    }
                }
            }
            getNotificationService().updateErrorNotification();
        }
    };
    private OpenPgpServiceConnection pgpServiceConnection;
    private PgpEngine mPgpEngine = null;
    private WakeLock wakeLock;
    private LruCache<String, Bitmap> mBitmapCache;
    private LruCache<String, Drawable> mDrawableCache;
    private final BroadcastReceiver mInternalEventReceiver = new InternalEventReceiver();
    private final BroadcastReceiver mInternalScreenEventReceiver = new InternalEventReceiver();

    private static String generateFetchKey(Account account, final Avatar avatar) {
        return account.getJid().asBareJid() + "_" + avatar.owner + "_" + avatar.sha1sum;
    }

    private boolean isInLowPingTimeoutMode(Account account) {
        synchronized (mLowPingTimeoutMode) {
            return mLowPingTimeoutMode.contains(account.getJid().asBareJid());
        }
    }

    public void startOngoingVideoTranscodingForegroundNotification() {
        mOngoingVideoTranscoding.set(true);
        toggleForegroundService();
    }

    public void stopOngoingVideoTranscodingForegroundNotification() {
        mOngoingVideoTranscoding.set(false);
        toggleForegroundService();
    }

    public boolean areMessagesInitialized() {
        return this.restoredFromDatabaseLatch.getCount() == 0;
    }

    public PgpEngine getPgpEngine() {
        if (!Config.supportOpenPgp()) {
            return null;
        } else if (pgpServiceConnection != null && pgpServiceConnection.isBound()) {
            if (this.mPgpEngine == null) {
                this.mPgpEngine = new PgpEngine(new OpenPgpApi(
                        getApplicationContext(),
                        pgpServiceConnection.getService()), this);
            }
            return mPgpEngine;
        } else {
            return null;
        }

    }

    public OpenPgpApi getOpenPgpApi() {
        if (!Config.supportOpenPgp()) {
            return null;
        } else if (pgpServiceConnection != null && pgpServiceConnection.isBound()) {
            return new OpenPgpApi(this, pgpServiceConnection.getService());
        } else {
            return null;
        }
    }

    public AppSettings getAppSettings() {
        return this.appSettings;
    }

    public FileBackend getFileBackend() {
        return this.fileBackend;
    }

    public AvatarService getAvatarService() {
        return this.mAvatarService;
    }

    public ExternalCallManager getExternalCallManager() {
        return this.externalCallManager;
    }


    public void attachLocationToConversation(final Conversation conversation, final Uri uri, final UiCallback<Message> callback) {
        int encryption = conversation.getNextEncryption();
        if (encryption == Message.ENCRYPTION_PGP) {
            encryption = Message.ENCRYPTION_DECRYPTED;
        }
        Message message = new Message(conversation, uri.toString(), encryption);
        Message.configurePrivateMessage(message);
        if (encryption == Message.ENCRYPTION_DECRYPTED) {
            getPgpEngine().encrypt(message, callback);
        } else {
            sendMessage(message);
            callback.success(message);
        }
    }

    public void attachFileToConversation(final Conversation conversation, final Uri uri, final String type, final UiCallback<Message> callback) {
        final Message message;
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            message = new Message(conversation, "", Message.ENCRYPTION_DECRYPTED);
        } else {
            message = new Message(conversation, "", conversation.getNextEncryption());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_FILE);
        }
        final AttachFileToConversationRunnable runnable = new AttachFileToConversationRunnable(this, uri, type, message, callback);
        if (runnable.isVideoMessage()) {
            VIDEO_COMPRESSION_EXECUTOR.execute(runnable);
        } else {
            FILE_ATTACHMENT_EXECUTOR.execute(runnable);
        }
    }

    public void attachImageToConversation(final Conversation conversation, final Uri uri,  final String type, final UiCallback<Message> callback) {
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(this, uri, type);
        final String compressPictures = getCompressPicturesPreference();

        if ("never".equals(compressPictures)
                || ("auto".equals(compressPictures) && getFileBackend().useImageAsIs(uri))
                || (mimeType != null && mimeType.endsWith("/gif"))
                || getFileBackend().unusualBounds(uri)) {
            attachFileToConversation(conversation, uri, mimeType, callback);
            return;
        }
        final Message message;
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            message = new Message(conversation, "", Message.ENCRYPTION_DECRYPTED);
        } else {
            message = new Message(conversation, "", conversation.getNextEncryption());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_IMAGE);
        }
        FILE_ATTACHMENT_EXECUTOR.execute(() -> {
            try {
                getFileBackend().copyImageToPrivateStorage(message, uri);
            } catch (FileBackend.ImageCompressionException e) {
                attachFileToConversation(conversation, uri, mimeType, callback);
                return;
            } catch (final FileBackend.FileCopyException e) {
                if (callback != null) {
                    callback.error(e.getResId());
                }
                return;
            }
            if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
                final PgpEngine pgpEngine = getPgpEngine();
                if (pgpEngine != null) {
                    pgpEngine.encrypt(message, callback);
                } else if (callback != null) {
                    callback.error(R.string.unable_to_connect_to_keychain);
                }
            } else {
                sendMessage(message);
                if (callback != null) {
                    callback.success(message);
                }
            }
        });
    }

    public Conversation find(Bookmark bookmark) {
        return find(bookmark.getAccount(), bookmark.getJid());
    }

    public Conversation find(final Account account, final Jid jid) {
        return find(getConversations(), account, jid);
    }

    public boolean isMuc(final Account account, final Jid jid) {
        final Conversation c = find(account, jid);
        return c != null && c.getMode() == Conversational.MODE_MULTI;
    }

    public void search(final List<String> term, final String uuid, final OnSearchResultsAvailable onSearchResultsAvailable) {
        MessageSearchTask.search(this, term, uuid, onSearchResultsAvailable);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        final String action = Strings.nullToEmpty(intent == null ? null : intent.getAction());
        final boolean needsForegroundService = intent != null && intent.getBooleanExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, false);
        if (needsForegroundService) {
            toggleForegroundService(true);
        }
        final String uuid = intent == null ? null : intent.getStringExtra("uuid");
        switch (action) {
            case QuickConversationsService.SMS_RETRIEVED_ACTION:
                mQuickConversationsService.handleSmsReceived();
                break;
            case ConnectivityManager.CONNECTIVITY_ACTION:
                if (hasInternetConnection()) {
                    if (Config.POST_CONNECTIVITY_CHANGE_PING) {
                        schedulePostConnectivityChange();
                    }
                    if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
                        resetAllAttemptCounts(true, false);
                        reconnectAllEligibleAccountsImmediately();
                    } else {
                        Resolver.clearCache();
                    }
                    if (phoneSyncInProgress.get()) {
                        if (getFirstAccount() != null) {
                            syncPhoneInfo(true);
                        }
                    }
                }
                break;
            case Intent.ACTION_MY_PACKAGE_REPLACED:

                handlePackageReplaced();
                break;
            case Intent.ACTION_SHUTDOWN:
                logoutAndSave(true);
                return START_NOT_STICKY;
            case ACTION_RESTORED_BACKUP:
                reloadFromDatabase();
                break;
            case ACTION_CLEAR_MESSAGE_NOTIFICATION:
                mNotificationExecutor.execute(() -> {
                    try {
                        final Conversation c = findConversationByUuid(uuid);
                        if (c != null) {
                            mNotificationService.clearMessages(c);
                        } else {
                            mNotificationService.clearMessages();
                        }
                        restoredFromDatabaseLatch.await();

                    } catch (InterruptedException e) {
                    }
                });
                break;
            case ACTION_REFRESH_VOICEMAILS:
                requestVoicemailRefresh();
                break;
            case ACTION_CLEAR_MISSED_CALL_NOTIFICATION:
                mNotificationExecutor.execute(() -> {
                    try {
                        final Conversation c = findConversationByUuid(uuid);
                        if (c != null) {
                            mNotificationService.clearMissedCalls(c);
                        } else {
                            mNotificationService.clearMissedCalls();
                        }
                        restoredFromDatabaseLatch.await();

                    } catch (InterruptedException e) {
                    }
                });
                break;
            case ACTION_DISMISS_CALL: {
                if (intent == null) {
                    break;
                }
                final String sessionId = intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                mJingleConnectionManager.rejectRtpSession(sessionId);
                break;
            }
            case ACTION_END_CALL: {
                if (intent == null) {
                    break;
                }
                final String sessionId = intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                mJingleConnectionManager.endRtpSession(sessionId);
            }
            break;
            case ACTION_DISMISS_ERROR_NOTIFICATIONS:
                dismissErrorNotifications();
                break;
            case ACTION_TRY_AGAIN:
                resetAllAttemptCounts(false, true);
                break;
            case ACTION_REPLY_TO_CONVERSATION:
                final Bundle remoteInput = intent == null ? null : RemoteInput.getResultsFromIntent(intent);
                if (remoteInput == null) {
                    break;
                }
                final CharSequence body = remoteInput.getCharSequence("text_reply");
                final boolean dismissNotification = intent.getBooleanExtra("dismiss_notification", false);
                final String lastMessageUuid = intent.getStringExtra("last_message_uuid");
                if (body == null || body.length() <= 0) {
                    break;
                }
                mNotificationExecutor.execute(() -> {
                    try {
                        restoredFromDatabaseLatch.await();
                        final Conversation c = findConversationByUuid(uuid);
                        if (c != null) {
                            directReply(c, body.toString(), lastMessageUuid, dismissNotification);
                        }
                    } catch (InterruptedException e) {
                    }
                });
                break;
            case ACTION_MARK_AS_READ:
                mNotificationExecutor.execute(() -> {
                    final Conversation c = findConversationByUuid(uuid);
                    if (c == null) {
                        return;
                    }
                    try {
                        restoredFromDatabaseLatch.await();
                        sendReadMarker(c, null);
                    } catch (InterruptedException e) {
                    }

                });
                break;
            case ACTION_SNOOZE:
                mNotificationExecutor.execute(() -> {
                    final Conversation c = findConversationByUuid(uuid);
                    if (c == null) {
                        return;
                    }
                    c.setMutedTill(System.currentTimeMillis() + 30 * 60 * 1000);
                    mNotificationService.clearMessages(c);
                    updateConversation(c);
                });
            case AudioManager.RINGER_MODE_CHANGED_ACTION:
            case NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED:
                if (dndOnSilentMode()) {
                    refreshAllPresences();
                }
                break;
            case Intent.ACTION_SCREEN_ON:
                deactivateGracePeriod();
            case Intent.ACTION_USER_PRESENT:
            case Intent.ACTION_SCREEN_OFF:
                if (awayWhenScreenLocked()) {
                    refreshAllPresences();
                }
                break;
            case ACTION_FCM_TOKEN_REFRESH:
                refreshAllFcmTokens();
                break;
            case ACTION_CONVERSATIONS_FCM_TOKEN_REFRESH:
                if (intent != null) {
                    String fcmToken = intent.getStringExtra(EXTRA_CONVERSATIONS_FCM_TOKEN);
                    conversationsManager.registerForFcmPushNotifications(fcmToken);
                } else {
                    conversationsManager.registerForFcmPushNotifications(null);
                }
                break;
            case ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS:
                if (intent == null) {
                    break;
                }
                final String instance = intent.getStringExtra("instance");
                final String application = intent.getStringExtra("application");
                final Messenger messenger = intent.getParcelableExtra("messenger");
                final UnifiedPushBroker.PushTargetMessenger pushTargetMessenger;
                if (messenger != null && application != null && instance != null) {
                    pushTargetMessenger = new UnifiedPushBroker.PushTargetMessenger(new UnifiedPushDatabase.PushTarget(application, instance),messenger);
                } else {
                    pushTargetMessenger = null;
                }
                final Optional<UnifiedPushBroker.Transport> transport = renewUnifiedPushEndpoints(pushTargetMessenger);
                if (instance != null && transport.isPresent()) {
                    unifiedPushBroker.rebroadcastEndpoint(messenger, instance, transport.get());
                }
                break;
            case ACTION_IDLE_PING:
                scheduleNextIdlePing();
                break;
            case ACTION_FCM_MESSAGE_RECEIVED:
                break;
            case ACTION_QUICK_LOG:
                final String message = intent == null ? null : intent.getStringExtra("message");
                if (message != null && Config.QUICK_LOG) {
                    quickLog(message);
                }
                break;
            case Intent.ACTION_SEND:
                final Uri uri = intent == null ? null : intent.getData();
                return START_STICKY;
            case ACTION_TEMPORARILY_DISABLE:
                toggleSoftDisabled(true);
                if (checkListeners()) {
                    stopSelf();
                }
                return START_NOT_STICKY;
        }
        final var extras =  intent == null ? null : intent.getExtras();
        try {
            internalPingExecutor.execute(() -> manageAccountConnectionStates(action, extras));
        } catch (final RejectedExecutionException e) {
            Log.e(Config.LOGTAG, "can not schedule connection states manager");
        }
        if (SystemClock.elapsedRealtime() - mLastExpiryRun.get() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages();
        }
        return START_STICKY;
    }

    private void quickLog(final String message) {
        if (Strings.isNullOrEmpty(message)) {
            return;
        }
        final Account account = AccountUtils.getFirstEnabled(this);
        if (account == null) {
            return;
        }
        final Conversation conversation =
                findOrCreateConversation(account, Config.BUG_REPORTS, false, true);
        final Message report = new Message(conversation, message, Message.ENCRYPTION_NONE);
        report.setStatus(Message.STATUS_RECEIVED);
        conversation.add(report);
        databaseBackend.createMessage(report);
        updateConversationUi();
    }

    private void manageAccountConnectionStatesInternal() {
        manageAccountConnectionStates(ACTION_INTERNAL_PING, null);
    }

    private synchronized void manageAccountConnectionStates(
            final String action, final Bundle extras) {
        final String pushedAccountHash = extras == null ? null : extras.getString("account");
        final boolean interactive = java.util.Objects.equals(ACTION_TRY_AGAIN, action);
        WakeLockHelper.acquire(wakeLock);
        boolean pingNow =
                ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                        || (Config.POST_CONNECTIVITY_CHANGE_PING
                        && ACTION_POST_CONNECTIVITY_CHANGE.equals(action));
        final HashSet<Account> pingCandidates = new HashSet<>();
        final String androidId = pushedAccountHash == null ? null : PhoneHelper.getAndroidId(this);
        for (final Account account : accounts) {
            final boolean pushWasMeantForThisAccount =
                    androidId != null
                            && CryptoHelper.getAccountFingerprint(account, androidId)
                            .equals(pushedAccountHash);
            pingNow |=
                    processAccountState(
                            account,
                            interactive,
                            "ui".equals(action),
                            pushWasMeantForThisAccount,
                            pingCandidates);
        }
        if (pingNow) {
            for (final Account account : pingCandidates) {
                final boolean lowTimeout = isInLowPingTimeoutMode(account);
                account.getXmppConnection().sendPing();
                scheduleWakeUpCall(
                        lowTimeout ? Config.LOW_PING_TIMEOUT : Config.PING_TIMEOUT,
                        account.getUuid().hashCode());
            }
        }
        WakeLockHelper.release(wakeLock);
    }

    private boolean processAccountState(final Account account, final boolean interactive, final boolean isUiAction, final boolean isAccountPushed, final HashSet<Account> pingCandidates) {
        if (!account.getStatus().isAttemptReconnect()) {
            return false;
        }
        // No internet check up front: it misreads VPN-in-airplane-mode and USB tethering, and an
        // attempt with no network fails fast. NO_INTERNET / AIRPLANE_MODE now come from the failed
        // attempt and fall through to the backoff below (upstream c8bb1a6c7).
        if (account.getStatus() == Account.State.ONLINE) {
            synchronized (mLowPingTimeoutMode) {
                long lastReceived = account.getXmppConnection().getLastPacketReceived();
                long lastSent = account.getXmppConnection().getLastPingSent();
                long pingInterval = isUiAction ? Config.PING_MIN_INTERVAL * 1000 : Config.PING_MAX_INTERVAL * 1000;
                long msToNextPing = (Math.max(lastReceived, lastSent) + pingInterval) - SystemClock.elapsedRealtime();
                int pingTimeout = mLowPingTimeoutMode.contains(account.getJid().asBareJid()) ? Config.LOW_PING_TIMEOUT * 1000 : Config.PING_TIMEOUT * 1000;
                long pingTimeoutIn = (lastSent + pingTimeout) - SystemClock.elapsedRealtime();
                if (lastSent > lastReceived) {
                    if (pingTimeoutIn < 0) {
                        this.reconnectAccount(account, true, interactive);
                    } else {
                        int secs = (int) (pingTimeoutIn / 1000);
                        this.scheduleWakeUpCall(secs, account.getUuid().hashCode());
                    }
                } else {
                    pingCandidates.add(account);
                    if (isAccountPushed) {
                        if (mLowPingTimeoutMode.add(account.getJid().asBareJid())) {
                        }
                        return true;
                    } else if (msToNextPing <= 0) {
                        return true;
                    } else {
                        this.scheduleWakeUpCall((int) (msToNextPing / 1000), account.getUuid().hashCode());
                        if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                        }
                    }
                }
            }
        } else if (account.getStatus() == Account.State.OFFLINE) {
            reconnectAccount(account, true, interactive);
        } else if (account.getStatus() == Account.State.CONNECTING) {
            long secondsSinceLastConnect = (SystemClock.elapsedRealtime() - account.getXmppConnection().getLastConnect()) / 1000;
            long secondsSinceLastDisco = (SystemClock.elapsedRealtime() - account.getXmppConnection().getLastDiscoStarted()) / 1000;
            long discoTimeout = Config.CONNECT_DISCO_TIMEOUT - secondsSinceLastDisco;
            long timeout = Config.CONNECT_TIMEOUT - secondsSinceLastConnect;
            if (timeout < 0) {
                account.getXmppConnection().resetAttemptCount(false);
                reconnectAccount(account, true, interactive);
            } else if (discoTimeout < 0) {
                account.getXmppConnection().sendDiscoTimeout();
                scheduleWakeUpCall((int) Math.min(timeout, discoTimeout), account.getUuid().hashCode());
            } else {
                scheduleWakeUpCall((int) Math.min(timeout, discoTimeout), account.getUuid().hashCode());
            }
        } else {
            final boolean aggressive = account.getStatus() == Account.State.SEE_OTHER_HOST || hasJingleRtpConnection(account);
            if (account.getXmppConnection().getTimeToNextAttempt(aggressive) <= 0) {
                reconnectAccount(account, true, interactive);
            }
        }
        return false;
    }

    private void toggleSoftDisabled(final boolean softDisabled) {
        for(final Account account : this.accounts) {
            if (account.isEnabled()) {
                if (account.setOption(Account.OPTION_SOFT_DISABLED, softDisabled)) {
                    updateAccount(account);
                }
            }
        }
    }

    public boolean processUnifiedPushMessage(final Account account, final Jid transport, final Element push) {
        return unifiedPushBroker.processPushMessage(account, transport, push);
    }


    public void uninitializeConversationsClient() {
        conversationsManager.shutdownClient();
    }
    public void discoverChannels(String query, ChannelDiscoveryService.Method method, ChannelDiscoveryService.OnChannelSearchResultsFound onChannelSearchResultsFound) {
        mChannelDiscoveryService.discover(Strings.nullToEmpty(query).trim(), method, onChannelSearchResultsFound);
    }

    public boolean isDataSaverDisabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        final ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        return !Compatibility.isActiveNetworkMetered(connectivityManager)
                || Compatibility.getRestrictBackgroundStatus(connectivityManager)
                == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
    }

    private void directReply(final Conversation conversation, final String body, final String lastMessageUuid, final boolean dismissAfterReply) {
        final Message inReplyTo = lastMessageUuid == null ? null : conversation.findMessageWithUuid(lastMessageUuid);
        final Message message = new Message(conversation, body, conversation.getNextEncryption());
        if (inReplyTo != null && inReplyTo.isPrivateMessage()) {
            Message.configurePrivateMessage(message, inReplyTo.getCounterpart());
        }
        message.markUnread();
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            getPgpEngine().encrypt(message, new UiCallback<Message>() {
                @Override
                public void success(Message message) {
                    if (dismissAfterReply) {
                        markRead((Conversation) message.getConversation(), true);
                    } else {
                        mNotificationService.pushFromDirectReply(message);
                    }
                }

                @Override
                public void error(int errorCode) {

                }

                @Override
                public void userInputRequired(PendingIntent pi) {

                }
            });
        } else {
            sendMessage(message);
            if (dismissAfterReply) {
                markRead(conversation, true);
            } else {
                mNotificationService.pushFromDirectReply(message);
            }
        }
    }


    private boolean dndOnSilentMode() {
        return getBooleanPreference(AppSettings.DND_ON_SILENT_MODE, R.bool.dnd_on_silent_mode);
    }

    private boolean manuallyChangePresence() {
        return getBooleanPreference(AppSettings.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
    }

    private boolean treatVibrateAsSilent() {
        return getBooleanPreference(AppSettings.TREAT_VIBRATE_AS_SILENT, R.bool.treat_vibrate_as_silent);
    }

    private boolean awayWhenScreenLocked() {
        return getBooleanPreference(AppSettings.AWAY_WHEN_SCREEN_IS_OFF, R.bool.away_when_screen_off);
    }

    private String getCompressPicturesPreference() {
        return getPreferences().getString("picture_compression", getResources().getString(R.string.picture_compression));
    }

    private Presence.Status getTargetPresence() {
        if (dndOnSilentMode() && isPhoneSilenced()) {
            return Presence.Status.DND;
        } else if (awayWhenScreenLocked() && isScreenLocked()) {
            return Presence.Status.AWAY;
        } else {
            return Presence.Status.ONLINE;
        }
    }

    public boolean isScreenLocked() {
        final KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        final PowerManager powerManager = getSystemService(PowerManager.class);
        final boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        final boolean interactive;
        try {
            interactive = powerManager != null && powerManager.isInteractive();
        } catch (final Exception e) {
            return false;
        }
        return locked || !interactive;
    }

    private boolean isPhoneSilenced() {
        final NotificationManager notificationManager = getSystemService(NotificationManager.class);
        final int filter = notificationManager == null ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN : notificationManager.getCurrentInterruptionFilter();
        final boolean notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY;
        final AudioManager audioManager = getSystemService(AudioManager.class);
        final int ringerMode = audioManager == null ? AudioManager.RINGER_MODE_NORMAL : audioManager.getRingerMode();
        try {
            if (treatVibrateAsSilent()) {
                return notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL;
            } else {
                return notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT;
            }
        } catch (final Throwable throwable) {
            return notificationDnd;
        }
    }

    private void resetAllAttemptCounts(boolean reallyAll, boolean retryImmediately) {
        for (Account account : accounts) {
            if (account.hasErrorStatus() || reallyAll) {
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    connection.resetAttemptCount(retryImmediately);
                }
            }
            if (account.setShowErrorNotification(true)) {
                mDatabaseWriterExecutor.execute(() -> databaseBackend.updateAccount(account));
            }
        }
        mNotificationService.updateErrorNotification();
    }

    private void dismissErrorNotifications() {
        for (final Account account : this.accounts) {
            if (account.hasErrorStatus()) {
                if (account.setShowErrorNotification(false)) {
                    mDatabaseWriterExecutor.execute(() -> databaseBackend.updateAccount(account));
                }
            }
        }
    }

    private void expireOldMessages() {
        expireOldMessages(false);
    }

    public void expireOldMessages(final boolean resetHasMessagesLeftOnServer) {
        mLastExpiryRun.set(SystemClock.elapsedRealtime());
        mDatabaseWriterExecutor.execute(() -> {
            long timestamp = getAutomaticMessageDeletionDate();
            if (timestamp > 0) {
                databaseBackend.expireOldMessages(timestamp);
                synchronized (XmppConnectionService.this.conversations) {
                    for (Conversation conversation : XmppConnectionService.this.conversations) {
                        conversation.expireOldMessages(timestamp);
                        if (resetHasMessagesLeftOnServer) {
                            conversation.messagesLoaded.set(true);
                            conversation.setHasMessagesLeftOnServer(true);
                        }
                    }
                }
                updateConversationUi();
            }
        });
    }

    public boolean hasInternetConnection() {
        return new NetworkManager(this).hasInternet();
    }

    @SuppressLint("TrulyRandom")
    @Override
    public void onCreate() {
        LibIdnXmppStringprep.setup();
        if (Compatibility.runsTwentySix()) {
            mNotificationService.initializeChannels();
        }
        mForceDuringOnCreate.set(Compatibility.runsAndTargetsTwentySix(this));
        toggleForegroundService();
        this.destroyed = false;
        OmemoSetting.load(this);
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable throwable) {
            Log.e(Config.LOGTAG, "unable to initialize security provider", throwable);
        }
        updateMemorizingTrustManager();
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 9;
        this.mBitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(final String key, final Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        this.mDrawableCache = new LruCache<String, Drawable>(cacheSize) {
            @Override
            protected int sizeOf(final String key, final Drawable drawable) {
                if (drawable instanceof BitmapDrawable) {
                    return ((BitmapDrawable) drawable).getBitmap().getByteCount() / 1024;
                } else {
                    return drawable.getIntrinsicWidth() * drawable.getIntrinsicHeight() * 40 / 1024;
                }
            }
        };
        if (mLastActivity == 0) {
            mLastActivity = getPreferences().getLong(SETTING_LAST_ACTIVITY_TS, System.currentTimeMillis());
        }
        // Do NOT arm the package-replace core-profile re-import here. onCreate() runs
        // on every service/process start (including a normal reboot's boot-restore),
        // so setting this flag unconditionally made the destructive re-import
        // (importCoreProfiles → deleteAllCoreProfiles → re-download) run on every
        // cold start. When the re-download failed transiently — e.g. the network or
        // auth session wasn't ready yet right after a reboot — the profiles had
        // already been wiped and never came back, leaving "No core profiles
        // available" while the boot-restored tunnel stayed up. The flag is armed only
        // by handlePackageReplaced(), which runs solely on ACTION_MY_PACKAGE_REPLACED.
        this.databaseBackend = DatabaseBackend.getInstance(getApplicationContext());
        this.organizations = databaseBackend.getOrganizations();
        this.accounts = databaseBackend.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            for (Account account : accounts) {
                try {
                    String org = account.getOrganization();
                    if (org == null) {
                        org = databaseBackend.getCognitoAccount(account).getOrganization();
                        account.setOrganization(org);
                        updateAccount(account);
                    }
                    queryGlacierUsers(account, true);
                    queryLatestVersion();
                    restorePhoneFromDatabase();
                } catch (Exception e) {
                }
            }
        }

        this.latestVersion = databaseBackend.getLatestVersion("latest_version");
        final SharedPreferences.Editor editor = getPreferences().edit();
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        editor.putBoolean(SystemEventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply();
        editor.apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
        reconfigurePushDistributor();

        if (CallIntegration.hasSystemFeature(this)) {
            CallIntegrationConnectionService.togglePhoneAccountsAsync(this, this.accounts);
        }

        restoreConversationsFromDatabase();


        if (QuickConversationsService.isContactListIntegration(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            startContactObserver();
        }
        FILE_OBSERVER_EXECUTOR.execute(fileBackend::deleteHistoricAvatarPath);
        if (Compatibility.hasStoragePermission()) {
            FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::startWatching);
            refreshFileAvailability();
        }
        // Whatever happened while we were in the background - a permission granted in system
        // settings, a backup restored, files copied in - is picked up on the way back in.
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this.foregroundObserver);
        if (Config.supportOpenPgp()) {
            this.pgpServiceConnection = new OpenPgpServiceConnection(this, "org.sufficientlysecure.keychain", new OpenPgpServiceConnection.OnBound() {
                @Override
                public void onBound(final IOpenPgpService2 service) {
                    for (Account account : accounts) {
                        final PgpDecryptionService pgp = account.getPgpDecryptionService();
                        if (pgp != null) {
                            pgp.continueDecryption(true);
                        }
                    }
                }

                @Override
                public void onError(final Exception exception) {
                    Log.e(Config.LOGTAG,"could not bind to OpenKeyChain", exception);
                }
            });
            this.pgpServiceConnection.bindToService();
        }

        final PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            this.wakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, "Conversations:Service");
        }

        toggleForegroundService();
        updateUnreadCountBadge();
        toggleScreenEventReceiver();
        final IntentFilter systemBroadcastFilter = new IntentFilter();
        scheduleNextIdlePing();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            systemBroadcastFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        }
        systemBroadcastFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        ContextCompat.registerReceiver(
                this,
                this.mInternalEventReceiver,
                systemBroadcastFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        mForceDuringOnCreate.set(false);
        toggleForegroundService();
        internalPingExecutor.scheduleAtFixedRate(this::manageAccountConnectionStatesInternal,10,10,TimeUnit.SECONDS);
        final SharedPreferences sharedPreferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
                if (AppSettings.KEEP_FOREGROUND_SERVICE.equals(key)) {
                    toggleForegroundService();
                }
                if ("user_sms_enabled".equals(key)
                        && sharedPreferences.getBoolean("user_sms_enabled", false)) {
                    final Account account = getFirstAccount();
                    if (account != null) {
                        maybePrefetchPhoneInfo(account);
                    }
                }
            }
        });
        if (getFirstAccount() != null) {
            secInfo = new SystemSecurityInfo(this);
        }
        connectivityManager = getSystemService(ConnectivityManager.class);
        startVpnStatusMonitoring();
        scheduleVoicemailRefreshWork();
        requestVoicemailRefresh();


        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                 queryDb(false);
                // Opening the app means "try now". Without this an account that has been failing
                // sits out a backoff that caps at five minutes, so the profile refresh above can
                // fix the underlying problem and the user still watches a broken screen -- which
                // is what makes force-quitting the app look like the only way back.
                retryFailedAccountsNow();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, new IntentFilter("APP_FOREGROUNDED"));
        startGlobalDisappearingMessagesHandler();
    }

    public LruCache<String, Drawable> getDrawableCache() {
        return this.mDrawableCache;
    }

    public void queryDb(boolean checkPhone) {
        Account account = getFirstAccount();
        if (account != null) {
            if (checkPhone) {
                maybePrefetchPhoneSync();
            }
            queryLatestVersion();
            queryGlacierUsers(account, checkPhone);
        }
    }

    private void maybePrefetchPhoneSync() {
        if (!getPreferences().getBoolean("user_sms_enabled", false)) {
            return;
        }
        final Account account = getFirstAccount();
        if (account == null || !account.isOnlineAndConnected()) {
            return;
        }
        if (getOrganizationForAccount(account) == null) {
            return;
        }
        if (!phoneSyncPrefetchStarted.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> syncPhoneInfo(false)).start();
    }

    private void handlePackageReplaced() {

        Account account = getFirstAccount();
        if (account == null) {
        } else if (account.isOnlineAndConnected()) {
        }

        pendingPackageReplaceCoreProfileImport.set(true);

    }

    private void maybeImportCoreProfiles(Account account) {
        if (!pendingPackageReplaceCoreProfileImport.get()) {
            return;
        }
        if (account == null) {
            return;
        }
        if (!glacierUserDataRefreshed.get()) {
            // The account's persisted core region can be stale (or empty) until the
            // concurrent Glacier user lookup completes and calls account.setGlacierUser().
            // Only the user-lookup callback flips this flag, so an org-lookup callback
            // racing ahead of it defers here instead of importing/clearing the pending
            // flag against out-of-date region data.
            return;
        }

        Amplify.Auth.fetchAuthSession(authSession -> {
            if (!authSession.isSignedIn()) {
                return;
            }
            if (!pendingPackageReplaceCoreProfileImport.compareAndSet(true, false)) {
                return;
            }

            importCoreProfiles(account, () -> {

                CoreProfile runningProfile = databaseBackend.getRunningCoreProfile();
                if (runningProfile != null) {

                    BootCompletedReceiver.startLastUsedTunnel(this, runningProfile);
                } else {

                    startPreferredCoreProfileAfterPackageReplace();
                }
            });
        }, error -> Log.w(Config.LOGTAG, "Deferring core profile import after package replacement until auth session check succeeds", error));
    }

    private void startPreferredCoreProfileAfterPackageReplace() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String preferredProfileName = preferences.getString("vpn_profile_uuid", null);
        if (TextUtils.isEmpty(preferredProfileName)) {

            return;
        }
        try {
            for (CoreProfile profile : databaseBackend.getCoreProfiles()) {

                if (preferredProfileName.equals(profile.getLegacyName())) {
                    PersistentConnectionProperties.getInstance().markProfileConnected(profile.getProfName());
                    BootCompletedReceiver.startLastUsedTunnel(this, profile);
                    break;
                }
            }
        } finally {
            preferences.edit().remove("vpn_profile_name").apply();
        }
    }


    public ArrayList<CoreProfile> restoreCoreProfilesFromDatabase() {
        return databaseBackend.getCoreProfiles();
    }

    private void restorePhoneFromDatabase() {

        if (getFirstAccount() != null) {
            mDatabaseReaderExecutor.execute(() -> {
                ArrayList<SmsProfile> smsProfilesFromDB = databaseBackend.getAllSmsProfiles();
                smsInfo.setDbProfs(smsProfilesFromDB);
                for (SmsProfile profile : smsProfilesFromDB) {
                    ArrayList<TwilioConversationEntity> convs = databaseBackend.getSmsConversationsObjForProxy(profile);
                    smsInfo.conv_list.put(profile.getUnformattedNumber(), convs);
                    for (TwilioConversationEntity conv : convs) {
                        String sid = conv.getConv_sid();
                        smsInfo.conv_by_sid.put(sid, conv);
                        Pair<SmsMessagePreviewUtils.SmsPreview, String> last =
                                databaseBackend.getLastSmsPreviewForConversation(sid);
                        if (last != null) {
                            smsInfo.conv_last_msg.put(sid, last.first.getText());
                            smsInfo.conv_last_msg_is_media.put(sid, last.first.isMedia());
                            smsInfo.conv_last_msg_sent.put(sid, last.second);
                            conv.setLastMessage(last.first.getText());
                            conv.setLastMessageIsMedia(last.first.isMedia());
                        } else if (conv.getLastMessage() != null) {
                            smsInfo.conv_last_msg.put(sid, conv.getLastMessage());
                            smsInfo.conv_last_msg_is_media.put(sid, conv.getLastMessageIsMedia());
                        }
                        smsInfo.conv_last_msg_count.put(sid, databaseBackend.getSmsMessagesCount(sid));
                        smsInfo.conv_messages.put(sid, databaseBackend.getSmsMessagesForConversation(sid));
                    }
                }
                if (smsInfo.getSelectedProfile() == null) {
                    smsInfo.setSelectedSmsProfile(null);
                }
            });
        }
    }


    private void checkForDeletedFiles() {
        if (destroyed) {
            return;
        }
        final long start = SystemClock.elapsedRealtime();
        final List<DatabaseBackend.FilePathInfo> relativeFilePaths = databaseBackend.getFilePathInfo();
        final List<DatabaseBackend.FilePathInfo> changed = new ArrayList<>();
        final List<DatabaseBackend.FilePathInfo> relocated = new ArrayList<>();
        int stranded = 0;
        for (final DatabaseBackend.FilePathInfo filePath : relativeFilePaths) {
            if (destroyed) {
                return;
            }
            // Media written before conversation files moved into app-owned storage still names the
            // old public location, which scoped storage hides from us - so it would be written off
            // as deleted below. Bring the file across, and the row with it, first.
            if (filePath.setPath(fileBackend.migrateLegacyMedia(filePath.path))) {
                relocated.add(filePath);
            }
            final File file = fileBackend.getFileForPath(filePath.path);
            // canRead(), not exists(): scoped storage happily stats a public file we have no
            // permission to open, so exists() would call media readable that we cannot show.
            final boolean missing = !file.canRead();
            if (missing && !fileBackend.isAppOwnedMediaPath(filePath.path)) {
                // Names a pre-7.x location and we cannot see it. Either it is genuinely gone, or
                // it is there and scoped storage is hiding it from us for want of a media
                // permission. Counted for the log only; the app does not ask for that permission.
                stranded++;
            }
            if (filePath.setDeleted(missing)) {
                changed.add(filePath);
            }
        }
        final long duration = SystemClock.elapsedRealtime() - start;
        if (!relocated.isEmpty()) {
            databaseBackend.updateFilePaths(relocated);
        }
        if (changed.size() > 0) {
            databaseBackend.markFilesAsChanged(changed);
            markChangedFiles(changed);
        }
        if (!changed.isEmpty() || !relocated.isEmpty()) {
            for (final OnMediaAvailabilityChanged listener : mOnMediaAvailabilityChanged) {
                listener.onMediaAvailabilityChanged();
            }
        }
    }

    public void startContactObserver() {
        getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (restoredFromDatabaseLatch.getCount() == 0) {

                    loadPhoneContacts();
                }
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) {
            getBitmapCache().evictAll();
        }
    }

    @Override
    public void onDestroy() {
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(this.foregroundObserver);
        try {
            unregisterReceiver(this.mInternalEventReceiver);
            unregisterReceiver(this.mInternalScreenEventReceiver);
        } catch (final IllegalArgumentException e) {
            //ignored
        }
        destroyed = false;
        if (disappearingMessagesRunnable != null) {
            disappearingMessagesHandler.removeCallbacks(disappearingMessagesRunnable);
        }
        fileObserver.stopWatching();
        // Before the executor, so a network callback cannot be handed work that can no longer
        // be accepted. Unregistering still cannot cancel a callback that is already running,
        // which onVpnUnderlyingTransportsChanged handles on its own.
        stopVpnStatusMonitoring();
        internalPingExecutor.shutdown();
        voicemailRefreshExecutor.shutdown();
        super.onDestroy();
    }

    private void scheduleVoicemailRefreshWork() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                VoicemailRefreshWorker.class,
                15,
                TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(getApplicationContext())
                .enqueueUniquePeriodicWork(
                        VoicemailRefreshWorker.UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request);
    }

    private void requestVoicemailRefresh() {
        voicemailRefreshExecutor.execute(() -> {
            try {
                refreshVoicemails();
            } catch (Exception e) {
                Log.w(Config.LOGTAG, "voicemail refresh failed", e);
            }
        });
    }

    public void restartFileObserver() {
        FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::restartWatching);
        refreshFileAvailability();
    }

    public void setOnMediaAvailabilityChangedListener(final OnMediaAvailabilityChanged listener) {
        mOnMediaAvailabilityChanged.add(listener);
    }

    public void removeOnMediaAvailabilityChangedListener(final OnMediaAvailabilityChanged listener) {
        mOnMediaAvailabilityChanged.remove(listener);
    }

    /**
     * Re-checks which message files are actually on disk. Media can come back while the app is
     * running - a media permission granted in system settings, a restored backup, files copied in
     * from a computer - and none of that reaches us through the file observer, so without this the
     * only way to pick it up was to force stop the app and let start up scan again. Overlapping
     * requests are dropped rather than queued; the work runs on the file observer thread.
     */
    public void refreshFileAvailability() {
        if (!fileAvailabilityCheckRunning.compareAndSet(false, true)) {
            return;
        }
        FILE_OBSERVER_EXECUTOR.execute(
                () -> {
                    try {
                        checkForDeletedFiles();
                    } finally {
                        fileAvailabilityCheckRunning.set(false);
                    }
                });
    }

    public void toggleScreenEventReceiver() {
        if (awayWhenScreenLocked() && !manuallyChangePresence()) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            ContextCompat.registerReceiver(this, this.mInternalScreenEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } else {
            try {
                unregisterReceiver(this.mInternalScreenEventReceiver);
            } catch (IllegalArgumentException e) {
                //ignored
            }
        }
    }

    public void toggleForegroundService() {
        toggleForegroundService(false);
    }

    public void setOngoingCall(AbstractJingleConnection.Id id, Set<Media> media, final boolean reconnecting) {
        ongoingCall.set(new OngoingCall(id, media, reconnecting));
        toggleForegroundService(false);
    }

    public void removeOngoingCall() {
        ongoingCall.set(null);
        toggleForegroundService(false);
    }

    public void endAllCalls() {
        mJingleConnectionManager.endAllRtpSessions();
        externalCallManager.disconnectPhoneCall();
        removeOngoingCall();
    }

    private void toggleForegroundService(final boolean force) {
        final boolean status;
        final OngoingCall ongoing = ongoingCall.get();
        final boolean ongoingVideoTranscoding = mOngoingVideoTranscoding.get();
        final int id;
        if (force
                || mForceDuringOnCreate.get()
                || ongoingVideoTranscoding
                || ongoing != null
                || (Compatibility.keepForegroundService(this) && hasEnabledAccounts())) {
            final Notification notification;
            if (ongoing != null) {
                notification = this.mNotificationService.getOngoingCallNotification(ongoing);
                id = NotificationService.ONGOING_CALL_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, true);
            } else if (ongoingVideoTranscoding) {
                notification = this.mNotificationService.getIndeterminateVideoTranscoding();
                id = NotificationService.ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, false);
            } else {
                notification = this.mNotificationService.createForegroundNotification();
                id = NotificationService.FOREGROUND_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, false);
            }
            mNotificationService.notify(id, notification);
            status = true;
        } else {
            id = 0;
            stopForeground(true);
            status = false;
        }

        for (final int toBeRemoved :
                Collections2.filter(
                        Arrays.asList(
                                NotificationService.FOREGROUND_NOTIFICATION_ID,
                                NotificationService.ONGOING_CALL_NOTIFICATION_ID,
                                NotificationService.ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID),
                        i -> i != id)) {
            mNotificationService.cancel(toBeRemoved);
        }
    }

    private void startForegroundOrCatch(
            final int id, final Notification notification, final boolean requireMicrophone) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                final int foregroundServiceType;
                if (requireMicrophone
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                } else if (getSystemService(PowerManager.class)
                        .isIgnoringBatteryOptimizations(getPackageName())) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
                } else {
                    // Not microphone/camera even when granted: Android 15+ refuses both from
                    // BOOT_COMPLETED, and the service would then run unprotected until killed.
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                }
                startForeground(id, notification, foregroundServiceType);
            } else {
                startForeground(id, notification);
            }
        } catch (final IllegalStateException | SecurityException e) {
            Log.e(Config.LOGTAG, "Could not start foreground service", e);
        }
    }

    public boolean foregroundNotificationNeedsUpdatingWhenErrorStateChanges() {
        return !mOngoingVideoTranscoding.get() && ongoingCall.get() == null && Compatibility.keepForegroundService(this) && hasEnabledAccounts();
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if ((Compatibility.keepForegroundService(this) && hasEnabledAccounts()) || mOngoingVideoTranscoding.get() || ongoingCall.get() != null) {
        } else {
            this.logoutAndSave(false);
        }
    }

    private void logoutAndSave(boolean stop) {
        endAllCalls();
        mNotificationService.cancelIncomingCallNotification();
        stopService(new Intent(this, IncomingCallNotificationService.class));
        int activeAccounts = 0;
        for (final Account account : accounts) {
            if (account.isConnectionEnabled()) {
                databaseBackend.writeRoster(account.getRoster());
                activeAccounts++;
            }
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, false)).start();
            }
        }
        if (stop || activeAccounts == 0) {
            stopSelf();
        }
    }

    private void schedulePostConnectivityChange() {
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final long triggerAtMillis = SystemClock.elapsedRealtime() + (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000);
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_POST_CONNECTIVITY_CHANGE);
        try {
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 1, intent, s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for post connectivity change", e);
        }
    }

    public void scheduleWakeUpCall(final int seconds, final int requestCode) {
        final long timeToWake = SystemClock.elapsedRealtime() + (seconds < 0 ? 1 : seconds + 1) * 1000L;
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_PING);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for ping", e);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void scheduleNextIdlePing() {
        final long timeToWake = SystemClock.elapsedRealtime() + (Config.IDLE_PING_INTERVAL * 1000);
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_IDLE_PING);
        try {
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT);
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (RuntimeException e) {
        }
    }

    public XmppConnection createConnection(final Account account) {
        final XmppConnection connection = new XmppConnection(account, this);
        connection.setOnStatusChangedListener(this.statusListener);
        connection.setOnJinglePacketReceivedListener((mJingleConnectionManager::deliverPacket));
        connection.setOnMessageAcknowledgeListener(this.mOnMessageAcknowledgedListener);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mMessageArchiveService);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mAvatarService);
        AxolotlService axolotlService = account.getAxolotlService();
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService);
        }
        return connection;
    }

    public void sendChatState(Conversation conversation) {
        if (sendChatStates()) {
            final var packet = mMessageGenerator.generateChatState(conversation);
            sendMessagePacket(conversation.getAccount(), packet);
        }
    }

    private void sendFileMessage(final Message message, final boolean delay) {
        final Account account = message.getConversation().getAccount();
        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                || message.getConversation().getMode() == Conversation.MODE_MULTI) {
            mHttpConnectionManager.createNewUploadConnection(message, delay);
        } else {
            mJingleConnectionManager.startJingleFileTransfer(message);
        }
    }

    public void sendMessage(final Message message) {
        sendMessage(message, false, false);
    }

    /**
     * Broadcasts a "<display name> left group" notice into a multi-user chat. This mirrors
     * the iOS client, which emits an in-room system message when a user leaves, so other
     * participants are informed of the departure. Receiving clients render it as a system
     * message (see {@code MessageParser#isLeaveGroupNotification}). Callers should send this
     * before leaving the room so the stanza is flushed while still joined.
     */
    public void sendLeavingGroupMessage(final Conversation conversation) {
        if (conversation == null || conversation.getMode() != Conversation.MODE_MULTI) {
            return;
        }
        final Account account = conversation.getAccount();
        String displayName = account.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = account.getUsername();
        }
        final String body = displayName + " " + getString(R.string.left_group);
        final Message message = new Message(conversation, body, conversation.getNextEncryption());
        sendMessage(message);
    }

    private void sendMessage(final Message message, final boolean resend, final boolean delay) {
        final Account account = message.getConversation().getAccount();
        if (account.setShowErrorNotification(true)) {
            databaseBackend.updateAccount(account);
            mNotificationService.updateErrorNotification();
        }
        final Conversation conversation = (Conversation) message.getConversation();
        account.deactivateGracePeriod();


        if (QuickConversationsService.isQuicksy() && conversation.getMode() == Conversation.MODE_SINGLE) {
            final Contact contact = conversation.getContact();
            if (!contact.showInRoster() && contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                createContact(contact, true);
            }
        }

        im.conversations.android.xmpp.model.stanza.Message packet = null;
        final boolean addToConversation = !message.edited();
        boolean saveInDb = addToConversation;
        message.setStatus(Message.STATUS_WAITING);

        if (message.getEncryption() != Message.ENCRYPTION_NONE && conversation.getMode() == Conversation.MODE_MULTI && conversation.isPrivateAndNonAnonymous()) {
            if (conversation.setAttribute(Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, true)) {
                databaseBackend.updateConversation(conversation);
            }
        }

        final boolean inProgressJoin = isJoinInProgress(conversation);


        if (account.isOnlineAndConnected() && !inProgressJoin) {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_NONE:
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay);
                        } else {
                            break;
                        }
                    } else {
                        packet = mMessageGenerator.generateChat(message);
                    }
                    break;
                case Message.ENCRYPTION_PGP:
                case Message.ENCRYPTION_DECRYPTED:
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay);
                        } else {
                            break;
                        }
                    } else {
                        packet = mMessageGenerator.generatePgpChat(message);
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay);
                        } else {
                            break;
                        }
                    } else {
                        XmppAxolotlMessage axolotlMessage = account.getAxolotlService().fetchAxolotlMessageFromCache(message);
                        if (axolotlMessage == null) {
                            account.getAxolotlService().preparePayloadMessage(message, delay);
                        } else {
                            packet = mMessageGenerator.generateAxolotlChat(message, axolotlMessage);
                        }
                    }
                    break;

            }
            if (packet != null) {
                if (account.getXmppConnection().getFeatures().sm()
                        || (conversation.getMode() == Conversation.MODE_MULTI && message.getCounterpart().isBareJid())) {
                    message.setStatus(Message.STATUS_UNSEND);
                } else {
                    message.setStatus(Message.STATUS_SEND);
                }
            }
        } else {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_DECRYPTED:
                    if (!message.needsUploading()) {
                        String pgpBody = message.getEncryptedBody();
                        String decryptedBody = message.getBody();
                        message.setBody(pgpBody);
                        message.setEncryption(Message.ENCRYPTION_PGP);
                        if (message.edited()) {
                            message.setBody(decryptedBody);
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                            if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                                Log.e(Config.LOGTAG, "error updated message in DB after edit");
                            }
                            updateConversationUi();
                            return;
                        } else {
                            databaseBackend.createMessage(message);
                            saveInDb = false;
                            message.setBody(decryptedBody);
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                        }
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    break;
            }
        }


        boolean mucMessage = conversation.getMode() == Conversation.MODE_MULTI && !message.isPrivateMessage();
        if (mucMessage) {
            message.setCounterpart(conversation.getMucOptions().getSelf().getFullJid());
        }

        if (resend) {
            if (packet != null && addToConversation) {
                if (account.getXmppConnection().getFeatures().sm() || mucMessage) {
                    markMessage(message, Message.STATUS_UNSEND);
                } else {
                    markMessage(message, Message.STATUS_SEND);
                }
            }
        } else {
            if (addToConversation) {
                conversation.add(message);
            }
            if (saveInDb) {
                databaseBackend.createMessage(message);
            } else if (message.edited()) {
                if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                    Log.e(Config.LOGTAG, "error updated message in DB after edit");
                }
            }
            updateConversationUi();
        }
        if (packet != null) {
            if (delay) {
                mMessageGenerator.addDelay(packet, message.getTimeSent());
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
                if (this.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
                }
            }
            sendMessagePacket(account, packet);
        }
    }

    private boolean isJoinInProgress(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                final boolean inProgress = account.inProgressConferenceJoins.contains(conversation);
                final boolean pending = account.pendingConferenceJoins.contains(conversation);
                final boolean inProgressJoin = inProgress || pending;
                return inProgressJoin;
            } else {
                return false;
            }
        }
    }

    private void sendUnsentMessages(final Conversation conversation) {
        // Encryption is baked into a message at compose time. When a message is composed for a
        // group while the room configuration (disco#info) is still being fetched, the group does
        // not yet look members-only + non-anonymous, so getNextEncryption() falls back to
        // ENCRYPTION_NONE and the message is held back (STATUS_WAITING) with plaintext encryption.
        // Every join path (invite, autojoin, manual join) funnels held-back group messages through
        // here, and this method only runs once the configuration is known, so re-derive the
        // encryption before flushing to guarantee such messages are never sent in the clear when
        // the group is actually OMEMO-capable. getNextEncryption() honors an explicit
        // per-conversation plaintext choice (it returns ENCRYPTION_NONE), so intentional plaintext
        // and genuinely non-encryptable rooms (e.g. anonymous public channels) are left untouched.
        final boolean upgradeGroupEncryption =
                conversation.getMode() == Conversation.MODE_MULTI
                        && conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL;
        conversation.findWaitingMessages(message -> {
            if (upgradeGroupEncryption
                    && message.getEncryption() == Message.ENCRYPTION_NONE
                    && !message.isPrivateMessage()) {
                message.setEncryption(Message.ENCRYPTION_AXOLOTL);
                databaseBackend.updateMessage(message, false);
            }
            resendMessage(message, true);
        });
    }

    public void resendMessage(final Message message, final boolean delay) {
        sendMessage(message, true, delay);
    }

    public void fetchBookmarks(final Account account) {
        final Iq iqPacket = new Iq(Iq.Type.GET);
        final Element query = iqPacket.query("jabber:iq:private");
        query.addChild("storage", Namespace.BOOKMARKS);
        final Consumer<Iq> callback = (response) -> {
            if (response.getType() == Iq.Type.RESULT) {
                final Element query1 = response.query();
                final Element storage = query1.findChild("storage", "storage:bookmarks");
                Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                processBookmarksInitial(account, bookmarks, false);
            }
        };
        sendIqPacket(account, iqPacket, callback);
    }

    public void fetchBookmarks2(final Account account) {
        final Iq retrieve = mIqGenerator.retrieveBookmarks();
        sendIqPacket(account, retrieve, (response) -> {
            if (response.getType() == Iq.Type.RESULT) {
                final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
                final Map<Jid, Bookmark> bookmarks = Bookmark.parseFromPubSub(pubsub, account);
                processBookmarksInitial(account, bookmarks, true);
            }
        });
    }

    public void fetchMessageDisplayedSynchronization(final Account account) {
        final var retrieve = mIqGenerator.retrieveMds();
        sendIqPacket(
                account,
                retrieve,
                (response) -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        return;
                    }
                    final var pubSub = response.findChild("pubsub", Namespace.PUBSUB);
                    final Element items = pubSub == null ? null : pubSub.findChild("items");
                    if (items == null
                            || !Namespace.MDS_DISPLAYED.equals(items.getAttribute("node"))) {
                        return;
                    }
                    for (final Element child : items.getChildren()) {
                        if ("item".equals(child.getName())) {
                            processMdsItem(account, child);
                        }
                    }
                });
    }

    public void processMdsItem(final Account account, final Element item) {
        final Jid jid =
                item == null ? null : InvalidJid.getNullForInvalid(item.getAttributeAsJid("id"));
        if (jid == null) {
            return;
        }
        final Element displayed = item.findChild("displayed", Namespace.MDS_DISPLAYED);
        final Element stanzaId =
                displayed == null ? null : displayed.findChild("stanza-id", Namespace.STANZA_IDS);
        final String id = stanzaId == null ? null : stanzaId.getAttribute("id");
        final Conversation conversation = find(account, jid);
        if (id != null && conversation != null) {
            conversation.setDisplayState(id);
            markReadUpToStanzaId(conversation, id);
        }
    }

    public void markReadUpToStanzaId(final Conversation conversation, final String stanzaId) {
        final Message message = conversation.findMessageWithServerMsgId(stanzaId);
        if (message == null) { // do we want to check if isRead?
            return;
        }
        markReadUpTo(conversation, message);
    }

    public void markReadUpTo(final Conversation conversation, final Message message) {
        final boolean isDismissNotification = isDismissNotification(message);
        final var uuid = message.getUuid();
        markRead(conversation, uuid, isDismissNotification);
    }

    private static boolean isDismissNotification(final Message message) {
        Message next = message.next();
        while (next != null) {
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                return false;
            }
            next = next.next();
        }
        return true;
    }

    public void processBookmarksInitial(final Account account, final Map<Jid, Bookmark> bookmarks, final boolean pep) {
        final Set<Jid> previousBookmarks = account.getBookmarkedJids();
        for (final Bookmark bookmark : bookmarks.values()) {
            previousBookmarks.remove(bookmark.getJid().asBareJid());
            processModifiedBookmark(bookmark, pep);
        }
        if (pep) {
            processDeletedBookmarks(account, previousBookmarks);
        }
        account.setBookmarks(bookmarks);
    }

    public void processDeletedBookmarks(final Account account, final Collection<Jid> bookmarks) {
        for (final Jid bookmark : bookmarks) {
            processDeletedBookmark(account, bookmark);
        }
    }

    public void processDeletedBookmark(final Account account, final Jid jid) {
        final Conversation conversation = find(account, jid);
        if (conversation == null) {
            return;
        }
        archiveConversation(conversation, false);
    }

    private void processModifiedBookmark(final Bookmark bookmark, final boolean pep) {
        final Account account = bookmark.getAccount();
        Conversation conversation = find(bookmark);
        if (conversation != null) {
            if (conversation.getMode() != Conversation.MODE_MULTI) {
                return;
            }
            bookmark.setConversation(conversation);
            if (pep && !bookmark.autojoin()) {
                archiveConversation(conversation, false);
            } else {
                final MucOptions mucOptions = conversation.getMucOptions();
                if (mucOptions.getError() == MucOptions.Error.NICK_IN_USE) {
                    final String current = mucOptions.getActualNick();
                    final String proposed = mucOptions.getProposedNick();
                    if (current != null && !current.equals(proposed)) {
                        joinMuc(conversation);
                    }
                }
            }
        } else if (bookmark.autojoin()) {
            conversation = findOrCreateConversation(account, bookmark.getFullJid(), true, true, false);
            bookmark.setConversation(conversation);
        }
    }

    public void processModifiedBookmark(final Bookmark bookmark) {
        processModifiedBookmark(bookmark, true);
    }

    public void createBookmark(final Account account, final Bookmark bookmark) {
        account.putBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
        } else if (connection.getFeatures().bookmarks2()) {
            final Element item = mIqGenerator.publishBookmarkItem(bookmark);
            pushNodeAndEnforcePublishOptions(account, Namespace.BOOKMARKS2, item, bookmark.getJid().asBareJid().toEscapedString(), PublishOptions.persistentWhitelistAccessMaxItems());
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    public void deleteBookmark(final Account account, final Bookmark bookmark) {
        account.removeBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
        } else if (connection.getFeatures().bookmarks2()) {
            final Iq request = mIqGenerator.deleteItem(Namespace.BOOKMARKS2, bookmark.getJid().asBareJid().toEscapedString());
            sendIqPacket(account, request, (response) -> {
                if (response.getType() == Iq.Type.ERROR) {
                }
            });
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    private void pushBookmarksPrivateXml(Account account) {
        final Iq iqPacket = new Iq(Iq.Type.SET);
        Element query = iqPacket.query("jabber:iq:private");
        Element storage = query.addChild("storage", "storage:bookmarks");
        for (final Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        sendIqPacket(account, iqPacket, mDefaultIqHandler);
    }

    private void pushBookmarksPep(Account account) {
        final Element storage = new Element("storage", "storage:bookmarks");
        for (final Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        pushNodeAndEnforcePublishOptions(account, Namespace.BOOKMARKS, storage, "current", PublishOptions.persistentWhitelistAccess());

    }

    private void pushNodeAndEnforcePublishOptions(final Account account, final String node, final Element element, final String id, final Bundle options) {
        pushNodeAndEnforcePublishOptions(account, node, element, id, options, true);

    }

    private void pushNodeAndEnforcePublishOptions(final Account account, final String node, final Element element, final String id, final Bundle options, final boolean retry) {
        final Iq packet = mIqGenerator.publishElement(node, element, id, options);
        sendIqPacket(account, packet, (response) -> {
            if (response.getType() == Iq.Type.RESULT) {
                return;
            }
            if (retry && PublishOptions.preconditionNotMet(response)) {
                pushNodeConfiguration(account, node, options, new OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        pushNodeAndEnforcePublishOptions(account, node, element, id, options, false);
                    }

                    @Override
                    public void onPushFailed() {
                    }
                });
            }
        });
    }

    /**
     * Re-reads accounts and conversations from the database.
     *
     * The account list is cached in memory from onCreate() and otherwise only ever changed
     * through createAccount()/deleteAccount(). A backup restore writes its rows straight to
     * SQLite, so a service instance that outlives the restore keeps reporting no accounts and no
     * conversations. ConversationsActivity decides onboarding from exactly that list, which is how
     * a successful restore could still land on "Get started" and then the login screen.
     *
     * The restore used to just stopService() this and hope for a fresh instance, but this is a
     * sticky foreground service the app re-starts on its own (connectivity, voicemail refresh),
     * and a stop cannot outrun the activity's rebind, so the stale instance often survived.
     */
    /**
     * Brings the in-memory account and conversation lists back in line with the database, for
     * callers that have found the two disagree. Runs the reload on the database reader executor
     * and invokes {@code onComplete} on that same executor once the roster and message tasks the
     * reload queues have finished, so the callback observes accounts that are both present and
     * initialised -- {@link Account#initAccountServices(XmppConnectionService)} has run for each.
     */
    public void reconcileWithDatabase(@Nullable final Runnable onComplete) {
        reloadFromDatabase(onComplete);
    }

    private void reloadFromDatabase() {
        reloadFromDatabase(null);
    }

    private void reloadFromDatabase(@Nullable final Runnable onComplete) {
        // Queued on mDatabaseReaderExecutor, the same serial executor
        // restoreConversationsFromDatabase() hands its reader task to. When this action is what
        // created the service, onCreate() has already loaded the database and queued that task;
        // running the reload inline would clear and replace the shared account and conversation
        // fields while the first task was still walking them, restoring the same messages and
        // initialising account services twice. Queueing puts the reload strictly after it.
        mDatabaseReaderExecutor.execute(
                () -> {
                    this.accounts = databaseBackend.getAccounts();
                    synchronized (this.conversations) {
                        this.conversations.clear();
                    }
                    restoreConversationsFromDatabase();
                    syncEnabledAccountSetting();
                    // The normal account-loading paths register with Telecom -- onCreate() for the
                    // whole list, createAccount() per account. A reload reaches neither, so a
                    // self-managed call integration would place calls with an unregistered
                    // PhoneAccountHandle until the service was recreated.
                    if (CallIntegration.hasSystemFeature(this)) {
                        CallIntegrationConnectionService.togglePhoneAccountsAsync(
                                this, this.accounts);
                    }
                    updateAccountUi();
                    updateConversationUi();
                    // Everything that needs a fully initialised Account waits for a second task
                    // on this same serial executor. restoreConversationsFromDatabase() does not
                    // initialise the accounts itself -- it queues the roster task that calls
                    // Account#initAccountServices(), and that task cannot start until this one
                    // returns. Connecting an account before it has run, or handing one to a
                    // caller's callback that logs in with it, means no Axolotl service and no
                    // listener for the packets that arrive. Queueing here, after that task was
                    // enqueued, puts this strictly behind it.
                    mDatabaseReaderExecutor.execute(
                            () -> {
                                for (final Account account : this.accounts) {
                                    reconnectAccountInBackground(account);
                                }
                                toggleForegroundService();
                                if (onComplete != null) {
                                    onComplete.run();
                                }
                            });
                });
    }

    private void restoreConversationsFromDatabase() {
        synchronized (this.conversations) {
            final Map<String, Account> accountLookupTable = new Hashtable<>();
            for (Account account : this.accounts) {
                accountLookupTable.put(account.getUuid(), account);
            }
            final long startTimeConversationsRestore = SystemClock.elapsedRealtime();
            this.conversations.addAll(databaseBackend.getConversations(Conversation.STATUS_AVAILABLE));
            for (Iterator<Conversation> iterator = conversations.listIterator(); iterator.hasNext(); ) {
                Conversation conversation = iterator.next();
                Account account = accountLookupTable.get(conversation.getAccountUuid());
                if (account != null) {
                    conversation.setAccount(account);
                } else {
                    Log.e(Config.LOGTAG, "unable to restore Conversations with " + conversation.getJid());
                    iterator.remove();
                }
            }
            long diffConversationsRestore = SystemClock.elapsedRealtime() - startTimeConversationsRestore;
            Runnable runnable = () -> {
                final long deletionDate = getAutomaticMessageDeletionDate();
                mLastExpiryRun.set(SystemClock.elapsedRealtime());
                if (deletionDate > 0) {
                    databaseBackend.expireOldMessages(deletionDate);
                }
                for (final Account account : accounts) {
                    databaseBackend.readRoster(account.getRoster());
                    account.initAccountServices(XmppConnectionService.this); //roster needs to be loaded at this stage
                }
                getBitmapCache().evictAll();
                loadPhoneContacts();
                final long startMessageRestore = SystemClock.elapsedRealtime();
                final Conversation quickLoad = QuickLoader.get(this.conversations);
                if (quickLoad != null) {
                    restoreMessages(quickLoad);
                    updateConversationUi();
                    final long diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore;
                }
                for (Conversation conversation : this.conversations) {
                    List<Message> messages = databaseBackend.getMessages(conversation, Config.PAGE_SIZE);
                    ArrayList<String> removedIds = new ArrayList();
                    for (int m=messages.size()-1; m>=0; m--){
                        Message message = messages.get(m);
                        if (message.getTimer() != Message.TIMER_NONE && message.getTimeRemaining() <= 0) {
                            removedIds.add(message.getUuid());
                            messages.remove(m);
                        }
                    }
                    if (removedIds.size() > 0) {
                        databaseBackend.removeDisappearingMessages(removedIds);
                        conversation.addAll(0, messages);
                    }

                    if (quickLoad != conversation) {
                        restoreMessages(conversation);
                    }
                }
                mNotificationService.finishBacklog();
                restoredFromDatabaseLatch.countDown();
                final long diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore;
                updateConversationUi();
            };
            mDatabaseReaderExecutor.execute(runnable); //will contain one write command (expiry) but that's fine
        }
    }

    private void restoreMessages(Conversation conversation) {
        conversation.addAll(0, databaseBackend.getMessages(conversation, Config.PAGE_SIZE));
        conversation.findUnsentTextMessages(message -> markMessage(message, Message.STATUS_WAITING));
        conversation.findUnreadMessagesAndCalls(mNotificationService::pushFromBacklog);
    }

    public void loadPhoneContacts() {
        mContactMergerExecutor.execute(() -> {
            final Map<Jid, JabberIdContact> contacts = JabberIdContact.load(this);
            for (final Account account : accounts) {
                final List<Contact> withSystemAccounts = account.getRoster().getWithSystemAccounts(JabberIdContact.class);
                for (final JabberIdContact jidContact : contacts.values()) {
                    final Contact contact = account.getRoster().getContact(jidContact.getJid());
                    boolean needsCacheClean = contact.setPhoneContact(jidContact);
                    if (needsCacheClean) {
                        getAvatarService().clear(contact);
                    }
                    withSystemAccounts.remove(contact);
                }
                for (final Contact contact : withSystemAccounts) {
                    boolean needsCacheClean = contact.unsetPhoneContact(JabberIdContact.class);
                    if (needsCacheClean) {
                        getAvatarService().clear(contact);
                    }
                }
            }
            mShortcutService.refresh(mInitialAddressbookSyncCompleted.compareAndSet(false, true));
            updateRosterUi();
            mQuickConversationsService.considerSync();
        });
    }


    public void syncRoster(final Account account) {
        mRosterSyncTaskManager.execute(account, () -> databaseBackend.writeRoster(account.getRoster()));
    }

    public List<Conversation> getConversations() {
        return this.conversations;
    }

    private void markFileDeleted(final File file) {
        synchronized (FILENAMES_TO_IGNORE_DELETION) {
            if (FILENAMES_TO_IGNORE_DELETION.remove(file.getAbsolutePath())) {
                return;
            }
        }
        final boolean isInternalFile = fileBackend.isInternalFile(file);
        final List<String> uuids = databaseBackend.markFileAsDeleted(file, isInternalFile);
        markUuidsAsDeletedFiles(uuids);
    }

    private void markUuidsAsDeletedFiles(List<String> uuids) {
        boolean deleted = false;
        for (Conversation conversation : getConversations()) {
            deleted |= conversation.markAsDeleted(uuids);
        }
        for (final String uuid : uuids) {
            evictPreview(uuid);
        }
        if (deleted) {
            updateConversationUi();
        }
    }

    private void markChangedFiles(List<DatabaseBackend.FilePathInfo> infos) {
        boolean changed = false;
        for (Conversation conversation : getConversations()) {
            changed |= conversation.markAsChanged(infos);
        }
        if (changed) {
            updateConversationUi();
        }
    }

    public void populateWithOrderedConversations(final List<Conversation> list) {
        populateWithOrderedConversations(list, true, true);
    }

    public void populateWithOrderedConversations(final List<Conversation> list, final boolean includeNoFileUpload) {
        populateWithOrderedConversations(list, includeNoFileUpload, true);
    }

    public void populateWithOrderedConversations(final List<Conversation> list, final boolean includeNoFileUpload, final boolean sort) {
        final List<String> orderedUuids;
        if (sort) {
            orderedUuids = null;
        } else {
            orderedUuids = new ArrayList<>();
            for (Conversation conversation : list) {
                orderedUuids.add(conversation.getUuid());
            }
        }
        list.clear();
        if (includeNoFileUpload) {
            list.addAll(getConversations());
        } else {
            for (Conversation conversation : getConversations()) {
                if (conversation.getMode() == Conversation.MODE_SINGLE
                        || (conversation.getAccount().httpUploadAvailable() && conversation.getMucOptions().participating())) {
                    list.add(conversation);
                }
            }
        }
        try {
            if (orderedUuids != null) {
                Collections.sort(list, (a, b) -> {
                    final int indexA = orderedUuids.indexOf(a.getUuid());
                    final int indexB = orderedUuids.indexOf(b.getUuid());
                    if (indexA == -1 || indexB == -1 || indexA == indexB) {
                        return a.compareTo(b);
                    }
                    return indexA - indexB;
                });
            } else {
                Collections.sort(list);
            }
        } catch (IllegalArgumentException e) {
            //ignore
        }
    }

    public void loadMoreMessages(final Conversation conversation, final long timestamp, final OnMoreMessagesLoaded callback) {
        if (XmppConnectionService.this.getMessageArchiveService().queryInProgress(conversation, callback)) {
            return;
        } else if (timestamp == 0) {
            return;
        }
        final Runnable runnable = () -> {
            final Account account = conversation.getAccount();
            List<Message> messages = databaseBackend.getMessages(conversation, 50, timestamp);
            if (messages.size() > 0) {
                conversation.addAll(0, messages);
                callback.onMoreMessagesLoaded(conversation);
            } else if (conversation.hasMessagesLeftOnServer()
                    && account.isOnlineAndConnected()
                    && conversation.getLastClearHistory().getTimestamp() == 0) {
                final boolean mamAvailable;
                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                    mamAvailable = account.getXmppConnection().getFeatures().mam() && !conversation.getContact().isBlocked();
                } else {
                    mamAvailable = conversation.getMucOptions().mamSupport();
                }
                if (mamAvailable) {
                    MessageArchiveService.Query query = getMessageArchiveService().query(conversation, new MamReference(0), timestamp, false);
                    if (query != null) {
                        query.setCallback(callback);
                        callback.informUser(R.string.fetching_history_from_server);
                    } else {
                        callback.informUser(R.string.not_fetching_history_retention_period);
                    }

                }
            }
        };
        mDatabaseReaderExecutor.execute(runnable);
    }

    public List<Account> getAccounts() {
        return this.accounts;
    }

    public Account getFirstAccount(){
        if (this.accounts != null && !accounts.isEmpty()){
            return accounts.get(0);
        }
        return null;
    }


    /**
     * This will find all conferences with the contact as member and also the conference that is the contact (that 'fake' contact is used to store the avatar)
     */
    public List<Conversation> findAllConferencesWith(Contact contact) {
        final ArrayList<Conversation> results = new ArrayList<>();
        for (final Conversation c : conversations) {
            if (c.getMode() != Conversation.MODE_MULTI) {
                continue;
            }
            final MucOptions mucOptions = c.getMucOptions();
            if (c.getJid().asBareJid().equals(contact.getJid().asBareJid()) || (mucOptions != null && mucOptions.isContactInRoom(contact))) {
                results.add(c);
            }
        }
        return results;
    }

    public Conversation find(final Iterable<Conversation> haystack, final Contact contact) {
        for (final Conversation conversation : haystack) {
            if (conversation.getContact() == contact) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation find(final Iterable<Conversation> haystack, final Account account, final Jid jid) {
        if (jid == null) {
            return null;
        }
        for (final Conversation conversation : haystack) {
            if ((account == null || conversation.getAccount() == account)
                    && (conversation.getJid().asBareJid().equals(jid.asBareJid()))) {
                return conversation;
            }
        }
        return null;
    }

    public boolean isConversationsListEmpty(final Conversation ignore) {
        synchronized (this.conversations) {
            final int size = this.conversations.size();
            return size == 0 || size == 1 && this.conversations.get(0) == ignore;
        }
    }

    public boolean isConversationStillOpen(final Conversation conversation) {
        synchronized (this.conversations) {
            for (Conversation current : this.conversations) {
                if (current == conversation) {
                    return true;
                }
            }
        }
        return false;
    }

    public Conversation findOrCreateConversation(Account account, Jid jid, boolean muc, final boolean async) {
        return this.findOrCreateConversation(account, jid, muc, false, async);
    }

    public Conversation findOrCreateConversation(final Account account, final Jid jid, final boolean muc, final boolean joinAfterCreate, final boolean async) {
        return this.findOrCreateConversation(account, jid, muc, joinAfterCreate, null, async);
    }

    public Conversation findOrCreateConversation(final Account account, final Jid jid, final boolean muc, final boolean joinAfterCreate, final MessageArchiveService.Query query, final boolean async) {
        synchronized (this.conversations) {
            Conversation conversation = find(account, jid);
            if (conversation != null) {
                return conversation;
            }
            conversation = databaseBackend.findConversation(account, jid);
            final boolean loadMessagesFromDb;
            if (conversation != null) {
                conversation.setStatus(Conversation.STATUS_AVAILABLE);
                conversation.setAccount(account);
                if (muc) {
                    conversation.setMode(Conversation.MODE_MULTI);
                    conversation.setContactJid(jid);
                } else {
                    conversation.setMode(Conversation.MODE_SINGLE);
                    conversation.setContactJid(jid.asBareJid());
                }
                databaseBackend.updateConversation(conversation);
                loadMessagesFromDb = conversation.messagesLoaded.compareAndSet(true, false);
            } else {
                String conversationName;
                Contact contact = account.getRoster().getContact(jid);
                if (contact != null) {
                    conversationName = contact.getDisplayName();
                } else {
                    conversationName = jid.getLocal();
                }
                if (muc) {
                    conversation = new Conversation(conversationName, account, jid,
                            Conversation.MODE_MULTI);
                } else {
                    conversation = new Conversation(conversationName, account, jid.asBareJid(),
                            Conversation.MODE_SINGLE);
                }
                this.databaseBackend.createConversation(conversation);
                loadMessagesFromDb = false;
            }
            final Conversation c = conversation;
            final Runnable runnable = () -> {
                if (loadMessagesFromDb) {
                    c.addAll(0, databaseBackend.getMessages(c, Config.PAGE_SIZE));
                    updateConversationUi();
                    c.messagesLoaded.set(true);
                }
                if (account.getXmppConnection() != null
                        && !c.getContact().isBlocked()
                        && account.getXmppConnection().getFeatures().mam()
                        && !muc) {
                    if (query == null) {
                        mMessageArchiveService.query(c);
                    } else {
                        if (query.getConversation() == null) {
                            mMessageArchiveService.query(c, query.getStart(), query.isCatchup());
                        }
                    }
                }
                if (joinAfterCreate) {
                    joinMuc(c);
                }
            };
            if (async) {
                mDatabaseReaderExecutor.execute(runnable);
            } else {
                runnable.run();
            }
            this.conversations.add(conversation);
            updateConversationUi();
            return conversation;
        }
    }

    public void archiveConversation(Conversation conversation) {
        archiveConversation(conversation, true);
    }

    private void archiveConversation(Conversation conversation, final boolean maySynchronizeWithBookmarks) {
        getNotificationService().clear(conversation);
        conversation.setStatus(Conversation.STATUS_ARCHIVED);
        conversation.setNextMessage(null);
        synchronized (this.conversations) {
            getMessageArchiveService().kill(conversation);
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                if (conversation.getAccount().getStatus() == Account.State.ONLINE) {
                    final Bookmark bookmark = conversation.getBookmark();
                    if (maySynchronizeWithBookmarks && bookmark != null) {
                        if (conversation.getMucOptions().getError() == MucOptions.Error.DESTROYED) {
                            Account account = bookmark.getAccount();
                            bookmark.setConversation(null);
                            deleteBookmark(account, bookmark);
                        } else if (bookmark.autojoin()) {
                            bookmark.setAutojoin(false);
                            createBookmark(bookmark.getAccount(), bookmark);
                        }
                    }
                }
                leaveMuc(conversation);
            } else {
                if (conversation.getContact().getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    stopPresenceUpdatesTo(conversation.getContact());
                }
            }
            updateConversation(conversation);
            this.conversations.remove(conversation);
            updateConversationUi();
        }
    }

    public void stopPresenceUpdatesTo(Contact contact) {
        sendPresencePacket(contact.getAccount(), mPresenceGenerator.stopPresenceUpdatesTo(contact));
        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
    }

    public void createAccount(final Account account) {
        account.initAccountServices(this);
        databaseBackend.createAccount(account);
        if (CallIntegration.hasSystemFeature(this)) {
            CallIntegrationConnectionService.togglePhoneAccountAsync(this, account);
        }
        this.accounts.add(account);
        boolean userChanged = updateGlacierUserPreferences(account);
        OrgUpdateResult orgResult = updateGlacierOrganizationPreferences(getOrganizationForAccount(account));
        this.reconnectAccountInBackground(account);
        if (userChanged) {
            updateAccountUi();
        }
        if (orgResult.any) {
            updateOrganizationUi();
        } else {
            noOrgUpdateUi();
        }
        if (orgResult.tracking) {
            syncLiveLocationUpdates();
        }
        if (orgResult.security) {
            syncAutomaticSecurityUpdates();
        }

        syncEnabledAccountSetting();
        toggleForegroundService();
    }

    private boolean updateGlacierUserPreferences(Account account) {
        SharedPreferences prefs = getPreferences();
        SharedPreferences.Editor editor = null;
        boolean changed = false;
        Boolean purchaseValue = account.getCanPurchaseNumbers();
        boolean purchase = purchaseValue != null ? purchaseValue : false;
        if (prefs.getBoolean("add_user_to_purchase_numbers", false) != purchase) {
            editor = prefs.edit();
            editor.putBoolean("add_user_to_purchase_numbers", purchase);
            changed = true;
        }
        Boolean smsValue = account.getSMSEnabled();
        boolean sms = smsValue != null ? smsValue : false;
        if (prefs.getBoolean("user_sms_enabled", false) != sms) {
            if (editor == null) {
                editor = prefs.edit();
            }
            editor.putBoolean("user_sms_enabled", sms);
            changed = true;
        }
        if (editor != null) {
            editor.apply();
        }
        return changed;
    }

    public void replaceSmsProfileList(ArrayList<SmsProfile> dbProfs, Runnable onComplete) {

        mDatabaseWriterExecutor.execute(() -> {
            try {
                databaseBackend.deactivateAllSmsProfiles();
                if (dbProfs != null) {
                    for (SmsProfile sp : dbProfs) {
                        sp.setActive(true);
                        databaseBackend.createSmsProfile(sp);
                    }
                }
            } catch (Exception e) {
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }
    private GlacierOrgEntity getOrganizationForAccount(final Account account) {
        if (account == null || TextUtils.isEmpty(account.getOrganization())) {
            return null;
        }
        return getOrganizationByName(account.getOrganization());
    }

    private GlacierOrgEntity getOrganizationByName(final String organizationName) {
        if (TextUtils.isEmpty(organizationName)) {
            return null;
        }
        if (organizations != null) {
            for (final GlacierOrgEntity organization : organizations) {
                if (organization != null && TextUtils.equals(organizationName, organization.getOrganization())) {
                    return organization;
                }
            }
        }
        final GlacierOrgEntity organization = databaseBackend.getOrganization(organizationName);
        if (organization != null) {
            cacheOrganization(organization);
        }
        return organization;
    }

    private void cacheOrganization(final GlacierOrgEntity organization) {
        if (organization == null || TextUtils.isEmpty(organization.getOrganization())) {
            return;
        }
        if (organizations == null) {
            organizations = new ArrayList<>();
        }
        for (int i = 0; i < organizations.size(); i++) {
            final GlacierOrgEntity current = organizations.get(i);
            if (current != null
                    && TextUtils.equals(current.getOrganization(), organization.getOrganization())) {
                organizations.set(i, organization);
                return;
            }
        }
        organizations.add(organization);
    }

    private boolean isVoicemailEnabledForAccount(final Account account) {
        final GlacierOrgEntity organization = getOrganizationForAccount(account);
        return organization != null && organization.isVoicemailEnabled();
    }

    private static class OrgUpdateResult {
        boolean any;
        boolean tracking;
        boolean security;
    }

    private OrgUpdateResult updateGlacierOrganizationPreferences(GlacierOrgEntity glacierOrgEntity) {
        OrgUpdateResult result = new OrgUpdateResult();
        if (glacierOrgEntity == null) {
            return result;
        }
        SharedPreferences prefs = getPreferences();
        SharedPreferences.Editor editor = prefs.edit();

        boolean c;
        c = putBooleanIfDiff(prefs, editor, "securityhub_data_enabled", glacierOrgEntity.isSecurityHubDataEnabled());
        result.security |= c;
        result.any |= c;

        c = putBooleanIfDiff(prefs, editor, "org_sms_enabled", glacierOrgEntity.isSmsEnabled());
        result.any |= c;

        c = putBooleanIfDiff(prefs, editor, "upload_enabled", glacierOrgEntity.isUploadEnabled());
        result.any |= c;

        c = putBooleanIfDiff(prefs, editor, "dns_enabled", glacierOrgEntity.isDnsEnabled());
        result.any |= c;

        if (glacierOrgEntity.isDnsEnabled()){
            fetchDnsProfile(glacierOrgEntity.getOrganization());
        }

        result.any |= c;

        c = putBooleanIfDiff(prefs, editor, "voicemail_enabled", glacierOrgEntity.isVoicemailEnabled());
        result.any |= c;

        c = putBooleanIfDiff(prefs, editor, "force_tracking", glacierOrgEntity.isForceTracking());
        result.tracking |= c;
        result.any |= c;

        c = putStringIfDiff(prefs, editor, "tracking_frequency", glacierOrgEntity.getTrackingFrequency());
        result.tracking |= c;
        result.any |= c;

        c = putBooleanIfDiff(prefs, editor, "tracking_enabled", glacierOrgEntity.isTrackingEnabled());
        result.tracking |= c;
        result.any |= c;

        if (!glacierOrgEntity.isTrackingEnabled()){
            editor.putBoolean("live_location_tracking", false);
        }

        editor.apply();

        return result;
    }

    public void fetchDnsProfile(String org) {
        new Thread(()-> {
            String getDnsProfileUrl =
                    Endpoints.base() + EndPointConfig.GET_DNS_PROFILE_URL;
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            final okhttp3.HttpUrl requestUrl = okhttp3.HttpUrl.parse(getDnsProfileUrl)
                    .newBuilder()
                    .addQueryParameter("organization", org)
                    .build();
            Request request = new Request.Builder()
                    .url(requestUrl)
                    .get()
                    .build();
        try (Response response = client.newCall(request).execute()){
                if (response != null && response.body() != null) {
                    String responseBody = response.body().string();
                    Gson gson = new Gson();
                    MobileApiResponses.DnsProfileResponse profileResponse = gson.fromJson(
                            responseBody,
                            MobileApiResponses.DnsProfileResponse.class);
                    if (profileResponse != null && "success".equals(profileResponse.message)) {
                        final String dnsId = profileResponse.data == null
                                ? null
                                : profileResponse.data.trim();
                        if (GlacierOrgEntity.isValidDnsId(dnsId)) {
                            final GlacierOrgEntity organization = getOrganizationByName(org);
                            if (organization != null) {
                                final String currentDnsId = organization.getDnsId();
                                if (!TextUtils.equals(currentDnsId, dnsId)) {
                                    organization.setDnsId(dnsId);
                                    databaseBackend.updateDnsId(organization, dnsId);
                                }
                            } else {
                                databaseBackend.updateDnsId(org, dnsId);
                            }
                        }
                    }
                }
            } catch(Exception e){
            }
        }).start();
    }

    private boolean putBooleanIfDiff(SharedPreferences prefs, SharedPreferences.Editor editor, String key, boolean value) {
        if (prefs.getBoolean(key, false) != value) {
            editor.putBoolean(key, value);
            return true;
        }
        return false;
    }

    private boolean putStringIfDiff(SharedPreferences prefs, SharedPreferences.Editor editor, String key, String value) {
        if (!prefs.getString(key, getResources().getString(R.string.tracking_frequency)).equals(value)) {
            editor.putString(key, value);
            return true;
        }
        return false;
    }

    public void createCognitoAccount(CognitoAccount cacct) {
        databaseBackend.createCognitoAccount(cacct);
    }

    public void updateCognitoPassword(final Account account, final String password) {
        if (account == null || TextUtils.isEmpty(password)) {
            return;
        }
        final CognitoAccount cognitoAccount = databaseBackend.getCognitoAccount(account);
        if (cognitoAccount == null) {
            return;
        }
        cognitoAccount.setPassword(password);
    }

    public void createGlacierOrg(GlacierOrgEntity orgEntity) {
        databaseBackend.createGlacierOrg(orgEntity);
        cacheOrganization(orgEntity);
        updateGlacierOrganizationPreferences(orgEntity);
        updateOrganizationUi();
    }

    public void updateGlacierOrg(GlacierOrgEntity orgEntity) {
        final GlacierOrgEntity existingOrg = getOrganizationByName(orgEntity.getOrganization());
        if (existingOrg == null) {
            databaseBackend.createGlacierOrg(orgEntity);
        } else {
            databaseBackend.updateOrganization(orgEntity);
        }

        updateGlacierOrganizationPreferences(orgEntity);

        updateOrganizationUi();
        syncLiveLocationUpdates();
        syncAutomaticSecurityUpdates();
    }

    public void updateDnsId(GlacierOrgEntity glacierOrgEntity, String dnsId) {
        if (glacierOrgEntity == null || TextUtils.isEmpty(glacierOrgEntity.getOrganization())) {
            return;
        }
        glacierOrgEntity.setDnsId(dnsId);
        databaseBackend.updateDnsId(glacierOrgEntity, dnsId);
        updateGlacierOrganizationPreferences(glacierOrgEntity);
        updateOrganizationUi();
    }

    private void syncEnabledAccountSetting() {
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        getPreferences().edit().putBoolean(SystemEventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
    }

    private void toggleSetProfilePictureActivity(final boolean enabled) {
        try {
            final ComponentName name = new ComponentName(this, ChooseAccountForProfilePictureActivity.class);
            final int targetState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager().setComponentEnabledSetting(name, targetState, PackageManager.DONT_KILL_APP);
        } catch (IllegalStateException e) {
        }
    }

    public boolean reconfigurePushDistributor() {
        return this.unifiedPushBroker.reconfigurePushDistributor();
    }

    private Optional<UnifiedPushBroker.Transport> renewUnifiedPushEndpoints(final UnifiedPushBroker.PushTargetMessenger pushTargetMessenger) {
        return this.unifiedPushBroker.renewUnifiedPushEndpoints(pushTargetMessenger);
    }

    public Optional<UnifiedPushBroker.Transport> renewUnifiedPushEndpoints() {
        return this.unifiedPushBroker.renewUnifiedPushEndpoints(null);
    }

    public UnifiedPushBroker getUnifiedPushBroker() {
        return this.unifiedPushBroker;
    }

    public void createAccountFromKey(final String alias, final OnAccountCreated callback) {
        new Thread(() -> {
            try {
                final X509Certificate[] chain = KeyChain.getCertificateChain(this, alias);
                final X509Certificate cert = chain != null && chain.length > 0 ? chain[0] : null;
                if (cert == null) {
                    callback.informUser(R.string.unable_to_parse_certificate);
                    return;
                }
                Pair<Jid, String> info = CryptoHelper.extractJidAndName(cert);
                if (info == null) {
                    callback.informUser(R.string.certificate_does_not_contain_jid);
                    return;
                }
                if (findAccountByJid(info.first) == null) {
                    final Account account = new Account(info.first, "");
                    account.setPrivateKeyAlias(alias);
                    account.setOption(Account.OPTION_DISABLED, true);
                    account.setOption(Account.OPTION_FIXED_USERNAME, true);
                    account.setDisplayName(info.second);
                    createAccount(account);
                    callback.onAccountCreated(account);

                    if (Config.X509_VERIFICATION) {
                        try {
                            getMemorizingTrustManager().getNonInteractive().checkClientTrusted(chain, "RSA");
                        } catch (CertificateException e) {
                            callback.informUser(R.string.certificate_chain_is_not_trusted);
                        }
                    }
                } else {
                    callback.informUser(R.string.account_already_exists);
                }
            } catch (Exception e) {
                callback.informUser(R.string.unable_to_parse_certificate);
            }
        }).start();

    }

    public void updateKeyInAccount(final Account account, final String alias) {
        try {
            X509Certificate[] chain = KeyChain.getCertificateChain(XmppConnectionService.this, alias);
            Pair<Jid, String> info = CryptoHelper.extractJidAndName(chain[0]);
            if (info == null) {
                showErrorToastInUi(R.string.certificate_does_not_contain_jid);
                return;
            }
            if (account.getJid().asBareJid().equals(info.first)) {
                account.setPrivateKeyAlias(alias);
                account.setDisplayName(info.second);
                databaseBackend.updateAccount(account);
                if (Config.X509_VERIFICATION) {
                    try {
                        getMemorizingTrustManager().getNonInteractive().checkClientTrusted(chain, "RSA");
                    } catch (CertificateException e) {
                        showErrorToastInUi(R.string.certificate_chain_is_not_trusted);
                    }
                    account.getAxolotlService().regenerateKeys(true);
                }
            } else {
                showErrorToastInUi(R.string.jid_does_not_match_certificate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void syncAutomaticSecurityUpdates() {
        SharedPreferences preferences = getPreferences();
        boolean enabled = preferences.getBoolean("securityhub_data_enabled", false);
        if (enabled) {
            if (!SecurityHubService.isSecurityHubWorkActive(this)) {
                final Account account = getFirstAccount();
                if (account != null) {
                    SecurityHubService.scheduleSecurityHubUpdates(
                            this,
                            account.getUsername(),
                            account.getOrganization(),
                            PhoneHelper.getAndroidId(this));
                }
            }
        } else {
            if (SecurityHubService.isSecurityHubWorkActive(this)) {
                SecurityHubService.cancelSecurityHubUpdates(this);
            }
        }
    }

    private long frequency = LocationService.DEFAULT_INTERVAL;
    public void syncLiveLocationUpdates() {

        SharedPreferences preferences = getPreferences();
        boolean trackingEnabled = preferences.getBoolean("tracking_enabled", getResources().getBoolean(R.bool.force_tracking));
        boolean forced = preferences.getBoolean("force_tracking", getResources().getBoolean(R.bool.force_tracking));
        boolean manual = preferences.getBoolean("live_location_tracking", getResources().getBoolean(R.bool.live_location_tracking));
        try {
            String freqpref = preferences.getString("tracking_frequency", getResources().getString(R.string.tracking_frequency));
            frequency =  Integer.parseInt(freqpref)* 1000L;
        }
        catch (Exception e){
        }
        boolean enabled = false;
        if (trackingEnabled) {
            if (forced) {
                enabled = true;
            } else if (manual) {
                enabled = true;
            }
        }
        locationForgroundUpdates(enabled);
    }


    public void locationForgroundUpdates(boolean enabled) {

        if (getAccounts() == null || getAccounts().size() == 0){
            return;
        }
        if (enabled) {

            if (!LocationService.isRunning() || LocationService.getFrequency() != frequency) {
                if (!hasLocationPermissions()) {
                    requestLocationPermissions();
                    return;
                } else {
                    startLocationService();
                }
            }
        }else{
            if (LocationService.isRunning() ){

                stopLocationService();
            }
        }

    }

    private boolean hasLocationPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;

        return fine && background;
    }

    private void requestLocationPermissions() {
        Intent intent = new Intent(this, LocationPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void startLocationService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        serviceIntent.putExtra("frequency", frequency);
        serviceIntent.putExtra("user", getFirstAccount().getUsername());
        serviceIntent.putExtra("org", getFirstAccount().getOrganization());
        try {
            serviceIntent.putExtra(LocationService.EXTRA_LOCATION_SERVICE, 1);
            startForegroundService(serviceIntent);

        } catch (SecurityException e) {
            Log.w(Config.LOGTAG, "Failed to start foreground service with location", e);
        }
    }


    private void stopLocationService() {
        Intent serviceIntent = new Intent(this, LocationService.class);
        serviceIntent.putExtra(LocationService.EXTRA_LOCATION_SERVICE, 2);
        startService(serviceIntent);

    }


    public void createLatestVersions(final LatestVersionEntity lv){

        mDatabaseWriterExecutor.execute(() -> databaseBackend.createLatestVersion(lv));

    }

    public boolean updateAccount(final Account account) {
        if (databaseBackend.updateAccount(account)) {
            account.setShowErrorNotification(true);
            this.statusListener.onStatusChanged(account);
            databaseBackend.updateAccount(account);
            reconnectAccountInBackground(account);
            updateAccountUi();
            getNotificationService().updateErrorNotification();
            toggleForegroundService();
            syncEnabledAccountSetting();
            mChannelDiscoveryService.cleanCache();
            if (CallIntegration.hasSystemFeature(this)) {
                CallIntegrationConnectionService.togglePhoneAccountAsync(this, account);
            }
            return true;
        } else {
            return false;
        }
    }

    public void updateAccountPasswordOnServer(final Account account, final String newPassword, final OnAccountPasswordChanged callback) {
        final Iq iq = getIqGenerator().generateSetPassword(account, newPassword);
        sendIqPacket(account, iq, (packet) -> {
            if (packet.getType() == Iq.Type.RESULT) {
                account.setPassword(newPassword);
                // A manually-set password means the user intends to authenticate with it. Clear
                // any stored JWT so SaslMechanism.Factory stops forcing PLAIN+token -- otherwise
                // the new password would silently never be used for auth.
                account.clearToken();
                account.setOption(Account.OPTION_MAGIC_CREATE, false);
                databaseBackend.updateAccount(account);
                callback.onPasswordChangeSucceeded();
            } else {
                callback.onPasswordChangeFailed();
            }
        });
    }

    public void unregisterAccount(final Account account, final Consumer<Boolean> callback) {
        final Iq iqPacket = new Iq(Iq.Type.SET);
        final Element query = iqPacket.addChild("query",Namespace.REGISTER);
        query.addChild("remove");
        sendIqPacket(account, iqPacket, (response) -> {
            if (response.getType() == Iq.Type.RESULT) {
                deleteAccount(account);
                callback.accept(true);
            } else {
                callback.accept(false);
            }
        });
    }

    public void deleteAccount(final Account account) {
        final boolean connected = account.getStatus() == Account.State.ONLINE;
        account.clearToken();
        synchronized (this.conversations) {
            if (connected) {
                account.getAxolotlService().deleteOmemoIdentity();
            }
            for (final Conversation conversation : conversations) {
                if (conversation.getAccount() == account) {
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        if (connected) {
                            leaveMuc(conversation);
                        }
                    }
                    conversations.remove(conversation);
                    mNotificationService.clear(conversation);
                }
            }
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, !connected)).start();
            }
            final Runnable runnable = () -> {
                if (!databaseBackend.deleteAccount(account)) {
                }
            };
            mDatabaseWriterExecutor.execute(runnable);
            this.accounts.remove(account);
            if (CallIntegration.hasSystemFeature(this)) {
                CallIntegrationConnectionService.unregisterPhoneAccount(this, account);
            }
            this.mRosterSyncTaskManager.clear(account);
            updateAccountUi();
            mNotificationService.updateErrorNotification();
            syncEnabledAccountSetting();
            toggleForegroundService();
        }
    }

    public void deleteOrg(String org_name){
        try {
            final Runnable runnable = () -> {
                databaseBackend.deleteOrganization(org_name);
            };
            mDatabaseWriterExecutor.execute(runnable);
        } catch (Exception ignore){};
    }

    public void setOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnConversationUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as ConversationListChangedListener");
            }
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnConversationUpdates.remove(listener);
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnShowErrorToastListener(OnShowErrorToast listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnShowErrorToasts.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnShowErrorToastListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnShowErrorToastListener(OnShowErrorToast onShowErrorToast) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnShowErrorToasts.remove(onShowErrorToast);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnAccountUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnAccountListChangedtListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnAccountUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }
    public void setOnOrganizationChangedListener(OnOrganizationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnOrganizationUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as setOnOrganizationChangedListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnOrganizationChangedListener(OnOrganizationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnOrganizationUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnAmplifySyncListener(OnAmplifySync listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnAmplifySync.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as setOnAmplifySinkListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnAmplifySyncListener(OnAmplifySync listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnAmplifySync.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnCoreStatusUpdateListener(OnCoreStatusUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnCoreStatusUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as setOnCoreStatusUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnCoreStatusUpdateListener(OnCoreStatusUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnCoreStatusUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setPhoneOnUpdateListener(OnPhoneUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnPhoneUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as setOnUpdatePhoneListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnPhoneUpdateListener(OnPhoneUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnPhoneUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnNativeContactsUpdatedListener(OnNativeContactUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnNativeContactUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as setOnNativeContactUpdatesListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnNativeContactsUpdatedListener(OnNativeContactUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnNativeContactUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnConsoleStatusListener(OnConsoleStatus listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnConsoleStatus.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnConsoleStatusListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnConsoleStatusListener(OnConsoleStatus listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnConsoleStatus.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }


    public void setOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnCaptchaRequested.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnCaptchaRequestListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnCaptchaRequested.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnRosterUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnRosterUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnRosterUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnUpdateBlocklist.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnUpdateBlocklistListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnUpdateBlocklist.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnKeyStatusUpdatedListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnKeyStatusUpdated.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnKeyStatusUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnNewKeysAvailableListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnKeyStatusUpdated.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.onJingleRtpConnectionUpdate.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnJingleRtpConnectionUpdate");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.onJingleRtpConnectionUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnMucRosterUpdateListener(OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnMucRosterUpdate.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnMucRosterListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnMucRosterUpdateListener(final OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnMucRosterUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public boolean checkListeners() {
        return (this.mOnAccountUpdates.size() == 0
                && this.mOnConversationUpdates.size() == 0
                && this.mOnRosterUpdates.size() == 0
                && this.mOnCaptchaRequested.size() == 0
                && this.mOnMucRosterUpdate.size() == 0
                && this.mOnUpdateBlocklist.size() == 0
                && this.mOnShowErrorToasts.size() == 0
                && this.onJingleRtpConnectionUpdate.size() == 0
                && this.mOnKeyStatusUpdated.size() == 0
                && this.mOnOrganizationUpdates.size() == 0
                && this.mOnNativeContactUpdates.size() == 0
                && this.mOnPhoneUpdates.size() == 0
                && this.mOnAmplifySync.size() == 0
                && this.mOnCoreStatusUpdates.size() == 0
        );
    }

    private void switchToForeground() {
        toggleSoftDisabled(false);
        final boolean broadcastLastActivity = broadcastLastActivity();
        for (Conversation conversation : getConversations()) {
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                conversation.getMucOptions().resetChatState();
            } else {
                conversation.setIncomingChatState(Config.DEFAULT_CHAT_STATE);
            }
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                account.deactivateGracePeriod();
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (connection.getFeatures().csi()) {
                        connection.sendActive();
                    }
                    if (broadcastLastActivity) {
                        sendPresence(account, false); //send new presence but don't include idle because we are not
                    }
                }
            }
        }
    }

    private void switchToBackground() {
        final boolean broadcastLastActivity = broadcastLastActivity();
        if (broadcastLastActivity) {
            mLastActivity = System.currentTimeMillis();
            final SharedPreferences.Editor editor = getPreferences().edit();
            editor.putLong(SETTING_LAST_ACTIVITY_TS, mLastActivity);
            editor.apply();
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence(account, true);
                    }
                    if (connection.getFeatures().csi()) {
                        connection.sendInactive();
                    }
                }
            }
        }
        this.mNotificationService.setIsInForeground(false);
    }

    public void connectMultiModeConversations(Account account) {
        List<Conversation> conversations = getConversations();
        for (Conversation conversation : conversations) {
            if (conversation.getMode() == Conversation.MODE_MULTI && conversation.getAccount() == account) {
                joinMuc(conversation);
            }
        }
    }

    public void mucSelfPingAndRejoin(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (account.inProgressConferenceJoins.contains(conversation)) {
                return;
            }
        }
        synchronized (account.inProgressConferencePings) {
            if (!account.inProgressConferencePings.add(conversation)) {
                return;
            }
        }
        final Jid self = conversation.getMucOptions().getSelf().getFullJid();
        final Iq ping = new Iq(Iq.Type.GET);
        ping.setTo(self);
        ping.addChild("ping", Namespace.PING);
        sendIqPacket(conversation.getAccount(), ping, (response) -> {
            if (response.getType() == Iq.Type.ERROR) {
                final var error = response.getError();
                if (error == null || error.hasChild("service-unavailable") || error.hasChild("feature-not-implemented") || error.hasChild("item-not-found")) {
                } else {
                    joinMuc(conversation);
                }
            } else if (response.getType() == Iq.Type.RESULT) {
            }
            synchronized (account.inProgressConferencePings) {
                account.inProgressConferencePings.remove(conversation);
            }
        });
    }
    public void joinMuc(Conversation conversation) {
        joinMuc(conversation, null, false);
    }

    public void joinMuc(Conversation conversation, boolean followedInvite) {
        joinMuc(conversation, null, followedInvite);
    }

    private void joinMuc(Conversation conversation, final OnConferenceJoined onConferenceJoined) {
        joinMuc(conversation, onConferenceJoined, false);
    }

    private void joinMuc(Conversation conversation, final OnConferenceJoined onConferenceJoined, final boolean followedInvite) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE) {
            synchronized (account.inProgressConferenceJoins) {
                account.inProgressConferenceJoins.add(conversation);
            }
            if (Config.MUC_LEAVE_BEFORE_JOIN) {
                sendPresencePacket(account, mPresenceGenerator.leave(conversation.getMucOptions()));
            }
            conversation.resetMucOptions();
            if (onConferenceJoined != null) {
                conversation.getMucOptions().flagNoAutoPushConfiguration();
            }
            conversation.setHasMessagesLeftOnServer(false);
            fetchConferenceConfiguration(conversation, new OnConferenceConfigurationFetched() {

                private void join(Conversation conversation) {
                    Account account = conversation.getAccount();
                    final MucOptions mucOptions = conversation.getMucOptions();

                    if (mucOptions.nonanonymous() && !mucOptions.membersOnly() && !conversation.getBooleanAttribute("accept_non_anonymous", false)) {
                        synchronized (account.inProgressConferenceJoins) {
                            account.inProgressConferenceJoins.remove(conversation);
                        }
                        mucOptions.setError(MucOptions.Error.NON_ANONYMOUS);
                        updateConversationUi();
                        if (onConferenceJoined != null) {
                            onConferenceJoined.onConferenceJoined(conversation);
                        }
                        return;
                    }

                    final Jid joinJid = mucOptions.getSelf().getFullJid();
                    final var packet = mPresenceGenerator.selfPresence(account, Presence.Status.ONLINE, mucOptions.nonanonymous() || onConferenceJoined != null);
                    packet.setTo(joinJid);
                    Element x = packet.addChild("x", "http://jabber.org/protocol/muc");
                    if (conversation.getMucOptions().getPassword() != null) {
                        x.addChild("password").setContent(mucOptions.getPassword());
                    }

                    if (mucOptions.mamSupport()) {
                        // Use MAM instead of the limited muc history to get history
                        x.addChild("history").setAttribute("maxchars", "0");
                    } else {
                        // Fallback to muc history
                        x.addChild("history").setAttribute("since", PresenceGenerator.getTimestamp(conversation.getLastMessageTransmitted().getTimestamp()));
                    }
                    sendPresencePacket(account, packet);
                    if (onConferenceJoined != null) {
                        onConferenceJoined.onConferenceJoined(conversation);
                    }
                    if (!joinJid.equals(conversation.getJid())) {
                        conversation.setContactJid(joinJid);
                        databaseBackend.updateConversation(conversation);
                    }

                    if (mucOptions.mamSupport()) {
                        getMessageArchiveService().catchupMUC(conversation);
                    }
                    if (mucOptions.isPrivateAndNonAnonymous()) {
                        fetchConferenceMembers(conversation);

                        if (followedInvite) {
                            final Bookmark bookmark = conversation.getBookmark();
                            if (bookmark != null) {
                                if (!bookmark.autojoin()) {
                                    bookmark.setAutojoin(true);
                                    createBookmark(account, bookmark);
                                }
                            } else {
                                saveConversationAsBookmark(conversation, null);
                            }
                        }
                    }
                    synchronized (account.inProgressConferenceJoins) {
                        account.inProgressConferenceJoins.remove(conversation);
                        sendUnsentMessages(conversation);
                    }
                }

                @Override
                public void onConferenceConfigurationFetched(Conversation conversation) {
                    if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                        return;
                    }
                    join(conversation);
                }

                @Override
                public void onFetchFailed(final Conversation conversation, final String errorCondition) {
                    if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                        return;
                    }
                    if ("remote-server-not-found".equals(errorCondition)) {
                        synchronized (account.inProgressConferenceJoins) {
                            account.inProgressConferenceJoins.remove(conversation);
                        }
                        conversation.getMucOptions().setError(MucOptions.Error.SERVER_NOT_FOUND);
                        updateConversationUi();
                    } else {
                        join(conversation);
                        fetchConferenceConfiguration(conversation);
                    }
                }
            });
            updateConversationUi();
        } else {
            synchronized (account.pendingConferenceJoins) {
                account.pendingConferenceJoins.add(conversation);
            }
            conversation.resetMucOptions();
            conversation.setHasMessagesLeftOnServer(false);
            updateConversationUi();
        }
    }

    private void fetchConferenceMembers(final Conversation conversation) {
        final Account account = conversation.getAccount();
        final AxolotlService axolotlService = account.getAxolotlService();
        final String[] affiliations = {"member", "admin", "owner"};
        final Consumer<Iq> callback = new Consumer<Iq>() {

            private int i = 0;
            private boolean success = true;
            private final Set<Jid> returnedRealJids = new HashSet<>();

            @Override
            public void accept(Iq response) {
                final boolean omemoEnabled = conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL;
                Element query = response.query("http://jabber.org/protocol/muc#admin");
                if (response.getType() == Iq.Type.RESULT && query != null) {
                    for (Element child : query.getChildren()) {
                        if ("item".equals(child.getName())) {
                            MucOptions.User user = AbstractParser.parseItem(conversation, child);
                            if (user.getRealJid() != null) {
                                returnedRealJids.add(user.getRealJid().asBareJid());
                            }
                            if (!user.realJidMatchesAccount()) {
                                boolean isNew = conversation.getMucOptions().updateUser(user);
                                Contact contact = user.getContact();
                                if (omemoEnabled
                                        && isNew
                                        && user.getRealJid() != null
                                        && (contact == null || !contact.mutualPresenceSubscription())
                                        && axolotlService.hasEmptyDeviceList(user.getRealJid())) {
                                    axolotlService.fetchDeviceIds(user.getRealJid());
                                }
                            }
                        }
                    }
                } else {
                    success = false;
                }
                ++i;
                if (i >= affiliations.length) {
                    if (success) {
                        conversation.getMucOptions().removeUnaffiliatedUsers(returnedRealJids);
                    }
                    List<Jid> members = conversation.getMucOptions().getMembers(true);
                    if (success) {
                        List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                        boolean changed = false;
                        for (ListIterator<Jid> iterator = cryptoTargets.listIterator(); iterator.hasNext(); ) {
                            Jid jid = iterator.next();
                            if (!members.contains(jid) && !members.contains(jid.getDomain())) {
                                iterator.remove();
                                changed = true;
                            }
                        }
                        if (changed) {
                            conversation.setAcceptedCryptoTargets(cryptoTargets);
                            updateConversation(conversation);
                        }
                    }
                    getAvatarService().clear(conversation);
                    updateMucRosterUi();
                    updateConversationUi();
                }
            }
        };
        for (String affiliation : affiliations) {
            sendIqPacket(account, mIqGenerator.queryAffiliation(conversation, affiliation), callback);
        }
    }

    public void refreshPrivateMucMemberLists(final Account account) {
        for (final Conversation conversation : getConversations()) {
            if (conversation.getAccount() == account
                    && conversation.getMode() == Conversation.MODE_MULTI
                    && conversation.getMucOptions().isPrivateAndNonAnonymous()
                    && conversation.getMucOptions().online()) {
                fetchConferenceMembers(conversation);
            }
        }
    }

    public void providePasswordForMuc(final Conversation conversation, final String password) {
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            conversation.getMucOptions().setPassword(password);
            if (conversation.getBookmark() != null) {
                final Bookmark bookmark = conversation.getBookmark();
                bookmark.setAutojoin(true);
                createBookmark(conversation.getAccount(), bookmark);
            }
            updateConversation(conversation);
            joinMuc(conversation);
        }
    }

    public void deleteAvatar(final Account account) {
        final AtomicBoolean executed = new AtomicBoolean(false);
        final Runnable onDeleted =
                () -> {
                    if (executed.compareAndSet(false, true)) {
                        account.setAvatar(null);
                        databaseBackend.updateAccount(account);
                        getAvatarService().clear(account);
                        updateAccountUi();
                    }
                };
        deleteVcardAvatar(account, onDeleted);
        deletePepNode(account, Namespace.AVATAR_DATA);
        deletePepNode(account, Namespace.AVATAR_METADATA, onDeleted);
    }

    public void deletePepNode(final Account account, final String node) {
        deletePepNode(account, node, null);
    }

    private void deletePepNode(final Account account, final String node, final Runnable runnable) {
        final Iq request = mIqGenerator.deleteNode(node);
        sendIqPacket(account, request, (packet) -> {
            if (packet.getType() == Iq.Type.RESULT) {
                if (runnable != null) {
                    runnable.run();
                }
            }
        });
    }

    private void deleteVcardAvatar(final Account account, @NonNull final Runnable runnable) {
        final Iq retrieveVcard = mIqGenerator.retrieveVcardAvatar(account.getJid().asBareJid());
        sendIqPacket(account, retrieveVcard, (response) -> {
            if (response.getType() != Iq.Type.RESULT) {
                return;
            }
            final Element vcard = response.findChild("vCard", "vcard-temp");
            if (vcard == null) {
                return;
            }
            Element photo = vcard.findChild("PHOTO");
            if (photo == null) {
                photo = vcard.addChild("PHOTO");
            }
            photo.clearChildren();
            final Iq publication = new Iq(Iq.Type.SET);
            publication.setTo(account.getJid().asBareJid());
            publication.addChild(vcard);
            sendIqPacket(account, publication, (publicationResponse) -> {
                if (publicationResponse.getType() == Iq.Type.RESULT) {
                    runnable.run();
                }
            });
        });
    }

    private boolean hasEnabledAccounts() {
        if (this.accounts == null) {
            return false;
        }
        for (final Account account : this.accounts) {
            if (account.isConnectionEnabled()) {
                return true;
            }
        }
        return false;
    }


    public void getAttachments(final Conversation conversation, int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments(conversation.getAccount(), conversation.getJid().asBareJid(), limit, onMediaLoaded);
    }

    public void getAttachments(final Account account, final Jid jid, final int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments(account.getUuid(), jid.asBareJid(), limit, onMediaLoaded);
    }


    public void getAttachments(final String account, final Jid jid, final int limit, final OnMediaLoaded onMediaLoaded) {
        new Thread(() -> onMediaLoaded.onMediaLoaded(getAttachments(account, jid, limit))).start();
    }

    private List<Attachment> getAttachments(final String account, final Jid jid, final int limit) {
        if (limit <= 0) {
            return fileBackend.convertToAttachments(
                    databaseBackend.getRelativeFilePaths(account, jid, 0));
        }
        // Rows are filtered against what is on disk after the query, so a single LIMIT could come
        // back short - or empty - when the most recent messages all reference files that are gone.
        // Keep pulling batches until we have enough that exist, or the table is exhausted.
        final int batchSize = Math.max(limit * 8, 32);
        final List<Attachment> attachments = new ArrayList<>();
        int offset = 0;
        while (attachments.size() < limit) {
            final List<DatabaseBackend.FilePath> batch =
                    databaseBackend.getRelativeFilePaths(account, jid, batchSize, offset);
            if (batch.isEmpty()) {
                break;
            }
            attachments.addAll(fileBackend.convertToAttachments(batch));
            offset += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        return attachments.size() > limit
                ? new ArrayList<>(attachments.subList(0, limit))
                : attachments;
    }

    public void persistSelfNick(final MucOptions.User self) {
        final Conversation conversation = self.getConversation();
        final boolean tookProposedNickFromBookmark = conversation.getMucOptions().isTookProposedNickFromBookmark();
        Jid full = self.getFullJid();
        if (!full.equals(conversation.getJid())) {
            conversation.setContactJid(full);
            databaseBackend.updateConversation(conversation);
        }

        final Bookmark bookmark = conversation.getBookmark();
        final String bookmarkedNick = bookmark == null ? null : bookmark.getNick();
        if (bookmark != null && (tookProposedNickFromBookmark || Strings.isNullOrEmpty(bookmarkedNick)) && !full.getResource().equals(bookmarkedNick)) {
            final Account account = conversation.getAccount();
            final String defaultNick = MucOptions.defaultNick(account);
            if (Strings.isNullOrEmpty(bookmarkedNick) && full.getResource().equals(defaultNick)) {
                return;
            }
            bookmark.setNick(full.getResource());
            createBookmark(bookmark.getAccount(), bookmark);
        }
    }

    public boolean renameInMuc(final Conversation conversation, final String nick, final UiCallback<Conversation> callback) {
        final MucOptions options = conversation.getMucOptions();
        final Jid joinJid = options.createJoinJid(nick);
        if (joinJid == null) {
            return false;
        }
        if (options.online()) {
            Account account = conversation.getAccount();
            options.setOnRenameListener(new OnRenameListener() {

                @Override
                public void onSuccess() {
                    callback.success(conversation);
                }

                @Override
                public void onFailure() {
                    callback.error(R.string.nick_in_use);
                }
            });

            final var packet = mPresenceGenerator.selfPresence(account, Presence.Status.ONLINE, options.nonanonymous());
            packet.setTo(joinJid);
            sendPresencePacket(account, packet);
        } else {
            conversation.setContactJid(joinJid);
            databaseBackend.updateConversation(conversation);
            if (conversation.getAccount().getStatus() == Account.State.ONLINE) {
                Bookmark bookmark = conversation.getBookmark();
                if (bookmark != null) {
                    bookmark.setNick(nick);
                    createBookmark(bookmark.getAccount(), bookmark);
                }
                joinMuc(conversation);
            }
        }
        return true;
    }

    public void leaveMuc(Conversation conversation) {
        leaveMuc(conversation, false);
    }

    private void leaveMuc(Conversation conversation, boolean now) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE || now) {
            sendPresencePacket(conversation.getAccount(), mPresenceGenerator.leave(conversation.getMucOptions()));
            conversation.getMucOptions().setOffline();
            Bookmark bookmark = conversation.getBookmark();
            if (bookmark != null) {
                bookmark.setConversation(null);
            }
        } else {
            synchronized (account.pendingConferenceLeaves) {
                account.pendingConferenceLeaves.add(conversation);
            }
        }
    }

    public String findConferenceServer(final Account account) {
        String server;
        if (account.getXmppConnection() != null) {
            server = account.getXmppConnection().getMucServer();
            if (server != null) {
                return server;
            }
        }
        for (Account other : getAccounts()) {
            if (other != account && other.getXmppConnection() != null) {
                server = other.getXmppConnection().getMucServer();
                if (server != null) {
                    return server;
                }
            }
        }
        return null;
    }


    public void createPublicChannel(final Account account, final String name, final Jid address, final UiCallback<Conversation> callback) {
        createPublicChannel(account, name, address, Collections.emptyList(), callback);
    }

    public void createPublicChannel(final Account account, final String name, final Jid address, final Iterable<Jid> invitees, final UiCallback<Conversation> callback) {
        joinMuc(findOrCreateConversation(account, address, true, false, true), conversation -> {
            final Bundle configuration = IqGenerator.defaultChannelConfiguration();
            if (!TextUtils.isEmpty(name)) {
                configuration.putString("muc#roomconfig_roomname", name);
            }
            pushConferenceConfiguration(conversation, configuration, new OnConfigurationPushed() {
                @Override
                public void onPushSucceeded() {
                    for (final Jid invite : invitees) {
                        invite(conversation, invite);
                    }
                    saveConversationAsBookmark(conversation, name);
                    callback.success(conversation);
                }

                @Override
                public void onPushFailed() {
                    if (conversation.getMucOptions().getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                        callback.error(R.string.unable_to_set_channel_configuration);
                    } else {
                        callback.error(R.string.joined_an_existing_channel);
                    }
                }
            });
        });
    }

    public boolean createAdhocConference(final Account account,
                                         final String name,
                                         final Iterable<Jid> jids,
                                         final UiCallback<Conversation> callback) {
        if (account.getStatus() == Account.State.ONLINE) {
            try {
                String server = findConferenceServer(account);
                if (server == null) {
                    if (callback != null) {
                        callback.error(R.string.no_conference_server_found);
                    }
                    return false;
                }
                final Jid jid = Jid.of(CryptoHelper.pronounceable(), server, null);
                final Conversation conversation = findOrCreateConversation(account, jid, true, false, true);
                joinMuc(conversation, new OnConferenceJoined() {
                    @Override
                    public void onConferenceJoined(final Conversation conversation) {
                        final Bundle configuration = IqGenerator.defaultGroupChatConfiguration();
                        if (!TextUtils.isEmpty(name)) {
                            configuration.putString("muc#roomconfig_roomname", name);
                        }
                        pushConferenceConfiguration(conversation, configuration, new OnConfigurationPushed() {
                            @Override
                            public void onPushSucceeded() {
                                for (Jid invite : jids) {
                                    invite(conversation, invite);
                                }
                                for (String resource : account.getSelfContact().getPresences().toResourceArray()) {
                                    Jid other = account.getJid().withResource(resource);
                                    directInvite(conversation, other);
                                }
                                saveConversationAsBookmark(conversation, name);
                                if (callback != null) {
                                    callback.success(conversation);
                                }
                            }

                            @Override
                            public void onPushFailed() {
                                archiveConversation(conversation);
                                if (callback != null) {
                                    callback.error(R.string.conference_creation_failed);
                                }
                            }
                        });
                    }
                });
                return true;
            } catch (IllegalArgumentException e) {
                if (callback != null) {
                    callback.error(R.string.conference_creation_failed);
                }
                return false;
            }
        } else {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again);
            }
            return false;
        }
    }

    public void fetchConferenceConfiguration(final Conversation conversation) {
        fetchConferenceConfiguration(conversation, null);
    }

    public void fetchConferenceConfiguration(final Conversation conversation, final OnConferenceConfigurationFetched callback) {
        final Iq request = mIqGenerator.queryDiscoInfo(conversation.getJid().asBareJid());
        final var account = conversation.getAccount();
        sendIqPacket(account, request, response -> {
            if (response.getType() == Iq.Type.RESULT) {
                final MucOptions mucOptions = conversation.getMucOptions();
                final Bookmark bookmark = conversation.getBookmark();
                final boolean sameBefore = StringUtils.equals(bookmark == null ? null : bookmark.getBookmarkName(), mucOptions.getName());

                if (mucOptions.updateConfiguration(new ServiceDiscoveryResult(response))) {
                    updateConversation(conversation);
                }

                if (bookmark != null && (sameBefore || bookmark.getBookmarkName() == null)) {
                    if (bookmark.setBookmarkName(StringUtils.nullOnEmpty(mucOptions.getName()))) {
                        createBookmark(account, bookmark);
                    }
                }


                if (callback != null) {
                    callback.onConferenceConfigurationFetched(conversation);
                }


                updateConversationUi();
            } else if (response.getType() == Iq.Type.TIMEOUT) {
                // No callback fires here on purpose. Iq.Type.TIMEOUT on this request is only ever
                // delivered by clearIqCallbacks(), i.e. the session is being torn down and a bind
                // is imminent; BindProcessor then clears inProgressConferenceJoins and
                // connectMultiModeConversations() requeues the join. Releasing the in-progress
                // marker here instead would make isJoinInProgress() false while no join presence
                // has been sent, so sendMessage() would transmit into a room we are not in and
                // skip the re-encryption that sendUnsentMessages() applies on a real join.
            } else {
                if (callback != null) {
                    callback.onFetchFailed(conversation, response.getErrorCondition());
                }
            }
        });
    }

    public void pushNodeConfiguration(Account account, final String node, final Bundle options, final OnConfigurationPushed callback) {
        pushNodeConfiguration(account, account.getJid().asBareJid(), node, options, callback);
    }

    public void pushNodeConfiguration(Account account, final Jid jid, final String node, final Bundle options, final OnConfigurationPushed callback) {
        sendIqPacket(account, mIqGenerator.requestPubsubConfiguration(jid, node), responseToRequest -> {
            if (responseToRequest.getType() == Iq.Type.RESULT) {
                Element pubsub = responseToRequest.findChild("pubsub", "http://jabber.org/protocol/pubsub#owner");
                Element configuration = pubsub == null ? null : pubsub.findChild("configure");
                Element x = configuration == null ? null : configuration.findChild("x", Namespace.DATA);
                if (x != null) {
                    final Data data = Data.parse(x);
                    data.submit(options);
                    sendIqPacket(account, mIqGenerator.publishPubsubConfiguration(jid, node, data), responseToPublish -> {
                        if (responseToPublish.getType() == Iq.Type.RESULT && callback != null) {
                            callback.onPushSucceeded();
                        } else if (responseToPublish.getType() == Iq.Type.ERROR && callback != null) {
                            callback.onPushFailed();
                        }
                    });
                } else if (callback != null) {
                    callback.onPushFailed();
                }
            } else if (responseToRequest.getType() == Iq.Type.ERROR && callback != null) {
                callback.onPushFailed();
            }
        });
    }

    public void pushConferenceConfiguration(final Conversation conversation, final Bundle options, final OnConfigurationPushed callback) {
        if (options.getString("muc#roomconfig_whois", "moderators").equals("anyone")) {
            conversation.setAttribute("accept_non_anonymous", true);
            updateConversation(conversation);
        }
        if (options.containsKey("muc#roomconfig_moderatedroom")) {
            final boolean moderated = "1".equals(options.getString("muc#roomconfig_moderatedroom"));
            options.putString("members_by_default", moderated ? "0" : "1");
        }
        if (options.containsKey("muc#roomconfig_allowpm")) {
            // ejabberd :-/
            final boolean allow = "anyone".equals(options.getString("muc#roomconfig_allowpm"));
            options.putString("allow_private_messages", allow ? "1" : "0");
            options.putString("allow_private_messages_from_visitors", allow ? "anyone" : "nobody");
        }
        final var account = conversation.getAccount();
        final Iq request = new Iq(Iq.Type.GET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner");
        sendIqPacket(account, request, response -> {
            if (response.getType() == Iq.Type.RESULT) {
                final Data data = Data.parse(response.query().findChild("x", Namespace.DATA));
                data.submit(options);
                final Iq set = new Iq(Iq.Type.SET);
                set.setTo(conversation.getJid().asBareJid());
                set.query("http://jabber.org/protocol/muc#owner").addChild(data);
                sendIqPacket(account, set, packet -> {
                    if (callback != null) {
                        if (packet.getType() == Iq.Type.RESULT) {
                            callback.onPushSucceeded();
                        } else {
                            callback.onPushFailed();
                        }
                    }
                });
            } else {
                if (callback != null) {
                    callback.onPushFailed();
                }
            }
        });
    }

    public void pushSubjectToConference(final Conversation conference, final String subject) {
        final var packet = this.getMessageGenerator().conferenceSubject(conference, StringUtils.nullOnEmpty(subject));
        this.sendMessagePacket(conference.getAccount(), packet);
    }

    public void changeAffiliationInConference(final Conversation conference, Jid user, final MucOptions.Affiliation affiliation, final OnAffiliationChanged callback) {
        final Jid jid = user.asBareJid();
        final Iq request = this.mIqGenerator.changeAffiliation(conference, jid, affiliation.toString());
        sendIqPacket(conference.getAccount(), request, (response) -> {
            if (response.getType() == Iq.Type.RESULT) {
                conference.getMucOptions().changeAffiliation(jid, affiliation);
                getAvatarService().clear(conference);
                if (callback != null) {
                    callback.onAffiliationChangedSuccessful();
                }
            } else if (callback != null) {
                callback.onAffiliationChangeFailed(jid, R.string.could_not_change_affiliation);
            }
        });
    }

    public void changeRoleInConference(final Conversation conference, final String nick, MucOptions.Role role) {
        final var account =conference.getAccount();
        final Iq request = this.mIqGenerator.changeRole(conference, nick, role.toString());
        sendIqPacket(account, request, (packet) -> {
            if (packet.getType() != Iq.Type.RESULT) {
            }
        });
    }

    public void destroyRoom(final Conversation conversation, final OnRoomDestroy callback) {
        final Iq request = new Iq(Iq.Type.SET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner").addChild("destroy");
        sendIqPacket(conversation.getAccount(), request, response -> {
            if (response.getType() == Iq.Type.RESULT) {
                if (callback != null) {
                    callback.onRoomDestroySucceeded();
                }
            } else if (response.getType() == Iq.Type.ERROR) {
                if (callback != null) {
                    callback.onRoomDestroyFailed();
                }
            }
        });
    }

    private void disconnect(final Account account, boolean force) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        if (!force) {
            final List<Conversation> conversations = getConversations();
            final List<Conversation> leftMucs = new ArrayList<>();
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account) {
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        leaveMuc(conversation, true);
                        leftMucs.add(conversation);
                    }
                }
            }
            // A graceful teardown sends unavailable presence to every room, but it leaves the
            // stream resumable. If the next connection resumes instead of binding, BindProcessor
            // - and with it connectMultiModeConversations() - never runs, so nothing rejoins the
            // rooms we just left: every group chat stays offline with Error.NO_RESPONSE and is
            // stuck showing "Joining group...". Queue the rejoins instead; the ONLINE status
            // handler drains pendingConferenceJoins on the bind and the resume path alike.
            // leaveMuc() clears the conversation from the pending sets, so this has to run after.
            synchronized (account.pendingConferenceJoins) {
                account.pendingConferenceJoins.addAll(leftMucs);
            }
            sendOfflinePresence(account);
        }
        connection.disconnect(force);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void updateMessage(Message message) {
        updateMessage(message, true);
    }

    public void updateMessage(Message message, boolean includeBody) {
        databaseBackend.updateMessage(message, includeBody);
        updateConversationUi();
    }

    public void createMessageAsync(final Message message) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.createMessage(message));
    }

    public void updateMessage(Message message, String uuid) {
        if (!databaseBackend.updateMessage(message, uuid)) {
            Log.e(Config.LOGTAG, "error updated message in DB after edit");
        }
        updateConversationUi();
    }

    public void syncDirtyContacts(Account account) {
        for (Contact contact : account.getRoster().getContacts()) {
            if (contact.getOption(Contact.Options.DIRTY_PUSH)) {
                pushContactToServer(contact);
            }
            if (contact.getOption(Contact.Options.DIRTY_DELETE)) {
                deleteContactOnServer(contact);
            }
        }
    }

    public void createContact(final Contact contact, final boolean autoGrant) {
        createContact(contact, autoGrant, null);
    }

    public void createContact(final Contact contact, final boolean autoGrant, final String preAuth) {
        if (autoGrant) {
            contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
            contact.setOption(Contact.Options.ASKING);
        }
        pushContactToServer(contact, preAuth);
    }

    public void pushContactToServer(final Contact contact) {
        pushContactToServer(contact, null);
    }

    private void pushContactToServer(final Contact contact, final String preAuth) {
        contact.resetOption(Contact.Options.DIRTY_DELETE);
        contact.setOption(Contact.Options.DIRTY_PUSH);
        final Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final boolean ask = contact.getOption(Contact.Options.ASKING);
            final boolean sendUpdates = contact
                    .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
                    && contact.getOption(Contact.Options.PREEMPTIVE_GRANT);
            final Iq iq = new Iq(Iq.Type.SET);
            iq.query(Namespace.ROSTER).addChild(contact.asElement());
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
            if (sendUpdates) {
                sendPresencePacket(account, mPresenceGenerator.sendPresenceUpdatesTo(contact));
            }
            if (ask) {
                sendPresencePacket(account, mPresenceGenerator.requestPresenceUpdatesFrom(contact, preAuth));
            }
        } else {
            syncRoster(contact.getAccount());
        }
    }

    public void publishMucAvatar(final Conversation conversation, final Uri image, final OnAvatarPublication callback) {
        new Thread(() -> {
            final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
            final int size = Config.AVATAR_SIZE;
            final Avatar avatar = getFileBackend().getPepAvatar(image, size, format);
            if (avatar != null) {
                if (!getFileBackend().save(avatar)) {
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                    return;
                }
                avatar.owner = conversation.getJid().asBareJid();
                publishMucAvatar(conversation, avatar, callback);
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
            }
        }).start();
    }

    public void publishAvatar(final Account account, final Uri image, final OnAvatarPublication callback) {
        new Thread(() -> {
            final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
            final int size = Config.AVATAR_SIZE;
            final Avatar avatar = getFileBackend().getPepAvatar(image, size, format);
            if (avatar != null) {
                if (!getFileBackend().save(avatar)) {
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                    return;
                }
                publishAvatar(account, avatar, callback);
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
            }
        }).start();

    }

    private void publishMucAvatar(Conversation conversation, Avatar avatar, OnAvatarPublication callback) {
        final var account = conversation.getAccount();
        final Iq retrieve = mIqGenerator.retrieveVcardAvatar(avatar);
        sendIqPacket(account, retrieve, (response) -> {
            boolean itemNotFound = response.getType() == Iq.Type.ERROR && response.hasChild("error") && response.findChild("error").hasChild("item-not-found");
            if (response.getType() == Iq.Type.RESULT || itemNotFound) {
                Element vcard = response.findChild("vCard", "vcard-temp");
                if (vcard == null) {
                    vcard = new Element("vCard", "vcard-temp");
                }
                Element photo = vcard.findChild("PHOTO");
                if (photo == null) {
                    photo = vcard.addChild("PHOTO");
                }
                photo.clearChildren();
                photo.addChild("TYPE").setContent(avatar.type);
                photo.addChild("BINVAL").setContent(avatar.image);
                final Iq publication = new Iq(Iq.Type.SET);
                publication.setTo(conversation.getJid().asBareJid());
                publication.addChild(vcard);
                sendIqPacket(account, publication, (publicationResponse) -> {
                    if (publicationResponse.getType() == Iq.Type.RESULT) {
                        callback.onAvatarPublicationSucceeded();
                    } else {
                        callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject);
                    }
                });
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_no_server_support);
            }
        });
    }

    public void publishAvatar(Account account, final Avatar avatar, final OnAvatarPublication callback) {
        final Bundle options;
        if (account.getXmppConnection().getFeatures().pepPublishOptions()) {
            options = PublishOptions.openAccess();
        } else {
            options = null;
        }
        publishAvatar(account, avatar, options, true, callback);
    }

    public void publishAvatar(Account account, final Avatar avatar, final Bundle options, final boolean retry, final OnAvatarPublication callback) {
        final Iq packet = this.mIqGenerator.publishAvatar(avatar, options);
        this.sendIqPacket(account, packet, result -> {
            if (result.getType() == Iq.Type.RESULT) {
                publishAvatarMetadata(account, avatar, options, true, callback);
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                pushNodeConfiguration(account, Namespace.AVATAR_DATA, options, new OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        publishAvatar(account, avatar, options, false, callback);
                    }

                    @Override
                    public void onPushFailed() {
                        publishAvatar(account, avatar, null, false, callback);
                    }
                });
            } else {
                Element error = result.findChild("error");
                if (callback != null) {
                    callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject);
                }
            }
        });
    }

    public void publishAvatarMetadata(Account account, final Avatar avatar, final Bundle options, final boolean retry, final OnAvatarPublication callback) {
        final Iq packet = XmppConnectionService.this.mIqGenerator.publishAvatarMetadata(avatar, options);
        sendIqPacket(account, packet, result -> {
            if (result.getType() == Iq.Type.RESULT) {
                if (account.setAvatar(avatar.getFilename())) {
                    getAvatarService().clear(account);
                    databaseBackend.updateAccount(account);
                    notifyAccountAvatarHasChanged(account);
                }
                if (callback != null) {
                    callback.onAvatarPublicationSucceeded();
                }
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                pushNodeConfiguration(account, Namespace.AVATAR_METADATA, options, new OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        publishAvatarMetadata(account, avatar, options, false, callback);
                    }

                    @Override
                    public void onPushFailed() {
                        publishAvatarMetadata(account, avatar, null, false, callback);
                    }
                });
            } else {
                if (callback != null) {
                    callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject);
                }
            }
        });
    }

    public void republishAvatarIfNeeded(Account account) {
        if (account.getAxolotlService().isPepBroken()) {
            return;
        }
        final Iq packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(account, packet, new Consumer<Iq>() {

            private Avatar parseAvatar(Iq packet) {
                Element pubsub = packet.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                if (pubsub != null) {
                    Element items = pubsub.findChild("items");
                    if (items != null) {
                        return Avatar.parseMetadata(items);
                    }
                }
                return null;
            }

            private boolean errorIsItemNotFound(Iq packet) {
                Element error = packet.findChild("error");
                return packet.getType() == Iq.Type.ERROR
                        && error != null
                        && error.hasChild("item-not-found");
            }

            @Override
            public void accept(final Iq packet) {
                if (packet.getType() == Iq.Type.RESULT || errorIsItemNotFound(packet)) {
                    Avatar serverAvatar = parseAvatar(packet);
                    if (serverAvatar == null && account.getAvatar() != null) {
                        Avatar avatar = fileBackend.getStoredPepAvatar(account.getAvatar());
                        if (avatar != null) {
                            publishAvatar(account, fileBackend.getStoredPepAvatar(account.getAvatar()), null);
                        } else {
                            Log.e(Config.LOGTAG, Jid.obfuscateForLog(account.getJid().asBareJid()) + ": error rereading avatar");
                        }
                    }
                }
            }
        });
    }

    public void cancelAvatarFetches(final Account account) {
        synchronized (mInProgressAvatarFetches) {
            for (final Iterator<String> iterator = mInProgressAvatarFetches.iterator(); iterator.hasNext(); ) {
                final String KEY = iterator.next();
                if (KEY.startsWith(account.getJid().asBareJid() + "_")) {
                    iterator.remove();
                }
            }
        }
    }

    public void fetchAvatar(Account account, Avatar avatar) {
        fetchAvatar(account, avatar, null);
    }

    public void fetchAvatar(Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final String KEY = generateFetchKey(account, avatar);
        synchronized (this.mInProgressAvatarFetches) {
            if (mInProgressAvatarFetches.add(KEY)) {
                switch (avatar.origin) {
                    case PEP:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarPep(account, avatar, callback);
                        break;
                    case VCARD:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarVcard(account, avatar);
                        break;
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                mOmittedPepAvatarFetches.add(KEY);
            }
        }
    }

    private void fetchAvatarPep(final Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrievePepAvatar(avatar);
        sendIqPacket(account, packet, (result) -> {
            synchronized (mInProgressAvatarFetches) {
                mInProgressAvatarFetches.remove(generateFetchKey(account, avatar));
            }
            final String ERROR = account.getJid().asBareJid() + ": fetching avatar for " + avatar.owner + " failed ";
            if (result.getType() == Iq.Type.RESULT) {
                avatar.image = IqParser.avatarData(result);
                if (avatar.image != null) {
                    if (getFileBackend().save(avatar)) {
                        if (account.getJid().asBareJid().equals(avatar.owner)) {
                            if (account.setAvatar(avatar.getFilename())) {
                                databaseBackend.updateAccount(account);
                            }
                            getAvatarService().clear(account);
                            updateConversationUi();
                            updateAccountUi();
                        } else {
                            final Contact contact = account.getRoster().getContact(avatar.owner);
                            contact.setAvatar(avatar);
                            syncRoster(account);
                            getAvatarService().clear(contact);
                            updateConversationUi();
                            updateRosterUi();
                        }
                        if (callback != null) {
                            callback.success(avatar);
                        }
                        return;
                    }
                }
            } else {
                Element error = result.findChild("error");
            }
            if (callback != null) {
                callback.error(0);
            }

        });
    }

    private void fetchAvatarVcard(final Account account, final Avatar avatar) {
        final Iq packet = this.mIqGenerator.retrieveVcardAvatar(avatar);
        this.sendIqPacket(account, packet, response -> {
            final boolean previouslyOmittedPepFetch;
            synchronized (mInProgressAvatarFetches) {
                final String KEY = generateFetchKey(account, avatar);
                mInProgressAvatarFetches.remove(KEY);
                previouslyOmittedPepFetch = mOmittedPepAvatarFetches.remove(KEY);
            }
            if (response.getType() == Iq.Type.RESULT) {
                Element vCard = response.findChild("vCard", "vcard-temp");
                Element photo = vCard != null ? vCard.findChild("PHOTO") : null;
                String image = photo != null ? photo.findChildContent("BINVAL") : null;
                if (image != null) {
                    avatar.image = image;
                    if (getFileBackend().save(avatar)) {
                        if (avatar.owner.isBareJid()) {
                            if (account.getJid().asBareJid().equals(avatar.owner) && account.getAvatar() == null) {
                                account.setAvatar(avatar.getFilename());
                                databaseBackend.updateAccount(account);
                                getAvatarService().clear(account);
                                updateAccountUi();
                            } else {
                                final Contact contact = account.getRoster().getContact(avatar.owner);
                                contact.setAvatar(avatar, previouslyOmittedPepFetch);
                                syncRoster(account);
                                getAvatarService().clear(contact);
                                updateRosterUi();
                            }
                            updateConversationUi();
                        } else {
                            Conversation conversation = find(account, avatar.owner.asBareJid());
                            if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                                MucOptions.User user = conversation.getMucOptions().findUserByFullJid(avatar.owner);
                                if (user != null) {
                                    if (user.setAvatar(avatar)) {
                                        getAvatarService().clear(user);
                                        updateConversationUi();
                                        updateMucRosterUi();
                                    }
                                    if (user.getRealJid() != null) {
                                        Contact contact = account.getRoster().getContact(user.getRealJid());
                                        contact.setAvatar(avatar);
                                        syncRoster(account);
                                        getAvatarService().clear(contact);
                                        updateRosterUi();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    public void checkForAvatar(final Account account, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(account, packet, response -> {
            if (response.getType() == Iq.Type.RESULT) {
                Element pubsub = response.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                if (pubsub != null) {
                    Element items = pubsub.findChild("items");
                    if (items != null) {
                        Avatar avatar = Avatar.parseMetadata(items);
                        if (avatar != null) {
                            avatar.owner = account.getJid().asBareJid();
                            if (fileBackend.isAvatarCached(avatar)) {
                                if (account.setAvatar(avatar.getFilename())) {
                                    databaseBackend.updateAccount(account);
                                }
                                getAvatarService().clear(account);
                                callback.success(avatar);
                            } else {
                                fetchAvatarPep(account, avatar, callback);
                            }
                            return;
                        }
                    }
                }
            }
            callback.error(0);
        });
    }

    public void notifyAccountAvatarHasChanged(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null && connection.getFeatures().bookmarksConversion()) {
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account && conversation.getMode() == Conversational.MODE_MULTI) {
                    final MucOptions mucOptions = conversation.getMucOptions();
                    if (mucOptions.online()) {
                        final var packet = mPresenceGenerator.selfPresence(account, Presence.Status.ONLINE, mucOptions.nonanonymous());
                        packet.setTo(mucOptions.getSelf().getFullJid());
                        connection.sendPresencePacket(packet);
                    }
                }
            }
        }
    }

    public void deleteContactOnServer(Contact contact) {
        contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
        contact.resetOption(Contact.Options.DIRTY_PUSH);
        contact.setOption(Contact.Options.DIRTY_DELETE);
        Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final Iq iq = new Iq(Iq.Type.SET);
            Element item = iq.query(Namespace.ROSTER).addChild("item");
            item.setAttribute("jid", contact.getJid());
            item.setAttribute("subscription", "remove");
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
        }
    }

    public void updateConversation(final Conversation conversation) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.updateConversation(conversation));
    }

    private void reconnectAccount(final Account account, final boolean force, final boolean interactive) {
        reconnectAccount(account, force, interactive, false);
    }

    private void reconnectAccount(
            final Account account,
            final boolean force,
            final boolean interactive,
            final boolean saslRecovery) {
        synchronized (account) {
            final XmppConnection existingConnection = account.getXmppConnection();
            final XmppConnection connection;
            if (existingConnection != null) {
                connection = existingConnection;
            } else if (account.isConnectionEnabled()) {
                connection = createConnection(account);
                account.setXmppConnection(connection);
            } else {
                return;
            }
            if (account.isConnectionEnabled()) {
                if (!saslRecovery && connection.isSaslRecoveryPending()) {
                    return;
                }
                if (!force) {
                    disconnect(account, false);
                }
                Thread thread = new Thread(connection);
                if (saslRecovery) {
                    connection.startSaslRecoveryAttempt(thread);
                }
                connection.setInteractive(interactive);
                connection.prepareNewConnection();
                connection.interrupt();
                thread.start();
                scheduleWakeUpCall(Config.CONNECT_DISCO_TIMEOUT, account.getUuid().hashCode());
            } else {
                connection.cancelSaslRecovery();
                disconnect(account, force || account.getTrueStatus().isError());
                account.getRoster().clearPresences();
                connection.resetEverything();
                final AxolotlService axolotlService = account.getAxolotlService();
                if (axolotlService != null) {
                    axolotlService.resetBrokenness();
                }
            }
        }
    }

    public void reconnectAccountInBackground(final Account account) {
        new Thread(() -> reconnectAccount(account, false, true)).start();
    }

    private void reconnectAccountForSaslRecovery(final Account account) {
        new Thread(() -> reconnectAccount(account, false, true, true)).start();
    }

    /**
     * Reconnects now, discarding the backoff that the previous failures built up.
     *
     * {@link #reconnectAccountInBackground(Account)} on its own leaves the attempt counter alone,
     * so an account that has been failing for a while waits out an interval that caps at five
     * minutes even when the reason it was failing has just been fixed. Use this whenever
     * something has changed that makes the next attempt likely to succeed -- a fresh XMPP token,
     * or the user explicitly asking for a reconnect.
     */
    public void reconnectAccountNow(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.resetAttemptCount(true);
        }
        reconnectAccountInBackground(account);
    }

    /**
     * Retries every account sitting in an error state, discarding its backoff.
     *
     * {@link #resetAllAttemptCounts(boolean, boolean)} cannot serve this: it defers to
     * {@link Account#hasErrorStatus()}, which only counts an account as failing once it has made
     * three attempts, so an account still in its first or second backoff -- the window a user is
     * most likely to be looking at -- would keep waiting out an interval it was never told to
     * abandon. Accounts that are ONLINE, CONNECTING or merely OFFLINE are left alone: none of
     * those report an error status, so a connection in progress is not disturbed.
     */
    private void retryFailedAccountsNow() {
        for (final Account account : this.accounts) {
            if (account.isConnectionEnabled() && account.getStatus().isError()) {
                reconnectAccountNow(account);
            }
        }
    }

    /**
     * Re-fetches this account's XMPP token and reconnects immediately. This is the user-facing
     * recovery for an account the server will no longer admit on its stored credential: it
     * renews the credential rather than relaxing any check.
     */
    public void resyncAccountCredentials(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.resetAttemptCount(true);
        }
        refreshXmppTokenAndReconnect(account);
    }

    /**
     * Reactive XMPP-token refresh: called by {@link com.glaciersecurity.glaciermessenger.xmpp.XmppConnection}
     * on a SASL auth failure while a token is in use. Re-fetches the glacier-users record (same
     * org/username as login; this also confirms the mobile-API session is alive), stores whatever
     * fresh {@code xmpp_token} it returns, then reconnects so SASL re-authenticates with it.
     * There is no dedicated refresh endpoint -- login and refresh share {@code glacier-users}.
     */
    public void refreshXmppTokenAndReconnect(final Account account) {
        final XmppConnection recovering = account.getXmppConnection();
        if (recovering != null) {
            recovering.beginSaslRecovery();
        }
        // glacier-users is keyed by the org login username (Cognito username), which is not
        // necessarily the JID local part -- pull it from the linked CognitoAccount, same as the
        // original login lookup, rather than account.getUsername() (the JID local part).
        final CognitoAccount cognitoAccount = databaseBackend.getCognitoAccount(account);
        final String organization =
                cognitoAccount != null ? cognitoAccount.getOrganization() : account.getOrganization();
        final String username = cognitoAccount != null ? cognitoAccount.getUserName() : null;
        if (Strings.isNullOrEmpty(organization) || Strings.isNullOrEmpty(username)) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": cannot refresh XMPP token without organization/username");
            reconnectAccountForSaslRecovery(account);
            return;
        }
        MobileApiClient.service()
                .getGlacierUser(organization, username)
                .enqueue(
                        new retrofit2.Callback<MobileApiResponses.GlacierUserResponse>() {
                            @Override
                            public void onResponse(
                                    retrofit2.Call<MobileApiResponses.GlacierUserResponse> call,
                                    retrofit2.Response<MobileApiResponses.GlacierUserResponse>
                                            response) {
                                if (!response.isSuccessful()
                                        || response.body() == null
                                        || response.body().user == null) {
                                    Log.e(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": XMPP token refresh failed with code "
                                                    + response.code());
                                    reconnectAccountForSaslRecovery(account);
                                    return;
                                }
                                // JWT-only backend: no token is a failed refresh, never a cue
                                // to fall back to a password.
                                if (!XmppTokenCredentials.applyToken(
                                        account, response.body().user.xmppToken)) {
                                    Log.e(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": XMPP token refresh returned no xmpp_token");
                                    reconnectAccountForSaslRecovery(account);
                                    return;
                                }
                                reconnectAccountForSaslRecovery(account);
                            }

                            @Override
                            public void onFailure(
                                    retrofit2.Call<MobileApiResponses.GlacierUserResponse> call,
                                    Throwable t) {
                                Log.e(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": XMPP token refresh request failed",
                                        t);
                                reconnectAccountForSaslRecovery(account);
                            }
                        });
    }

    public void invite(final Conversation conversation, final Jid contact) {
        final MucOptions.User user = conversation.getMucOptions().findUserByRealJid(contact.asBareJid());
        if (user == null || user.getAffiliation() == MucOptions.Affiliation.OUTCAST) {
            changeAffiliationInConference(conversation, contact, MucOptions.Affiliation.NONE, null);
        }
        final var packet = mMessageGenerator.invite(conversation, contact);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void directInvite(Conversation conversation, Jid jid) {
        final var packet = mMessageGenerator.directInvite(conversation, jid);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void resetSendingToWaiting(Account account) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount() == account) {
                conversation.findUnsentTextMessages(message -> markMessage(message, Message.STATUS_WAITING));
            }
        }
    }

    public Message markMessage(final Account account, final Jid recipient, final String uuid, final int status) {
        return markMessage(account, recipient, uuid, status, null);
    }

    public Message markMessage(final Account account, final Jid recipient, final String uuid, final int status, String errorMessage) {
        if (uuid == null) {
            return null;
        }
        for (Conversation conversation : getConversations()) {
            if (conversation.getJid().asBareJid().equals(recipient) && conversation.getAccount() == account) {
                final Message message = conversation.findSentMessageWithUuidOrRemoteId(uuid);
                if (message != null) {
                    markMessage(message, status, errorMessage);
                }
                return message;
            }
        }
        return null;
    }

    public boolean markMessage(final Conversation conversation, final String uuid, final int status, final String serverMessageId) {
        return markMessage(conversation, uuid, status, serverMessageId, null);
    }

    public boolean markMessage(final Conversation conversation, final String uuid, final int status, final String serverMessageId, final LocalizedContent body) {
        if (uuid == null) {
            return false;
        } else {
            final Message message = conversation.findSentMessageWithUuid(uuid);
            if (message != null) {
                if (message.getServerMsgId() == null) {
                    message.setServerMsgId(serverMessageId);
                }
                if (message.getEncryption() == Message.ENCRYPTION_NONE
                        && message.isTypeText()
                        && isBodyModified(message, body)) {
                    message.setBody(body.content);
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language);
                    }
                    markMessage(message, status, null, true);
                } else {
                    markMessage(message, status);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    private static boolean isBodyModified(final Message message, final LocalizedContent body) {
        if (body == null || body.content == null) {
            return false;
        }
        return !body.content.equals(message.getBody());
    }

    public void markMessage(Message message, int status) {
        markMessage(message, status, null);
    }

    public void markMessage(final Message message, final int status, final String errorMessage) {
        markMessage(message, status, errorMessage, false);
    }

    public void markMessage(final Message message, final int status, final String errorMessage, final boolean includeBody) {
        final int oldStatus = message.getStatus();
        if (status == Message.STATUS_SEND_FAILED && (oldStatus == Message.STATUS_SEND_RECEIVED || oldStatus == Message.STATUS_SEND_DISPLAYED)) {
            return;
        }
        if (status == Message.STATUS_SEND_RECEIVED && oldStatus == Message.STATUS_SEND_DISPLAYED) {
            return;
        }
        message.setErrorMessage(errorMessage);
        message.setStatus(status);
        databaseBackend.updateMessage(message, includeBody);
        updateConversationUi();
        if (oldStatus != status && status == Message.STATUS_SEND_FAILED) {
            mNotificationService.pushFailedDelivery(message);
        }
    }

    private SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    public long getAutomaticMessageDeletionDate() {
        final long timeout = getLongPreference(AppSettings.AUTOMATIC_MESSAGE_DELETION, R.integer.automatic_message_deletion);
        return timeout == 0 ? timeout : (System.currentTimeMillis() - (timeout * 1000));
    }

    public long getLongPreference(String name, @IntegerRes int res) {
        long defaultValue = getResources().getInteger(res);
        try {
            return Long.parseLong(getPreferences().getString(name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBooleanPreference(String name, @BoolRes int res) {
        return getPreferences().getBoolean(name, getResources().getBoolean(res));
    }

    public boolean confirmMessages() {
        return getBooleanPreference("confirm_messages", R.bool.confirm_messages);
    }

    public boolean allowMessageCorrection() {
        return getBooleanPreference("allow_message_correction", R.bool.allow_message_correction);
    }

    public boolean sendChatStates() {
        return getBooleanPreference("chat_states", R.bool.chat_states);
    }

    public boolean showExtendedConnectionOptions() {
        return QuickConversationsService.isConversations() && getBooleanPreference(AppSettings.SHOW_CONNECTION_OPTIONS, R.bool.show_connection_options);
    }

    public boolean broadcastLastActivity() {
        return getBooleanPreference(AppSettings.BROADCAST_LAST_ACTIVITY, R.bool.last_activity);
    }

    public int unreadCount() {
        int count = 0;
        for (Conversation conversation : getConversations()) {
            count += conversation.unreadCount();
        }
        return count;
    }


    private <T> List<T> threadSafeList(Set<T> set) {
        synchronized (LISTENER_LOCK) {
            return set.isEmpty() ? Collections.emptyList() : new ArrayList<>(set);
        }
    }

    public void showErrorToastInUi(int resId) {
        for (OnShowErrorToast listener : threadSafeList(this.mOnShowErrorToasts)) {
            listener.onShowErrorToast(resId);
        }
    }

    public void updateConversationUi() {
        for (OnConversationUpdate listener : threadSafeList(this.mOnConversationUpdates)) {
            listener.onConversationUpdate();
        }
    }

    private void startGlobalDisappearingMessagesHandler() {
        disappearingMessagesRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                ArrayList<String> removedIds = new ArrayList<>();
                for (Conversation conversation : getConversations()) {
                    synchronized (conversation.getMessages()) {
                        for (int i = conversation.getMessages().size() - 1; i >= 0; i--) {
                            Message message = conversation.getMessages().get(i);
                            if (message.getTimer() != Message.TIMER_NONE
                                    && message.getTimeRemaining() <= 0
                                    && message.getUuid() != null) {
                                removedIds.add(message.getUuid());
                            }
                        }
                    }
                }
                if (!removedIds.isEmpty()) {
                    databaseBackend.removeDisappearingMessages(removedIds);
                    for (Conversation conversation : getConversations()) {
                        List<Message> fresh = databaseBackend.getMessages(conversation, Config.PAGE_SIZE);
                        conversation.clearMessages();
                        conversation.addAll(0, fresh);
                    }
                    updateConversationUi();
                }
                disappearingMessagesHandler.postDelayed(this, 1000);
            }
        };
        disappearingMessagesHandler.postDelayed(disappearingMessagesRunnable, 2000);
    }

    public void notifyJingleRtpConnectionUpdate(final Account account, final Jid with, final String sessionId, final RtpEndUserState state) {
        for (OnJingleRtpConnectionUpdate listener : threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onJingleRtpConnectionUpdate(account, with, sessionId, state);
        }
    }

    public void notifyJingleRtpConnectionUpdate(CallIntegration.AudioDevice selectedAudioDevice, Set<CallIntegration.AudioDevice> availableAudioDevices) {
        for (OnJingleRtpConnectionUpdate listener : threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
        }
    }

    public void updateAccountUi() {
        for (final OnAccountUpdate listener : threadSafeList(this.mOnAccountUpdates)) {
            listener.onAccountUpdate();
        }
    }

    public void updateUserSync() {
        for (final OnAmplifySync listener : threadSafeList(this.mOnAmplifySync)) {
            listener.onUserSyncComplete();
        }
    }

    public void updateOrgSync() {
        for (final OnAmplifySync listener : threadSafeList(this.mOnAmplifySync)) {
            listener.onOrgSyncComplete();
        }
    }

    public void updateOrganizationUi() {
        for (final OnOrganizationUpdate listener : threadSafeList(this.mOnOrganizationUpdates)) {
            listener.onOrganizationUpdate();
        }
    }

    public void noOrgUpdateUi() {
        for (final OnOrganizationUpdate listener : threadSafeList(this.mOnOrganizationUpdates)) {
            listener.onNoOrganizationUpdate();
        }
    }


    public void updateLatestVersionSyncUi() {
        for (final OnAmplifySync listener : threadSafeList(this.mOnAmplifySync)) {
            listener.onLatestVersionSyncComplete();
        }
    }

    public void onConversationClientSync(boolean success) {
        final Runnable notifyListeners = () -> {
            for (final OnPhoneUpdate listener : threadSafeList(this.mOnPhoneUpdates)) {
                listener.onConversationClientSync(success);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListeners.run();
        } else {
            phoneSyncTimeoutHandler.post(notifyListeners);
        }
    }


    public void updatePhoneUi() {
        for (final OnPhoneUpdate listener : threadSafeList(this.mOnPhoneUpdates)) {
            listener.onPhoneUpdate();
        }
    }

    public void updateVoicemailUi() {
        for (final OnPhoneUpdate listener : threadSafeList(this.mOnPhoneUpdates)) {
            listener.onVoicemailUpdate();
        }
    }

    private void refreshVoicemails() {
        Account account = getFirstAccount();
        if (account == null
                || !isVoicemailEnabledForAccount(account)) {
            return;
        }
        ArrayList<SmsProfile> smsProfiles = smsInfo.getExistingProfs();
        if (smsProfiles == null || smsProfiles.isEmpty()) {
            return;
        }
        for (SmsProfile smsProfile : smsProfiles) {
            smsInfo.listVoicemailRequest(account, smsProfile);
        }
    }

    public void updateRosterUi() {
        for (OnRosterUpdate listener : threadSafeList(this.mOnRosterUpdates)) {
            listener.onRosterUpdate();
        }
    }

    public void updateNativeContactUi() {
        for (OnNativeContactUpdate listener : threadSafeList(this.mOnNativeContactUpdates)) {
            listener.onNativeContactUpdate();
        }
    }

    private void updateConsoleUi(boolean up) {
        for (OnConsoleStatus listener : threadSafeList(this.mOnConsoleStatus)) {
            if (up) {
                listener.onConsoleUp();
            } else {
                listener.onConsoleDown();
            }
        }
    }

    public boolean displayCaptchaRequest(Account account, String id, Data data, Bitmap captcha) {
        if (mOnCaptchaRequested.size() > 0) {
            DisplayMetrics metrics = getApplicationContext().getResources().getDisplayMetrics();
            Bitmap scaled = Bitmap.createScaledBitmap(captcha, (int) (captcha.getWidth() * metrics.scaledDensity),
                    (int) (captcha.getHeight() * metrics.scaledDensity), false);
            for (OnCaptchaRequested listener : threadSafeList(this.mOnCaptchaRequested)) {
                listener.onCaptchaRequested(account, id, data, scaled);
            }
            return true;
        }
        return false;
    }

    public void updateBlocklistUi() {
        for (OnUpdateBlocklist listener : threadSafeList(this.mOnUpdateBlocklist)) {
            listener.OnUpdateBlocklist();
        }
    }

    public void updateMucRosterUi() {
        for (OnMucRosterUpdate listener : threadSafeList(this.mOnMucRosterUpdate)) {
            listener.onMucRosterUpdate();
        }
    }

    public void keyStatusUpdated(AxolotlService.FetchStatus report) {
        for (OnKeyStatusUpdated listener : threadSafeList(this.mOnKeyStatusUpdated)) {
            listener.onKeyStatusUpdated(report);
        }
    }


    public Account findAccountByJid(final Jid jid) {
        for (final Account account : this.accounts) {
            if (account.getJid().asBareJid().equals(jid.asBareJid())) {
                return account;
            }
        }
        return null;
    }

    public Account findAccountByUuid(final String uuid) {
        for (Account account : this.accounts) {
            if (account.getUuid().equals(uuid)) {
                return account;
            }
        }
        return null;
    }

    public Conversation findConversationByUuid(String uuid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getUuid().equals(uuid)) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation findUniqueConversationByJid(XmppUri xmppUri) {
        List<Conversation> findings = new ArrayList<>();
        for (Conversation c : getConversations()) {
            if (c.getAccount().isEnabled() && c.getJid().asBareJid().equals(xmppUri.getJid()) && ((c.getMode() == Conversational.MODE_MULTI) == xmppUri.isAction(XmppUri.ACTION_JOIN))) {
                findings.add(c);
            }
        }
        return findings.size() == 1 ? findings.get(0) : null;
    }

    public boolean markRead(final Conversation conversation, boolean dismiss) {
        return markRead(conversation, null, dismiss).size() > 0;
    }

    public void markRead(final Conversation conversation) {
        markRead(conversation, null, true);
    }

    public List<Message> markRead(final Conversation conversation, String upToUuid, boolean dismiss) {
        if (dismiss) {
            mNotificationService.clear(conversation);
        }
        final List<Message> readMessages = conversation.markRead(upToUuid);
        if (readMessages.size() > 0) {
            Runnable runnable = () -> {
                for (Message message : readMessages) {
                    databaseBackend.updateMessage(message, false);
                }
            };
            mDatabaseWriterExecutor.execute(runnable);
            updateConversationUi();
            updateUnreadCountBadge();
            return readMessages;
        } else {
            return readMessages;
        }
    }

    public synchronized void updateUnreadCountBadge() {
        int count = unreadCount();
        if (unreadCount != count) {
            if (count > 0) {
                ShortcutBadger.applyCount(getApplicationContext(), count);
            } else {
                ShortcutBadger.removeCount(getApplicationContext());
            }
            unreadCount = count;
        }
    }

    public void sendReadMarker(final Conversation conversation, final String upToUuid) {
        final boolean isPrivateAndNonAnonymousMuc =
                conversation.getMode() == Conversation.MODE_MULTI
                        && conversation.isPrivateAndNonAnonymous();
        final List<Message> readMessages = this.markRead(conversation, upToUuid, true);
        if (readMessages.isEmpty()) {
            return;
        }
        final var account = conversation.getAccount();
        final var connection = account.getXmppConnection();
        updateConversationUi();
        final var last =
                Iterables.getLast(
                        Collections2.filter(
                                readMessages,
                                m ->
                                        !m.isPrivateMessage()
                                                && m.getStatus() == Message.STATUS_RECEIVED),
                        null);
        if (last == null) {
            return;
        }

        final boolean sendDisplayedMarker =
                confirmMessages()
                        && (last.trusted() || isPrivateAndNonAnonymousMuc)
                        && last.getRemoteMsgId() != null
                        && (last.markable || isPrivateAndNonAnonymousMuc);
        final boolean serverAssist =
                connection != null && connection.getFeatures().mdsServerAssist();

        final String stanzaId = last.getServerMsgId();

        if (sendDisplayedMarker && serverAssist) {
            final var mdsDisplayed = mIqGenerator.mdsDisplayed(stanzaId, conversation);
            final var packet = mMessageGenerator.confirm(last);
            packet.addChild(mdsDisplayed);
            if (!last.isPrivateMessage()) {
                packet.setTo(packet.getTo().asBareJid());
            }
            this.sendMessagePacket(account, packet);
        } else {
            publishMds(last);
            // read markers will be sent after MDS to flush the CSI stanza queue
            if (sendDisplayedMarker) {
                final var packet = mMessageGenerator.confirm(last);
                this.sendMessagePacket(account, packet);
            }
        }
    }

    private void publishMds(@Nullable final Message message) {
        final String stanzaId = message == null ? null : message.getServerMsgId();
        if (Strings.isNullOrEmpty(stanzaId)) {
            return;
        }
        final Conversation conversation;
        final var conversational = message.getConversation();
        if (conversational instanceof Conversation c) {
            conversation = c;
        } else {
            return;
        }
        final var account = conversation.getAccount();
        final var connection = account.getXmppConnection();
        if (connection == null || !connection.getFeatures().mds()) {
            return;
        }
        final Jid itemId;
        if (message.isPrivateMessage()) {
            itemId = message.getCounterpart();
        } else {
            itemId = conversation.getJid().asBareJid();
        }
        publishMds(account, itemId, stanzaId, conversation);
    }

    private void publishMds(
            final Account account, final Jid itemId, final String stanzaId, final Conversation conversation) {
        final var item = mIqGenerator.mdsDisplayed(stanzaId, conversation);
        pushNodeAndEnforcePublishOptions(
                account,
                Namespace.MDS_DISPLAYED,
                item,
                itemId.toEscapedString(),
                PublishOptions.persistentWhitelistAccessMaxItems());
    }

    public boolean sendReactions(final Message message, final Collection<String> reactions) {
        if (message.getConversation() instanceof Conversation conversation) {
            final String reactToId;
            final Collection<Reaction> combinedReactions;
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                final var self = conversation.getMucOptions().getSelf();
                final String occupantId = Strings.emptyToNull(self.getOccupantId());
                reactToId = message.getServerMsgId();
                combinedReactions =
                        Reaction.withOccupantId(
                                message.getReactions(),
                                reactions,
                                false,
                                self.getFullJid(),
                                conversation.getAccount().getJid(),
                                occupantId);
            } else {
                if (message.isCarbon() || message.getStatus() == Message.STATUS_RECEIVED) {
                    reactToId = message.getRemoteMsgId();
                } else {
                    reactToId = message.getUuid();
                }
                combinedReactions =
                        Reaction.withFrom(
                                message.getReactions(),
                                reactions,
                                false,
                                conversation.getAccount().getJid());
            }
            if (Strings.isNullOrEmpty(reactToId)) {
                return false;
            }
            final var reactionMessage =
                    mMessageGenerator.reaction(conversation, reactToId, reactions);
            sendMessagePacket(conversation.getAccount(), reactionMessage);
            message.setReactions(combinedReactions);
            updateMessage(message, false);
            playReactionFeedback(this);
            return true;
        } else {
            return false;
        }
    }


    public void playReactionFeedback(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            MediaPlayer mediaPlayer = MediaPlayer.create(context, R.raw.message_pop);
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(0.3f, 0.3f); // Lower volume
                mediaPlayer.setOnCompletionListener(MediaPlayer::release);
                mediaPlayer.start();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                vibratorManager.getDefaultVibrator()
                        .vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }
    }

    public MemorizingTrustManager getMemorizingTrustManager() {
        return this.mMemorizingTrustManager;
    }

    public void setMemorizingTrustManager(MemorizingTrustManager trustManager) {
        this.mMemorizingTrustManager = trustManager;
    }

    public void updateMemorizingTrustManager() {
        setMemorizingTrustManager(new MemorizingTrustManager());
    }

    public LruCache<String, Bitmap> getBitmapCache() {
        return this.mBitmapCache;
    }

    public Collection<String> getKnownHosts() {
        final Set<String> hosts = new HashSet<>();
        for (final Account account : getAccounts()) {
            hosts.add(account.getServer());
            for (final Contact contact : account.getRoster().getContacts()) {
                if (contact.showInRoster()) {
                    final String server = contact.getServer();
                    if (server != null) {
                        hosts.add(server);
                    }
                }
            }
        }
        if (Config.QUICKSY_DOMAIN != null) {
            hosts.remove(Config.QUICKSY_DOMAIN.toEscapedString()); //we only want to show this when we type a e164 number
        }
        hosts.add(Config.MAGIC_CREATE_DOMAIN);
        return hosts;
    }

    public Collection<String> getKnownConferenceHosts() {
        final Set<String> mucServers = new HashSet<>();
        for (final Account account : accounts) {
            if (account.getXmppConnection() != null) {
                mucServers.addAll(account.getXmppConnection().getMucServers());
                for (final Bookmark bookmark : account.getBookmarks()) {
                    final Jid jid = bookmark.getJid();
                    final String s = jid == null ? null : jid.getDomain().toEscapedString();
                    if (s != null) {
                        mucServers.add(s);
                    }
                }
            }
        }
        return mucServers;
    }

    public void sendMessagePacket(final Account account, final im.conversations.android.xmpp.model.stanza.Message packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendMessagePacket(packet);
        }
    }

    public void sendPresencePacket(final Account account, final im.conversations.android.xmpp.model.stanza.Presence packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendPresencePacket(packet);
        }
    }

    public void sendCreateAccountWithCaptchaPacket(Account account, String id, Data data) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        connection.sendCreateAccountWithCaptchaPacket(id, data);
    }

    public void sendIqPacket(final Account account, final Iq packet, final Consumer<Iq> callback) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendIqPacket(packet, callback);
        } else if (callback != null) {
            callback.accept(Iq.TIMEOUT);
        }
    }

    public void sendPresence(final Account account) {
        sendPresence(account, checkListeners() && broadcastLastActivity());
    }

    private void sendPresence(final Account account, final boolean includeIdleTimestamp) {
        final Presence.Status status;
        if (manuallyChangePresence()) {
            status = account.getPresenceStatus();
        } else {
            status = getTargetPresence();
            account.setPresenceStatus(status);
        }
        if (status == Presence.Status.OFFLINE || status == Presence.Status.XA) {
            sendOfflinePresence(account);
            return;
        }
        final var packet = mPresenceGenerator.selfPresence(account, status);
        if (mLastActivity > 0 && includeIdleTimestamp) {
            long since = Math.min(mLastActivity, System.currentTimeMillis()); //don't send future dates
            packet.addChild("idle", Namespace.IDLE).setAttribute("since", AbstractGenerator.getTimestamp(since));
        }
        sendPresencePacket(account, packet);
    }

    private void deactivateGracePeriod() {
        for (Account account : getAccounts()) {
            account.deactivateGracePeriod();
        }
    }

    public void refreshAllPresences() {
        boolean includeIdleTimestamp = checkListeners() && broadcastLastActivity();
        for (Account account : getAccounts()) {
            if (account.isConnectionEnabled()) {
                sendPresence(account, includeIdleTimestamp);
            }
        }
    }

    private void refreshAllFcmTokens() {
        for (Account account : getAccounts()) {
            if (account.isOnlineAndConnected() && mPushManagementService.available(account)) {
                mPushManagementService.registerPushTokenOnServer(account);
            }
        }
    }


    private void sendOfflinePresence(final Account account) {
        sendPresencePacket(account, mPresenceGenerator.sendOfflinePresence(account));
    }

    public MessageGenerator getMessageGenerator() {
        return this.mMessageGenerator;
    }

    public PresenceGenerator getPresenceGenerator() {
        return this.mPresenceGenerator;
    }

    public IqGenerator getIqGenerator() {
        return this.mIqGenerator;
    }

    public JingleConnectionManager getJingleConnectionManager() {
        return this.mJingleConnectionManager;
    }

    private boolean hasJingleRtpConnection(final Account account) {
        return this.mJingleConnectionManager.hasJingleRtpConnection(account);
    }

    public MessageArchiveService getMessageArchiveService() {
        return this.mMessageArchiveService;
    }

    public QuickConversationsService getQuickConversationsService() {
        return this.mQuickConversationsService;
    }

    public ConversationsManager getConversationsManager() {
        return this.conversationsManager;
    }

    public List<Contact> findContacts(Jid jid, String accountJid) {
        ArrayList<Contact> contacts = new ArrayList<>();
        for (Account account : getAccounts()) {
            if ((account.isEnabled() || accountJid != null)
                    && (accountJid == null || accountJid.equals(account.getJid().asBareJid().toString()))) {
                Contact contact = account.getRoster().getContactFromContactList(jid);
                if (contact != null) {
                    contacts.add(contact);
                }
            }
        }
        return contacts;
    }

    public Conversation findFirstMuc(Jid jid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount().isEnabled() && conversation.getJid().asBareJid().equals(jid.asBareJid()) && conversation.getMode() == Conversation.MODE_MULTI) {
                return conversation;
            }
        }
        return null;
    }

    public NotificationService getNotificationService() {
        return this.mNotificationService;
    }

    public HttpConnectionManager getHttpConnectionManager() {
        return this.mHttpConnectionManager;
    }

    public void resendFailedMessages(final Message message) {
        final Collection<Message> messages = new ArrayList<>();
        Message current = message;
        while (current.getStatus() == Message.STATUS_SEND_FAILED) {
            messages.add(current);
            if (current.mergeable(current.next())) {
                current = current.next();
            } else {
                break;
            }
        }
        for (final Message msg : messages) {
            msg.setTime(System.currentTimeMillis());
            markMessage(msg, Message.STATUS_WAITING);
            this.resendMessage(msg, false);
        }
        if (message.getConversation() instanceof Conversation) {
            ((Conversation) message.getConversation()).sort();
        }
        updateConversationUi();
    }

    public void clearConversationHistory(final Conversation conversation) {
        final long clearDate;
        final String reference;
        if (conversation.countMessages() > 0) {
            Message latestMessage = conversation.getLatestMessage();
            clearDate = latestMessage.getTimeSent() + 1000;
            reference = latestMessage.getServerMsgId();
        } else {
            clearDate = System.currentTimeMillis();
            reference = null;
        }
        conversation.clearMessages();
        conversation.setHasMessagesLeftOnServer(false); //avoid messages getting loaded through mam
        conversation.setLastClearHistory(clearDate, reference);
        Runnable runnable = () -> {
            databaseBackend.deleteMessagesInConversation(conversation);
            databaseBackend.updateConversation(conversation);
        };
        mDatabaseWriterExecutor.execute(runnable);
    }

    public boolean sendBlockRequest(final Blockable blockable, final boolean reportSpam, final String serverMsgId) {
        if (blockable != null && blockable.getBlockedJid() != null) {
            final var account = blockable.getAccount();
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(account, getIqGenerator().generateSetBlockRequest(jid, reportSpam, serverMsgId), (response) -> {
                if (response.getType() == Iq.Type.RESULT) {
                    account.addToBlocklist(jid);
                    updateBlocklistUi();
                }
            });
            if (blockable.getBlockedJid().isFullJid()) {
                return false;
            } else if (removeBlockedConversations(blockable.getAccount(), jid)) {
                updateConversationUi();
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean removeBlockedConversations(final Account account, final Jid blockedJid) {
        boolean removed = false;
        synchronized (this.conversations) {
            boolean domainJid = blockedJid.getLocal() == null;
            for (Conversation conversation : this.conversations) {
                boolean jidMatches = (domainJid && blockedJid.getDomain().equals(conversation.getJid().getDomain()))
                        || blockedJid.equals(conversation.getJid().asBareJid());
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversation.MODE_SINGLE
                        && jidMatches) {
                    this.conversations.remove(conversation);
                    markRead(conversation);
                    conversation.setStatus(Conversation.STATUS_ARCHIVED);
                    updateConversation(conversation);
                    removed = true;
                }
            }
        }
        return removed;
    }

    public void sendUnblockRequest(final Blockable blockable) {
        if (blockable != null && blockable.getJid() != null) {
            final var account = blockable.getAccount();
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(account, getIqGenerator().generateSetUnblockRequest(jid), response -> {
                if (response.getType() == Iq.Type.RESULT) {
                    account.removeFromBlocklist(jid);
                    updateBlocklistUi();
                }
            });
        }
    }

    public void publishDisplayName(final Account account) {
        String displayName = account.getDisplayName();
        final Iq request;
        if (TextUtils.isEmpty(displayName)) {
            request = mIqGenerator.deleteNode(Namespace.NICK);
        } else {
            request = mIqGenerator.publishNick(displayName);
        }
        mAvatarService.clear(account);
        sendIqPacket(account, request, (packet) -> {
            if (packet.getType() == Iq.Type.ERROR) {
            }
        });
        if (displayName != null && !displayName.isEmpty()) {
            Iq publishv = mIqGenerator.publishVcardWithNick(displayName);
            sendIqPacket(account, publishv, (response) -> {
                if (response.getType() == Iq.Type.ERROR)
                {
                }
            });
            final Presence.Status status = account.getPresenceStatus();
            if (status == Presence.Status.OFFLINE || status == Presence.Status.XA) {
                // The account advertises itself as unavailable; a broadcast presence carrying the
                // vCard hint would put the user back online for every contact.
            } else {
                sendPresencePacket(account, mPresenceGenerator.sendPresenceWithvCard(account, displayName));
            }
        }
    }

    public ServiceDiscoveryResult getCachedServiceDiscoveryResult(Pair<String, String> key) {
        ServiceDiscoveryResult result = discoCache.get(key);
        if (result != null) {
            return result;
        } else {
            result = databaseBackend.findDiscoveryResult(key.first, key.second);
            if (result != null) {
                discoCache.put(key, result);
            }
            return result;
        }
    }

    public void fetchCaps(final Account account, final Jid jid, final Presence presence) {
        final Pair<String, String> key = new Pair<>(presence.getHash(), presence.getVer());
        final ServiceDiscoveryResult disco = getCachedServiceDiscoveryResult(key);
        if (disco != null) {
            presence.setServiceDiscoveryResult(disco);
            final Contact contact = account.getRoster().getContact(jid);
            if (contact.refreshRtpCapability()) {
                syncRoster(account);
            }
        } else {
            final Iq request = new Iq(Iq.Type.GET);
            request.setTo(jid);
            final String node = presence.getNode();
            final String ver = presence.getVer();
            final Element query = request.query(Namespace.DISCO_INFO);
            if (node != null && ver != null) {
                query.setAttribute("node", node + "#" + ver);
            }
            sendIqPacket(account, request, (response) -> {
                if (response.getType() == Iq.Type.RESULT) {
                    final ServiceDiscoveryResult discoveryResult = new ServiceDiscoveryResult(response);
                    if (presence.getVer().equals(discoveryResult.getVer())) {
                        databaseBackend.insertDiscoveryResult(discoveryResult);
                        injectServiceDiscoveryResult(account.getRoster(), presence.getHash(), presence.getVer(), discoveryResult);
                    }
                }
            });
        }
    }

    private void injectServiceDiscoveryResult(Roster roster, String hash, String ver, ServiceDiscoveryResult disco) {
        boolean rosterNeedsSync = false;
        for (final Contact contact : roster.getContacts()) {
            boolean serviceDiscoverySet = false;
            for (final Presence presence : contact.getPresences().getPresences()) {
                if (hash.equals(presence.getHash()) && ver.equals(presence.getVer())) {
                    presence.setServiceDiscoveryResult(disco);
                    serviceDiscoverySet = true;
                }
            }
            if (serviceDiscoverySet) {
                rosterNeedsSync |= contact.refreshRtpCapability();
            }
        }
        if (rosterNeedsSync) {
            syncRoster(roster.getAccount());
        }
    }

    public void fetchMamPreferences(final Account account, final OnMamPreferencesFetched callback) {
        final MessageArchiveService.Version version = MessageArchiveService.Version.get(account);
        final Iq request = new Iq(Iq.Type.GET);
        request.addChild("prefs", version.namespace);
        sendIqPacket(account, request, (packet) -> {
            final Element prefs = packet.findChild("prefs", version.namespace);
            if (packet.getType() == Iq.Type.RESULT && prefs != null) {
                callback.onPreferencesFetched(prefs);
            } else {
                callback.onPreferencesFetchFailed();
            }
        });
    }

    public PushManagementService getPushManagementService() {
        return mPushManagementService;
    }

    public void changeStatus(Account account, PresenceTemplate template, String signature) {
        if (!template.getStatusMessage().isEmpty()) {
            databaseBackend.insertPresenceTemplate(template);
        }
        account.setPgpSignature(signature);
        account.setPresenceStatus(template.getStatus());
        account.setPresenceStatusMessage(template.getStatusMessage());
        databaseBackend.updateAccount(account);
        sendPresence(account);
    }

    public List<PresenceTemplate> getPresenceTemplates(Account account) {
        List<PresenceTemplate> templates = databaseBackend.getPresenceTemplates();
        for (PresenceTemplate template : account.getSelfContact().getPresences().asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template);
            }
        }
        return templates;
    }

    public void saveConversationAsBookmark(final Conversation conversation, final String name) {
        final Account account = conversation.getAccount();
        final Bookmark bookmark = new Bookmark(account, conversation.getJid().asBareJid());
        final String nick = conversation.getJid().getResource();
        if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
            bookmark.setNick(nick);
        }
        if (!TextUtils.isEmpty(name)) {
            bookmark.setBookmarkName(name);
        }
        bookmark.setAutojoin(true);
        createBookmark(account, bookmark);
        bookmark.setConversation(conversation);
    }

    public boolean verifyFingerprints(Contact contact, List<XmppUri.Fingerprint> fingerprints) {
        boolean performedVerification = false;
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                FingerprintStatus fingerprintStatus = axolotlService.getFingerprintTrust(fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        performedVerification = true;
                        axolotlService.setFingerprintTrust(fingerprint, fingerprintStatus.toVerified());
                    }
                } else {
                    axolotlService.preVerifyFingerprint(contact, fingerprint);
                }
            }
        }
        return performedVerification;
    }

    public boolean verifyFingerprints(Account account, List<XmppUri.Fingerprint> fingerprints) {
        final AxolotlService axolotlService = account.getAxolotlService();
        boolean verifiedSomething = false;
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                FingerprintStatus fingerprintStatus = axolotlService.getFingerprintTrust(fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        axolotlService.setFingerprintTrust(fingerprint, fingerprintStatus.toVerified());
                        verifiedSomething = true;
                    }
                } else {
                    axolotlService.preVerifyFingerprint(account, fingerprint);
                    verifiedSomething = true;
                }
            }
        }
        return verifiedSomething;
    }

    public boolean blindTrustBeforeVerification() {
        return getBooleanPreference(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, R.bool.btbv);
    }

    public ShortcutService getShortcutService() {
        return mShortcutService;
    }

    public void pushMamPreferences(Account account, Element prefs) {
        final Iq set = new Iq(Iq.Type.SET);
        set.addChild(prefs);
        sendIqPacket(account, set, null);
    }

    public void evictPreview(String uuid) {
        if (mBitmapCache.remove(uuid) != null) {
        }
    }
    private SystemSecurityInfo secInfo = new SystemSecurityInfo(this);
    private SMSdbInfo smsInfo = new SMSdbInfo(this);
    private OnSMSCompleteCallbackListener onSMSCompleteCallbackListener;
    private ConnectivityManager connectivityManager;

    // Which transports the VPN tunnel was last seen running over, as a bitmask, so a handoff
    // underneath an established tunnel can be told apart from the tunnel itself changing state.
    // -1 means "not observed yet".
    private volatile int lastKnownVpnUnderlyingTransports = -1;

    private static final int[] UNDERLYING_TRANSPORTS = {
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
    };
    private ConnectivityManager.NetworkCallback vpnStatusCallback;
    private final Set<Network> ownVpnNetworks = ConcurrentHashMap.newKeySet();
    private Boolean lastKnownVpnStatus;

    private LatestVersionEntity latestVersion;


    public SystemSecurityInfo getSecInfo() {
        return secInfo;
    }

    public void setSecInfo(SystemSecurityInfo secInfo) {
        this.secInfo = secInfo;
        if (secInfo == null) {
            return;
        }
        final Boolean previousStatus = lastKnownVpnStatus;
        lastKnownVpnStatus = null;
        final boolean currentStatus = previousStatus != null ? previousStatus : isGlacierVpnActive();
        handleVpnStatusChanged(currentStatus);
    }

    public SMSdbInfo getSmsInfo() {
        return smsInfo;
    }

    public void setSmsInfo(SMSdbInfo smsInfo) {
        this.smsInfo = smsInfo;
    }

    public void setOnSMSCompleteCallbackListener(OnSMSCompleteCallbackListener listener) {
        this.onSMSCompleteCallbackListener = listener;
    }

    public OnSMSCompleteCallbackListener getOnSMSCompleteCallbackListener() {
        return this.onSMSCompleteCallbackListener;
    }

    public boolean isUsingVPN() {
        if (lastKnownVpnStatus != null) {
            return lastKnownVpnStatus;
        }
        final boolean active = isGlacierVpnActive();
        handleVpnStatusChanged(active);
        return active;
    }

    private void startVpnStatusMonitoring() {
        if (connectivityManager == null) {
            connectivityManager = getSystemService(ConnectivityManager.class);
        }
        if (connectivityManager == null) {
            Log.e(Config.LOGTAG, "ConnectivityManager unavailable; cannot monitor VPN state");
            reconcileVpnStateFromBackend();
            return;
        }
        if (vpnStatusCallback != null) {
            return;
        }
        final NetworkRequest request = WireGuardStateMonitor.vpnNetworkRequest();
        vpnStatusCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                reconcileVpnStateFromBackend();
                // The VPN tunnel just came up (e.g. an always-on VPN restored after a
                // reboot). The routing the XMPP socket needs has changed, and any
                // connection attempt that started while traffic was still blocked is
                // now stuck in a long reconnect backoff — which surfaces as a never
                // ending "connecting" bar on the chat screen until the backoff lapses.
                // Kick the accounts so they reconnect promptly over the new tunnel.
                onVpnNetworkAvailable();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities capabilities) {
                if (WireGuardStateMonitor.isOwnVpn(capabilities)) {
                    ownVpnNetworks.add(network);
                }
                onVpnUnderlyingTransportsChanged(underlyingTransportMask(capabilities));
            }

            @Override
            public void onLost(@NonNull Network network) {
                lastKnownVpnUnderlyingTransports = -1;
                if (ownVpnNetworks.remove(network)) {
                    handleVpnLost();
                } else {
                    // Another VPN app's network went away; Core may still be up.
                    reconcileVpnStateFromBackend();
                }
            }
        };
        try {
            connectivityManager.registerNetworkCallback(request, vpnStatusCallback);
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "Unable to register VPN status callback", e);
            vpnStatusCallback = null;
            return;
        }
        reconcileVpnStateFromBackend();
    }

    private void stopVpnStatusMonitoring() {
        if (connectivityManager != null && vpnStatusCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(vpnStatusCallback);
            } catch (RuntimeException e) {
                Log.e(Config.LOGTAG, "Unable to unregister VPN status callback", e);
            }
            vpnStatusCallback = null;
        }
        lastKnownVpnUnderlyingTransports = -1;
    }

    // Drive the source of truth from the actual backend state. This is the only
    // VPN-monitoring path that should derive state from the Go backend; everyone
    // else reads PersistentConnectionProperties. An empty backend reading here is
    // a query (startup/onAvailable), not an observed disconnect, so it must not
    // clear a known-connected state (which would break the boot-restore that runs
    // off the persisted running row).
    private void reconcileVpnStateFromBackend() {
        ensureWireGuardBackend();
        if (PersistentConnectionProperties.getInstance().getBackend() == null) {
            // Can't determine ground truth yet; leave the source of truth untouched.
            return;
        }
        final Set<String> runningProfiles = getRunningCoreProfileNames();
        handleVpnStatusChanged(!runningProfiles.isEmpty(), runningProfiles, false);
    }

    // The VPN network was lost — an authoritative disconnect that clears state.
    private void handleVpnLost() {
        handleVpnStatusChanged(false, Collections.emptySet(), true);
    }

    private static int underlyingTransportMask(final NetworkCapabilities capabilities) {
        int mask = 0;
        for (final int transport : UNDERLYING_TRANSPORTS) {
            if (capabilities.hasTransport(transport)) {
                mask |= 1 << transport;
            }
        }
        return mask;
    }

    // A VPN network keeps the same Network object when the connection underneath it moves from
    // wifi to cellular, so onAvailable/onLost never fire for a handoff. Neither does anything
    // useful reach the CONNECTIVITY_ACTION path: that reports the *active* network, which is
    // still the tunnel, so hasInternetConnection() reads true on both sides of the move. The
    // XMPP socket rides the tun interface, whose addresses do not change either, so the account
    // stays ONLINE while its packets go nowhere -- until WireGuard has something to send and
    // re-handshakes from the new source address, the server is still answering to the old
    // endpoint. Left alone that is only noticed when the regular 300s ping falls due, costing
    // up to five minutes of dropped messages and missed incoming calls. The transport set on
    // the tunnel's own capabilities is the one thing that does change, so treat a change in it
    // as a network change and re-test the connection immediately.
    private void onVpnUnderlyingTransportsChanged(final int transports) {
        if (transports == 0) {
            // A tunnel with no transports underneath it is a reading taken mid-transition, not
            // a network to compare against; keep the last real one so the move that follows is
            // still seen as a change from wifi (or cellular) rather than from nothing.
            return;
        }
        final int previous = lastKnownVpnUnderlyingTransports;
        lastKnownVpnUnderlyingTransports = transports;
        if (previous == -1 || previous == transports) {
            return;
        }
        try {
            internalPingExecutor.execute(() -> {
                if (Config.POST_CONNECTIVITY_CHANGE_PING) {
                    schedulePostConnectivityChange();
                }
                if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
                    resetAllAttemptCounts(true, false);
                }
                // Accounts that dropped off get connected now instead of sitting out a
                // backoff that was computed against the old network...
                reconnectAllEligibleAccountsImmediately();
                // ...and the ones still marked ONLINE -- the stale sockets this is really
                // about -- get pinged, arming the existing ping timeout to reconnect them.
                manageAccountConnectionStates(ConnectivityManager.CONNECTIVITY_ACTION, null);
            });
        } catch (final RejectedExecutionException e) {
            // The service is being torn down and this callback was already running when the
            // executor stopped accepting work, so there is no connection left to re-test.
        }
    }

    // Force a prompt XMPP reconnect when the VPN tunnel becomes available so the
    // chat connection doesn't sit in its backoff (showing an endless "connecting"
    // bar) after the route it needs has only just come up — typically an always-on
    // VPN restored on boot. Accounts that are already online are left untouched.
    private void onVpnNetworkAvailable() {
        reconnectAllEligibleAccountsImmediately();
    }

    // Bypass each account's exponential reconnect backoff (up to 300s) and attempt
    // a connection right now. Used whenever we have independent evidence that the
    // network just became usable again (VPN tunnel up, connectivity broadcast) so
    // the account doesn't sit out a stale backoff timer that was computed before
    // the network was actually working. Accounts that are already online are left
    // untouched.
    private void reconnectAllEligibleAccountsImmediately() {
        if (accounts == null || accounts.isEmpty() || !hasInternetConnection()) {
            return;
        }
        Resolver.clearCache();
        for (final Account account : accounts) {
            if (!account.isConnectionEnabled() || account.getStatus() == Account.State.ONLINE) {
                continue;
            }
            final XmppConnection connection = account.getXmppConnection();
            if (connection != null) {
                connection.resetAttemptCount(true);
            }
            reconnectAccountInBackground(account);
        }
    }

    private void handleVpnStatusChanged(boolean isVpnActive) {
        final Set<String> runningProfiles = isVpnActive
                ? getRunningCoreProfileNames() : Collections.emptySet();
        // Legacy callers (setSecInfo/isUsingVPN) refresh from a query, so don't let
        // a stale empty reading tear the state down.
        handleVpnStatusChanged(isVpnActive, runningProfiles, false);
    }

    private void handleVpnStatusChanged(boolean isVpnActive, Set<String> runningProfiles,
                                        boolean allowClearOnEmpty) {
        final boolean vpnStatusChanged = lastKnownVpnStatus == null || lastKnownVpnStatus != isVpnActive;
        lastKnownVpnStatus = isVpnActive;

        final boolean runningProfilesChanged =
                updateCoreProfilesRunningState(runningProfiles, allowClearOnEmpty);

        notifyCoreStatusListeners(isVpnActive);

        final SystemSecurityInfo secInfo = this.secInfo;
        if (secInfo != null) {
            secInfo.setUsingVpn(isVpnActive);
            if (vpnStatusChanged || runningProfilesChanged) {
                // Off the calling thread: this runs from onCreate, so a slow canAuthenticate()
                // binder call used to hold the main thread past the 5s input-dispatch deadline
                // and ANR whichever activity was waiting for focus.
                refreshSecurityInfo(() -> {
                    if (getFirstAccount() != null) {
                        secInfo.trySecurityInfoUpload();
                    }
                });
            }
        }
    }

    /**
     * Refresh the cached security info on a background thread, then run {@code onRefreshed}
     * on that same thread.
     *
     * <p>{@link SystemSecurityInfo#checkCurrentSecurityInfo()} blocks -- it talks to the
     * keyguard and biometric system services over binder, walks the installed packages and
     * forks a process per system property. Every caller goes through this one serial executor,
     * so the cached info is only ever written from a single thread.
     */
    public void refreshSecurityInfo(@Nullable final Runnable onRefreshed) {
        final SystemSecurityInfo secInfo = this.secInfo;
        if (secInfo == null) {
            if (onRefreshed != null) {
                onRefreshed.run();
            }
            return;
        }
        mSecurityInfoExecutor.execute(() -> {
            try {
                secInfo.checkCurrentSecurityInfo();
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Unable to refresh security info", e);
            }
            if (onRefreshed != null) {
                onRefreshed.run();
            }
        });
    }

    private boolean updateCoreProfilesRunningState(Set<String> runningProfiles,
                                                   boolean allowClearOnEmpty) {
        // Fold the observed backend state into the single source of truth, which
        // also persists it. Returns true only when the state actually changed.
        return PersistentConnectionProperties.getInstance().reconcileRunningProfiles(
                runningProfiles == null ? Collections.emptySet() : runningProfiles,
                allowClearOnEmpty);
    }

    private void notifyCoreStatusListeners(boolean isVpnActive) {
        for (OnCoreStatusUpdate listener : threadSafeList(this.mOnCoreStatusUpdates)) {
            listener.onCoreStatusUpdated(isVpnActive);
        }
    }

    private Set<String> getRunningCoreProfileNames() {
        ensureWireGuardBackend();
        final GoBackend backend = PersistentConnectionProperties.getInstance().getBackend();
        if (backend == null) {
            return Collections.emptySet();
        }
        final Set<String> runningNames = new HashSet<>();
        try {
            final Set<String> backendNames = backend.getRunningTunnelNames();
            if (backendNames != null) {
                runningNames.addAll(backendNames);
            }
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to query running tunnel names", e);
        }
        if (!runningNames.isEmpty()) {
            return runningNames;
        }
        for (WgTunnel tunnel : PersistentConnectionProperties.getInstance().getTunnels()) {
            try {
                if (backend.getState(tunnel) == Tunnel.State.UP) {
                    runningNames.add(tunnel.getName());
                }
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Unable to query tunnel state for " + tunnel.getName(), e);
            }
        }
        return runningNames;
    }

    private boolean isGlacierVpnActive() {
        ensureWireGuardBackend();
        try {
            return PersistentConnectionProperties.getInstance().isRunningTunnel();
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to determine VPN state", e);
            return false;
        }
    }

    private void ensureWireGuardBackend() {
        if (PersistentConnectionProperties.getInstance().getBackend() == null) {
            try {
                PersistentConnectionProperties.getInstance().setBackend(new GoBackend(this));
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Unable to initialize WireGuard backend", e);
            }
        }
    }

    public LatestVersionEntity getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(LatestVersionEntity latestVersion) {
        this.latestVersion = latestVersion;

        new Thread(()-> {
            if (latestVersion != null) {
                createLatestVersions(latestVersion);
                setLatestVersionPrefs(latestVersion);

            }

        }).start();

    }

    private void setLatestVersionPrefs(LatestVersionEntity latestVersion) {
        SharedPreferences prefs = getPreferences();
        prefs.edit().putString("latest_version_glacier_android", latestVersion.getGlacier_android()).apply();
        prefs.edit().putString("latest_version_android_os", latestVersion.getAndroid_os()).apply();
        prefs.edit().putString("latest_version_altos", latestVersion.getAlt_os()).apply();
        prefs.edit().putString("latest_version_samsung_os", latestVersion.getSamsung_os()).apply();

    }

    public void queryLatestVersion() {
        MobileApiClient.service().getLatestVersion("latest_versions").enqueue(new retrofit2.Callback<MobileApiResponses.LatestVersionsResponse>() {
            @Override
            public void onResponse(retrofit2.Call<MobileApiResponses.LatestVersionsResponse> call,
                                   retrofit2.Response<MobileApiResponses.LatestVersionsResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(Config.LOGTAG, "Latest version lookup failed with code " + response.code());
                    return;
                }
                final LatestVersionEntity latestVersionEntity =
                        MobileApiClient.toLatestVersionEntity(response.body().versions);
                if (latestVersionEntity != null) {
                    setLatestVersion(latestVersionEntity);
                } else {
                    Log.e(Config.LOGTAG, "getLatestVersionModelQuery error");
                }
                updateLatestVersionSyncUi();
            }

            @Override
            public void onFailure(Call<MobileApiResponses.LatestVersionsResponse> call, Throwable t) {
                Log.e(Config.LOGTAG, "getLatestVersionModelQuery error");
                updateLatestVersionSyncUi();
            }
            });
        }

    public void queryGlacierUsers(Account account, boolean checkPhone) {
        // The user and organization lookups are independent (both only need
        // account.getOrganization()/getUsername(), not each other's response), so they are
        // fired concurrently instead of chaining the org call inside the user callback -
        // this halves the network round-trip latency of this startup path.
        MobileApiClient.service().getGlacierUser(account.getOrganization(), account.getUsername()).enqueue(new retrofit2.Callback<MobileApiResponses.GlacierUserResponse>() {
            @Override
            public void onResponse(retrofit2.Call<MobileApiResponses.GlacierUserResponse> call,
                                   retrofit2.Response<MobileApiResponses.GlacierUserResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(Config.LOGTAG, "Glacier user lookup failed with code " + response.code());
                    updateUserSync();
                    return;
                }
                final GlacierUserEntity userData = MobileApiClient.toGlacierUsers(response.body().user);
                account.setGlacierUser(userData);
                final String previousToken = account.getToken();
                // Keep the stored JWT in step with this periodic/startup profile refresh, same as
                // the initial login and the reactive-refresh-on-auth-failure path. No token in the
                // response leaves the stored credentials untouched.
                final boolean tokenChanged =
                        XmppTokenCredentials.applyToken(account, userData.getXmppToken())
                                && !userData.getXmppToken().equals(previousToken);
                if (Strings.isNullOrEmpty(userData.getXmppToken())) {
                    Log.w(Config.LOGTAG, account.getJid().asBareJid() + ": profile refresh returned no xmpp_token");
                }
                glacierUserDataRefreshed.set(true);
                updateUserSync();
                boolean userChanged = updateGlacierUserPreferences(account);
                databaseBackend.updateAccount(account);
                if (userChanged) {
                     updateAccount(account);
                 }
                if (tokenChanged && account.getStatus() != Account.State.ONLINE) {
                    // A new token is exactly what unblocks an account the server will no longer
                    // let in on its old credential, but persisting it does not touch the
                    // connection -- without this the account sits out its reconnect backoff,
                    // which caps at five minutes, and a user who opened the app to fix things
                    // sees nothing happen.
                    reconnectAccountNow(account);
                }
                if(userData.getCoreRegion() != null && !userData.getCoreRegion().isEmpty()){
                    maybeImportCoreProfiles(account);
                }
            }

            @Override
            public void onFailure(retrofit2.Call<MobileApiResponses.GlacierUserResponse> call, Throwable t) {
                Log.e(Config.LOGTAG, "Glacier user lookup failed", t);
                updateUserSync();
            }
        });
        queryGlacierOrganization(account, checkPhone);
    }

    public void updateSecHubDB(Account account, String securityInfoJson) {
        if (account == null) {
            Log.w(Config.LOGTAG, "Cannot update Security Hub DB: account is null");
            return;
        }
        SecurityInfoApiClient.updateSecurityInfo(
                account.getOrganization(),
                account.getUsername(),
                securityInfoJson,
                new SecurityInfoApiClient.UpdateCallback() {
                    @Override
                    public void onSuccess() {
                        SecurityHubReportStatus.recordSuccess(XmppConnectionService.this);
                    }

                    @Override
                    public void onFailure(@NonNull String message, Throwable t) {
                        if (t != null) {
                            Log.e(Config.LOGTAG, message, t);
                        } else {
                            Log.e(Config.LOGTAG, message);
                        }
                        SecurityHubReportStatus.recordFailure(XmppConnectionService.this, message);
                    }
                });
    }

    /**
     * Re-run the organization lookup when the previous attempt failed, so org-controlled settings
     * are not left stale until the user pulls to refresh. Called on
     * bind, which is the first moment the network and auth session are known to be usable.
     *
     * <p>Only retries after a recorded failure, so the ordinary cold start -- where the lookup
     * fired from the database restore is already in flight or done -- issues no extra request.
     */
    public void retryGlacierOrganizationIfLastAttemptFailed(final Account account) {
        if (account == null || !glacierOrgFetchFailed.compareAndSet(true, false)) {
            return;
        }
        queryGlacierOrganization(account, false);
    }

    public void queryGlacierOrganization(Account account, boolean checkPhone) {
        MobileApiClient.service().getGlacierOrganization(account.getOrganization(), account.getUsername()).enqueue(new retrofit2.Callback<MobileApiResponses.GlacierOrganizationResponse>() {
            @Override
            public void onResponse(retrofit2.Call<MobileApiResponses.GlacierOrganizationResponse> call,
                                   retrofit2.Response<MobileApiResponses.GlacierOrganizationResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().organization != null) {
                    try {
                        final GlacierOrgEntity goe = MobileApiClient.toGlacierOrgEntity(response.body().organization);
                        if (goe != null) {

                            glacierOrgFetchFailed.set(false);
                            OrgUpdateResult result = updateGlacierOrganizationPreferences(goe);
                            updateOrgSync();
                            if (result.any){
                                updateGlacierOrg(goe);
                            } else {
                                noOrgUpdateUi();
                                syncLiveLocationUpdates();
                                syncAutomaticSecurityUpdates();
                            }
                            if (checkPhone) {
                                maybePrefetchPhoneInfo(account);
                            }
                        } else {
                            Log.e(Config.LOGTAG, "Glacier organization lookup failed with code " + response.code());
                        }
                    } catch (Exception e) {
                        Log.e(Config.LOGTAG, "Glacier organization lookup failed with code " + response.code());
                    } finally {
                        maybeImportCoreProfiles(account);
                    }
                } else {
                    Log.e(Config.LOGTAG, "Glacier organization lookup failed with code " + response.code() + " reason " + response.message());
                    glacierOrgFetchFailed.set(true);
                    noOrgUpdateUi();
                    maybeImportCoreProfiles(account);
                }
            }

            @Override
            public void onFailure(retrofit2.Call<MobileApiResponses.GlacierOrganizationResponse> call, Throwable t) {
                Log.e(Config.LOGTAG, "Glacier organization lookup failed", t);
                glacierOrgFetchFailed.set(true);
                maybeImportCoreProfiles(account);
            }
        });
    }

    private void maybePrefetchPhoneInfo(Account account) {
        if (account == null) {
            return;
        }
        if (!account.isOnlineAndConnected()) {
            return;
        }
        if (getOrganizationForAccount(account) == null) {
            return;
        }
        final Account firstAccount = getFirstAccount();
        if (firstAccount == null
                || !Objects.equal(firstAccount.getUuid(), account.getUuid())) {
            return;
        }
        if (!isSmsPrefetchEnabled()) {
            return;
        }
        if (!phonePrefetchTriggered.compareAndSet(false, true)) {
            return;
        }
        phonePrefetchInProgress.set(true);
        new Thread(() -> {
            try {
                syncPhoneInfo(false);
            } finally {
                phonePrefetchInProgress.set(false);
            }
        }).start();
    }

    private boolean isSmsPrefetchEnabled() {
        return getPreferences().getBoolean("user_sms_enabled", false)
                || (smsInfo != null && smsInfo.isSMSEnabled());
    }

    public synchronized void syncPhoneInfo(boolean force) {

        getAccessToken();
        if (phoneSyncInProgress.get() && !force) {
            return;
        }
        if (hasPhoneLoaded() && !force) {
            return;
        }

        final Account account = getFirstAccount();
        if (account == null) {
            Log.w(Config.LOGTAG, "syncPhoneInfo called with no account");
            markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus.NA);
            return;
        }

        phoneSyncInProgress.set(true);
        phoneSyncCompletedNoNumbers.set(false);
        phoneSyncTimeoutHandler.removeCallbacks(phoneSyncTimeoutRunnable);
        final long timeout = Math.min(
                INITIAL_PHONE_SYNC_TIMEOUT_MS * (1L << phoneSyncRetryCount),
                MAX_PHONE_SYNC_TIMEOUT_MS);
        phoneSyncTimeoutHandler.postDelayed(phoneSyncTimeoutRunnable, timeout);
        smsInfo.getSelectedTwilioNumber(account, force);
    }

    public void markPhoneSyncComplete(SMSdbInfo.PhoneSyncStatus status) {

        phoneSyncInProgress.set(false);
        phoneSyncCompletedNoNumbers.set(status == SMSdbInfo.PhoneSyncStatus.NO_NUMBERS
                || status == SMSdbInfo.PhoneSyncStatus.PHONE_NOT_ENABLED);
        phoneSyncTimeoutHandler.removeCallbacks(phoneSyncTimeoutRunnable);
        phoneSyncRetryCount = 0;

        if (onSMSCompleteCallbackListener != null) {
            onSMSCompleteCallbackListener.onRefreshComplete(status);
        }
        updatePhoneUi();
    }

    public boolean isPhoneSyncInProgress() {

        return phoneSyncInProgress.get();
    }
    public boolean isPhonePrefetchInProgress() {
        return phonePrefetchInProgress.get();
    }
    private String accessToken;

    private final VoiceRegistrationStatus voiceRegistrationStatus = new VoiceRegistrationStatus();

    /**
     * Whether this device is currently registered to receive Twilio call invites, and if not,
     * why. Registration failing is invisible to the user -- the phone just never rings -- so the
     * outcome is recorded rather than only logged.
     */
    public VoiceRegistrationStatus getVoiceRegistrationStatus() {
        return voiceRegistrationStatus;
    }

    /**
     * Fetch a fresh voice token and register for call invites again. Exposed so the Phone
     * module's "tap to retry" can act on a registration failure, which is otherwise something
     * the user has no way to recover from short of restarting the app.
     */
    public void retryVoiceRegistration() {
        getAccessToken();
    }

    private void registerForCallInvites() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(new OnCompleteListener<String>() {
                    @Override
                    public void onComplete(@NonNull Task<String> task) {
                        if (task.isSuccessful()) {
                    try {
                                // Get new FCM token
                                String fcmToken = task.getResult();
        Voice.setLogLevel(LogLevel.OFF);
                                Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, registrationListener);
                            } catch (Exception e) {
                                Log.e(Config.LOGTAG, "Error getting Firebase Instance ID token", e);
                                voiceRegistrationStatus.recordFailure(
                                        VoiceRegistrationStatus.Stage.FCM_TOKEN,
                                        0,
                                        e.getMessage(),
                                        System.currentTimeMillis());
                                updatePhoneUi();
                            }
                        } else {
                            final Exception cause = task.getException();
                            Log.e(Config.LOGTAG, "Error getting Firebase Instance ID token", cause);
                            voiceRegistrationStatus.recordFailure(
                                    VoiceRegistrationStatus.Stage.FCM_TOKEN,
                                    0,
                                    cause == null ? null : cause.getMessage(),
                                    System.currentTimeMillis());
                            updatePhoneUi();
                        }
                    }
                });
    }

    RegistrationListener registrationListener = registrationListener();

    private RegistrationListener registrationListener () {
        return new RegistrationListener() {
            @Override
            public void onRegistered(@NonNull String accessToken, @NonNull String fcmToken) {
                voiceRegistrationStatus.recordRegistered();
                updatePhoneUi();
            }

            @Override
            public void onError(@NonNull RegistrationException error,
                                @NonNull String accessToken,
                                @NonNull String fcmToken) {
                final String message = String.format(
                        Locale.US,
                        "Registration Error: %d, %s",
                        error.getErrorCode(),
                        error.getMessage());
                // Log.e is dropped entirely in a production build, where Config.logLevel is 0,
                // so the recorded status below is the only thing that survives to explain why
                // this device stopped receiving calls.
                Log.e(Config.LOGTAG, message);
                voiceRegistrationStatus.recordFailure(
                        VoiceRegistrationStatus.Stage.TWILIO_REGISTER,
                        error.getErrorCode(),
                        error.getMessage(),
                        System.currentTimeMillis());
                updatePhoneUi();
            }
        };
    }


    public void checkConsoleStatus() {
        new Thread(() -> {
            final Account account = getFirstAccount();
            if (account == null) {
                return;
            }
            if (!ConnectivityReceiver.isConnected(getApplicationContext())) {
                updateConsoleUi(true);
                return;
            }

            Request request = new Request.Builder()
                    .url(
                            Endpoints.base() + EndPointConfig.IS_LIVE_URL)
                    .get()
                    .build();
            try (Response response = getOkHttpClient().newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        boolean isLive = false;
                        Gson gson = new Gson();
                        SMSdbInfo.IsLiveResponse isLiveResponse;
                        try {
                            isLiveResponse = gson.fromJson(responseBody, SMSdbInfo.IsLiveResponse.class);
                            isLive =  (isLiveResponse.isLive);
                        } catch (JsonSyntaxException jsonSyntaxException) {
                            Log.e(Config.LOGTAG, "isLive response parse failure (code=" + response.code() + ", responseBodyLength=" + responseBody.length() + ")", jsonSyntaxException);
                        }

                        updateConsoleUi(isLive);

                    }
                } catch (Exception e) {
                if (ConnectivityReceiver.isConnected(getApplicationContext())) {
                    updateConsoleUi(false);
                } else {
                    updateConsoleUi(true);
                }

            }
        }).start();
    }


    private void getAccessToken() {
        new Thread(() -> {
            final Account account = getFirstAccount();
            if (account == null) {
                // The service can reach here before any account has been loaded (or after the
                // last one is removed), and a voice token is meaningless without an identity.
                Log.w(Config.LOGTAG, "getAccessToken called with no account");
                return;
            }

            String accessTokenUrl =
                    Endpoints.base() + EndPointConfig.VOICE_TOKEN_URL;
            OkHttpClient client = getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("username", account.getUsername());
            formFields.put("identity", account.getUsername());
            formFields.put("organization", account.getOrganization());

            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(accessTokenUrl, formFields)
                        .build();
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
                Toast.makeText(this, R.string.access_token_failed, Toast.LENGTH_LONG).show();
                return;
            }

            try {
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                        Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
                }

                @Override
                public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response) {
                    try {
                            String responseBody = "{\"message\":\"failure\"}";
                            if (response.body() != null) {
                                responseBody = response.body().string();
                        }

                            final String parsedToken = TwilioAccessTokenParser.parse(responseBody);
                            if (parsedToken == null) {
                                Log.e(Config.LOGTAG, "Voice registration token response missing valid JWT");
                                voiceRegistrationStatus.recordFailure(
                                        VoiceRegistrationStatus.Stage.ACCESS_TOKEN,
                                        response.code(),
                                        "voice token response carried no usable JWT",
                                        System.currentTimeMillis());
                                updatePhoneUi();
                                return;
                            }
                            accessToken = parsedToken;
                            registerForCallInvites();

                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
                        }
                    }
                });
                    } catch (Exception ex) {
                ex.printStackTrace();
                Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
                Toast.makeText(this, R.string.access_token_failed, Toast.LENGTH_LONG).show();
                    }
        }).start();
    }


    public void enableAccount(Account account) {
        account.setOption(Account.OPTION_DISABLED, false);
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.resetEverything();
        }
        if (!this.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }

    public void disableAccount(Account account) {
        account.setOption(Account.OPTION_DISABLED, true);
        if (!this.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }

    public void updateCallHistory(ArrayList<HistoryItem> callHistory) {

        mDatabaseWriterExecutor.execute(() -> databaseBackend.addCallHistoryList(callHistory));
    }

    public void updateSmsProfileList(SmsProfile sp) {
        sp.setActive(true);
        mDatabaseWriterExecutor.execute(() -> databaseBackend.createSmsProfile(sp));

    }


    public void addVoicemailRecord(VoicemailRecord voicemailRecord) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.addVoicemailRecord(voicemailRecord));
    }

    public void updateVoicemailStatusForProxy(String status, SmsProfile sp) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.updateVoicemailStatusForProxy(status, sp));
    }

    private static boolean isValidRegionName(String region) {
        return region != null && region.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static boolean isPathWithinDirectory(File file, File directory) {
        try {
            return file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    public void addCoreProfile(CoreProfile coreProfile) {
        addCoreProfile(coreProfile, null);
    }

    public void addCoreProfile(CoreProfile coreProfile, Runnable onAdded) {
        mDatabaseWriterExecutor.execute(() -> {
            databaseBackend.addCoreProfile(coreProfile);
            if (onAdded != null) {
                onAdded.run();
            }
        });
    }

    public CoreProfile getRunningCoreProfile() {
        return databaseBackend.getRunningCoreProfile();
    }

    public void setRunningCoreProfile(CoreProfile cp) {
        // Update the source of truth (which persists asynchronously) so the
        // in-memory connected state reflects the connect immediately.
        PersistentConnectionProperties.getInstance().markProfileConnected(cp.getProfName());
    }

    public void importCoreProfiles(@Nullable Account account, @Nullable Runnable onComplete) {

        if (account == null) {

            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        List<String> regions = account.getCoreRegion();

        if (regions == null) {
            regions = Collections.emptyList();
        }


        Set<String> sanitizedRegions = new LinkedHashSet<>();
        for (String region : regions) {

            if (region == null) {
                continue;
            }
            String sanitized = region.replace("\"", "").trim();
            if (!sanitized.isEmpty()) {
                sanitizedRegions.add(sanitized);
            }
        }

        if (sanitizedRegions.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        File profilesDir = new File(getFilesDir(), "profiles");
        if (!profilesDir.exists() && !profilesDir.mkdirs()) {
            Log.w(Config.LOGTAG, "Unable to create profiles directory at " + profilesDir.getAbsolutePath());
        }

        GoBackend backend = PersistentConnectionProperties.getInstance().getBackend();
        if (backend == null) {
            try {
                PersistentConnectionProperties.getInstance().setBackend(new GoBackend(this));
                backend = PersistentConnectionProperties.getInstance().getBackend();
            } catch (Exception e) {
                Log.w(Config.LOGTAG, "Unable to initialize backend for core profile import", e);
            }
        }

        final List<String> finalRegions = new ArrayList<>(sanitizedRegions);
        final GoBackend finalBackend = backend;
        final File finalProfilesDir = profilesDir;
        final AtomicInteger remaining = new AtomicInteger(finalRegions.size());

        final Runnable startDownloads = () -> {
            deleteAllCoreProfiles();
            startCoreProfileDownloads(
                    finalRegions,
                    account,
                    finalBackend,
                    finalProfilesDir,
                    remaining,
                    onComplete);
        };
        final Runnable skipRemaining = () -> completeRemainingCoreProfileImports(remaining, onComplete);

        Amplify.Auth.fetchAuthSession(authSession -> {
            if (authSession.isSignedIn()) {
                startDownloads.run();
                return;
            }
            Log.w(Config.LOGTAG,
                    "Auth session is not signed in; requesting the new reauthentication flow before core profile download");
            requestCoreProfileReauthentication(account, skipRemaining);
        }, error -> {
            Log.e(Config.LOGTAG, "Failed to fetch auth session before core profile download", error);
            requestCoreProfileReauthentication(account, skipRemaining);
        });
    }

    private void handleCoreProfileImportCompletion(AtomicInteger remaining, @Nullable Runnable onComplete) {
        if (remaining.decrementAndGet() == 0 && onComplete != null) {
            onComplete.run();
        }
    }

    private void completeRemainingCoreProfileImports(AtomicInteger remaining, @Nullable Runnable onComplete) {
        int outstanding = Math.max(remaining.get(), 0);
        for (int i = 0; i < outstanding; i++) {
            handleCoreProfileImportCompletion(remaining, onComplete);
        }
    }

    private void startCoreProfileDownloads(List<String> regions,
                                           Account account,
                                           @Nullable GoBackend backend,
                                           File profilesDir,
                                           AtomicInteger remaining,
                                           @Nullable Runnable onComplete) {
        if (regions.isEmpty()) {
            completeRemainingCoreProfileImports(remaining, onComplete);
            return;
        }

        for (String regionName : regions) {

            // ── Region name allowlist ────────────────────────────────────────
            if (!isValidRegionName(regionName)) {
                Log.e(Config.LOGTAG, "Rejecting invalid region name from server: [redacted]");
                handleCoreProfileImportCompletion(remaining, onComplete);
                continue;
            }
            // ─────────────────────────────────────────────────────────────────

            final String sanitizedRegion = regionName.replace("_", "--");
            final String destinationName = sanitizedRegion + "--" + account.getUsername() + ".conf";
            final File localFile = new File(profilesDir, destinationName);

            // ── Canonical path confinement ───────────────────────────────────
            if (!isPathWithinDirectory(localFile, profilesDir)) {
                Log.e(Config.LOGTAG, "Rejecting profile path outside profiles directory");
                handleCoreProfileImportCompletion(remaining, onComplete);
                continue;
            }
            // ─────────────────────────────────────────────────────────────────

            File parent = localFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(Config.LOGTAG, "Unable to create directory for profile");
            }

            MobileApiClient.service()
                    .getCoreProfile(account.getOrganization(), regionName, account.getUsername())
                    .enqueue(new retrofit2.Callback<okhttp3.ResponseBody>() {
                        @Override
                        public void onResponse(Call<okhttp3.ResponseBody> call,
                                               retrofit2.Response<okhttp3.ResponseBody> response) {
                            if (!response.isSuccessful() || response.body() == null) {
                                Log.e(Config.LOGTAG, "Core profile request failed for region, code=" + response.code());
                                handleCoreProfileImportCompletion(remaining, onComplete);
                                return;
                            }
                            try (FileOutputStream outputStream = new FileOutputStream(localFile)) {
                                outputStream.write(response.body().bytes());
                            } catch (IOException e) {
                                Log.e(Config.LOGTAG, "Failed to save core profile", e);
                                handleCoreProfileImportCompletion(remaining, onComplete);
                                return;
                            }
                            CoreProfile coreProfile = new CoreProfile(
                                    sanitizedRegion,
                                    localFile,
                                    regionName + "_" + account.getUsername() + ".conf");
                            if (backend != null) {
                                try {
                                    Tunnel tunnel = PersistentConnectionProperties.getInstance()
                                            .getTunnel(coreProfile.getProfName());
                                    if (backend.getState(tunnel) == Tunnel.State.UP) {
                                        coreProfile.setRunning(true);
                                    }
                                } catch (Exception e) {
                                }
                            }
                            addCoreProfile(coreProfile, () -> handleCoreProfileImportCompletion(remaining, onComplete));
                        }

                        @Override
                        public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) {
                            Log.e(Config.LOGTAG, "Core profile request failed", t);
                            handleCoreProfileImportCompletion(remaining, onComplete);
                        }
                    });
        }
    }

    private void requestCoreProfileReauthentication(@Nullable Account account,
                                                    @Nullable Runnable onFailure) {
        if (account == null) {
            Log.w(Config.LOGTAG, "No account available for core profile reauthentication flow");
            if (onFailure != null) {
                onFailure.run();
            }
            return;
        }

        Log.w(
                Config.LOGTAG,
                "Core profile download requires reauthentication; launching the new reauthentication flow for "
                        + account.getJid());
        if (onFailure != null) {
            onFailure.run();
        }
    }

    public void addSMSConversation(com.twilio.conversations.Conversation conversation, SmsProfile sp, String identityNumber) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.addSMSConversation(conversation, sp, identityNumber));
        String sid = conversation.getSid();
        TwilioConversationEntity conv = new TwilioConversationEntity(
                sid,
                conversation.getFriendlyName(),
                identityNumber,
                null,
                conversation.getLastMessageDate() != null ? conversation.getLastMessageDate().toString() : null,
                null);
        smsInfo.conv_by_sid.put(sid, conv);
        Pair<SmsMessagePreviewUtils.SmsPreview, String> last =
                databaseBackend.getLastSmsPreviewForConversation(sid);
        if (last != null) {
            smsInfo.conv_last_msg.put(sid, last.first.getText());
            smsInfo.conv_last_msg_is_media.put(sid, last.first.isMedia());
            smsInfo.conv_last_msg_sent.put(sid, last.second);
            conv.setLastMessage(last.first.getText());
            conv.setLastMessageIsMedia(last.first.isMedia());
        }
        smsInfo.conv_last_msg_count.put(sid, databaseBackend.getSmsMessagesCount(sid));
        if (identityNumber != null) {
            ArrayList<TwilioConversationEntity> list = smsInfo.conv_list.get(identityNumber);
            if (list == null) {
                list = new ArrayList<>();
                smsInfo.conv_list.put(identityNumber, list);
            }
            list.add(conv);
        }
    }
    public void addSMSConversation(com.twilio.conversations.Conversation conversation, String proxy, String identityNumber) {
        if (identityNumber == null){
            identityNumber = "null";
        }
        final String id = identityNumber;
        mDatabaseWriterExecutor.execute(() -> databaseBackend.addSMSConversation(conversation, proxy, id));
        String sid = conversation.getSid();
        TwilioConversationEntity conv = new TwilioConversationEntity(
                sid,
                conversation.getFriendlyName(),
                id,
                null,
                conversation.getLastMessageDate() != null ? conversation.getLastMessageDate().toString() : null,
                null);
        smsInfo.conv_by_sid.put(sid, conv);
        Pair<SmsMessagePreviewUtils.SmsPreview, String> last =
                databaseBackend.getLastSmsPreviewForConversation(sid);
        if (last != null) {
            smsInfo.conv_last_msg.put(sid, last.first.getText());
            smsInfo.conv_last_msg_is_media.put(sid, last.first.isMedia());
            smsInfo.conv_last_msg_sent.put(sid, last.second);
            conv.setLastMessage(last.first.getText());
            conv.setLastMessageIsMedia(last.first.isMedia());
        }
        smsInfo.conv_last_msg_count.put(sid, databaseBackend.getSmsMessagesCount(sid));
        ArrayList<TwilioConversationEntity> list = smsInfo.conv_list.get(id);
        if (list == null) {
            list = new ArrayList<>();
            smsInfo.conv_list.put(id, list);
        }
        list.add(conv);
    }


    public void initializeConversationsClient(Account mAccount, boolean forced) {
            phoneSyncInProgress.set(true);
            phoneSyncCompletedNoNumbers.set(false);
            conversationsManager.init(mAccount, forced);
    }

    public void registerForConversationsPushNotifications(@Nullable String fcmToken) {
        conversationsManager.registerForFcmPushNotifications(fcmToken);
    }

    public void getConversationBySidAndUpdate(com.twilio.conversations.Conversation conversation, com.twilio.conversations.Message lastMessage) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.getConversationBySidAndUpdate(conversation, lastMessage));
        String sid = conversation.getSid();
        if (lastMessage != null) {
            SmsMessagePreviewUtils.SmsPreview preview = SmsMessagePreviewUtils.buildPreview(lastMessage);
            smsInfo.conv_last_msg.put(sid, preview.getText());
            smsInfo.conv_last_msg_is_media.put(sid, preview.isMedia());
            smsInfo.conv_last_msg_sent.put(sid, lastMessage.getAuthor());
            smsInfo.conv_last_msg_count.put(sid, databaseBackend.getSmsMessagesCount(sid));
            TwilioConversationEntity conv = smsInfo.conv_by_sid.get(sid);
            if (conv != null) {
                conv.setLastMessage(preview.getText());
                conv.setLastMessageIsMedia(preview.isMedia());
                if (lastMessage.getDateCreated() != null) {
                    conv.setLastMessageDate(lastMessage.getDateCreated());
                }
            } else {
                String identityNumber = null;
                try {
                    JSONObject attrs = conversation.getAttributes().getJSONObject();
                    if (attrs != null) {
                        identityNumber = attrs.optString("identity_number", null);
                    }
                } catch (Exception ignore) {
                }
                TwilioConversationEntity newConv = new TwilioConversationEntity(
                        sid,
                        conversation.getFriendlyName(),
                        identityNumber,
                        preview.getText(),
                        lastMessage.getDateCreated(),
                        preview.isMedia());
                smsInfo.conv_by_sid.put(sid, newConv);
                if (identityNumber != null) {
                    ArrayList<TwilioConversationEntity> list = smsInfo.conv_list.get(identityNumber);
                    if (list == null) {
                        list = new ArrayList<>();
                        smsInfo.conv_list.put(identityNumber, list);
                    }
                    list.add(newConv);
                }
            }
        } else {
            smsInfo.conv_last_msg_count.put(sid, databaseBackend.getSmsMessagesCount(sid));
        }
    }

    public void populateSMSMessagesForConversation(List<com.twilio.conversations.Message> result) {
        if (result == null || result.isEmpty()) {
            Log.w(Config.LOGTAG, "populateSMSMessagesForConversation called with no messages");
            return;
        }

        mDatabaseWriterExecutor.execute(() -> databaseBackend.addSmsMessages(result));

        for (com.twilio.conversations.Message message : result) {
            com.twilio.conversations.Conversation conversation = message.getConversation();
            if (conversation == null) {
                Log.w(Config.LOGTAG, "Skipping SMS message without an associated conversation");
                continue;
            }

            String sid = conversation.getSid();
            if (sid == null) {
                Log.w(Config.LOGTAG, "Skipping SMS message with null conversation SID");
                continue;
            }

            ArrayList<TwilioMessageEntity> list = smsInfo.conv_messages.get(sid);
            if (list == null) {
                list = new ArrayList<>();
                smsInfo.conv_messages.put(sid, list);
            }

            list.add(new TwilioMessageEntity(
                    message.getSid(),
                    sid,
                    message.getDateCreated(),
                    message.getBody(),
                    message.getAuthor()));
        }
    }

    public void receivedSmsMessage(com.twilio.conversations.Message message) {
        mDatabaseWriterExecutor.execute(() -> {
            databaseBackend.addSmsMessage(message);
            databaseBackend.getConversationBySidAndUpdate(message.getConversation(), message);
        });

        String sid = message.getConversation().getSid();
        SmsMessagePreviewUtils.SmsPreview preview = SmsMessagePreviewUtils.buildPreview(message);
        smsInfo.conv_last_msg.put(sid, preview.getText());
        smsInfo.conv_last_msg_is_media.put(sid, preview.isMedia());
        smsInfo.conv_last_msg_sent.put(sid, message.getAuthor());

        ArrayList<TwilioMessageEntity> messagesForSid = smsInfo.conv_messages.get(sid);
        if (messagesForSid == null) {
            messagesForSid = new ArrayList<>();
            smsInfo.conv_messages.put(sid, messagesForSid);
        }
        TwilioMessageEntity newEntity = new TwilioMessageEntity(
                message.getSid(),
                sid,
                message.getDateCreated(),
                message.getBody(),
                message.getAuthor());
        boolean isNewMessage = true;
        if (message.getSid() != null) {
            for (int i = 0; i < messagesForSid.size(); i++) {
                TwilioMessageEntity existing = messagesForSid.get(i);
                if (message.getSid().equals(existing.messageSid)) {
                    messagesForSid.set(i, newEntity);
                    isNewMessage = false;
                    break;
                }
            }
        }
        if (isNewMessage) {
            messagesForSid.add(newEntity);
            smsInfo.conv_last_msg_count.merge(sid, 1, Integer::sum);
        }

        TwilioConversationEntity conv = smsInfo.conv_by_sid.get(sid);
        if (conv != null) {
            conv.setLastMessage(preview.getText());
            conv.setLastMessageIsMedia(preview.isMedia());
            if (message.getDateCreated() != null) {
                conv.setLastMessageDate(message.getDateCreated());
            }
        } else {
            String identityNumber = null;
            try {
                JSONObject attrs = message.getConversation().getAttributes().getJSONObject();
                if (attrs != null) {
                    identityNumber = attrs.optString("identity_number", null);
                }
            } catch (Exception ignore) {
            }
            TwilioConversationEntity newConv = new TwilioConversationEntity(
                    sid,
                    message.getConversation().getFriendlyName(),
                    identityNumber,
                    preview.getText(),
                    message.getDateCreated(),
                    preview.isMedia());
            smsInfo.conv_by_sid.put(sid, newConv);

        }
    }

    public SmsProfile getSmsProfileFromProxy(String proxy) {
        return databaseBackend.getSmsProfileFromProxy(proxy);
    }


    public ArrayList<TwilioConversationEntity> getSmsConversationsForProxy(SmsProfile smsProfile) {
        ArrayList<TwilioConversationEntity> conversations = databaseBackend.getSmsConversationsObjForProxy(smsProfile);
        if (smsProfile != null) {
            String profileNumber = smsProfile.getUnformattedNumber();
            for (TwilioConversationEntity conversation : conversations) {
                smsInfo.setConversationUnreadCount(profileNumber,
                        conversation.getConv_sid(),
                        conversation.getUnreadCount());
                TwilioConversationEntity cached = smsInfo.conv_by_sid.get(conversation.getConv_sid());
                if (cached != null) {
                    cached.setUnreadCount(conversation.getUnreadCount());
                }
            }
        }
        return conversations;
    }

    public void updateSmsConversationUnreadCount(String profileNumber, String conversationSid, int unreadCount) {
        if (conversationSid == null || conversationSid.isEmpty()) {
            return;
        }
        int safeUnreadCount = Math.max(unreadCount, 0);
        mDatabaseWriterExecutor.execute(() -> {
            databaseBackend.updateSmsConversationUnreadCount(conversationSid, safeUnreadCount);
            phoneSyncTimeoutHandler.post(this::updatePhoneUi);
        });
        smsInfo.setConversationUnreadCount(profileNumber, conversationSid, safeUnreadCount);
        TwilioConversationEntity cached = smsInfo.conv_by_sid.get(conversationSid);
        if (cached != null) {
            cached.setUnreadCount(safeUnreadCount);
        }
    }

    public boolean hasLoadedSmsConvos(){

        int convCount = getSmsConversationCount();

        if(!conversationsManager.isConversationsClientNull()){

            return convCount == conversationsManager.getConversationsClientConversationCount();
        }
        boolean hasConversations = convCount > 0;

        return hasConversations;
    }

    public int getSmsConversationCount() {
        return databaseBackend.getSmsConversationCount();
    }

        public boolean hasPhoneLoaded(){

            if (isPhoneSyncInProgress()) {
                return false;
            }
            // A completed sync that found no assigned numbers (or SMS disabled) is a terminal
            // state: the Conversations client is never initialized, so treat it as loaded to
            // avoid the Phone UI being stuck on a loading/connecting spinner indefinitely.
            if (phoneSyncCompletedNoNumbers.get()) {
                return true;
            }
            return !conversationsManager.isConversationsClientNull();

        }


    public TwilioConversationEntity getConversationsBetweenContactAndProxy(String convName, SmsProfile smsProfile) {
        return databaseBackend.getConversationBetweenContactAndProxy(convName, smsProfile);
    }

    public ArrayList<HistoryItem> getHistoryLogListforProxy(SmsProfile smsProfile) {
        return databaseBackend.getHistoryObjForProxy(smsProfile);
    }

    public ArrayList<HistoryItem> getHistoryLogListforProxyAndRemote(SmsProfile smsProfile, String remoteNumber) {
        return databaseBackend.getHistoryObjForProxyAndRemote(remoteNumber, smsProfile);
    }

    public boolean hasUnreadMissedCalls(@Nullable String proxySid) {
        return databaseBackend.hasUnreadMissedCalls(proxySid);
    }

    public void markMissedCallsReadForProxy(@Nullable SmsProfile smsProfile, @Nullable Runnable onComplete) {
        if (smsProfile == null) {
            return;
        }
        mDatabaseWriterExecutor.execute(() -> {
            databaseBackend.markMissedCallsReadForProxy(smsProfile.getId());
            if (onComplete != null) {
                new Handler(Looper.getMainLooper()).post(onComplete);
            }
        });
    }

    public void deleteCoreProfile(CoreProfile coreProfile) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteCoreProfile(coreProfile));

    }

    public void deleteAllCoreProfiles() {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteAllCoreProfiles());
    }

    public void clearRunningCoreProfiles() {
        clearRunningCoreProfiles(null);
    }

    public void clearRunningCoreProfiles(@Nullable Runnable onComplete) {
        // Move the source of truth into DISCONNECTING synchronously; monitor
        // reconciliation persists the final disconnected state once the backend
        // confirms DOWN/empty.
        PersistentConnectionProperties.getInstance().markAllDisconnected();
        if (onComplete != null) {
            onComplete.run();
        }
    }

    public boolean doesVoicemailExist(VoicemailRecord vm) {
        return databaseBackend.doesVoicemailExist(vm.getRecordingSid());
    }

    public ArrayList<VoicemailRecord> getVoicemailsForProxy(SmsProfile smsProxy) {
        return databaseBackend.getVoicemailsForProxy(smsProxy);
    }

    public VoicemailRecord getVoicemailRecord(String recordingSid) {
        return databaseBackend.getVoicemailRecord(recordingSid);
    }

    public int getUnreadVoicemailsCount(SmsProfile smsProxy) {
        int count = 0;
        for (VoicemailRecord vm : databaseBackend.getVoicemailsForProxy(smsProxy)) {
            if (vm.getStatus() == VoicemailRecord.VM_Status.NEW) {
                count++;
            }
        }
        return count;
    }

    public boolean hasAnyUnreadVoicemail() {
        if (smsInfo == null) {
            return false;
        }
        ArrayList<SmsProfile> profiles = smsInfo.getExistingProfs();
        if (profiles == null || profiles.isEmpty()) {
            return false;
        }
        for (SmsProfile profile : profiles) {
            if (profile != null && getUnreadVoicemailsCount(profile) > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyUnreadSmsMessages() {
        if (smsInfo == null) {
            return false;
        }
        ArrayList<SmsProfile> profiles = smsInfo.getExistingProfs();
        if (profiles == null || profiles.isEmpty()) {
            return false;
        }
        for (SmsProfile profile : profiles) {
            if (profile != null && smsInfo.hasUnreadForProfile(profile.getUnformattedNumber())) {
                return true;
            }
        }
        return false;
    }

    public synchronized int getUnreadVoicemailCount() {
        return unreadVoicemailCount;
    }

    public synchronized void setUnreadVoicemailCount(int count) {
        unreadVoicemailCount = count;
    }

    public void removeHistoryLog(String sid) {
        mDatabaseWriterExecutor.execute(()-> databaseBackend.removeHistoryLog(sid));
    }

    public void deleteAllHistoryLogsForProxy(SmsProfile smsProfile) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteAllHistoryLogsForProxy(smsProfile));
    }

    public void deleteTwilioConversation(String sid) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.removeTwilioConversation(sid));
        TwilioConversationEntity conv = smsInfo.conv_by_sid.remove(sid);
        smsInfo.conv_last_msg.remove(sid);
        smsInfo.conv_last_msg_sent.remove(sid);
        smsInfo.conv_last_msg_count.remove(sid);
        smsInfo.conv_messages.remove(sid);
        if (conv != null && conv.getIdentityNumber() != null) {
            ArrayList<TwilioConversationEntity> list = smsInfo.conv_list.get(conv.getIdentityNumber());
            if (list != null) {
                list.remove(conv);
                if (list.isEmpty()) {
                    smsInfo.conv_list.remove(conv.getIdentityNumber());
                }
            }
        }
    }

    public void deleteAllTwilioConversationForProxy(SmsProfile smsProfile) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteAllTwilioConversationsForProxy(smsProfile));
    }

    public void deleteSmsProfileAndData(SmsProfile smsProfile) {
        if (smsProfile == null) {
            return;
        }
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteSmsProfileAndData(smsProfile));
    }

    public void deleteAllPhoneDB() {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteAllPhoneDB());
    }

    public void deleteSmsProfile(SmsProfile smsProfile){
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteSmsProfile(smsProfile.getId()));
    }

    public void deleteAllConvAndMessages() {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteAllConvAndMessages());
    }

    public void deleteAllConvAndMessagesBlocking() {

        CountDownLatch latch = new CountDownLatch(1);
        mDatabaseWriterExecutor.execute(() -> {
            databaseBackend.deleteAllConvAndMessages();
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    public interface OnMamPreferencesFetched {
        void onPreferencesFetched(Element prefs);

        void onPreferencesFetchFailed();
    }

    public interface OnAccountCreated {
        void onAccountCreated(Account account);

        void informUser(int r);
    }

    public interface OnMoreMessagesLoaded {
        void onMoreMessagesLoaded(Conversation conversation);

        void informUser(int r);
    }

    public interface OnAccountPasswordChanged {
        void onPasswordChangeSucceeded();

        void onPasswordChangeFailed();
    }

    public interface OnRoomDestroy {
        void onRoomDestroySucceeded();

        void onRoomDestroyFailed();
    }

    public interface OnAffiliationChanged {
        void onAffiliationChangedSuccessful();

        void onAffiliationChangeFailed(Jid jid, int resId);
    }

    /**
     * Raised after a scan changes which message files are reachable, so screens showing media can
     * reload. Media browsers query on their own start up only; without this, media that arrives
     * while such a screen is already open stays invisible until it is closed and reopened.
     */
    public interface OnMediaAvailabilityChanged {
        void onMediaAvailabilityChanged();
    }

    public interface OnConversationUpdate {
        void onConversationUpdate();
    }

    public interface OnJingleRtpConnectionUpdate {
        void onJingleRtpConnectionUpdate(final Account account, final Jid with, final String sessionId, final RtpEndUserState state);

        void onAudioDeviceChanged(CallIntegration.AudioDevice selectedAudioDevice, Set<CallIntegration.AudioDevice> availableAudioDevices);
    }

    public interface OnAccountUpdate {
        void onAccountUpdate();
    }

    public interface OnOrganizationUpdate {
        void onOrganizationUpdate();
        void onNoOrganizationUpdate();

    }

    public interface OnAmplifySync {
        void onOrgSyncComplete();
        void onUserSyncComplete();
        void onLatestVersionSyncComplete();
    }

    public interface OnCoreStatusUpdate {
        void onCoreStatusUpdated(boolean isCoreConnected);
    }

    public interface OnPhoneUpdate {
        void onPhoneUpdate();
        void onCallHistoryUpdate();
        void onVoicemailUpdate();
        void onAccessToken();
        void onConversationClientSync(boolean success);
    }

    public interface OnCaptchaRequested {
        void onCaptchaRequested(Account account, String id, Data data, Bitmap captcha);
    }

    public interface OnRosterUpdate {
        void onRosterUpdate();
    }

    public interface OnNativeContactUpdate {
        void onNativeContactUpdate();
    }

    public interface OnMucRosterUpdate {
        void onMucRosterUpdate();
    }

    public interface OnConferenceConfigurationFetched {
        void onConferenceConfigurationFetched(Conversation conversation);

        void onFetchFailed(Conversation conversation, String errorCondition);
    }

    public interface OnConferenceJoined {
        void onConferenceJoined(Conversation conversation);
    }

    public interface OnConfigurationPushed {
        void onPushSucceeded();

        void onPushFailed();
    }

    public interface OnShowErrorToast {
        void onShowErrorToast(int resId);
    }

    public interface OnConsoleStatus {
        void onConsoleUp();
        void onConsoleDown();
    }

    public class XmppConnectionBinder extends Binder {
        public XmppConnectionService getService() {
            return XmppConnectionService.this;
        }
    }

    private class InternalEventReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            // Registering replays the last CONNECTIVITY_ACTION. It is not a network change, and
            // acting on it restarted the first connect attempt at every service start.
            if (isInitialStickyBroadcast()
                    && ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                return;
            }
            onStartCommand(intent, 0, 0);
        }
    }


    public static class OngoingCall {
        public final AbstractJingleConnection.Id id;
        public final Set<Media> media;
        public final boolean reconnecting;

        public OngoingCall(AbstractJingleConnection.Id id, Set<Media> media, final boolean reconnecting) {
            this.id = id;
            this.media = media;
            this.reconnecting = reconnecting;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OngoingCall that = (OngoingCall) o;
            return reconnecting == that.reconnecting && Objects.equal(id, that.id) && Objects.equal(media, that.media);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id, media, reconnecting);
        }
    }

    public static void toggleForegroundService(final XmppConnectionService service) {
        if (service == null) {
            return;
        }
        service.toggleForegroundService();
    }

    public static void toggleForegroundService(final ConversationsActivity activity) {
        if (activity == null) {
            return;
        }
        toggleForegroundService(activity.xmppConnectionService);
    }

}
