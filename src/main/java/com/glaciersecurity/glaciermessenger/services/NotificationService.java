package com.glaciersecurity.glaciermessenger.services;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.s;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;

import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.SmsConvActivity;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.BigPictureStyle;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.MucOptions;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.ui.ConversationsActivity;
import com.glaciersecurity.glaciermessenger.ui.LoginAccountActivity;
import com.glaciersecurity.glaciermessenger.ui.RtpSessionActivity;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.GeoHelper;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.ui.util.SoundPoolManager;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.AbstractJingleConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.Media;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationService {

    public static final Object CATCHUP_LOCK = new Object();

    private static final int LED_COLOR = 0xff00ff00;

    private static final long[] CALL_PATTERN = {0, 500, 300, 600, 3000};

    private static final String MESSAGES_GROUP = "com.glaciersecurity.glaciermessenger.messages";
    private static final String MISSED_CALLS_GROUP = "com.glaciersecurity.glaciermessenger.missed_calls";
    private static final int NOTIFICATION_ID_MULTIPLIER = 1024 * 1024;
    static final int FOREGROUND_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 4;
    private static final int NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 2;
    private static final int ERROR_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 6;
    private static final int INCOMING_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 8;
    public static final int ONGOING_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 10;
    public static final int MISSED_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 12;
    private static final int DELIVERY_FAILED_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 14;
    public static final int ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID =
            NOTIFICATION_ID_MULTIPLIER * 14;

    public static final int VOICEMAIL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 16;
    public static final int SMS_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 17;
    public static final int PHONE_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 18;
    public static final int MISSED_PHONE_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 19;
    public static final int INCOMING_PHONE_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 20;
    public static final String ACTION_PHONE_BADGES_UPDATED =
            "com.glaciersecurity.glaciermessenger.ACTION_PHONE_BADGES_UPDATED";

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer>
            REQUEST_CODE_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger
            REQUEST_CODE_COUNTER = new java.util.concurrent.atomic.AtomicInteger(1);

    private final XmppConnectionService mXmppConnectionService;
    private final LinkedHashMap<String, ArrayList<Message>> notifications = new LinkedHashMap<>();
    private final HashMap<Conversation, AtomicInteger> mBacklogMessageCounter = new HashMap<>();
    private final LinkedHashMap<Conversational, MissedCallsInfo> mMissedCalls =
            new LinkedHashMap<>();
    private Conversation mOpenConversation;
    private boolean mIsInForeground;
    private long mLastNotification;
    private long mLastVoicemailAlert;


    private static final String INCOMING_CALLS_NOTIFICATION_CHANNEL = "incoming_calls_channel";
    private static final String INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX =
            "incoming_calls_channel#";
    public static final String MESSAGES_NOTIFICATION_CHANNEL = "messages";
    private static final String SMS_MESSAGES_HIGH_PRIORITY_NOTIFICATION_CHANNEL = "sms_messages_high_priority_v1";
    private static final String VOICEMAIL_NOTIFICATION_CHANNEL = "voicemail_channel";


    NotificationService(final XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }
    private static boolean displaySnoozeAction(List<Message> messages) {
        int numberOfMessagesWithoutReply = 0;
        for (Message message : messages) {
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                ++numberOfMessagesWithoutReply;
            } else {
                return false;
            }
        }
        return numberOfMessagesWithoutReply >= 3;
    }

    public static Pattern generateNickHighlightPattern(final String nick) {
        return Pattern.compile("(?<=(^|\\s))" + Pattern.quote(nick) + "(?=\\s|$|\\p{Punct})");
    }

    private static boolean isImageMessage(Message message) {
        return message.getType() != Message.TYPE_TEXT
                && message.getTransferable() == null
                && !message.isDeleted()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getFileParams().height > 0;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    void initializeChannels() {
        final Context c = mXmppConnectionService;
        final NotificationManager notificationManager =
                c.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }

        notificationManager.deleteNotificationChannel("export");
        notificationManager.deleteNotificationChannel("incoming_calls");
        notificationManager.deleteNotificationChannel(INCOMING_CALLS_NOTIFICATION_CHANNEL);

        notificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        "status", c.getString(R.string.notification_group_status_information)));
        notificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        "chats", c.getString(R.string.notification_group_messages)));
        notificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        "phone", c.getString(R.string.notification_group_phone)));
        notificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        "calls", c.getString(R.string.notification_group_calls)));
        notificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        "sms", c.getString(R.string.notification_group_sms)));
        notificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        "glaciercalls", c.getString(R.string.notification_group_phone_calls)));
        final NotificationChannel foregroundServiceChannel =
                new NotificationChannel(
                        "foreground",
                        c.getString(R.string.foreground_service_channel_name),
                        NotificationManager.IMPORTANCE_MIN);
        foregroundServiceChannel.setDescription(
                c.getString(
                        R.string.foreground_service_channel_description,
                        c.getString(R.string.app_name)));
        foregroundServiceChannel.setShowBadge(false);
        foregroundServiceChannel.setGroup("status");
        notificationManager.createNotificationChannel(foregroundServiceChannel);
        final NotificationChannel errorChannel =
                new NotificationChannel(
                        "error",
                        c.getString(R.string.error_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        errorChannel.setDescription(c.getString(R.string.error_channel_description));
        errorChannel.setShowBadge(false);
        errorChannel.setGroup("status");
        notificationManager.createNotificationChannel(errorChannel);

        final NotificationChannel videoCompressionChannel =
                new NotificationChannel(
                        "compression",
                        c.getString(R.string.video_compression_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        videoCompressionChannel.setShowBadge(false);
        videoCompressionChannel.setGroup("status");
        notificationManager.createNotificationChannel(videoCompressionChannel);

        // changed channel and group names
        final NotificationChannel callsChannel = new NotificationChannel("glaciercalls",
                "GlacierCallChannel",
                NotificationManager.IMPORTANCE_HIGH);
        callsChannel.setShowBadge(true);
        Uri callUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"+ mXmppConnectionService.getPackageName() + "/" + R.raw.incoming);
        callsChannel.setSound(callUri, new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build());

        callsChannel.setLightColor(ContextCompat.getColor(mXmppConnectionService, R.color.glacier_blue));
        final int dat = 70;
        final long[] pattern = {0, 3 * dat, dat, dat};
        callsChannel.setVibrationPattern(pattern);
        callsChannel.enableVibration(true);
        callsChannel.enableLights(true);
        callsChannel.setGroup("glaciercalls");
        notificationManager.createNotificationChannel(callsChannel);

        final NotificationChannel exportChannel =
                new NotificationChannel(
                        "backup",
                        c.getString(R.string.backup_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        exportChannel.setShowBadge(false);
        exportChannel.setGroup("status");
        notificationManager.createNotificationChannel(exportChannel);

        createInitialIncomingCallChannelIfNecessary(c);

        final NotificationChannel ongoingCallsChannel =
                new NotificationChannel(
                        "ongoing_calls",
                        c.getString(R.string.ongoing_calls_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        ongoingCallsChannel.setShowBadge(false);
        ongoingCallsChannel.setGroup("calls");
        notificationManager.createNotificationChannel(ongoingCallsChannel);

        final NotificationChannel missedCallsChannel =
                new NotificationChannel(
                        "missed_calls",
                        c.getString(R.string.missed_calls_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
        missedCallsChannel.setShowBadge(true);
        missedCallsChannel.setSound(null, null);
        missedCallsChannel.setLightColor(LED_COLOR);
        missedCallsChannel.enableLights(true);
        missedCallsChannel.setGroup("calls");
        notificationManager.createNotificationChannel(missedCallsChannel);

        final NotificationChannel voicemailChannel =
                new NotificationChannel(
                        VOICEMAIL_NOTIFICATION_CHANNEL,
                        c.getString(R.string.voicemail_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
        voicemailChannel.setShowBadge(true);
        voicemailChannel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build());
        voicemailChannel.setLightColor(LED_COLOR);
        voicemailChannel.enableLights(true);
        voicemailChannel.setGroup("calls");
        notificationManager.createNotificationChannel(voicemailChannel);

        final var messagesChannel =
                prepareMessagesChannel(mXmppConnectionService, MESSAGES_NOTIFICATION_CHANNEL);
        notificationManager.createNotificationChannel(messagesChannel);
        final NotificationChannel smsMessagesChannel =
                new NotificationChannel(
                        SMS_MESSAGES_HIGH_PRIORITY_NOTIFICATION_CHANNEL,
                        c.getString(R.string.messages_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
        smsMessagesChannel.setShowBadge(true);
        smsMessagesChannel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build());
        smsMessagesChannel.setLightColor(LED_COLOR);
        smsMessagesChannel.setVibrationPattern(pattern);
        smsMessagesChannel.enableVibration(true);
        smsMessagesChannel.enableLights(true);
        smsMessagesChannel.setGroup("sms");
        notificationManager.createNotificationChannel(smsMessagesChannel);
        final NotificationChannel silentMessagesChannel =
                new NotificationChannel(
                        "silent_messages",
                        c.getString(R.string.silent_messages_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        silentMessagesChannel.setDescription(
                c.getString(R.string.silent_messages_channel_description));
        silentMessagesChannel.setShowBadge(true);
        silentMessagesChannel.setLightColor(LED_COLOR);
        silentMessagesChannel.enableLights(true);
        silentMessagesChannel.setGroup("chats");
        notificationManager.createNotificationChannel(silentMessagesChannel);

        final NotificationChannel deliveryFailedChannel =
                new NotificationChannel(
                        "delivery_failed",
                        c.getString(R.string.delivery_failed_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT);
        deliveryFailedChannel.setShowBadge(false);
        deliveryFailedChannel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build());
        deliveryFailedChannel.setGroup("chats");
        notificationManager.createNotificationChannel(deliveryFailedChannel);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void createConversationChannel(
            final Context context, final ShortcutInfoCompat shortcut) {
        final var messagesChannel = prepareMessagesChannel(context, UUID.randomUUID().toString());
        messagesChannel.setName(shortcut.getShortLabel());
        messagesChannel.setConversationId(MESSAGES_NOTIFICATION_CHANNEL, shortcut.getId());
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(messagesChannel);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static NotificationChannel prepareMessagesChannel(
            final Context context, final String id) {
        final NotificationChannel messagesChannel =
                new NotificationChannel(
                        id,
                        context.getString(R.string.messages_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
        messagesChannel.setShowBadge(true);
        messagesChannel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build());
        messagesChannel.setLightColor(LED_COLOR);
        final int dat = 70;
        final long[] pattern = {0, 3 * dat, dat, dat};
        messagesChannel.setVibrationPattern(pattern);
        messagesChannel.enableVibration(true);
        messagesChannel.enableLights(true);
        messagesChannel.setGroup("chats");
        return messagesChannel;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void createInitialIncomingCallChannelIfNecessary(final Context context) {
        final var currentIteration = getCurrentIncomingCallChannelIteration(context);
        if (currentIteration.isPresent()) {
            return;
        }
        createInitialIncomingCallChannel(context);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static Optional<Integer> getCurrentIncomingCallChannelIteration(final Context context) {
        final var notificationManager = context.getSystemService(NotificationManager.class);
        for (final NotificationChannel channel : notificationManager.getNotificationChannels()) {
            final String id = channel.getId();
            if (Strings.isNullOrEmpty(id)) {
                continue;
            }
            if (id.startsWith(INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX)) {
                final var parts = Splitter.on('#').splitToList(id);
                if (parts.size() == 2) {
                    final var iteration = Ints.tryParse(parts.get(1));
                    if (iteration != null) {
                        return Optional.of(iteration);
                    }
                }
            }
        }
        return Optional.absent();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static Optional<NotificationChannel> getCurrentIncomingCallChannel(
            final Context context) {
        final var iteration = getCurrentIncomingCallChannelIteration(context);
        return iteration.transform(
                i -> {
                    final var notificationManager =
                            context.getSystemService(NotificationManager.class);
                    return notificationManager.getNotificationChannel(
                            INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + i);
                });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void createInitialIncomingCallChannel(final Context context) {
        final var appSettings = new AppSettings(context);
        final var ringtoneUri = appSettings.getRingtone();
        createIncomingCallChannel(context, ringtoneUri, 0);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void recreateIncomingCallChannel(final Context context, final Uri ringtone) {
        final var currentIteration = getCurrentIncomingCallChannelIteration(context);
        final int nextIteration;
        if (currentIteration.isPresent()) {
            final var notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.deleteNotificationChannel(
                    INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + currentIteration.get());
            nextIteration = currentIteration.get() + 1;
        } else {
            nextIteration = 0;
        }
        createIncomingCallChannel(context, ringtone, nextIteration);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void createIncomingCallChannel(
            final Context context, final Uri ringtoneUri, final int iteration) {
        final var notificationManager = context.getSystemService(NotificationManager.class);
        final var id = INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + iteration;
        final NotificationChannel incomingCallsChannel =
                new NotificationChannel(
                        id,
                        context.getString(R.string.incoming_calls_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);
        incomingCallsChannel.setSound(
                ringtoneUri,
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        incomingCallsChannel.setShowBadge(false);
        incomingCallsChannel.setLightColor(LED_COLOR);
        incomingCallsChannel.enableLights(true);
        incomingCallsChannel.setGroup("calls");
        incomingCallsChannel.setBypassDnd(true);
        incomingCallsChannel.enableVibration(true);
        incomingCallsChannel.setVibrationPattern(CALL_PATTERN);
        notificationManager.createNotificationChannel(incomingCallsChannel);
    }

    private boolean notifyMessage(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        return message.getStatus() == Message.STATUS_RECEIVED
                && !conversation.isMuted()
                && (conversation.alwaysNotify() || wasHighlightedOrPrivate(message))
                && (!conversation.isWithStranger() || notificationsFromStrangers())
                && message.getType() != Message.TYPE_RTP_SESSION;
    }

    private boolean notifyMissedCall(final Message message) {
        return message.getType() == Message.TYPE_RTP_SESSION
                && message.getStatus() == Message.STATUS_RECEIVED;
    }

    public boolean notificationsFromStrangers() {
        return mXmppConnectionService.getBooleanPreference(
                "notifications_from_strangers", R.bool.notifications_from_strangers);
    }

    public void pushFromBacklog(final Message message) {
        if (notifyMessage(message)) {
            synchronized (notifications) {
                getBacklogMessageCounter((Conversation) message.getConversation())
                        .incrementAndGet();
                pushToStack(message);
            }
        } else if (notifyMissedCall(message)) {
            synchronized (mMissedCalls) {
                pushMissedCall(message);
            }
        }
    }

    private AtomicInteger getBacklogMessageCounter(Conversation conversation) {
        synchronized (mBacklogMessageCounter) {
            if (!mBacklogMessageCounter.containsKey(conversation)) {
                mBacklogMessageCounter.put(conversation, new AtomicInteger(0));
            }
            return mBacklogMessageCounter.get(conversation);
        }
    }

    void pushFromDirectReply(final Message message) {
        synchronized (notifications) {
            pushToStack(message);
            updateNotification(false);
        }
    }

    public void finishBacklog(boolean notify, Account account) {
        synchronized (notifications) {
            mXmppConnectionService.updateUnreadCountBadge();
            if (account == null || !notify) {
                updateNotification(notify);
            } else {
                final int count;
                final List<String> conversations;
                synchronized (this.mBacklogMessageCounter) {
                    conversations = getBacklogConversations(account);
                    count = getBacklogMessageCount(account);
                }
                updateNotification(count > 0, conversations);
            }
        }
        synchronized (mMissedCalls) {
            updateMissedCallNotifications(mMissedCalls.keySet());
        }
    }

    private List<String> getBacklogConversations(Account account) {
        final List<String> conversations = new ArrayList<>();
        for (Map.Entry<Conversation, AtomicInteger> entry : mBacklogMessageCounter.entrySet()) {
            if (entry.getKey().getAccount() == account) {
                conversations.add(entry.getKey().getUuid());
            }
        }
        return conversations;
    }

    private int getBacklogMessageCount(Account account) {
        int count = 0;
        for (Iterator<Map.Entry<Conversation, AtomicInteger>> it =
             mBacklogMessageCounter.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Conversation, AtomicInteger> entry = it.next();
            if (entry.getKey().getAccount() == account) {
                count += entry.getValue().get();
                it.remove();
            }
        }
        return count;
    }

    void finishBacklog() {
        finishBacklog(false, null);
    }

    private void pushToStack(final Message message) {
        final String conversationUuid = message.getConversationUuid();
        if (notifications.containsKey(conversationUuid)) {
            notifications.get(conversationUuid).add(message);
        } else {
            final ArrayList<Message> mList = new ArrayList<>();
            mList.add(message);
            notifications.put(conversationUuid, mList);
        }
    }

    public void push(final Message message) {
        synchronized (CATCHUP_LOCK) {
            final XmppConnection connection =
                    message.getConversation().getAccount().getXmppConnection();
            if (connection != null && connection.isWaitingForSmCatchup()) {
                connection.incrementSmCatchupMessageCounter();
                pushFromBacklog(message);
            } else {
                pushNow(message);
            }
        }
    }

    public void pushReactionNotification(
            final Message originalMessage,
            final Collection<String> newReactions,
            @Nullable final Jid from,
            @Nullable final Jid trueJid,
            @Nullable final String occupantId) {
        if (newReactions == null || newReactions.isEmpty()) {
            return;
        }
        if (originalMessage.getTimer() != Message.TIMER_NONE
                && originalMessage.getTimeRemaining() <= 0) {
            return;
        }
        final Conversation conversation = (Conversation) originalMessage.getConversation();
        if (conversation.isMuted()
                || (conversation.isWithStranger() && !notificationsFromStrangers())) {
            return;
        }
        final Message notificationMessage =
                Message.createStatusMessage(
                        conversation,
                        buildReactionNotificationBody(
                                conversation,
                                originalMessage,
                                newReactions,
                                from,
                                trueJid,
                                occupantId));
        notificationMessage.setUuid(UUID.randomUUID().toString());
        notificationMessage.setTime(System.currentTimeMillis());
        notificationMessage.setType(Message.TYPE_PRIVATE);
        notificationMessage.markUnread();
        if (from != null) {
            notificationMessage.setCounterpart(from);
        }
        if (trueJid != null) {
            notificationMessage.setTrueCounterpart(trueJid.asBareJid());
        }
        if (occupantId != null) {
            notificationMessage.setOccupantId(occupantId);
        }
        push(notificationMessage);
    }

    public void pushFailedDelivery(final Message message) {
        final Conversation conversation = (Conversation) message.getConversation();
        final boolean isScreenLocked = !mXmppConnectionService.isScreenLocked();
        if (this.mIsInForeground
                && isScreenLocked
                && this.mOpenConversation == message.getConversation()) {
            return;
        }
        final PendingIntent pendingIntent = createContentIntent(conversation);
        final int notificationId =
                generateRequestCode(conversation, 0) + DELIVERY_FAILED_NOTIFICATION_ID;
        final int failedDeliveries = conversation.countFailedDeliveries();
        final Notification notification =
                new Builder(mXmppConnectionService, "delivery_failed")
                        .setContentTitle(conversation.getName())
                        .setAutoCancel(true)
                        .setSmallIcon(R.drawable.ic_error_24dp)
                        .setContentText(
                                mXmppConnectionService
                                        .getResources()
                                        .getQuantityText(
                                                R.plurals.some_messages_could_not_be_delivered,
                                                failedDeliveries))
                        .setGroup("delivery_failed")
                        .setContentIntent(pendingIntent)
                        .build();
        final Notification summaryNotification =
                new Builder(mXmppConnectionService, "delivery_failed")
                        .setContentTitle(
                                mXmppConnectionService.getString(R.string.failed_deliveries))
                        .setContentText(
                                mXmppConnectionService
                                        .getResources()
                                        .getQuantityText(
                                                R.plurals.some_messages_could_not_be_delivered,
                                                1024))
                        .setSmallIcon(R.drawable.ic_error_24dp)
                        .setGroup("delivery_failed")
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .build();
        notify(notificationId, notification);
        notify(DELIVERY_FAILED_NOTIFICATION_ID, summaryNotification);
    }

    public synchronized void startRinging(
            final AbstractJingleConnection.Id id, final Set<Media> media) {
        showIncomingCallNotification(id, media, false);
        HeadsetRinger.start(
                mXmppConnectionService,
                new AppSettings(mXmppConnectionService).getRingtone(),
                () -> answerFromHeadset(id));
    }

    /**
     * A headset button press while the call rings. Audio calls only -- a press must not turn the
     * camera on -- and only with the microphone already granted, since asking needs the screen.
     */
    private void answerFromHeadset(final AbstractJingleConnection.Id id) {
        final var rtpConnection =
                mXmppConnectionService.getJingleConnectionManager().getOngoingRtpConnection();
        if (rtpConnection == null || !id.equals(rtpConnection.getId())) {
            return;
        }
        if (!Media.audioOnly(rtpConnection.getMedia())) {
            return;
        }
        if (ContextCompat.checkSelfPermission(mXmppConnectionService, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            rtpConnection.acceptCall();
        } catch (final IllegalStateException e) {
            Log.w(Config.LOGTAG, "could not answer from the headset button", e);
        }
    }

    private void showIncomingCallNotification(
            final AbstractJingleConnection.Id id,
            final Set<Media> media,
            final boolean onlyAlertOnce) {
        final Intent fullScreenIntent =
                new Intent(mXmppConnectionService, RtpSessionActivity.class);
        fullScreenIntent.putExtra(
                RtpSessionActivity.EXTRA_ACCOUNT,
                id.account.getJid().asBareJid().toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final int channelIteration;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            channelIteration = getCurrentIncomingCallChannelIteration(mXmppConnectionService).or(0);
        } else {
            channelIteration = 0;
        }
        final var channelId = INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + channelIteration;
        final NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mXmppConnectionService, channelId);
        if (media.contains(Media.VIDEO)) {
            builder.setSmallIcon(R.drawable.ic_videocam_24dp);
            builder.setContentTitle(
                    mXmppConnectionService.getString(R.string.rtp_state_incoming_video_call));
        } else {
            builder.setSmallIcon(R.drawable.ic_call_24dp);
            builder.setContentTitle(
                    mXmppConnectionService.getString(R.string.rtp_state_incoming_call));
        }
        final Contact contact = id.getContact();
        builder.setLargeIcon(
                mXmppConnectionService
                        .getAvatarService()
                        .getForListItem(contact, AvatarService.getSystemUiAvatarSize(mXmppConnectionService)));
        final Uri systemAccount = contact.getSystemAccount();
        if (systemAccount != null) {
            builder.addPerson(systemAccount.toString());
        }
        if (!onlyAlertOnce) {
            final var appSettings = new AppSettings(mXmppConnectionService);
            final var ringtone = appSettings.getRingtone();
            if (ringtone != null) {
                builder.setSound(ringtone, AudioManager.STREAM_RING);
            }
            builder.setVibrate(CALL_PATTERN);
            SoundPoolManager.getInstance(mXmppConnectionService).vibrateIfNeeded();
        }
        builder.setOnlyAlertOnce(onlyAlertOnce);
        builder.setContentText(id.account.getRoster().getContact(id.with).getDisplayName());
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        final PendingIntent pendingIntent = createPendingRtpSession(id, Intent.ACTION_VIEW, 101);
        builder.setFullScreenIntent(pendingIntent, true);
        builder.setContentIntent(pendingIntent); // old androids need this?
        builder.setOngoing(true);
        builder.addAction(
                new NotificationCompat.Action.Builder(
                        R.drawable.ic_call_end_24dp,
                        mXmppConnectionService.getString(R.string.dismiss_call),
                        createCallAction(
                                id.sessionId,
                                XmppConnectionService.ACTION_DISMISS_CALL,
                                102))
                        .build());
        builder.addAction(
                new NotificationCompat.Action.Builder(
                        R.drawable.ic_call_24dp,
                        mXmppConnectionService.getString(R.string.answer_call),
                        createPendingRtpSession(
                                id, RtpSessionActivity.ACTION_ACCEPT_CALL, 103))
                        .build());
        modifyIncomingCall(builder);
        final Notification notification = builder.build();
        notification.audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        notification.flags = notification.flags | Notification.FLAG_INSISTENT;
        notify(INCOMING_CALL_NOTIFICATION_ID, notification);
    }

    public Notification getOngoingCallNotification(
            final XmppConnectionService.OngoingCall ongoingCall) {
        final AbstractJingleConnection.Id id = ongoingCall.id;
        final NotificationCompat.Builder builder =
                new NotificationCompat.Builder(mXmppConnectionService, "ongoing_calls");
        if (ongoingCall.media.contains(Media.VIDEO)) {
            builder.setSmallIcon(R.drawable.ic_videocam_24dp);
            if (ongoingCall.reconnecting) {
                builder.setContentTitle(
                        mXmppConnectionService.getString(R.string.reconnecting_video_call));
            } else {
                builder.setContentTitle(
                        mXmppConnectionService.getString(R.string.ongoing_video_call));
            }
        } else {
            builder.setSmallIcon(R.drawable.ic_call_24dp);
            if (ongoingCall.reconnecting) {
                builder.setContentTitle(
                        mXmppConnectionService.getString(R.string.reconnecting_call));
            } else {
                builder.setContentTitle(mXmppConnectionService.getString(R.string.ongoing_call));
            }
        }
        builder.setContentText(id.account.getRoster().getContact(id.with).getDisplayName());
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        // Request code 105, not the 101 the incoming-call notification uses. A PendingIntent is
        // identified by (context, requestCode, intent, flags), and the intent is matched with
        // Intent.filterEquals(), which ignores extras. Both notifications deep link into
        // RtpSessionActivity with ACTION_VIEW and no data, so on 101 they are one PendingIntent:
        // FLAG_UPDATE_CURRENT means whichever is built last rewrites the extras of the other, and
        // the notification still in the shade starts naming a session that is not its own. A
        // separate code lets the two carry their own session ids.
        builder.setContentIntent(createPendingRtpSession(id, Intent.ACTION_VIEW, 105));
        builder.setOngoing(true);
        builder.addAction(
                new NotificationCompat.Action.Builder(
                        R.drawable.ic_call_end_24dp,
                        mXmppConnectionService.getString(R.string.hang_up),
                        createCallAction(
                                id.sessionId, XmppConnectionService.ACTION_END_CALL, 104))
                        .build());
        builder.setLocalOnly(true);
        return builder.build();
    }

    private PendingIntent createPendingRtpSession(
            final AbstractJingleConnection.Id id, final String action, final int requestCode) {
        final Intent fullScreenIntent =
                new Intent(mXmppConnectionService, RtpSessionActivity.class);
        fullScreenIntent.setAction(action);
        fullScreenIntent.putExtra(
                RtpSessionActivity.EXTRA_ACCOUNT,
                id.account.getJid().asBareJid().toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toEscapedString());
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId);
        return PendingIntent.getActivity(
                mXmppConnectionService,
                requestCode,
                fullScreenIntent,
                s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void cancelIncomingCallNotification() {
        cancel(INCOMING_CALL_NOTIFICATION_ID);
        SoundPoolManager.getInstance(mXmppConnectionService).stopRinging();
        HeadsetRinger.stop();
    }

    public boolean stopSoundAndVibration() {
        final var jingleRtpConnection =
                mXmppConnectionService.getJingleConnectionManager().getOngoingRtpConnection();
        if (jingleRtpConnection == null) {
            return false;
        }
        final var notificationManager = mXmppConnectionService.getSystemService(NotificationManager.class);
        if (Iterables.any(
                Arrays.asList(notificationManager.getActiveNotifications()),
                n -> n.getId() == INCOMING_CALL_NOTIFICATION_ID)) {
            showIncomingCallNotification(
                    jingleRtpConnection.getId(), jingleRtpConnection.getMedia(), true);
            SoundPoolManager.getInstance(mXmppConnectionService).stopRinging();
            HeadsetRinger.stop();
            return true;
        }
        return false;
    }

    public static void cancelIncomingCallNotification(final Context context) {
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(context);
        try {
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID);
        } catch (RuntimeException e) {
        }
        SoundPoolManager.getInstance(context).stopRinging();
        HeadsetRinger.stop();
    }

    private void pushNow(final Message message) {
        mXmppConnectionService.updateUnreadCountBadge();
        if (!notifyMessage(message)) {
            return;
        }
        final boolean isScreenLocked = mXmppConnectionService.isScreenLocked();
        if (this.mIsInForeground
                && !isScreenLocked
                && this.mOpenConversation == message.getConversation()) {
            return;
        }
        synchronized (notifications) {
            pushToStack(message);
            final Conversational conversation = message.getConversation();
            final Account account = conversation.getAccount();
            final var chatOverviewInForeground =
                    (this.mIsInForeground && this.mOpenConversation == null) && !isScreenLocked;
            final boolean doNotify;
            if (inMiniGracePeriod(account)) {
                doNotify = false;
            } else {
                doNotify = true;
            }
            updateNotification(doNotify, Collections.singletonList(conversation.getUuid()));
        }
    }

    private void pushMissedCall(final Message message) {
        final Conversational conversation = message.getConversation();
        final MissedCallsInfo info = mMissedCalls.get(conversation);
        if (info == null) {
            mMissedCalls.put(conversation, new MissedCallsInfo(message.getTimeSent()));
        } else {
            info.newMissedCall(message.getTimeSent());
        }
    }

    public void pushMissedCallNow(final Message message) {
        synchronized (mMissedCalls) {
            pushMissedCall(message);
            updateMissedCallNotifications(Collections.singleton(message.getConversation()));
        }
    }

    public void clear(final Conversation conversation) {
        clearMessages(conversation);
        clearMissedCalls(conversation);
    }

    public void clearMessages() {
        synchronized (notifications) {
            for (ArrayList<Message> messages : notifications.values()) {
                markAsReadIfHasDirectReply(messages);
            }
            notifications.clear();
            updateNotification(false);
        }
    }

    public void clearMessages(final Conversation conversation) {
        synchronized (this.mBacklogMessageCounter) {
            this.mBacklogMessageCounter.remove(conversation);
        }
        synchronized (notifications) {
            markAsReadIfHasDirectReply(conversation);
            if (notifications.remove(conversation.getUuid()) != null) {
                cancel(conversation.getUuid(), NOTIFICATION_ID);
                updateNotification(false, null, true);
            }
        }
    }

    public void clearMissedCall(final Message message) {
        synchronized (mMissedCalls) {
            final Iterator<Map.Entry<Conversational, MissedCallsInfo>> iterator =
                    mMissedCalls.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Conversational, MissedCallsInfo> entry = iterator.next();
                final Conversational conversational = entry.getKey();
                final MissedCallsInfo missedCallsInfo = entry.getValue();
                if (conversational.getUuid().equals(message.getConversation().getUuid())) {
                    if (missedCallsInfo.removeMissedCall()) {
                        cancel(conversational.getUuid(), MISSED_CALL_NOTIFICATION_ID);
                        iterator.remove();
                    }
                }
            }
            updateMissedCallNotifications(null);
        }
    }

    public void clearMissedCalls() {
        synchronized (mMissedCalls) {
            for (final Conversational conversation : mMissedCalls.keySet()) {
                cancel(conversation.getUuid(), MISSED_CALL_NOTIFICATION_ID);
            }
            mMissedCalls.clear();
            updateMissedCallNotifications(null);
        }
    }

    public void clearMissedCalls(final Conversation conversation) {
        synchronized (mMissedCalls) {
            if (mMissedCalls.remove(conversation) != null) {
                cancel(conversation.getUuid(), MISSED_CALL_NOTIFICATION_ID);
                updateMissedCallNotifications(null);
            }
        }
    }

    private void markAsReadIfHasDirectReply(final Conversation conversation) {
        markAsReadIfHasDirectReply(notifications.get(conversation.getUuid()));
    }

    private void markAsReadIfHasDirectReply(final ArrayList<Message> messages) {
        if (messages != null && !messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last.getStatus() != Message.STATUS_RECEIVED) {
                if (mXmppConnectionService.markRead((Conversation) last.getConversation(), false)) {
                    mXmppConnectionService.updateConversationUi();
                }
            }
        }
    }

    private void setNotificationColor(final Builder mBuilder) {
        mBuilder.setColor(ContextCompat.getColor(mXmppConnectionService, R.color.blue_steel));
    }

    public void updateNotification() {
        synchronized (notifications) {
            updateNotification(false);
        }
    }

    private void updateNotification(final boolean notify) {
        updateNotification(notify, null, false);
    }

    private void updateNotification(final boolean notify, final List<String> conversations) {
        updateNotification(notify, conversations, false);
    }

    private void updateNotification(
            final boolean notify, final List<String> conversations, final boolean summaryOnly) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);

        final boolean notifyOnlyOneChild =
                notify
                        && conversations != null
                        && conversations.size()
                        == 1; // if this check is changed to > 0 catchup messages will
        // create one notification per conversation

        if (notifications.isEmpty()) {
            cancel(NOTIFICATION_ID);
        } else {
            final Builder mBuilder;
            if (notifications.size() == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                mBuilder =
                        buildSingleConversations(notifications.values().iterator().next(), notify);
                modifyForSoundVibrationAndLight(mBuilder, notify, preferences);
                notify(NOTIFICATION_ID, mBuilder.build());
            } else {
                mBuilder = buildMultipleConversation(notify);
                if (notifyOnlyOneChild) {
                    mBuilder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
                }
                modifyForSoundVibrationAndLight(mBuilder, notify, preferences);
                if (!summaryOnly) {
                    for (Map.Entry<String, ArrayList<Message>> entry : notifications.entrySet()) {
                        String uuid = entry.getKey();
                        final boolean notifyThis =
                                notifyOnlyOneChild ? conversations.contains(uuid) : notify;
                        Builder singleBuilder =
                                buildSingleConversations(entry.getValue(), notifyThis);
                        if (!notifyOnlyOneChild) {
                            singleBuilder.setGroupAlertBehavior(
                                    NotificationCompat.GROUP_ALERT_SUMMARY);
                        }
                        modifyForSoundVibrationAndLight(singleBuilder, notifyThis, preferences);
                        singleBuilder.setGroup(MESSAGES_GROUP);
                        setNotificationColor(singleBuilder);
                        notify(entry.getKey(), NOTIFICATION_ID, singleBuilder.build());
                    }
                }
                notify(NOTIFICATION_ID, mBuilder.build());
            }
        }
    }

    private void updateMissedCallNotifications(final Set<Conversational> update) {
        if (mMissedCalls.isEmpty()) {
            cancel(MISSED_CALL_NOTIFICATION_ID);
            return;
        }
        if (mMissedCalls.size() == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            final Conversational conversation = mMissedCalls.keySet().iterator().next();
            final MissedCallsInfo info = mMissedCalls.values().iterator().next();
            final Notification notification = missedCall(conversation, info);
            notify(MISSED_CALL_NOTIFICATION_ID, notification);
        } else {
            final Notification summary = missedCallsSummary();
            notify(MISSED_CALL_NOTIFICATION_ID, summary);
            if (update != null) {
                for (final Conversational conversation : update) {
                    final MissedCallsInfo info = mMissedCalls.get(conversation);
                    if (info != null) {
                        final Notification notification = missedCall(conversation, info);
                        notify(conversation.getUuid(), MISSED_CALL_NOTIFICATION_ID, notification);
                    }
                }
            }
        }
    }

    private void modifyForSoundVibrationAndLight(
            final Builder mBuilder, final boolean notify, final SharedPreferences preferences) {
        final Resources resources = mXmppConnectionService.getResources();
        final String ringtone =
                preferences.getString(
                        AppSettings.NOTIFICATION_RINGTONE,
                        resources.getString(R.string.notification_ringtone));
        final boolean vibrate =
                preferences.getBoolean(
                        AppSettings.NOTIFICATION_VIBRATE,
                        resources.getBoolean(R.bool.vibrate_on_notification));
        final boolean led =
                preferences.getBoolean(
                        AppSettings.NOTIFICATION_LED, resources.getBoolean(R.bool.led));
        // AppSettings.NOTIFICATION_HEADS_UP used to be read here and then ignored. The read is
        // gone, but the preference is still live in preferences_notifications.xml and still
        // shown to the user as "Heads-up Notifications" — and this was its only consumer, so
        // that toggle currently does nothing. Wire it up here or drop it from the screen.
        if (notify) {
            if (vibrate) {
                final int dat = 70;
                final long[] pattern = {0, 3 * dat, dat, dat};
                mBuilder.setVibrate(pattern);
            } else {
                mBuilder.setVibrate(new long[] {0});
            }
            Uri uri = Uri.parse(ringtone);
            try {
                mBuilder.setSound(fixRingtoneUri(uri));
            } catch (SecurityException e) {
            }
        } else {
            mBuilder.setLocalOnly(true);
        }
        mBuilder.setCategory(Notification.CATEGORY_MESSAGE);
        mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);
        setNotificationColor(mBuilder);
        mBuilder.setDefaults(0);
        if (led) {
            mBuilder.setLights(LED_COLOR, 2000, 3000);
        }
    }

    private void modifyIncomingCall(final Builder mBuilder) {
        mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);
        setNotificationColor(mBuilder);
        mBuilder.setLights(LED_COLOR, 2000, 3000);
    }

    private Uri fixRingtoneUri(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "file".equals(uri.getScheme())) {
            return FileBackend.getUriForFile(mXmppConnectionService, new File(uri.getPath()));
        } else {
            return uri;
        }
    }

    private Notification missedCallsSummary() {
        final Builder publicBuilder = buildMissedCallsSummary(true);
        final Builder builder = buildMissedCallsSummary(false);
        builder.setPublicVersion(publicBuilder.build());
        return builder.build();
    }

    private Builder buildMissedCallsSummary(boolean publicVersion) {
        final Builder builder =
                new NotificationCompat.Builder(mXmppConnectionService, "missed_calls");
        int totalCalls = 0;
        final List<String> names = new ArrayList<>();
        long lastTime = 0;
        for (final Map.Entry<Conversational, MissedCallsInfo> entry : mMissedCalls.entrySet()) {
            final Conversational conversation = entry.getKey();
            final MissedCallsInfo missedCallsInfo = entry.getValue();
            names.add(conversation.getContact().getDisplayName());
            totalCalls += missedCallsInfo.getNumberOfCalls();
            lastTime = Math.max(lastTime, missedCallsInfo.getLastTime());
        }
        final String title =
                (totalCalls == 1)
                        ? mXmppConnectionService.getString(R.string.missed_call)
                        : (mMissedCalls.size() == 1)
                        ? mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                R.plurals.n_missed_calls, totalCalls, totalCalls)
                        : mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                R.plurals.n_missed_calls_from_m_contacts,
                                mMissedCalls.size(),
                                totalCalls,
                                mMissedCalls.size());
        builder.setContentTitle(title);
        builder.setTicker(title);
        if (!publicVersion) {
            builder.setContentText(Joiner.on(", ").join(names));
        }
        builder.setSmallIcon(R.drawable.ic_call_missed_24db);
        builder.setGroupSummary(true);
        builder.setGroup(MISSED_CALLS_GROUP);
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setWhen(lastTime);
        if (!mMissedCalls.isEmpty()) {
            final Conversational firstConversation = mMissedCalls.keySet().iterator().next();
            builder.setContentIntent(createContentIntent(firstConversation));
        }
        builder.setDeleteIntent(createMissedCallsDeleteIntent(null));
        modifyMissedCall(builder);
        return builder;
    }

    private Notification missedCall(final Conversational conversation, final MissedCallsInfo info) {
        final Builder publicBuilder = buildMissedCall(conversation, info, true);
        final Builder builder = buildMissedCall(conversation, info, false);
        builder.setPublicVersion(publicBuilder.build());
        return builder.build();
    }

    private Builder buildMissedCall(
            final Conversational conversation, final MissedCallsInfo info, boolean publicVersion) {
        final Builder builder =
                new NotificationCompat.Builder(mXmppConnectionService, "missed_calls");
        final String title =
                (info.getNumberOfCalls() == 1)
                        ? mXmppConnectionService.getString(R.string.missed_call)
                        : mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                R.plurals.n_missed_calls,
                                info.getNumberOfCalls(),
                                info.getNumberOfCalls());
        builder.setContentTitle(title);
        final String name = conversation.getContact().getDisplayName();
        if (publicVersion) {
            builder.setTicker(title);
        } else {
            builder.setTicker(
                    mXmppConnectionService
                            .getResources()
                            .getQuantityString(
                                    R.plurals.n_missed_calls_from_x,
                                    info.getNumberOfCalls(),
                                    info.getNumberOfCalls(),
                                    name));
            builder.setContentText(name);
        }
        builder.setSmallIcon(R.drawable.ic_call_missed_24db);
        builder.setGroup(MISSED_CALLS_GROUP);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setWhen(info.getLastTime());
        builder.setContentIntent(createContentIntent(conversation));
        builder.setDeleteIntent(createMissedCallsDeleteIntent(conversation));
        if (!publicVersion && conversation instanceof Conversation) {
            builder.setLargeIcon(
                    mXmppConnectionService
                            .getAvatarService()
                            .getForConversation(
                                    (Conversation) conversation,
                                    AvatarService.getSystemUiAvatarSize(mXmppConnectionService)));
        }
        modifyMissedCall(builder);
        return builder;
    }

    private void modifyMissedCall(final Builder builder) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService);
        final Resources resources = mXmppConnectionService.getResources();
        final boolean led = preferences.getBoolean("led", resources.getBoolean(R.bool.led));
        if (led) {
            builder.setLights(LED_COLOR, 2000, 3000);
        }
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setSound(null);
        setNotificationColor(builder);
    }

    private Builder buildMultipleConversation(final boolean notify) {
        final Builder mBuilder =
                new NotificationCompat.Builder(
                        mXmppConnectionService,
                        notify ? MESSAGES_NOTIFICATION_CHANNEL : "silent_messages");
        final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        style.setBigContentTitle(
                mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                R.plurals.x_unread_conversations,
                                notifications.size(),
                                notifications.size()));
        final List<String> names = new ArrayList<>();
        Conversation conversation = null;
        for (final ArrayList<Message> messages : notifications.values()) {
            if (messages.isEmpty()) {
                continue;
            }
            conversation = (Conversation) messages.get(0).getConversation();
            final String name = conversation.getName().toString();
            SpannableString styledString;
            if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
                int count = messages.size();
                styledString =
                        new SpannableString(
                                name
                                        + ": "
                                        + mXmppConnectionService
                                        .getResources()
                                        .getQuantityString(
                                                R.plurals.x_messages, count, count));
                styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                style.addLine(styledString);
            } else {
                styledString =
                        new SpannableString(
                                name
                                        + ": "
                                        + UIHelper.getMessagePreview(
                                        mXmppConnectionService, messages.get(0))
                                        .first);
                styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                style.addLine(styledString);
            }
            names.add(name);
        }
        final String contentTitle =
                mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                R.plurals.x_unread_conversations,
                                notifications.size(),
                                notifications.size());
        mBuilder.setContentTitle(contentTitle);
        mBuilder.setTicker(contentTitle);
        mBuilder.setContentText(Joiner.on(", ").join(names));
        mBuilder.setStyle(style);
        if (conversation != null) {
            mBuilder.setContentIntent(createContentIntent(conversation));
        }
        mBuilder.setGroupSummary(true);
        mBuilder.setGroup(MESSAGES_GROUP);
        mBuilder.setDeleteIntent(createDeleteIntent(null));
        mBuilder.setSmallIcon(R.drawable.ic_gchat_icon);
        return mBuilder;
    }

    private Builder buildSingleConversations(
            final ArrayList<Message> messages, final boolean notify) {
        final var channel = notify ? MESSAGES_NOTIFICATION_CHANNEL : "silent_messages";
        final Builder notificationBuilder =
                new NotificationCompat.Builder(mXmppConnectionService, channel);
        if (messages.isEmpty()) {
            return notificationBuilder;
        }
        final Conversation conversation = (Conversation) messages.get(0).getConversation();
        notificationBuilder.setLargeIcon(
                mXmppConnectionService
                        .getAvatarService()
                        .getForConversation(
                                conversation,
                                AvatarService.getSystemUiAvatarSize(mXmppConnectionService)));
        notificationBuilder.setContentTitle(conversation.getName());
        if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
            int count = messages.size();
            notificationBuilder.setContentText(
                    mXmppConnectionService
                            .getResources()
                            .getQuantityString(R.plurals.x_messages, count, count));
        } else {
            final Message message;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                    && (message = getImage(messages)) != null) {
                modifyForImage(notificationBuilder, message, messages);
            } else {
                modifyForTextOnly(notificationBuilder, messages);
            }
            RemoteInput remoteInput =
                    new RemoteInput.Builder("text_reply")
                            .setLabel(UIHelper.getMessageHint(mXmppConnectionService, conversation))
                            .build();
            PendingIntent markAsReadPendingIntent = createReadPendingIntent(conversation);
            NotificationCompat.Action markReadAction =
                    new NotificationCompat.Action.Builder(
                            R.drawable.ic_mark_chat_read_24dp,
                            mXmppConnectionService.getString(R.string.mark_as_read),
                            markAsReadPendingIntent)
                            .setSemanticAction(
                                    NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                            .setShowsUserInterface(false)
                            .build();
            final String replyLabel = mXmppConnectionService.getString(R.string.reply);
            final String lastMessageUuid = Iterables.getLast(messages).getUuid();
            final NotificationCompat.Action replyAction =
                    new NotificationCompat.Action.Builder(
                            R.drawable.ic_send_24dp,
                            replyLabel,
                            createReplyIntent(conversation, lastMessageUuid, false))
                            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                            .setShowsUserInterface(false)
                            .addRemoteInput(remoteInput)
                            .build();
            final NotificationCompat.Action wearReplyAction =
                    new NotificationCompat.Action.Builder(
                            R.drawable.ic_reply_24dp,
                            replyLabel,
                            createReplyIntent(conversation, lastMessageUuid, true))
                            .addRemoteInput(remoteInput)
                            .build();
            notificationBuilder.extend(
                    new NotificationCompat.WearableExtender().addAction(wearReplyAction));
            int addedActionsCount = 1;
            notificationBuilder.addAction(markReadAction);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                notificationBuilder.addAction(replyAction);
                ++addedActionsCount;
            }

            if (displaySnoozeAction(messages)) {
                String label = mXmppConnectionService.getString(R.string.snooze);
                PendingIntent pendingSnoozeIntent = createSnoozeIntent(conversation);
                NotificationCompat.Action snoozeAction =
                        new NotificationCompat.Action.Builder(
                                R.drawable.ic_notifications_paused_24dp,
                                label,
                                pendingSnoozeIntent)
                                .build();
                notificationBuilder.addAction(snoozeAction);
                ++addedActionsCount;
            }
            if (addedActionsCount < 3) {
                final Message firstLocationMessage = getFirstLocationMessage(messages);
                if (firstLocationMessage != null) {
                    final PendingIntent pendingShowLocationIntent =
                            createShowLocationIntent(firstLocationMessage);
                    if (pendingShowLocationIntent != null) {
                        final String label =
                                mXmppConnectionService
                                        .getResources()
                                        .getString(R.string.show_location);
                        NotificationCompat.Action locationAction =
                                new NotificationCompat.Action.Builder(
                                        R.drawable.ic_location_pin_24dp,
                                        label,
                                        pendingShowLocationIntent)
                                        .build();
                        notificationBuilder.addAction(locationAction);
                        ++addedActionsCount;
                    }
                }
            }
            if (addedActionsCount < 3) {
                Message firstDownloadableMessage = getFirstDownloadableMessage(messages);
                if (firstDownloadableMessage != null) {
                    String label =
                            mXmppConnectionService
                                    .getResources()
                                    .getString(
                                            R.string.download_x_file,
                                            UIHelper.getFileDescriptionString(
                                                    mXmppConnectionService,
                                                    firstDownloadableMessage));
                    PendingIntent pendingDownloadIntent =
                            createDownloadIntent(firstDownloadableMessage);
                    NotificationCompat.Action downloadAction =
                            new NotificationCompat.Action.Builder(
                                    R.drawable.ic_download_24dp,
                                    label,
                                    pendingDownloadIntent)
                                    .build();
                    notificationBuilder.addAction(downloadAction);
                    ++addedActionsCount;
                }
            }
        }
        final ShortcutInfoCompat info;
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            final Contact contact = conversation.getContact();
            final Uri systemAccount = contact.getSystemAccount();
            if (systemAccount != null) {
                notificationBuilder.addPerson(systemAccount.toString());
            }
            info = mXmppConnectionService.getShortcutService().getShortcutInfoCompat(contact);
        } else {
            info =
                    mXmppConnectionService
                            .getShortcutService()
                            .getShortcutInfoCompat(conversation.getMucOptions());
        }
        notificationBuilder.setWhen(conversation.getLatestMessage().getTimeSent());
        notificationBuilder.setSmallIcon(R.drawable.ic_gchat_icon);
        notificationBuilder.setDeleteIntent(createDeleteIntent(conversation));
        notificationBuilder.setContentIntent(createContentIntent(conversation));
        if (channel.equals(MESSAGES_NOTIFICATION_CHANNEL) && info != null) {
            // when do not want 'customized' notifications for silent notifications in their
            // respective channels
            notificationBuilder.setShortcutInfo(info);
            if (Build.VERSION.SDK_INT >= 30) {
                mXmppConnectionService
                        .getSystemService(ShortcutManager.class)
                        .pushDynamicShortcut(info.toShortcutInfo());
            }
        }
        return notificationBuilder;
    }

    private String buildReactionNotificationBody(
            final Conversation conversation,
            final Message originalMessage,
            final Collection<String> newReactions,
            @Nullable final Jid from,
            @Nullable final Jid trueJid,
            @Nullable final String occupantId) {
        final String actorName =
                resolveReactionDisplayName(conversation, from, trueJid, occupantId);
        final String joinedReactions = Joiner.on(" ").join(newReactions);
        final CharSequence preview =
                UIHelper.getMessagePreview(mXmppConnectionService, originalMessage).first;
        final String previewText = preview == null ? "" : preview.toString();
        if (!Strings.isNullOrEmpty(previewText)) {
            return mXmppConnectionService.getString(
                    R.string.reaction_notification_with_preview,
                    actorName,
                    joinedReactions,
                    previewText);
        }
        return mXmppConnectionService.getString(
                R.string.reaction_notification_without_preview,
                actorName,
                joinedReactions);
    }

    private String resolveReactionDisplayName(
            final Conversation conversation,
            @Nullable final Jid from,
            @Nullable final Jid trueJid,
            @Nullable final String occupantId) {
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            final Contact contact = conversation.getContact();
            if (contact != null) {
                return contact.getDisplayName();
            } else if (from != null) {
                return from.asBareJid().toString();
            } else {
                return conversation.getName().toString();
            }
        }
        final MucOptions mucOptions = conversation.getMucOptions();
        MucOptions.User user = null;
        if (!Strings.isNullOrEmpty(occupantId)) {
            user = mucOptions.findUserByOccupantId(occupantId);
        }
        if (user == null && trueJid != null) {
            user = mucOptions.findOrCreateUserByRealJid(trueJid.asBareJid(), from);
        }
        if (user == null && from != null) {
            user = mucOptions.findUserByFullJid(from);
        }
        if (user != null) {
            return user.getName();
        }
        if (from != null) {
            return UIHelper.getDisplayedMucCounterpart(from);
        }
        return conversation.getName().toString();
    }

    private void modifyForImage(
            final Builder builder, final Message message, final ArrayList<Message> messages) {
        try {
            final Bitmap bitmap =
                    mXmppConnectionService
                            .getFileBackend()
                            .getThumbnailBitmap(message, mXmppConnectionService.getResources(), getPixel(288), false);            final ArrayList<Message> tmp = new ArrayList<>();
            for (final Message msg : messages) {
                if (msg.getType() == Message.TYPE_TEXT && msg.getTransferable() == null) {
                    tmp.add(msg);
                }
            }
            final BigPictureStyle bigPictureStyle = new NotificationCompat.BigPictureStyle();
            bigPictureStyle.bigPicture(bitmap);
            if (!tmp.isEmpty()) {
                CharSequence text = getMergedBodies(tmp);
                bigPictureStyle.setSummaryText(text);
                builder.setContentText(text);
                builder.setTicker(text);
            } else {
                final String description =
                        UIHelper.getFileDescriptionString(mXmppConnectionService, message);
                builder.setContentText(description);
                builder.setTicker(description);
            }
            builder.setStyle(bigPictureStyle);
        } catch (final IOException e) {
            modifyForTextOnly(builder, messages);
        }
    }

    private Person getPerson(Message message) {
        final Contact contact = message.getContact();
        final Person.Builder builder = new Person.Builder();
        if (contact != null) {
            builder.setName(contact.getDisplayName());
            final Uri uri = contact.getSystemAccount();
            if (uri != null) {
                builder.setUri(uri.toString());
            }
        } else {
            builder.setName(UIHelper.getMessageDisplayName(message));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIcon(
                    IconCompat.createWithBitmap(
                            mXmppConnectionService
                                    .getAvatarService()
                                    .getForMessage(
                                            message,
                                            AvatarService.getSystemUiAvatarSize(
                                                    mXmppConnectionService),
                                            false)));
        }
        return builder.build();
    }

    private void modifyForTextOnly(final Builder builder, final ArrayList<Message> messages) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final Conversation conversation = (Conversation) messages.get(0).getConversation();
            final Person.Builder meBuilder =
                    new Person.Builder().setName(mXmppConnectionService.getString(R.string.me));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                meBuilder.setIcon(
                        IconCompat.createWithBitmap(
                                mXmppConnectionService
                                        .getAvatarService()
                                        .getForAccount(
                                                conversation.getAccount(),
                                                AvatarService.getSystemUiAvatarSize(
                                                        mXmppConnectionService))));
            }
            final Person me = meBuilder.build();
            NotificationCompat.MessagingStyle messagingStyle =
                    new NotificationCompat.MessagingStyle(me);
            final boolean multiple = conversation.getMode() == Conversation.MODE_MULTI;
            if (multiple) {
                messagingStyle.setConversationTitle(conversation.getName());
            }
            for (Message message : messages) {
                final Person sender =
                        message.getStatus() == Message.STATUS_RECEIVED ? getPerson(message) : null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isImageMessage(message)) {
                    final Uri dataUri =
                            FileBackend.getMediaUri(
                                    mXmppConnectionService,
                                    mXmppConnectionService.getFileBackend().getFile(message));
                    NotificationCompat.MessagingStyle.Message imageMessage =
                            new NotificationCompat.MessagingStyle.Message(
                                    UIHelper.getMessagePreview(mXmppConnectionService, message)
                                            .first,
                                    message.getTimeSent(),
                                    sender);
                    if (dataUri != null) {
                        imageMessage.setData(message.getMimeType(), dataUri);
                    }
                    messagingStyle.addMessage(imageMessage);
                } else {
                    messagingStyle.addMessage(
                            UIHelper.getMessagePreview(mXmppConnectionService, message).first,
                            message.getTimeSent(),
                            sender);
                }
            }
            messagingStyle.setGroupConversation(multiple);
            builder.setStyle(messagingStyle);
        } else {
            if (messages.get(0).getConversation().getMode() == Conversation.MODE_SINGLE) {
                builder.setStyle(
                        new NotificationCompat.BigTextStyle().bigText(getMergedBodies(messages)));
                final CharSequence preview =
                        UIHelper.getMessagePreview(
                                mXmppConnectionService, messages.get(messages.size() - 1))
                                .first;
                builder.setContentText(preview);
                builder.setTicker(preview);
                builder.setNumber(messages.size());
            } else {
                final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                SpannableString styledString;
                for (Message message : messages) {
                    final String name = UIHelper.getMessageDisplayName(message);
                    styledString = new SpannableString(name + ": " + message.getBody());
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    style.addLine(styledString);
                }
                builder.setStyle(style);
                int count = messages.size();
                if (count == 1) {
                    final String name = UIHelper.getMessageDisplayName(messages.get(0));
                    styledString = new SpannableString(name + ": " + messages.get(0).getBody());
                    styledString.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), 0);
                    builder.setContentText(styledString);
                    builder.setTicker(styledString);
                } else {
                    final String text =
                            mXmppConnectionService
                                    .getResources()
                                    .getQuantityString(R.plurals.x_messages, count, count);
                    builder.setContentText(text);
                    builder.setTicker(text);
                }
            }
        }
    }

    private Message getImage(final Iterable<Message> messages) {
        Message image = null;
        for (final Message message : messages) {
            if (message.getStatus() != Message.STATUS_RECEIVED) {
                return null;
            }
            if (isImageMessage(message)) {
                image = message;
            }
        }
        return image;
    }

    private Message getFirstDownloadableMessage(final Iterable<Message> messages) {
        for (final Message message : messages) {
            if (message.getTransferable() != null
                    || (message.getType() == Message.TYPE_TEXT && message.treatAsDownloadable())) {
                return message;
            }
        }
        return null;
    }

    private Message getFirstLocationMessage(final Iterable<Message> messages) {
        for (final Message message : messages) {
            if (message.isGeoUri()) {
                return message;
            }
        }
        return null;
    }

    private CharSequence getMergedBodies(final ArrayList<Message> messages) {
        final StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (text.length() != 0) {
                text.append("\n");
            }
            text.append(UIHelper.getMessagePreview(mXmppConnectionService, message).first);
        }
        return text.toString();
    }

    private PendingIntent createShowLocationIntent(final Message message) {
        final PackageManager packageManager = mXmppConnectionService.getPackageManager();
        final Iterable<Intent> intents =
                GeoHelper.createGeoIntentsFromMessage(mXmppConnectionService, message);
        for (final Intent intent : intents) {
            // MATCH_DEFAULT_ONLY is what Intent.resolveActivity() used to ask for here, so the
            // Intent picked is still the one that would have been launched.
            final List<ResolveInfo> matches =
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (matches.isEmpty()) {
                continue;
            }
            // The notification action runs this PendingIntent with our identity, so it must not
            // wrap an implicit Intent another app could redirect
            // (CodeQL java/android/implicit-pendingintents). All but one of the candidates
            // GeoHelper builds are implicit: geo:, the maps https: link, and the location
            // plugin's action when one is installed -- only the in-app ShowLocationActivity
            // fallback names a component. A single match is the activity implicit resolution
            // would have reached
            // anyway, so naming it outright costs nothing; with several the chooser has to stay,
            // and FLAG_IMMUTABLE is what stops the payload being rewritten there.
            if (matches.size() == 1) {
                final ActivityInfo activityInfo = matches.get(0).activityInfo;
                if (activityInfo != null) {
                    intent.setComponent(
                            new ComponentName(activityInfo.packageName, activityInfo.name));
                }
            }
            return PendingIntent.getActivity(
                    mXmppConnectionService,
                    generateRequestCode(message.getConversation(), 18),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return null;
    }

    private PendingIntent createContentIntent(
            final String conversationUuid, final String downloadMessageUuid) {
        final Intent viewConversationIntent =
                new Intent(mXmppConnectionService, ConversationsActivity.class);
        viewConversationIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        viewConversationIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversationUuid);
        if (downloadMessageUuid != null) {
            viewConversationIntent.putExtra(
                    ConversationsActivity.EXTRA_DOWNLOAD_UUID, downloadMessageUuid);
            return PendingIntent.getActivity(
                    mXmppConnectionService,
                    generateRequestCode(conversationUuid, 8),
                    viewConversationIntent,
                    s()
                            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            return PendingIntent.getActivity(
                    mXmppConnectionService,
                    generateRequestCode(conversationUuid, 10),
                    viewConversationIntent,
                    s()
                            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private int generateRequestCode(String uuid, int actionId) {
        final String key = uuid + ":" + actionId;
        return REQUEST_CODE_MAP.computeIfAbsent(key, k -> REQUEST_CODE_COUNTER.getAndIncrement());
    }

    private int generateRequestCode(Conversational conversation, int actionId) {
        return generateRequestCode(conversation.getUuid(), actionId);
    }

    private PendingIntent createDownloadIntent(final Message message) {
        return createContentIntent(message.getConversationUuid(), message.getUuid());
    }

    private PendingIntent createContentIntent(final Conversational conversation) {
        return createContentIntent(conversation.getUuid(), null);
    }

    private PendingIntent createDeleteIntent(final Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_CLEAR_MESSAGE_NOTIFICATION);
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid());
            intent.setPackage(mXmppConnectionService.getPackageName());
            return PendingIntent.getService(
                    mXmppConnectionService,
                    generateRequestCode(conversation, 20),
                    intent,
                    s()
                            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return PendingIntent.getService(
                mXmppConnectionService,
                0,
                intent,
                s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createMissedCallsDeleteIntent(final Conversational conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_CLEAR_MESSAGE_NOTIFICATION);
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid());
            intent.setPackage(mXmppConnectionService.getPackageName());
            return PendingIntent.getService(
                    mXmppConnectionService,
                    generateRequestCode(conversation, 20),
                    intent,
                    s()
                            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return PendingIntent.getService(
                mXmppConnectionService,
                0,
                intent,
                s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createReplyIntent(
            final Conversation conversation,
            final String lastMessageUuid,
            final boolean dismissAfterReply) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_REPLY_TO_CONVERSATION);
        intent.putExtra("uuid", conversation.getUuid());
        intent.putExtra("dismiss_notification", dismissAfterReply);
        intent.putExtra("last_message_uuid", lastMessageUuid);
        intent.setPackage(mXmppConnectionService.getPackageName());
        final int id = generateRequestCode(conversation, dismissAfterReply ? 12 : 14);
        return PendingIntent.getService(
                mXmppConnectionService,
                id,
                intent,
                s()
                        ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createReadPendingIntent(Conversation conversation) {
        final Intent intent = new Intent(mXmppConnectionService, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_MARK_AS_READ);
        intent.putExtra("uuid", conversation.getUuid());
        intent.setPackage(mXmppConnectionService.getPackageName());
        return PendingIntent.getService(
                mXmppConnectionService,
                generateRequestCode(conversation, 16),
                intent,
                s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createCallAction(String sessionId, final String action, int requestCode) {
        return pendingServiceIntent(
                mXmppConnectionService,
                action,
                requestCode,
                ImmutableMap.of(RtpSessionActivity.EXTRA_SESSION_ID, sessionId));
    }

    private PendingIntent createSnoozeIntent(final Conversation conversation) {
        return pendingServiceIntent(
                mXmppConnectionService,
                XmppConnectionService.ACTION_SNOOZE,
                generateRequestCode(conversation, 22),
                ImmutableMap.of("uuid", conversation.getUuid()));
    }

    private static PendingIntent pendingServiceIntent(
            final Context context, final String action, final int requestCode) {
        return pendingServiceIntent(context, action, requestCode, ImmutableMap.of());
    }

    private static PendingIntent pendingServiceIntent(
            final Context context,
            final String action,
            final int requestCode,
            final Map<String, String> extras) {
        final Intent intent = new Intent(context, XmppConnectionService.class);
        intent.setAction(action);
        intent.setPackage(context.getPackageName());
        for (final Map.Entry<String, String> entry : extras.entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        return PendingIntent.getService(
                context,
                requestCode,
                intent,
                s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean wasHighlightedOrPrivate(final Message message) {
        if (message.getConversation() instanceof Conversation conversation) {
            final String nick = conversation.getMucOptions().getActualNick();
            final Pattern highlight = generateNickHighlightPattern(nick);
            if (message.getBody() == null || nick == null) {
                return false;
            }
            final Matcher m = highlight.matcher(message.getBody());
            return (m.find() || message.isPrivateMessage());
        } else {
            return false;
        }
    }

    public void setOpenConversation(final Conversation conversation) {
        this.mOpenConversation = conversation;
    }

    public void setIsInForeground(final boolean foreground) {
        this.mIsInForeground = foreground;
    }

    private int getPixel(final int dp) {
        final DisplayMetrics metrics = mXmppConnectionService.getResources().getDisplayMetrics();
        return ((int) (dp * metrics.density));
    }

    private boolean inMiniGracePeriod(final Account account) {
        final int miniGrace =
                account.getStatus() == Account.State.ONLINE
                        ? Config.MINI_GRACE_PERIOD
                        : Config.MINI_GRACE_PERIOD * 2;
        return SystemClock.elapsedRealtime() < (this.mLastNotification + miniGrace);
    }

    Notification createForegroundNotification() {
        final Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        mBuilder.setContentTitle(mXmppConnectionService.getString(R.string.app_name));
        final List<Account> accounts = mXmppConnectionService.getAccounts();
        final int enabled;
        final int connected;
        if (accounts == null) {
            enabled = 0;
            connected = 0;
        } else {
            enabled = Iterables.size(Iterables.filter(accounts, Account::isEnabled));
            connected = Iterables.size(Iterables.filter(accounts, Account::isOnlineAndConnected));
        }
        mBuilder.setContentText(
                mXmppConnectionService.getString(R.string.connected_accounts, connected, enabled));
        final PendingIntent openIntent = createOpenConversationsIntent();
        if (openIntent != null) {
            mBuilder.setContentIntent(openIntent);
        }
        mBuilder.setWhen(0)
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(connected > 0 ? R.drawable.ic_link_24dp : R.drawable.ic_link_off_24dp)
                .setLocalOnly(true);

        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("foreground");
            mBuilder.addAction(
                    R.drawable.ic_notifications_off_24dp,
                    mXmppConnectionService.getString(R.string.hide_notification),
                    pendingNotificationSettingsIntent(mXmppConnectionService));
        }

        return mBuilder.build();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static PendingIntent pendingNotificationSettingsIntent(final Context context) {
        final Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        intent.putExtra(Settings.EXTRA_CHANNEL_ID, "foreground");
        return PendingIntent.getActivity(
                context,
                89,
                intent,
                s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createOpenConversationsIntent() {
        try {
            return PendingIntent.getActivity(
                    mXmppConnectionService,
                    0,
                    new Intent(mXmppConnectionService, ConversationsActivity.class),
                    s()
                            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void updateErrorNotification() {
        if (Config.SUPPRESS_ERROR_NOTIFICATION) {
            cancel(ERROR_NOTIFICATION_ID);
            return;
        }
        final boolean showAllErrors = QuickConversationsService.isConversations();
        final List<Account> errors = new ArrayList<>();
        for (final Account account : mXmppConnectionService.getAccounts()) {
            if (account.hasErrorStatus()
                    && account.showErrorNotification()
                    && (showAllErrors
                    || account.getLastErrorStatus() == Account.State.UNAUTHORIZED)) {
                errors.add(account);
            }
        }
        if (mXmppConnectionService.foregroundNotificationNeedsUpdatingWhenErrorStateChanges()) {
            try {
                notify(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
            } catch (final RuntimeException e) {
            }
        }
        final Notification.Builder mBuilder = new Notification.Builder(mXmppConnectionService);
        if (errors.isEmpty()) {
            cancel(ERROR_NOTIFICATION_ID);
            return;
        } else if (errors.size() == 1) {
            mBuilder.setContentTitle(
                    mXmppConnectionService.getString(R.string.problem_connecting_to_account));
            mBuilder.setContentText(errors.get(0).getJid().asBareJid().toEscapedString());
        } else {
            mBuilder.setContentTitle(
                    mXmppConnectionService.getString(R.string.problem_connecting_to_accounts));
            mBuilder.setContentText(mXmppConnectionService.getString(R.string.touch_to_fix));
        }
        try {
            mBuilder.addAction(
                    R.drawable.ic_autorenew_24dp,
                    mXmppConnectionService.getString(R.string.try_again),
                    pendingServiceIntent(
                            mXmppConnectionService, XmppConnectionService.ACTION_TRY_AGAIN, 45));
            mBuilder.setDeleteIntent(
                    pendingServiceIntent(
                            mXmppConnectionService,
                            XmppConnectionService.ACTION_DISMISS_ERROR_NOTIFICATIONS,
                            69));
        } catch (final RuntimeException e) {
        }
        mBuilder.setVisibility(Notification.VISIBILITY_PRIVATE);
        mBuilder.setSmallIcon(R.drawable.ic_warning_24dp);
        mBuilder.setLocalOnly(true);
        mBuilder.setPriority(Notification.PRIORITY_LOW);
        boolean hasOnlyUnauthorizedErrors = !errors.isEmpty();
        Account unauthorizedAccount = null;
        for (final Account account : errors) {
            if (account.getLastErrorStatus() == Account.State.UNAUTHORIZED) {
                if (unauthorizedAccount == null) {
                    unauthorizedAccount = account;
                }
            } else {
                hasOnlyUnauthorizedErrors = false;
            }
        }

        final Intent intent;
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            intent = new Intent(mXmppConnectionService, AccountUtils.MANAGE_ACCOUNT_ACTIVITY);
        } else if (hasOnlyUnauthorizedErrors && unauthorizedAccount != null) {
            intent = new Intent(mXmppConnectionService, LoginAccountActivity.class);
            intent.putExtra("jid", unauthorizedAccount.getJid().asBareJid().toEscapedString());
            intent.putExtra(LoginAccountActivity.EXTRA_OPENED_FROM_NOTIFICATION, true);
        } else {
            intent = new Intent(mXmppConnectionService, ConversationsActivity.class);
        }
        mBuilder.setContentIntent(
                PendingIntent.getActivity(
                        mXmppConnectionService,
                        145,
                        intent,
                        s()
                                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                                : PendingIntent.FLAG_UPDATE_CURRENT));
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("error");
        }
        notify(ERROR_NOTIFICATION_ID, mBuilder.build());
    }

    void updateFileAddingNotification(final int current, final Message message) {

        final Notification notification = videoTranscoding(current, message);
        notify(ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID, notification);
    }

    private Notification videoTranscoding(final int current, @Nullable final Message message) {
        final Notification.Builder builder = new Notification.Builder(mXmppConnectionService);
        builder.setContentTitle(mXmppConnectionService.getString(R.string.transcoding_video));
        if (current >= 0) {
            builder.setProgress(100, current, false);
        } else {
            builder.setProgress(100, 0, true);
        }
        builder.setSmallIcon(R.drawable.ic_hourglass_top_24dp);
        if (message != null) {
            builder.setContentIntent(createContentIntent(message.getConversation()));
        }
        builder.setOngoing(true);
        if (Compatibility.runsTwentySix()) {
            builder.setChannelId("compression");
        }
        return builder.build();
    }

    public Notification getIndeterminateVideoTranscoding() {
        return videoTranscoding(-1, null);
    }

    private void notify(final String tag, final int id, final Notification notification) {
        if (ActivityCompat.checkSelfPermission(
                mXmppConnectionService, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        final var notificationManager =
                mXmppConnectionService.getSystemService(NotificationManager.class);
        try {
            notificationManager.notify(tag, id, notification);
        } catch (final RuntimeException e) {
        }
    }

    public void notify(final int id, final Notification notification) {
        try {
            if (ActivityCompat.checkSelfPermission(
                    mXmppConnectionService, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            final var notificationManager =
                    mXmppConnectionService.getSystemService(NotificationManager.class);
            notificationManager.notify(id, notification);
        } catch (final RuntimeException e) {
        }
    }

    public void cancel(final int id) {
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.cancel(id);
        } catch (final RuntimeException e) {
        }
    }

    private void cancel(final String tag, final int id) {
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(mXmppConnectionService);
        try {
            notificationManager.cancel(tag, id);
        } catch (final RuntimeException e) {
        }
    }

    public void notifyVoicemail(VoicemailRecord vm) {
        final List<Account> accounts = mXmppConnectionService.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(mXmppConnectionService, VOICEMAIL_NOTIFICATION_CHANNEL);
        Intent intent = new Intent(mXmppConnectionService, PhoneModuleActivity.class);
        intent.putExtra("proxyNum", vm.getTo());
        intent.putExtra(PhoneModuleActivity.PHONE_MOD_INTENT, PhoneModuleActivity.VOICEMAIL_FRAG);
        PendingIntent pendingIntent = PendingIntent.getActivity(mXmppConnectionService, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentTitle("Glacier Voicemail for " + PhoneUtils.lastFourDigits( vm.getTo()));
        mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);
        mBuilder.setContentText("from " + vm.getFrom());
        mBuilder.setSmallIcon(R.drawable.ic_voicemail_24dp);
        final long now = SystemClock.elapsedRealtime();
        final boolean withinGrace = now - mLastVoicemailAlert < Config.ALERT_GRACE_PERIOD_MS;
        if (withinGrace) {
            mBuilder.setSilent(true);
            mBuilder.setOnlyAlertOnce(true);
        } else {
            mLastVoicemailAlert = now;
        }
        mBuilder.setAutoCancel(true);
        mBuilder.setContentIntent(pendingIntent);
        String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(vm.getTo());
        notify(notificationTag, VOICEMAIL_NOTIFICATION_ID, mBuilder.build());
        broadcastPhoneBadgeUpdate();
    }

    public void notifySmsMessage(
            String conversationSid, String newMessage, String author, String messageTo) {
        final var channel = SMS_MESSAGES_HIGH_PRIORITY_NOTIFICATION_CHANNEL;
        final Builder mBuilder =
                new NotificationCompat.Builder(mXmppConnectionService, channel);
        Intent intent = new Intent(mXmppConnectionService, SmsConvActivity.class);
        intent.putExtra("conv_sid", conversationSid);
        intent.putExtra("friendly_name", messageTo);
        intent.putExtra("proxy_number", messageTo);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        mXmppConnectionService,
                        generateRequestCode(conversationSid, 24),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentTitle("Glacier SMS for " + PhoneUtils.lastFourDigits(messageTo));
        mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);
        String contact = NativeContactsUtils.getContactNameIfExists(mXmppConnectionService, author);
        mBuilder.setContentText(contact + ": " + newMessage);
        mBuilder.setSmallIcon(R.drawable.ic_baseline_sms_24);
        mBuilder.setAutoCancel(true);
        mBuilder.setContentIntent(pendingIntent);
        String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(messageTo);
        if (notificationTag == null || notificationTag.isEmpty()) {
            notificationTag = conversationSid;
        }
        notify(notificationTag, SMS_NOTIFICATION_ID, mBuilder.build());
        broadcastPhoneBadgeUpdate();
    }

    public void clearSmsNotification(String conversationSid) {
        cancel(conversationSid, SMS_NOTIFICATION_ID);
    }

    public void clearSmsNotificationForProfile(String profileNumber) {
        if (profileNumber == null || profileNumber.isEmpty()) {
            return;
        }
        String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(profileNumber);
        cancel(notificationTag, SMS_NOTIFICATION_ID);
        broadcastPhoneBadgeUpdate();
    }

    public void clearMissedCallNotificationForProfile(String profileNumber) {
        if (profileNumber == null || profileNumber.isEmpty()) {
            return;
        }
        String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(profileNumber);
        cancel(notificationTag, MISSED_PHONE_CALL_NOTIFICATION_ID);
        broadcastPhoneBadgeUpdate();
    }

    public void clearVoicemailNotificationForProfile(String profileNumber) {
        if (profileNumber == null || profileNumber.isEmpty()) {
            return;
        }
        String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(profileNumber);
        cancel(notificationTag, VOICEMAIL_NOTIFICATION_ID);
        broadcastPhoneBadgeUpdate();
    }

    private void broadcastPhoneBadgeUpdate() {
        LocalBroadcastManager.getInstance(mXmppConnectionService)
                .sendBroadcast(new Intent(ACTION_PHONE_BADGES_UPDATED));
    }

    private static class MissedCallsInfo {
        private int numberOfCalls;
        private long lastTime;

        MissedCallsInfo(final long time) {
            numberOfCalls = 1;
            lastTime = time;
        }

        public void newMissedCall(final long time) {
            ++numberOfCalls;
            lastTime = time;
        }

        public boolean removeMissedCall() {
            --numberOfCalls;
            return numberOfCalls <= 0;
        }

        public int getNumberOfCalls() {
            return numberOfCalls;
        }

        public long getLastTime() {
            return lastTime;
        }
    }
}
