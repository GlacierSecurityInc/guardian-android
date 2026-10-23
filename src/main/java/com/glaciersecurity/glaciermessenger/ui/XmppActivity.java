package com.glaciersecurity.glaciermessenger.ui;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.lang.reflect.Method;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.BoolRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.databinding.DataBindingUtil;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.databinding.DialogAddReactionBinding;
import com.glaciersecurity.glaciermessenger.databinding.DialogQuickeditBinding;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.PgpEngine;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.Presences;
import com.glaciersecurity.glaciermessenger.entities.Reaction;
import com.glaciersecurity.glaciermessenger.services.AvatarService;
import com.glaciersecurity.glaciermessenger.services.BarcodeProvider;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.services.ExternalCallManager;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService.XmppConnectionBinder;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallListener;
import com.glaciersecurity.glaciermessenger.ui.RtpSessionActivity;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.ui.util.NavigationUtils;
import com.glaciersecurity.glaciermessenger.ui.util.PresenceSelector;
import com.glaciersecurity.glaciermessenger.ui.util.SettingsUtils;
import com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.CognitoClientMigrationManager;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnKeyStatusUpdated;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import im.conversations.android.xmpp.model.reactions.Restrictions;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public abstract class XmppActivity extends ActionBarActivity {

    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_WEBHOOK_TRIGGERED_REAUTH = "webhook_triggered_reauth";
    public static final String EXTRA_WEBHOOK_TRIGGERED_REAUTH_ENDPOINT =
            "webhook_triggered_reauth_endpoint";
    private static final Set<String> WEBHOOK_REAUTH_ALLOWED_ENDPOINTS = ImmutableSet.<String>builder()
            .add(EndPointConfig.FILESAFE_UPLOAD_URL,
                    EndPointConfig.GET_CORE_PROFILE, EndPointConfig.MEDIA_UPLOAD_URL, EndPointConfig.GET_DNS_PROFILE_URL, EndPointConfig.UPDATE_SECHUB_INFO)
            .build();
    private static final Set<Class<? extends XmppActivity>> REAUTH_PROMPT_ALLOWED_ACTIVITIES =
            ImmutableSet.of(
                    ConversationsActivity.class,
                    FileSafeActivity.class,
                    SecurityHubActivity.class,
                    SettingsActivity.class,
                    DNSActivity.class,
                    PhoneModuleActivity.class,
                    CoreConnectionActivity.class);
    protected static final int REQUEST_ANNOUNCE_PGP = 0x0101;
    protected static final int REQUEST_INVITE_TO_CONVERSATION = 0x0102;
    protected static final int REQUEST_CHOOSE_PGP_ID = 0x0103;
    protected static final int REQUEST_BATTERY_OP = 0x49ff;
    protected static final int REQUEST_POST_NOTIFICATION = 0x50ff;
    protected static final int REQUEST_LOCAL_NETWORK_ACCESS = 0x51ff;
    public XmppConnectionService xmppConnectionService;
    public boolean xmppConnectionServiceBound = false;

    protected static final String FRAGMENT_TAG_DIALOG = "dialog";

    private boolean isCameraFeatureAvailable = false;
    private boolean phoneBadgeReceiverRegistered = false;
    private boolean reauthenticationReceiverRegistered = false;
    // Static because the owed prompt is process-wide, not per-activity. The broadcast reaches
    // whichever XmppActivity is started, which may be one that cannot show the dialog (not in
    // REAUTH_PROMPT_ALLOWED_ACTIVITIES, or mid state-save). A per-instance flag was then dropped
    // when the user moved to an activity that could have shown it. UI thread only.
    private static boolean pendingReauthenticationDialog = false;


    protected boolean mUsingEnterKey = false;
    protected Toast mToast;
    private ExternalCallManager mExternalCallManager;
    private final ExternalCallListener mInternalCallListener = new ExternalCallListener() {
        @Override
        public void onRinging(@NonNull Call call) {
        }

        @Override
        public void onConnectFailure(@NonNull Call call, @NonNull CallException error) {
        }

        @Override
        public void onConnected(@NonNull Call call) {
        }

        @Override
        public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
        }

        @Override
        public void onReconnected(@NonNull Call call) {
        }

        @Override
        public void onDisconnected(@NonNull Call call, CallException error) {
            runOnUiThread(() -> updateReturnToExternalCall(false));
        }

        @Override
        public void onCallStatusChange(boolean bool) {
            runOnUiThread(() -> updateReturnToExternalCall(bool));
        }
    };
    public Runnable onOpenPGPKeyPublished = () -> Toast.makeText(XmppActivity.this, R.string.openpgp_has_been_published, Toast.LENGTH_SHORT).show();
    protected ConferenceInvite mPendingConferenceInvite = null;
    protected ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            XmppConnectionBinder binder = (XmppConnectionBinder) service;
            xmppConnectionService = binder.getService();
            xmppConnectionServiceBound = true;
            registerListeners();
            onBackendConnected();
            maybeShowMigrationReauthenticationDialog();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            xmppConnectionServiceBound = false;
        }
    };
    private DisplayMetrics metrics;
    private long mLastUiRefresh = 0;
    private final Handler mRefreshUiHandler = new Handler();
    private final BroadcastReceiver phoneBadgeUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (NotificationService.ACTION_PHONE_BADGES_UPDATED.equals(intent.getAction())) {
                runOnUiThread(() -> updateGlobalPhoneBadge());
            }
        }
    };
    private final BroadcastReceiver reauthenticationRequiredReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (!OkHttpClientProvider.ACTION_REAUTHENTICATION_REQUIRED.equals(intent.getAction())) {
                return;
            }
            runOnUiThread(() -> {
                pendingReauthenticationDialog = true;
                showReauthenticationDialogIfAllowed();
            });
        }
    };
    private final Runnable mRefreshUiRunnable = () -> {
        mLastUiRefresh = SystemClock.elapsedRealtime();
        refreshUiReal();
    };
    private final UiCallback<Conversation> adhocCallback = new UiCallback<Conversation>() {
        @Override
        public void success(final Conversation conversation) {
            runOnUiThread(() -> {
                switchToConversation(conversation);
                hideToast();
            });
        }

        @Override
        public void error(final int errorCode) {
            runOnUiThread(() -> replaceToast(getString(errorCode)));
        }

        @Override
        public void userInputRequired(PendingIntent pi) {

        }
    };

    public static boolean cancelPotentialWork(Message message, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Message oldMessage = bitmapWorkerTask.message;
            if (oldMessage == null || message != oldMessage) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    protected void hideToast() {
        final var toast = this.mToast;
        if (toast == null) {
            return;
        }
        toast.cancel();
    }

    protected void replaceToast(String msg) {
        replaceToast(msg, true);
    }

    protected void replaceToast(String msg, boolean showlong) {
        hideToast();
        mToast = Toast.makeText(this, msg, showlong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mToast.show();
    }

    protected final void refreshUi() {
        final long diff = SystemClock.elapsedRealtime() - mLastUiRefresh;
        if (diff > Config.REFRESH_UI_INTERVAL) {
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
            runOnUiThread(mRefreshUiRunnable);
        } else {
            final long next = Config.REFRESH_UI_INTERVAL - diff;
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next);
        }
    }

    abstract protected void refreshUiReal();

    @Override
    public void onStart() {
        super.onStart();
        if (!xmppConnectionServiceBound) {
            connectToBackend();
        } else {
            this.registerListeners();
            this.onBackendConnected();
            maybeShowMigrationReauthenticationDialog();
        }
        registerPhoneBadgeReceiver();
        registerReauthenticationReceiver();
        this.mUsingEnterKey = usingEnterKey();
    }

    public void connectToBackend() {
        Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction("ui");
        try {
            startService(intent);
        } catch (IllegalStateException e) {
            Log.w(Config.LOGTAG, "unable to start service from " + getClass().getSimpleName());
        }
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    protected boolean consumeWebhookTriggeredReauthFlag() {
        final Intent intent = getIntent();
        if (intent == null) {
            return false;
        }
        final boolean triggered = intent.getBooleanExtra(EXTRA_WEBHOOK_TRIGGERED_REAUTH, false);
        final String endpoint =
                intent.getStringExtra(EXTRA_WEBHOOK_TRIGGERED_REAUTH_ENDPOINT);
        final boolean endpointAllowed = endpoint != null
                && WEBHOOK_REAUTH_ALLOWED_ENDPOINTS.contains(endpoint);
        if (triggered || endpoint != null) {
            intent.removeExtra(EXTRA_WEBHOOK_TRIGGERED_REAUTH);
            intent.removeExtra(EXTRA_WEBHOOK_TRIGGERED_REAUTH_ENDPOINT);
            setIntent(intent);
        }
        return triggered && endpointAllowed;
    }

    private void maybeShowMigrationReauthenticationDialog() {
        if (!isReauthPromptAllowedForCurrentActivity()) {
            return;
        }
        // onServiceConnected can be delivered after onSaveInstanceState (or once the activity is
        // finishing), and committing the dialog transaction then throws IllegalStateException.
        // Bail out before the webhook flag is consumed so onStart can retry the prompt.
        if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
            return;
        }
        final boolean needsMigrationReauth =
                CognitoClientMigrationManager.isMigrationReauthV1Needed(getApplicationContext());
        if (!needsMigrationReauth && !consumeWebhookTriggeredReauthFlag()) {
            return;
        }
        final boolean alreadyShown = getSupportFragmentManager().findFragmentByTag(
                ReauthenticateAccountDialog.MIGRATION_NOTICE_TAG) != null;
        if (alreadyShown) {
            return;
        }
        ReauthenticateAccountDialog.newMigrationNoticeInstance()
                .show(getSupportFragmentManager(), ReauthenticateAccountDialog.MIGRATION_NOTICE_TAG);
    }

    private boolean isReauthPromptAllowedForCurrentActivity() {
        for (final Class<? extends XmppActivity> allowedActivity : REAUTH_PROMPT_ALLOWED_ACTIVITIES) {
            if (allowedActivity.isInstance(this)) {
                return true;
            }
        }
        return false;
    }

    protected void updateGlobalPhoneBadge() {
        final BottomNavigationView navConversations = findViewById(R.id.navigation_conversations);
        if (navConversations == null) {
            return;
        }
        NavigationUtils.updatePhoneVisibility(navConversations,
                getPreferences().getBoolean("user_sms_enabled", false));
        NavigationUtils.updatePhoneBadge(navConversations, this);
    }

    @Override
    protected void onStop() {
        unregisterPhoneBadgeReceiver();
        unregisterReauthenticationReceiver();
        super.onStop();
        if (xmppConnectionServiceBound) {
            this.unregisterListeners();
            unbindService(mConnection);
            xmppConnectionServiceBound = false;
        }
    }

    protected void registerPhoneBadgeReceiver() {
        if (phoneBadgeReceiverRegistered || getPhoneBadgeNavigationViewId() == View.NO_ID) {
            return;
        }
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                        phoneBadgeUpdateReceiver,
                        new IntentFilter(NotificationService.ACTION_PHONE_BADGES_UPDATED));
        phoneBadgeReceiverRegistered = true;
    }

    protected void unregisterPhoneBadgeReceiver() {
        if (!phoneBadgeReceiverRegistered) {
            return;
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(phoneBadgeUpdateReceiver);
        phoneBadgeReceiverRegistered = false;
    }

    protected void registerReauthenticationReceiver() {
        if (reauthenticationReceiverRegistered) {
            return;
        }
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                        reauthenticationRequiredReceiver,
                        new IntentFilter(OkHttpClientProvider.ACTION_REAUTHENTICATION_REQUIRED));
        reauthenticationReceiverRegistered = true;
    }

    private void showReauthenticationDialogIfAllowed() {
        if (!isReauthPromptAllowedForCurrentActivity()) {
            return;
        }
        if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
            return;
        }
        final boolean alreadyShown = getSupportFragmentManager().findFragmentByTag(
                ReauthenticateAccountDialog.TAG) != null;
        if (alreadyShown) {
            pendingReauthenticationDialog = false;
            return;
        }
        ReauthenticateAccountDialog.newInstance()
                .show(getSupportFragmentManager(), ReauthenticateAccountDialog.TAG);
        pendingReauthenticationDialog = false;
    }

    protected void unregisterReauthenticationReceiver() {
        if (!reauthenticationReceiverRegistered) {
            return;
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(reauthenticationRequiredReceiver);
        reauthenticationReceiverRegistered = false;
    }


    protected void refreshPhoneBadge() {
        @IdRes final int navViewId = getPhoneBadgeNavigationViewId();
        if (navViewId == View.NO_ID) {
            return;
        }
        final BottomNavigationView nav = findViewById(navViewId);
        if (nav == null) {
            return;
        }
        NavigationUtils.updatePhoneVisibility(nav, getPreferences().getBoolean("user_sms_enabled", false));
        NavigationUtils.updatePhoneBadge(nav, this);
    }

    @IdRes
    protected int getPhoneBadgeNavigationViewId() {
        return View.NO_ID;
    }


    public boolean hasPgp() {
        return xmppConnectionService.getPgpEngine() != null;
    }

    public void showInstallPgpDialog() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.openkeychain_required));
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setMessage(Html.fromHtml(getString(R.string.openkeychain_required_long, getString(R.string.app_name))));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setNeutralButton(getString(R.string.restart),
                (dialog, which) -> {
                    if (xmppConnectionServiceBound) {
                        unbindService(mConnection);
                        xmppConnectionServiceBound = false;
                    }
                    stopService(new Intent(XmppActivity.this,
                            XmppConnectionService.class));
                    finish();
                });
        builder.setPositiveButton(
                getString(R.string.install),
                (dialog, which) -> {
                    final Uri uri =
                            Uri.parse("market://details?id=org.sufficientlysecure.keychain");
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW, uri);
                    PackageManager manager = getApplicationContext().getPackageManager();
                    final var infos = manager.queryIntentActivities(marketIntent, 0);
                    if (infos.isEmpty()) {
                        final var website = Uri.parse("http://www.openkeychain.org/");
                        final Intent browserIntent = new Intent(Intent.ACTION_VIEW, website);
                        try {
                            startActivity(browserIntent);
                        } catch (final ActivityNotFoundException e) {
                            Toast.makeText(
                                            this,
                                            R.string.application_found_to_open_website,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    } else {
                        startActivity(marketIntent);
                    }
                    finish();
                });
        builder.create().show();
    }

    public void addReaction(final Message message, Consumer<Collection<String>> callback) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        final DialogAddReactionBinding viewBinding =
                DataBindingUtil.inflate(getLayoutInflater(), R.layout.dialog_add_reaction, null, false);
        builder.setView(viewBinding.getRoot());
        final var dialog = builder.create();
        bindReactionOptions(viewBinding, message, callback, dialog::dismiss);
        dialog.show();
    }

    public void bindReactionOptions(
            final DialogAddReactionBinding binding,
            final Message message,
            final Consumer<Collection<String>> callback,
            @Nullable final Runnable onComplete) {
        final LayoutInflater layoutInflater = LayoutInflater.from(binding.getRoot().getContext());
        binding.emojis.removeAllViews();
        for (final String emoji : Reaction.SUGGESTIONS) {
            final TextView button =
                    (TextView)
                            layoutInflater.inflate(
                                    R.layout.item_emoji_button, binding.emojis, false);
            binding.emojis.addView(button);
            button.setText(emoji);
            button.setOnClickListener(
                    v -> {
                        final var aggregated = message.getAggregatedReactions();
                        if (aggregated.ourReactions.contains(emoji)) {
                            callback.accept(aggregated.ourReactions);
                        } else {
                            final ImmutableSet.Builder<String> reactionBuilder =
                                    new ImmutableSet.Builder<>();
                            reactionBuilder.addAll(aggregated.ourReactions);
                            reactionBuilder.add(emoji);
                            callback.accept(reactionBuilder.build());
                        }
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    });
        }
        binding.more.setOnClickListener(
                v -> {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                    startAddReactionActivity(message);
                });
    }

    private void startAddReactionActivity(final Message message) {
        final var intent = new Intent(this, AddReactionActivity.class);
        intent.putExtra("conversation", message.getConversation().getUuid());
        intent.putExtra("message", message.getUuid());
        startActivity(intent);
    }

    public void sendReactions(final Message message, final Collection<String> reactions) {
        final Restrictions restrictions;
        if (message.getConversation() instanceof Conversation c
                && c.getMode() == Conversational.MODE_MULTI) {
            restrictions = c.getMucOptions().getReactionsRestrictions();
        } else {
            restrictions = null;
        }
        final Integer max = restrictions == null ? null : restrictions.maxReactionsPerUser();
        final Collection<String> allowList = restrictions == null ? null : restrictions.allowList();
        if (max != null && max < reactions.size()) {
            Toast.makeText(this, R.string.number_reactions_are_restricted, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (allowList != null && !allowList.isEmpty() && !allowList.containsAll(reactions)) {
            Toast.makeText(this, R.string.this_reaction_isnt_allowed, Toast.LENGTH_LONG).show();
            return;
        }
        if (!xmppConnectionService.sendReactions(message, reactions)) {
            Toast.makeText(this, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show();
        }
    }

    protected void deleteAccount(final Account account) {
        this.deleteAccount(account, null);
    }

    protected void deleteAccount(final Account account, final Runnable postDelete) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        final View dialogView = getLayoutInflater().inflate(R.layout.dialog_delete_account, null);
        final CheckBox deleteFromServer =
                dialogView.findViewById(R.id.delete_from_server);
        builder.setView(dialogView);
        builder.setTitle(R.string.mgmt_account_delete);
        builder.setPositiveButton(getString(R.string.delete), null);
        builder.setNegativeButton(getString(R.string.cancel), null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            final Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setOnClickListener(v -> {
                final boolean unregister = deleteFromServer.isChecked();
                if (unregister) {
                    if (account.isOnlineAndConnected()) {
                        deleteFromServer.setEnabled(false);
                        button.setText(R.string.please_wait);
                        button.setEnabled(false);
                        xmppConnectionService.unregisterAccount(account, result -> {
                            runOnUiThread(() -> {
                                if (result) {
                                    dialog.dismiss();
                                    if (postDelete != null) {
                                        postDelete.run();
                                    }
                                    if (xmppConnectionService.getAccounts().size() == 0) {
                                        final Intent intent = new Intent(this, LoginAccountActivity.class);
                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        startActivity(intent);
                                    }
                                } else {
                                    deleteFromServer.setEnabled(true);
                                    button.setText(R.string.delete);
                                    button.setEnabled(true);
                                    Toast.makeText(this, R.string.could_not_delete_account_from_server, Toast.LENGTH_LONG).show();
                                }
                            });
                        });
                    } else {
                        Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_LONG).show();
                    }
                } else {
                    xmppConnectionService.deleteAccount(account);
                    dialog.dismiss();
                    if (xmppConnectionService.getAccounts().size() == 0) {
                        final Intent intent = new Intent(this, LoginAccountActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    } else if (postDelete != null) {
                        postDelete.run();
                    }
                }
            });
        });
        dialog.show();
    }

    protected abstract void onBackendConnected();

    protected void registerListeners() {
        if (this instanceof XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.setOnConversationListChangedListener((XmppConnectionService.OnConversationUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMediaAvailabilityChanged) {
            this.xmppConnectionService.setOnMediaAvailabilityChangedListener((XmppConnectionService.OnMediaAvailabilityChanged) this);
        }
        if (this instanceof XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.setOnAccountListChangedListener((XmppConnectionService.OnAccountUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.setOnCaptchaRequestedListener((XmppConnectionService.OnCaptchaRequested) this);
        }
        if (this instanceof XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.setOnRosterUpdateListener((XmppConnectionService.OnRosterUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.setOnMucRosterUpdateListener((XmppConnectionService.OnMucRosterUpdate) this);
        }
        if (this instanceof OnUpdateBlocklist) {
            this.xmppConnectionService.setOnUpdateBlocklistListener((OnUpdateBlocklist) this);
        }
        if (this instanceof XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.setOnShowErrorToastListener((XmppConnectionService.OnShowErrorToast) this);
        }
        if (this instanceof OnKeyStatusUpdated) {
            this.xmppConnectionService.setOnKeyStatusUpdatedListener((OnKeyStatusUpdated) this);
        }
        if (this instanceof XmppConnectionService.OnJingleRtpConnectionUpdate) {
            this.xmppConnectionService.setOnRtpConnectionUpdateListener((XmppConnectionService.OnJingleRtpConnectionUpdate) this);
        }

        if (this instanceof XmppConnectionService.OnOrganizationUpdate) {
            this.xmppConnectionService.setOnOrganizationChangedListener((XmppConnectionService.OnOrganizationUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnAmplifySync) {
            this.xmppConnectionService.setOnAmplifySyncListener((XmppConnectionService.OnAmplifySync) this);
        }

        if (this instanceof XmppConnectionService.OnCoreStatusUpdate) {
            this.xmppConnectionService.setOnCoreStatusUpdateListener((XmppConnectionService.OnCoreStatusUpdate) this);
        }

        if (this instanceof XmppConnectionService.OnPhoneUpdate) {
            this.xmppConnectionService.setPhoneOnUpdateListener((XmppConnectionService.OnPhoneUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnNativeContactUpdate) {
            this.xmppConnectionService.setOnNativeContactsUpdatedListener((XmppConnectionService.OnNativeContactUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnConsoleStatus) {
            this.xmppConnectionService.setOnConsoleStatusListener((XmppConnectionService.OnConsoleStatus) this);
        }

        mExternalCallManager = xmppConnectionService.getExternalCallManager();
        if (mExternalCallManager != null) {
            mExternalCallManager.setCallListener(mInternalCallListener);
            updateReturnToExternalCall(mExternalCallManager.isOnCall());
        } else {
            updateReturnToExternalCall(false);

        }

        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(jingleManager != null && jingleManager.getOngoingRtpConnection() != null);
    }

    protected void unregisterListeners() {
        if (this instanceof XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.removeOnConversationListChangedListener((XmppConnectionService.OnConversationUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMediaAvailabilityChanged) {
            this.xmppConnectionService.removeOnMediaAvailabilityChangedListener((XmppConnectionService.OnMediaAvailabilityChanged) this);
        }
        if (this instanceof XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.removeOnAccountListChangedListener((XmppConnectionService.OnAccountUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.removeOnCaptchaRequestedListener((XmppConnectionService.OnCaptchaRequested) this);
        }
        if (this instanceof XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.removeOnRosterUpdateListener((XmppConnectionService.OnRosterUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.removeOnMucRosterUpdateListener((XmppConnectionService.OnMucRosterUpdate) this);
        }
        if (this instanceof OnUpdateBlocklist) {
            this.xmppConnectionService.removeOnUpdateBlocklistListener((OnUpdateBlocklist) this);
        }
        if (this instanceof XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.removeOnShowErrorToastListener((XmppConnectionService.OnShowErrorToast) this);
        }
        if (this instanceof OnKeyStatusUpdated) {
            this.xmppConnectionService.removeOnNewKeysAvailableListener((OnKeyStatusUpdated) this);
        }
        if (this instanceof XmppConnectionService.OnJingleRtpConnectionUpdate) {
            this.xmppConnectionService.removeRtpConnectionUpdateListener((XmppConnectionService.OnJingleRtpConnectionUpdate) this);
        }

        if (this instanceof XmppConnectionService.OnOrganizationUpdate) {
            this.xmppConnectionService.removeOnOrganizationChangedListener((XmppConnectionService.OnOrganizationUpdate) this);
        }

        if (this instanceof XmppConnectionService.OnAmplifySync) {
            this.xmppConnectionService.removeOnAmplifySyncListener((XmppConnectionService.OnAmplifySync) this);
        }
        if (this instanceof XmppConnectionService.OnCoreStatusUpdate) {
            this.xmppConnectionService.removeOnCoreStatusUpdateListener((XmppConnectionService.OnCoreStatusUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnNativeContactUpdate) {
            this.xmppConnectionService.removeOnNativeContactsUpdatedListener((XmppConnectionService.OnNativeContactUpdate) this);
        }

        if (this instanceof XmppConnectionService.OnConsoleStatus) {
            this.xmppConnectionService.removeOnConsoleStatusListener((XmppConnectionService.OnConsoleStatus) this);
        }

        if (this instanceof XmppConnectionService.OnPhoneUpdate) {
            this.xmppConnectionService.removeOnPhoneUpdateListener((XmppConnectionService.OnPhoneUpdate) this);
        }

        if (mExternalCallManager != null) {
            mExternalCallManager.setCallListener(null);
        }
        updateReturnToExternalCall(false);
        updateReturnToGlacierCall(false);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.action_privacy_policy) {
            openPrivacyPolicy();
        } else if (id == R.id.action_support) {
            openSupportPage();
        } else if (id == R.id.action_filesafe) {
            startActivity(new Intent(this, FileSafeActivity.class));
        } else if (id == R.id.action_accounts) {
            AccountUtils.launchManageAccounts(this);
        } else if (id == R.id.action_account) {
            AccountUtils.launchManageAccount(this);
        } else if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.action_core_connect) {
            navigateToCore();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (menu != null) {
            final MenuItem fileSafe = menu.findItem(R.id.action_filesafe);
            if (fileSafe != null) {
                fileSafe.setVisible(getPreferences().getBoolean("upload_enabled", false));
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void navigateToCore() {
        Intent coreConnectionActivity = new Intent(getApplicationContext(), CoreConnectionActivity.class);
        startActivity(coreConnectionActivity);
    }

    private void updateReturnToExternalCall(boolean show) {
        final View bar = findViewById(R.id.return_to_external_call);
        if (bar == null) {
            return;
        }

        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            bar.setOnClickListener(v -> {
                if (mExternalCallManager != null && mExternalCallManager.isOnCall()) {
                    Intent intent = new Intent(getApplicationContext(), ExternalCallActivity.class);
                    intent.putExtra("returning", "true");
                    startActivity(intent);
                } else {
                    bar.setVisibility(View.GONE);
                }
            });
        } else {
            bar.setOnClickListener(null);
        }
    }

    protected void updateReturnToGlacierCall(boolean show) {
        final View bar = findViewById(R.id.return_to_glacier_call);
        if (bar == null) {
            return;
        }

        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            bar.setOnClickListener(v -> {
                final var manager = xmppConnectionService.getJingleConnectionManager();
                if (manager != null) {
                    final var jrc = manager.getOngoingRtpConnection();
                    if (jrc != null) {
                        final Intent intent = new Intent(getApplicationContext(), RtpSessionActivity.class);
                        intent.setAction(Intent.ACTION_VIEW);
                        intent.putExtra(RtpSessionActivity.EXTRA_ACCOUNT,
                                jrc.getId().account.getJid().asBareJid().toEscapedString());
                        intent.putExtra(RtpSessionActivity.EXTRA_WITH, jrc.getId().with.toEscapedString());
                        intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, jrc.getId().sessionId)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);
                    } else {
                        bar.setVisibility(View.GONE);
                    }
                } else {
                    bar.setVisibility(View.GONE);
                }
            });
        } else {
            bar.setOnClickListener(null);
        }
    }

    private void openPrivacyPolicy() {
        final var viewPolicyIntent = new Intent(Intent.ACTION_VIEW);
        viewPolicyIntent.setData(Uri.parse(BuildConfig.PRIVACY_POLICY));
        try {
            startActivity(viewPolicyIntent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void openSupportPage() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(getString(R.string.support_url)));
        try {
            startActivity(intent);
        } catch (java.lang.Exception ex) {
            runOnUiThread(() -> replaceToast(getString(R.string.no_web_access)));

        }
    }


    public void selectPresence(final Conversation conversation, final PresenceSelector.OnPresenceSelected listener) {
        final Contact contact = conversation.getContact();
        if (contact.showInRoster() || contact.isSelf()) {
            final Presences presences = contact.getPresences();
            if (presences.size() == 0) {
                if (contact.isSelf()) {
                    conversation.setNextCounterpart(null);
                    listener.onPresenceSelected();
                } else if (!contact.getOption(Contact.Options.TO)
                        && !contact.getOption(Contact.Options.ASKING)
                        && contact.getAccount().getStatus() == Account.State.ONLINE) {
                    showAskForPresenceDialog(contact);
                } else if (!contact.getOption(Contact.Options.TO)
                        || !contact.getOption(Contact.Options.FROM)) {
                    PresenceSelector.warnMutualPresenceSubscription(this, conversation, listener);
                } else {
                    conversation.setNextCounterpart(null);
                    listener.onPresenceSelected();
                }
            } else if (presences.size() == 1) {
                final String presence = presences.toResourceArray()[0];
                conversation.setNextCounterpart(PresenceSelector.getNextCounterpart(contact, presence));
                listener.onPresenceSelected();
            } else {
                PresenceSelector.showPresenceSelectionDialog(this, conversation, listener);
            }
        } else {
            showAddToRosterDialog(conversation.getContact());
        }
    }

    @SuppressLint("UnsupportedChromeOsCameraSystemFeature")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        metrics = getResources().getDisplayMetrics();
        this.isCameraFeatureAvailable = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    protected boolean isCameraFeatureAvailable() {
        return this.isCameraFeatureAvailable;
    }

    protected boolean isOptimizingBattery() {
        final PowerManager pm = getSystemService(PowerManager.class);
        return !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    protected boolean isAffectedByDataSaver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null
                    && cm.isActiveNetworkMetered()
                    && Compatibility.getRestrictBackgroundStatus(cm) == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        } else {
            return false;
        }
    }

    private boolean usingEnterKey() {
        return getBooleanPreference("display_enter_key", R.bool.display_enter_key);
    }

    protected SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    protected boolean getBooleanPreference(String name, @BoolRes int res) {
        return getPreferences().getBoolean(name, getResources().getBoolean(res));
    }

    public void switchToConversation(Conversation conversation) {
        switchToConversation(conversation, null);
    }

    public void switchToConversationAndQuote(Conversation conversation, String text) {
        switchToConversation(conversation, text, true, null, false, false);
    }

    public void switchToConversation(Conversation conversation, String text) {
        switchToConversation(conversation, text, false, null, false, false);
    }

    public void switchToConversationDoNotAppend(Conversation conversation, String text) {
        switchToConversation(conversation, text, false, null, false, true);
    }

    public void highlightInMuc(Conversation conversation, String nick) {
        switchToConversation(conversation, null, false, nick, false, false);
    }

    public void privateMsgInMuc(Conversation conversation, String nick) {
        switchToConversation(conversation, null, false, nick, true, false);
    }

    private void switchToConversation(Conversation conversation, String text, boolean asQuote, String nick, boolean pm, boolean doNotAppend) {
        Intent intent = new Intent(this, ConversationsActivity.class);
        intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        if (text != null) {
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (asQuote) {
                intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, true);
            }
        }
        if (nick != null) {
            intent.putExtra(ConversationsActivity.EXTRA_NICK, nick);
            intent.putExtra(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, pm);
        }
        if (doNotAppend) {
            intent.putExtra(ConversationsActivity.EXTRA_DO_NOT_APPEND, true);
        }
        intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    public void switchToContactDetails(Contact contact) {
        switchToContactDetails(contact, null);
    }

    public void switchToContactDetails(Contact contact, String messageFingerprint) {
        Intent intent = new Intent(this, ContactDetailsActivity.class);
        intent.setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT);
        intent.putExtra(EXTRA_ACCOUNT, contact.getAccount().getJid().asBareJid().toEscapedString());
        intent.putExtra("contact", contact.getJid().toEscapedString());
        intent.putExtra("fingerprint", messageFingerprint);
        startActivity(intent);
    }

    public void switchToAccount(Account account, String fingerprint) {
        switchToAccount(account, false, fingerprint);
    }

    public void switchToAccount(Account account) {
        switchToAccount(account, false, null);
    }

    public void switchToAccount(Account account, boolean init, String fingerprint) {
        Intent intent = new Intent(this, MyProfileActivity.class);
        intent.putExtra("jid", account.getJid().asBareJid().toEscapedString());
        intent.putExtra("init", init);
        if (init) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        if (fingerprint != null) {
            intent.putExtra("fingerprint", fingerprint);
        }
        startActivity(intent);
        if (init) {
            overridePendingTransition(0, 0);
        }
    }

    protected void delegateUriPermissionsToService(Uri uri) {
        Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startService(intent);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "unable to delegate uri permission", e);
        }
    }

    protected void inviteToConversation(Conversation conversation) {
        startActivityForResult(ChooseContactActivity.create(this, conversation), REQUEST_INVITE_TO_CONVERSATION);
    }

    protected void announcePgp(final Account account, final Conversation conversation, Intent intent, final Runnable onSuccess) {
        if (account.getPgpId() == 0) {
            choosePgpSignId(account);
        } else {
            final String status = Strings.nullToEmpty(account.getPresenceStatusMessage());
            xmppConnectionService.getPgpEngine().generateSignature(intent, account, status, new UiCallback<String>() {

                @Override
                public void userInputRequired(final PendingIntent pi) {
                    try {
                        startIntentSenderForResult(pi.getIntentSender(), REQUEST_ANNOUNCE_PGP, null, 0, 0, 0, Compatibility.pgpStartIntentSenderOptions());
                    } catch (final SendIntentException ignored) {
                    }
                }

                @Override
                public void success(String signature) {
                    account.setPgpSignature(signature);
                    xmppConnectionService.databaseBackend.updateAccount(account);
                    xmppConnectionService.sendPresence(account);
                    if (conversation != null) {
                        conversation.setNextEncryption(Message.ENCRYPTION_PGP);
                        xmppConnectionService.updateConversation(conversation);
                        refreshUi();
                    }
                    if (onSuccess != null) {
                        runOnUiThread(onSuccess);
                    }
                }

                @Override
                public void error(int error) {
                    if (error == 0) {
                        account.setPgpSignId(0);
                        account.unsetPgpSignature();
                        xmppConnectionService.databaseBackend.updateAccount(account);
                        choosePgpSignId(account);
                    } else {
                        displayErrorDialog(error);
                    }
                }
            });
        }
    }

    protected void choosePgpSignId(final Account account) {
        xmppConnectionService.getPgpEngine().chooseKey(account, new UiCallback<>() {
            @Override
            public void success(final Account a) {
            }

            @Override
            public void error(int errorCode) {

            }

            @Override
            public void userInputRequired(PendingIntent pi) {
                try {
                    startIntentSenderForResult(pi.getIntentSender(),
                            REQUEST_CHOOSE_PGP_ID, null, 0, 0, 0, Compatibility.pgpStartIntentSenderOptions());
                } catch (final SendIntentException ignored) {
                }
            }
        });
    }

    protected void displayErrorDialog(final int errorCode) {
        runOnUiThread(() -> {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(XmppActivity.this);
            builder.setTitle(getString(R.string.error));
            builder.setMessage(errorCode);
            builder.setNeutralButton(R.string.accept, null);
            builder.create().show();
        });

    }

    protected void showAddToRosterDialog(final Contact contact) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(contact.getJid().toString());
        builder.setMessage(getString(R.string.not_in_roster));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.add_contact), (dialog, which) -> xmppConnectionService.createContact(contact, true));
        builder.create().show();
    }

    private void showAskForPresenceDialog(final Contact contact) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(contact.getJid().toString());
        builder.setMessage(R.string.request_presence_updates);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.request_now,
                (dialog, which) -> {
                    if (xmppConnectionServiceBound) {
                        xmppConnectionService.sendPresencePacket(contact
                                .getAccount(), xmppConnectionService
                                .getPresenceGenerator()
                                .requestPresenceUpdatesFrom(contact));
                    }
                });
        builder.create().show();
    }

    protected void quickEdit(String previousValue, @StringRes int hint, OnValueEdited callback) {
        quickEdit(previousValue, callback, hint, false, false);
    }

    protected void quickEdit(String previousValue, @StringRes int hint, OnValueEdited callback, boolean permitEmpty) {
        quickEdit(previousValue, callback, hint, false, permitEmpty);
    }

    protected void quickPasswordEdit(String previousValue, OnValueEdited callback) {
        quickEdit(previousValue, callback, R.string.password, true, false);
    }

    @SuppressLint("InflateParams")
    private void quickEdit(final String previousValue,
                           final OnValueEdited callback,
                           final @StringRes int hint,
                           boolean password,
                           boolean permitEmpty) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        final DialogQuickeditBinding binding = DataBindingUtil.inflate(getLayoutInflater(), R.layout.dialog_quickedit, null, false);
        if (password) {
            binding.inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        builder.setPositiveButton(R.string.accept, null);
        if (hint != 0) {
            binding.inputLayout.setHint(getString(hint));
        }
        binding.inputEditText.requestFocus();
        if (previousValue != null) {
            binding.inputEditText.getText().append(previousValue);
        }
        builder.setView(binding.getRoot());
        builder.setNegativeButton(R.string.cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> SoftKeyboardUtils.showKeyboard(binding.inputEditText));
        dialog.show();
        View.OnClickListener clickListener = v -> {
            String value = binding.inputEditText.getText().toString();
            if (!value.equals(previousValue) && (!value.trim().isEmpty() || permitEmpty)) {
                String error = callback.onValueEdited(value);
                if (error != null) {
                    binding.inputLayout.setError(error);
                    return;
                }
            }
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
            dialog.dismiss();
        };
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(clickListener);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener((v -> {
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
            dialog.dismiss();
        }));
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(dialog1 -> {
            SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
        });
    }

    protected boolean hasStoragePermission() {
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INVITE_TO_CONVERSATION && resultCode == RESULT_OK) {
            mPendingConferenceInvite = ConferenceInvite.parse(data);
            if (xmppConnectionServiceBound && mPendingConferenceInvite != null) {
                if (mPendingConferenceInvite.execute(this)) {
                    mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
                    mToast.show();
                }
                mPendingConferenceInvite = null;
            }
        }
    }

    public boolean copyTextToClipboard(String text, int labelResId) {
        ClipboardManager mClipBoardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String label = getResources().getString(labelResId);
        if (mClipBoardManager != null) {
            ClipData mClipData = ClipData.newPlainText(label, text);
            mClipBoardManager.setPrimaryClip(mClipData);
            return true;
        }
        return false;
    }

    protected boolean manuallyChangePresence() {
        return getBooleanPreference(AppSettings.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
    }

    protected String getShareableUri() {
        return getShareableUri(false);
    }

    protected String getShareableUri(boolean http) {
        return null;
    }

    protected void shareLink(boolean http) {
        String uri = getShareableUri(http);
        if (uri == null || uri.isEmpty()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, getShareableUri(http));
        try {
            startActivity(Intent.createChooser(intent, getText(R.string.share_uri_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show();
        }
    }

    protected void launchOpenKeyChain(long keyId) {
        PgpEngine pgp = XmppActivity.this.xmppConnectionService.getPgpEngine();
        try {
            startIntentSenderForResult(
                    pgp.getIntentForKey(keyId).getIntentSender(), 0, null, 0,
                    0, 0, Compatibility.pgpStartIntentSenderOptions());
        } catch (final Throwable e) {
            Toast.makeText(XmppActivity.this, R.string.openpgp_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingReauthenticationDialog) {
            showReauthenticationDialogIfAllowed();
        }
        SettingsUtils.applyScreenshotSetting(this);
        View consoleError = findViewById(R.id.console_error);
        if (consoleError != null && consoleError.getVisibility() == View.VISIBLE && xmppConnectionServiceBound) {
            xmppConnectionService.checkConsoleStatus();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        View consoleError = findViewById(R.id.console_error);
        if (consoleError != null) {
            consoleError.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onMenuOpened(int id, Menu menu) {
        if (id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
            MenuDoubleTabUtil.recordMenuOpen();
            forceShowOverflowIcons(menu);
        }
        return super.onMenuOpened(id, menu);
    }

    private static void forceShowOverflowIcons(Menu menu) {
        if (menu != null && menu.getClass().getSimpleName().equals("MenuBuilder")) {
            try {
                Method setOptionalIconsVisible =
                        menu.getClass().getDeclaredMethod("setOptionalIconsVisible", Boolean.TYPE);
                setOptionalIconsVisible.setAccessible(true);
                setOptionalIconsVisible.invoke(menu, true);
            } catch (Exception ignored) {
            }
        }
    }

    protected void showQrCode() {
        showQrCode(getShareableUri());
    }

    protected void showQrCode(final String uri) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        final Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        final int width = Math.min(size.x, size.y);
        final int black;
        final int white;
        if (Activities.isNightMode(this)) {
            black = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest, "No surface color configured");
            white = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceInverse, "No inverse surface color configured");
        } else {
            black = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceInverse, "No inverse surface color configured");
            white = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest, "No surface color configured");
        }
        final var bitmap = BarcodeProvider.create2dBarcodeBitmap(uri, width, black, white);
        final ImageView view = new ImageView(this);
        view.setBackgroundColor(white);
        view.setImageBitmap(bitmap);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(view);
        builder.create().show();
    }

    protected Account extractAccount(Intent intent) {
        final String jid = intent != null ? intent.getStringExtra(EXTRA_ACCOUNT) : null;
        try {
            return jid != null ? xmppConnectionService.findAccountByJid(Jid.ofEscaped(jid)) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public AvatarService avatarService() {
        return xmppConnectionService.getAvatarService();
    }

    public void loadBitmap(Message message, ImageView imageView) {
        Drawable bm;
        try {
            bm = xmppConnectionService.getFileBackend().getThumbnail(message, getResources(), (int) (metrics.density * 288), true);
        } catch (IOException e) {
            bm = null;
        }
        if (bm != null) {
            cancelPotentialWork(message, imageView);
            imageView.setImageDrawable(bm);
            imageView.setBackgroundColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 28 && bm instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) bm).start();
            }
        } else {
            if (cancelPotentialWork(message, imageView)) {
                imageView.setBackgroundColor(MaterialColors.getColor(imageView, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF333333));
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
                final AsyncDrawable asyncDrawable = new AsyncDrawable(
                        getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.execute(message);
                } catch (final RejectedExecutionException ignored) {
                    ignored.printStackTrace();
                }
            }
        }
    }

    protected interface OnValueEdited {
        String onValueEdited(String value);
    }

    public static class ConferenceInvite {
        private String uuid;
        private final List<Jid> jids = new ArrayList<>();

        public static ConferenceInvite parse(Intent data) {
            ConferenceInvite invite = new ConferenceInvite();
            invite.uuid = data.getStringExtra(ChooseContactActivity.EXTRA_CONVERSATION);
            if (invite.uuid == null) {
                return null;
            }
            invite.jids.addAll(ChooseContactActivity.extractJabberIds(data));
            return invite;
        }

        public boolean execute(final XmppActivity activity) {
            final XmppConnectionService service = activity.xmppConnectionService;
            final Conversation conversation = service.findConversationByUuid(this.uuid);
            if (conversation == null) {
                return false;
            }
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                for (final Jid jid : jids) {
                    service.invite(conversation, jid);
                }
                return false;
            } else {
                jids.add(conversation.getJid().asBareJid());
                return service.createAdhocConference(conversation.getAccount(), null, jids, activity.adhocCallback);
            }
        }
    }

    static class BitmapWorkerTask extends AsyncTask<Message, Void, Drawable> {
        private final WeakReference<ImageView> imageViewReference;
        private Message message = null;

        private BitmapWorkerTask(ImageView imageView) {
            this.imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Drawable doInBackground(Message... params) {

            if (isCancelled()) {
                return null;
            }
            message = params[0];
            try {
                final XmppActivity activity = find(imageViewReference);
                if (activity != null && activity.xmppConnectionService != null) {
                    return activity.xmppConnectionService.getFileBackend().getThumbnail(message, imageViewReference.get().getContext().getResources(), (int) (activity.metrics.density * 288), false);
                } else {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Drawable drawable) {
            if (!isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageDrawable(drawable);
                    imageView.setBackgroundColor(drawable == null ? MaterialColors.getColor(imageView, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF333333) : 0x00000000);
                    if (Build.VERSION.SDK_INT >= 28 && drawable instanceof AnimatedImageDrawable) {
                        ((AnimatedImageDrawable) drawable).start();
                    }
                }
            }
        }
    }

        private static class AsyncDrawable extends BitmapDrawable {
            private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

            private AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
                super(res, bitmap);
                bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
            }

            private BitmapWorkerTask getBitmapWorkerTask() {
                return bitmapWorkerTaskReference.get();
            }
        }

        public static XmppActivity find(@NonNull WeakReference<ImageView> viewWeakReference) {
            final View view = viewWeakReference.get();
            return view == null ? null : find(view);
        }

        public static XmppActivity find(@NonNull final View view) {
            Context context = view.getContext();
            while (context instanceof ContextWrapper) {
                if (context instanceof XmppActivity) {
                    return (XmppActivity) context;
                }
                context = ((ContextWrapper) context).getBaseContext();
            }
            return null;

    }
}
