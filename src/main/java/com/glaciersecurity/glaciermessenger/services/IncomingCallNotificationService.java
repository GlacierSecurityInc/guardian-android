package com.glaciersecurity.glaciermessenger.services;

import static com.glaciersecurity.glaciermessenger.services.NotificationService.MISSED_PHONE_CALL_NOTIFICATION_ID;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.os.IBinder;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;



import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.util.SoundPoolManager;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;

import java.io.IOException;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.HashMap;
import java.util.Map;

public class IncomingCallNotificationService extends Service {

    private static final long[] CALL_PATTERN = {0, 500, 300, 600, 3000};

    // Fallback foreground-notification id used when the start intent carries no
    // notification id (e.g. a cancel push). startForeground() rejects id 0.
    private static final int FOREGROUND_PLACEHOLDER_ID = 1002;

    NotificationManagerCompat managerCompat;
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this,"glaciercalls");

    @Override
    public void onCreate() {
        super.onCreate();
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        SoundPoolManager.getInstance(this).stopRinging();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        final int notificationId = intent.getIntExtra(Config.INCOMING_CALL_NOTIFICATION_ID, 0);

        // The incoming-call and cancel intents are delivered from the background via
        // startForegroundService(); Android requires startForeground() promptly, and on
        // targetSdk 34+ the ringtone and full-screen call UI may only be triggered while a
        // phoneCall foreground service is held. Promote before any (possibly slow) work.
        if (Config.ACTION_INCOMING_CALL.equals(action) || Config.ACTION_CANCEL_CALL.equals(action)) {
            enterForeground(notificationId);
        }

        List<Account> accounts = DatabaseBackend.getInstance(this).getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            Log.w(Config.LOGTAG, "IncomingCallNotificationService started without account");
            endForeground();
            return START_NOT_STICKY;
        }

        if (action != null) {
            CallInvite callInvite = intent.getParcelableExtra(Config.INCOMING_CALL_INVITE);
            CancelledCallInvite cancelledCallInvite = intent.getParcelableExtra(Config.CANCELLED_CALL_INVITE);

            switch (action) {
                case Config.ACTION_INCOMING_CALL:
                    handleIncomingCall(callInvite, notificationId);
                    break;
                case Config.ACTION_ACCEPT_CALL:
                    NotificationManagerCompat.from(this).cancel(notificationId);
                    accept(callInvite, notificationId);
                    break;
                case Config.ACTION_REJECT_CALL:
                    NotificationManagerCompat.from(this).cancel(notificationId);
                    reject(callInvite);
                    break;
                case Config.ACTION_CANCEL_CALL:
                    NotificationManagerCompat.from(this).cancel(notificationId);
                    handleCancelledCall(cancelledCallInvite);
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createIncomingCallNotification(CallInvite callInvite, int notificationId, String proxy) {
        Intent externalCallIntent = new Intent(this, ExternalCallActivity.class);
        externalCallIntent.setAction(Config.ACTION_INCOMING_CALL_NOTIFICATION);
        externalCallIntent.putExtra(Config.INCOMING_CALL_INVITE, callInvite);
        externalCallIntent.putExtra(Config.TO_PROXY, proxy);
        externalCallIntent.putExtra(Config.CALL_SID_KEY, callInvite.getCallSid());
        externalCallIntent.putExtra(Config.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        externalCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, notificationId, externalCallIntent,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent rejectIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        rejectIntent.setAction(Config.ACTION_REJECT_CALL);
        rejectIntent.putExtra(Config.INCOMING_CALL_INVITE, callInvite);
        rejectIntent.putExtra(Config.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piRejectIntent = PendingIntent.getService(getApplicationContext(), notificationId, rejectIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent acceptIntent = new Intent(getApplicationContext(), ExternalCallActivity.class);
        acceptIntent.setAction(Config.ACTION_ACCEPT_CALL);
        acceptIntent.putExtra(Config.INCOMING_CALL_INVITE, callInvite);
        acceptIntent.putExtra(Config.TO_PROXY, proxy);
        acceptIntent.putExtra(Config.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        acceptIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piAcceptIntent = PendingIntent.getActivity(getApplicationContext(), notificationId, acceptIntent, PendingIntent.FLAG_IMMUTABLE);

        final String channelId = NotificationService.getCurrentIncomingCallChannel(this)
                .transform(NotificationChannel::getId)
                .or("glaciercalls");

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, channelId);
        mBuilder.setContentTitle("Incoming Call to " + PhoneUtils.reformatNumber(proxy))
                .setContentText("Call from " + displayFrom(callInvite.getFrom()))
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .addAction(R.drawable.ic_call_end_white_24px, getString(R.string.decline), piRejectIntent)
                .addAction(android.R.drawable.ic_menu_call, getString(R.string.answer), piAcceptIntent)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_gchat_icon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(CALL_PATTERN);
        SoundPoolManager.getInstance(this).vibrateIfNeeded();
        mBuilder.setFullScreenIntent(pendingIntent, true); // THIS HERE is the full-screen intent

        mBuilder.setCategory(Notification.CATEGORY_CALL);
        managerCompat = NotificationManagerCompat.from(this);
        Notification notification = mBuilder.build();
        notification.audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        // This notification's own sound (channel + FLAG_INSISTENT) is the only ring source that
        // is reliable regardless of app/Activity state. ExternalCallActivity additionally plays
        // its own ringtone when it is freshly created for the call, which can cause an audible
        // double ring in that case, but that's preferable to silence: when an ExternalCallActivity
        // instance is already running (singleTask), the incoming-call Intent it receives doesn't
        // reliably re-trigger its ringtone, so this notification must not depend on the Activity.
        notification.flags |= Notification.FLAG_INSISTENT;
        // Surface the ringing notification as the foreground-service notification (replacing
        // the placeholder posted in enterForeground). This keeps the service in the foreground
        // for the duration of ringing and does not depend on POST_NOTIFICATIONS.
        try {
            ServiceCompat.startForeground(
                    this,
                    notificationId != 0 ? notificationId : FOREGROUND_PLACEHOLDER_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to show incoming call notification", e);
        }
    }



    private void createMissedCallNotification(CallInfo callInfo, CancelledCallInvite callCancel) {
        Intent intent = new Intent(this, PhoneModuleActivity.class);
        intent.putExtra(PhoneModuleActivity.PHONE_MOD_INTENT, PhoneModuleActivity.HISTORY_FRAG);

        String proxy = callInfo != null ? callInfo.getProxy() : callCancel.getTo();
        try {
            intent.putExtra("proxyNum", proxy);
        } catch (Exception ignored) {
        }

        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentTitle("Missed Call for "+ PhoneUtils.lastFourDigits(proxy));
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setContentText("from " + displayFrom(callCancel.getFrom()));
        builder.setSmallIcon(R.drawable.ic_baseline_phone_missed_24);
        builder.setAutoCancel(true);
        builder.setSound(null);
        builder.setContentIntent(pendingIntent);
        managerCompat = NotificationManagerCompat.from(this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(proxy);
        managerCompat.notify(notificationTag, MISSED_PHONE_CALL_NOTIFICATION_ID, builder.build());
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(NotificationService.ACTION_PHONE_BADGES_UPDATED));
    }

    private String displayFrom(String s){
        return NativeContactsUtils.getContactNameIfExists(this, s);
    }

    private void accept(CallInvite callInvite, int notificationId) {
        endForeground();
        Intent activeCallIntent = new Intent(this, ExternalCallActivity.class);
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activeCallIntent.putExtra(Config.INCOMING_CALL_INVITE, callInvite);
        activeCallIntent.putExtra(Config.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        activeCallIntent.setAction(Config.ACTION_ACCEPT_CALL);
        startActivity(activeCallIntent);
    }

    private void reject(CallInvite callInvite) {
        endForeground();
        callInvite.reject(getApplicationContext());
    }

    private void handleCancelledCall(CancelledCallInvite callCancel) {
        endForeground();
        getCallDetails(callCancel.getCallSid(), new CallInfoCallback() {
            @Override
            public void onCallInfoReceived(CallInfo callInfo) {
                createMissedCallNotification(callInfo, callCancel);
            }
        });
        Intent cancelCallIntent = new Intent(this, ExternalCallActivity.class);
        cancelCallIntent.setAction(Config.ACTION_CANCEL_CALL);
        LocalBroadcastManager.getInstance(this).sendBroadcast(cancelCallIntent);

    }

    private void handleIncomingCall(CallInvite callInvite, int notificationId) {
        getCallDetails(callInvite.getCallSid(), new CallInfoCallback() {
            @Override
            public void onCallInfoReceived(CallInfo callInfo) {
                String proxy = callInfo != null ? callInfo.getProxy() : callInvite.getTo();
                createIncomingCallNotification(callInvite, notificationId, proxy);
            }
        });
    }


    public interface CallInfoCallback {
        void onCallInfoReceived(CallInfo callInfo);
    }

    private void getCallDetails(String callSid, CallInfoCallback callback) {
        List<Account> accounts = DatabaseBackend.getInstance(this).getAccounts();
        if (accounts.isEmpty()) {
            Log.e(Config.LOGTAG, "No accounts found while fetching call details");
            callback.onCallInfoReceived(null);
            return;
        }
        Account account = accounts.get(0);
        GlacierOrgEntity org = DatabaseBackend.getInstance(this).getOrganization(account.getOrganization());
        if (org == null) {
            Log.e(Config.LOGTAG, "Organization not found for account");
            callback.onCallInfoReceived(null);
            return;
        }
        new Thread(() -> {
            String listVoicemailsUrl =
                    Endpoints.base() + EndPointConfig.GET_CALL_DETAILS_URL;
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("callSid", callSid);
            formFields.put("username", account.getUsername());
            formFields.put("organization", account.getOrganization());

            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(listVoicemailsUrl, formFields)
                        .build();
            } catch (IOException e) {
                Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
                callback.onCallInfoReceived(null);
                return;
            }
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                Gson gson = new Gson();
                ExternalCallManager.CallDetailsResponse callDetailsResponse = gson.fromJson(responseBody, ExternalCallManager.CallDetailsResponse.class);
                if (callDetailsResponse.message != null  && callDetailsResponse.message.equals("success")) {
                    CallInfo callDetail = getCallDetails(callDetailsResponse.call);
                    callback.onCallInfoReceived(callDetail);

                }
            } catch (Exception ex) {
                Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
                callback.onCallInfoReceived(null);

            }
        }).start();

    }

    private CallInfo getCallDetails(Object callInfo) {
         try {
                    if (callInfo instanceof LinkedTreeMap) {
                        LinkedTreeMap<Object, Object> treeMap = (LinkedTreeMap<Object, Object>) callInfo;
                        CallInfo vm = new CallInfo(treeMap);
                        return vm;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

            return null;

    }


    /**
     * Promote the service to a phoneCall foreground service with a lightweight placeholder
     * notification. Called synchronously before any slow work so we satisfy the
     * startForegroundService() contract; the placeholder is replaced by the full ringing
     * notification once the call details have been resolved.
     */
    private void enterForeground(int notificationId) {
        final int id = notificationId != 0 ? notificationId : FOREGROUND_PLACEHOLDER_ID;
        try {
            ServiceCompat.startForeground(
                    this,
                    id,
                    buildForegroundPlaceholder(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to enter foreground for incoming call", e);
        }
    }

    private Notification buildForegroundPlaceholder() {
        final String channelId = NotificationService.getCurrentIncomingCallChannel(this)
                .transform(NotificationChannel::getId)
                .or("glaciercalls");
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Incoming call")
                .setSmallIcon(R.drawable.ic_gchat_icon)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();
    }

    private void endForeground() {
        NotificationManagerCompat.from(this).cancelAll();
        SoundPoolManager.getInstance(this).stopRinging();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
