package com.glaciersecurity.glaciermessenger.receiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import com.glaciersecurity.glaciermessenger.utils.Log;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.persistance.UnifiedPushDatabase;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;

public class UnifiedPushDistributor extends BroadcastReceiver {

    // distributor actions (these are actions used for connector->distributor broadcasts)
    // we, the distributor, have a broadcast receiver listening for those actions

    public static final String ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    public static final String ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER";

    // connector actions (these are actions used for distributor->connector broadcasts)
    public static final String ACTION_UNREGISTERED =
            "org.unifiedpush.android.connector.UNREGISTERED";
    public static final String ACTION_BYTE_MESSAGE =
            "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE";
    public static final String ACTION_REGISTRATION_FAILED =
            "org.unifiedpush.android.connector.REGISTRATION_FAILED";

    // this action is only used in 'messenger' communication to tell the app that a registration is
    // probably fine but can not be processed right now; for example due to spotty internet
    public static final String ACTION_REGISTRATION_DELAYED =
            "org.unifiedpush.android.connector.REGISTRATION_DELAYED";
    public static final String ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE";
    public static final String ACTION_NEW_ENDPOINT =
            "org.unifiedpush.android.connector.NEW_ENDPOINT";

    public static final String EXTRA_MESSAGE = "message";

    public static final String PREFERENCE_ACCOUNT = "up_push_account";
    public static final String PREFERENCE_PUSH_SERVER = "up_push_server";

    public static final List<String> PREFERENCES =
            Arrays.asList(PREFERENCE_ACCOUNT, PREFERENCE_PUSH_SERVER);

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = Strings.nullToEmpty(intent.getAction());
        switch (action) {
            case ACTION_REGISTER, ACTION_UNREGISTER -> onConnectorCommand(context, intent, action);
            // a protected broadcast; only the system can send it and it carries no connector
            // identity, so it is handled before identity resolution
            case Intent.ACTION_PACKAGE_FULLY_REMOVED -> unregisterApplication(
                    context, intent.getData());
            default -> {}
        }
    }

    private void onConnectorCommand(
            final Context context, final Intent intent, final String action) {
        final String application = resolveCallingApplication(context, intent);
        if (Strings.isNullOrEmpty(application)) {
            return;
        }
        final Parcelable messenger = intent.getParcelableExtra("messenger");
        final String instance = intent.getStringExtra("token");
        final List<String> features = intent.getStringArrayListExtra("features");
        if (ACTION_REGISTER.equals(action)) {
            register(context, application, instance, features, messenger);
        } else {
            unregister(context, application, instance);
        }
    }

    /**
     * Resolves the package name of the connector that sent this broadcast, using only sources the
     * operating system vouches for. Returns null when the sender cannot be identified.
     *
     * <p>This diverges from the inherited Conversations code, which fell back to the {@code
     * application} string extra whenever no PendingIntent was present. That extra is written by the
     * caller and can name any package, so it is not an identity. The inherited code also read the
     * PendingIntent from an {@code app} extra -- a key the UnifiedPush connector library has never
     * sent, it has always been {@code pi} -- so in practice the verified path never ran and every
     * registration was taken on the spoofable one.
     */
    private String resolveCallingApplication(final Context context, final Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            final String sentFromPackage = getSentFromPackage();
            if (!Strings.isNullOrEmpty(sentFromPackage)) {
                return sentFromPackage;
            }
        }
        // below Android 14 a connector identifies itself with an immutable PendingIntent; the OS
        // guarantees its creator package
        final String creator = asApplication(intent.getParcelableExtra("pi"));
        if (Strings.isNullOrEmpty(creator)) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && getTargetSdk(context, creator) >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // an app that targets 14+ is able to share its identity and is expected to; accepting
            // the PendingIntent from it would re-open a path around that
            return null;
        }
        return creator;
    }

    private static String asApplication(final Parcelable parcelable) {
        if (parcelable instanceof PendingIntent pendingIntent) {
            return Strings.emptyToNull(pendingIntent.getIntentSender().getCreatorPackage());
        } else {
            return null;
        }
    }

    private static int getTargetSdk(final Context context, final String application) {
        try {
            return context.getPackageManager().getApplicationInfo(application, 0).targetSdkVersion;
        } catch (final PackageManager.NameNotFoundException e) {
            // an application we can not see is not one we are willing to accept a fallback for
            return Integer.MAX_VALUE;
        }
    }

    private void register(
            final Context context,
            final String application,
            final String instance,
            final Collection<String> features,
            final Parcelable messenger) {
        if (Strings.isNullOrEmpty(application) || Strings.isNullOrEmpty(instance)) {
            Log.w(Config.LOGTAG, "ignoring invalid UnifiedPush registration");
            return;
        }
        final List<String> receivers = getBroadcastReceivers(context, application);
        if (receivers.contains(application)) {
            if (UnifiedPushDatabase.getInstance(context).register(application, instance)) {
                quickLog(
                        context,
                        String.format(
                                "successfully registered %s (token = %s) for UnifiedPushed",
                                application, instance));
                final Intent serviceIntent = new Intent(context, XmppConnectionService.class);
                serviceIntent.setAction(XmppConnectionService.ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS);
                serviceIntent.putExtra("instance", instance);
                serviceIntent.putExtra("application", application);
                if (messenger instanceof Messenger) {
                    serviceIntent.putExtra("messenger", messenger);
                }
                Compatibility.startService(context, serviceIntent);
            } else {
                final Intent registrationFailed = new Intent(ACTION_REGISTRATION_FAILED);
                registrationFailed.putExtra(EXTRA_MESSAGE, "instance already exists");
                registrationFailed.setPackage(application);
                registrationFailed.putExtra("token", instance);
                if (messenger instanceof Messenger m) {
                    final var message = new Message();
                    message.obj = registrationFailed;
                    try {
                        m.send(message);
                    } catch (final RemoteException e) {
                        context.sendBroadcast(registrationFailed);
                    }
                } else {
                    context.sendBroadcast(registrationFailed);
                }
            }
        } else {
            if (messenger instanceof Messenger m) {
                sendRegistrationFailed(m, "Your application is not registered to receive messages");
            }
        }
    }

    private void sendRegistrationFailed(final Messenger messenger, final String error) {
        final Intent intent = new Intent(ACTION_REGISTRATION_FAILED);
        intent.putExtra(EXTRA_MESSAGE, error);
        final var message = new Message();
        message.obj = intent;
        try {
            messenger.send(message);
        } catch (final RemoteException e) {
        }
    }

    private List<String> getBroadcastReceivers(final Context context, final String application) {
        final Intent messageIntent = new Intent(ACTION_MESSAGE);
        messageIntent.setPackage(application);
        final List<ResolveInfo> resolveInfo =
                context.getPackageManager().queryBroadcastReceivers(messageIntent, 0);
        return Lists.transform(
                resolveInfo, ri -> ri.activityInfo == null ? null : ri.activityInfo.packageName);
    }

    private void unregister(
            final Context context, final String application, final String instance) {
        if (Strings.isNullOrEmpty(application) || Strings.isNullOrEmpty(instance)) {
            Log.w(Config.LOGTAG, "ignoring invalid UnifiedPush un-registration");
            return;
        }
        final UnifiedPushDatabase unifiedPushDatabase = UnifiedPushDatabase.getInstance(context);
        // the delete is scoped to the verified caller; resolveCallingApplication() has already
        // established that this broadcast really came from `application`
        if (unifiedPushDatabase.deleteInstance(application, instance)) {
            quickLog(
                    context,
                    String.format(
                            "successfully unregistered token %s from UnifiedPushed (application requested unregister)",
                            instance));
        }
    }

    private void unregisterApplication(final Context context, final Uri uri) {
        if (uri != null && "package".equalsIgnoreCase(uri.getScheme())) {
            final String application = uri.getSchemeSpecificPart();
            if (Strings.isNullOrEmpty(application)) {
                return;
            }
            final UnifiedPushDatabase database = UnifiedPushDatabase.getInstance(context);
            if (database.deleteApplication(application)) {
                quickLog(
                        context,
                        String.format(
                                "successfully removed %s from UnifiedPushed (ACTION_PACKAGE_FULLY_REMOVED)",
                                application));
            }
        }
    }

    public static String hash(String... components) {
        return BaseEncoding.base64()
                .encode(
                        Hashing.sha256()
                                .hashString(Joiner.on('\0').join(components), Charsets.UTF_8)
                                .asBytes());
    }

    public static void quickLog(final Context context, final String message) {
        final Intent intent = new Intent(context, XmppConnectionService.class);
        intent.setAction(XmppConnectionService.ACTION_QUICK_LOG);
        intent.putExtra("message", message);
        Compatibility.startService(context, intent);
    }
}
