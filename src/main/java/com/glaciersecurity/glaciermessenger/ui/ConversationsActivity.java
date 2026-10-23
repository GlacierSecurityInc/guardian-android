/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.glaciersecurity.glaciermessenger.ui;


import static com.glaciersecurity.glaciermessenger.ui.ConversationFragment.REQUEST_DECRYPT_PGP;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

import com.glaciersecurity.glaciermessenger.conversations.utils.SignupUtils;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.ShortcutService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.databinding.ActivityConversationsBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.DirectConnectionUtils;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.openintents.openpgp.util.OpenPgpApi;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.OmemoSetting;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Conversational;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.util.NavigationUtils;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnBackendConnected;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationArchived;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationRead;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationSelected;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnConversationsListItemUpdated;
import com.glaciersecurity.glaciermessenger.ui.util.ActivityResult;
import com.glaciersecurity.glaciermessenger.ui.util.ConversationMenuConfigurator;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.ToolbarUtils;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.ui.RtpSessionActivity;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.utils.ExceptionHelper;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;
import com.twilio.conversations.ConversationsClient;
import com.twilio.conversations.Message;


public class ConversationsActivity extends XmppActivity implements ConversationsManagerListener, OnConversationSelected, OnConversationArchived, OnConversationsListItemUpdated, OnConversationRead, XmppConnectionService.OnAccountUpdate, XmppConnectionService.OnConversationUpdate, XmppConnectionService.OnRosterUpdate, OnUpdateBlocklist, XmppConnectionService.OnShowErrorToast, XmppConnectionService.OnAffiliationChanged, ConnectivityReceiver.ConnectivityReceiverListener, XmppConnectionService.OnJingleRtpConnectionUpdate{

    public static final String ACTION_VIEW_CONVERSATION = "com.glaciersecurity.glaciermessenger.action.VIEW";

    public static final String EXTRA_CONVERSATION = "com.glaciersecurity.glaciermessenger.conversationUuid";
    public static final String EXTRA_DOWNLOAD_UUID = "download_uuid";
    public static final String EXTRA_AS_QUOTE = "as_quote";
    public static final String EXTRA_NICK = "nick";
    public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";
    public static final String EXTRA_DO_NOT_APPEND = "do_not_append";
    public static final String EXTRA_POST_INIT_ACTION = "post_init_action";
    public static final String POST_ACTION_RECORD_VOICE = "record_voice";
    public static final String EXTRA_TYPE = "type";


    private static final List<String> VIEW_AND_SHARE_ACTIONS = Arrays.asList(
            ACTION_VIEW_CONVERSATION,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE
);

    public static final int REQUEST_OPEN_MESSAGE = 0x9876;
    public static final int REQUEST_PLAY_PAUSE = 0x5432;


    //secondary fragment (when holding the conversation, must be initialized before refreshing the overview fragment
    private static final @IdRes
    int[] FRAGMENT_ID_NOTIFICATION_ORDER = {R.id.secondary_fragment, R.id.main_fragment};
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    private ActivityConversationsBinding binding;
    private boolean mActivityPaused = true;
    private final AtomicBoolean mRedirectInProcess = new AtomicBoolean(false);
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;
    private ConversationsManager conversationsManager;
    private long lastForcedReconnectAttempt = 0;
    private static final long FORCED_RECONNECT_MIN_INTERVAL_MS = 10_000L;
    // showDialogsIfMainIsOverview() also runs on every back stack change, so the battery
    // optimization prompt is capped at once per activity instance rather than being permanently
    // silenced the first time it is shown.
    private boolean askedForBatteryOptimizations = false;


    private static boolean isViewOrShareIntent(Intent i) {
        return i != null
                && VIEW_AND_SHARE_ACTIONS.contains(i.getAction())
                && (i.hasExtra(EXTRA_CONVERSATION) || i.hasExtra(Intent.EXTRA_SHORTCUT_ID));
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, ConversationsActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    @Override
    protected int getPhoneBadgeNavigationViewId() {
        return R.id.navigation_conversations;
    }

    @Override
    protected void refreshUiReal() {
        invalidateOptionsMenu();
        invalidateActionBarTitle();
        if (binding.navigationConversations != null) {
            NavigationUtils.updatePhoneVisibility(binding.navigationConversations,
                    getPreferences().getBoolean("user_sms_enabled", false));
        }
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id);
        }
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.updateOfflineStatusBar();
        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(jingleManager != null && jingleManager.getOngoingRtpConnection() != null);
        }
    }

    @Override
    protected void onBackendConnected() {
        if (performRedirectIfNecessary(true)) {
            return;
        }
        xmppConnectionService.getNotificationService().setIsInForeground(true);
        NavigationUtils.updatePhoneVisibility(binding.navigationConversations,getPreferences().getBoolean("user_sms_enabled", false) );

                NavigationUtils.updateSecurityHubBadge(binding.navigationConversations,
                        !xmppConnectionService.getSecInfo().isSecure());

        conversationsManager = xmppConnectionService.getConversationsManager();
        if (conversationsManager != null) {
            conversationsManager.setOnConversationsManagerListener(this);
        }

        forceReconnectIfWaitingOnBackoff();

        final Intent intent = pendingViewIntent.pop();
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment);
                }
                invalidateActionBarTitle();
                return;
            }
        }
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id);
        }

        final ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }

        invalidateActionBarTitle();
        if (binding.secondaryFragment != null && ConversationFragment.getConversation(this) == null) {
            Conversation conversation = ConversationsOverviewFragment.getSuggestion(this);
            if (conversation != null) {
                openConversation(conversation, null);
            }
        }
        showDialogsIfMainIsOverview();
        loadProfileBar();

        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);

        connectionToolbarAdapter.updateOfflineStatusBar();
        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(jingleManager != null && jingleManager.getOngoingRtpConnection() != null);

    }

    /**
     * When the Chats screen comes to the foreground, the account may be sitting idle in a
     * reconnect-backoff window (up to several minutes) rather than actively connecting. Waiting
     * for that backoff to expire on its own is what makes the "Connecting" bar appear to hang.
     * Nudge a reconnect attempt immediately instead, throttled so rapid onStart/onStop cycles
     * (e.g. rotation) don't cause repeated forced reconnects.
     */
    private void forceReconnectIfWaitingOnBackoff() {
        if (xmppConnectionService == null || xmppConnectionService.getAccounts().isEmpty()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - lastForcedReconnectAttempt < FORCED_RECONNECT_MIN_INTERVAL_MS) {
            return;
        }
        final Account account = xmppConnectionService.getAccounts().get(0);
        final Account.State status = account.getStatus();
        if (status.isAttemptReconnect()
                && status != Account.State.CONNECTING
                && status != Account.State.ONLINE) {
            lastForcedReconnectAttempt = now;
            xmppConnectionService.reconnectAccountInBackground(account);
        }
    }


    private void loadProfileBar() {
        Account account = getFirstAccount();
        if (account != null) {
            AvatarWorkerTask.loadAvatar(account, binding.avater, R.dimen.avatar_on_details_screen_size);

        }
    }


    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (menu != null) {
            final MenuItem fileSafe = menu.findItem(R.id.action_filesafe);
            if (fileSafe != null) {
                fileSafe.setVisible(getPreferences().getBoolean("upload_enabled", false));
            }
        }return super.onPrepareOptionsMenu(menu);
    }

    private Account getFirstAccount(){
        if (xmppConnectionServiceBound) {
            return xmppConnectionService.getFirstAccount();
        }
        return null;
    }

    private boolean performRedirectIfNecessary(boolean noAnimation) {
        return performRedirectIfNecessary(null, noAnimation);
    }

    private boolean performRedirectIfNecessary(final Conversation ignore, final boolean noAnimation) {
        if (xmppConnectionService == null) {
            return false;
        }
        final boolean isConversationsListEmpty = xmppConnectionService.isConversationsListEmpty(ignore);
        if (!isConversationsListEmpty) {
            mRedirectInProcess.set(false);
            return false;
        }

        if (!xmppConnectionService.getAccounts().isEmpty()) {
            mRedirectInProcess.set(false);
            return false;
        }

        // The service caches its account list in memory, so "no accounts" can mean the database
        // really is empty or merely that the service has not caught up with it -- as after a
        // backup restore writes rows straight to SQLite. Sending a user who has an account into
        // onboarding is the worse error of the two, so let the database have the last word.
        if (!DatabaseBackend.getInstance(this).getAccounts().isEmpty()) {
            mRedirectInProcess.set(false);
            return false;
        }

        if (mRedirectInProcess.compareAndSet(false, true)) {
            final Intent intent = SignupUtils.getRedirectionIntent(this);
            if (noAnimation) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            }
            runOnUiThread(() -> {
                startActivity(intent);
                if (noAnimation) {
                    overridePendingTransition(0, 0);
                }
            });
        }
        return true;
    }

    private void showDialogsIfMainIsOverview() {
        if (xmppConnectionService == null) {
            return;
        }
        final Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            if (ExceptionHelper.checkForCrash(this)) {
                return;
            }
            if (openBatteryOptimizationDialogIfNeeded()) {
                return;
            }
            requestRuntimePermissionsIfNeeded();
        }
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds") String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    private boolean openBatteryOptimizationDialogIfNeeded() {
        if (askedForBatteryOptimizations) {
            return false;
        }
        if (isOptimizingBattery() && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
            askedForBatteryOptimizations = true;
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.battery_optimizations_enabled);
            builder.setMessage(getString(R.string.battery_optimizations_enabled_dialog, getString(R.string.app_name)));
            builder.setPositiveButton(R.string.next, (dialog, which) -> {
                final Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                final Uri uri = Uri.parse("package:" + getPackageName());
                intent.setData(uri);
                try {
                    startActivityForResult(intent, REQUEST_BATTERY_OP);
                } catch (final ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show();
                    setNeverAskForBatteryOptimizationsAgain();
                }
            });
            // Only an explicit decline stops the prompt for good. Tapping 'next' used to dismiss the
            // dialog and therefore silence it too, so anyone who backed out of the system screen
            // without granting the exemption was never asked again.
            builder.setNegativeButton(R.string.not_now, (dialog, which) -> setNeverAskForBatteryOptimizationsAgain());
            builder.setOnCancelListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
            return true;
        }
        return false;
    }

    private boolean requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATION);
            return true;
        }
        return false;
    }

    private boolean requestLocalNetworkPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= DirectConnectionUtils.ACCESS_LOCAL_NETWORK_SDK_INT
                && ActivityCompat.checkSelfPermission(this, DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION}, REQUEST_LOCAL_NETWORK_ACCESS);
            return true;
        }
        return false;
    }

    // Requests at most one runtime permission at a time so system dialogs never overlap.
    // Existing users (who never went through onboarding again after an app update) get
    // prompted for new permissions, like ACCESS_LOCAL_NETWORK, here as well.
    // Never media: with "selected photos" access, asking opens the photo picker on launch.
    private void requestRuntimePermissionsIfNeeded() {
        if (requestNotificationPermissionIfNeeded()) {
            return;
        }
        requestLocalNetworkPermissionIfNeeded();
    }

    private void notifyFragmentOfBackendConnected(@IdRes int id) {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment instanceof OnBackendConnected callback) {
            callback.onBackendConnected();
        }
    }

    private void refreshFragment(@IdRes int id) {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment instanceof XmppFragment xmppFragment) {
            xmppFragment.refresh();
        }
    }

    private boolean processViewIntent(Intent intent) {
        final String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
        Conversation conversation = uuid != null ? xmppConnectionService.findConversationByUuid(uuid) : null;
        if (conversation == null) {
            final String shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
            conversation = shortcutId != null
                    ? ShortcutService.findConversationForShortcut(xmppConnectionService, shortcutId)
                    : null;
        }
        if (conversation == null) {
            return false;
        }
        openConversation(conversation, intent.getExtras());
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATION) {
            // continue the chain regardless of grant/deny so ACCESS_LOCAL_NETWORK still gets requested
            requestLocalNetworkPermissionIfNeeded();
        }
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switch (requestCode) {
                    case REQUEST_OPEN_MESSAGE:
                        refreshUiReal();
                        ConversationFragment.openPendingMessage(this);
                        break;
                    case REQUEST_PLAY_PAUSE:
                        ConversationFragment.startStopPending(this);
                        break;
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(final ActivityResult activityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }
        if (activityResult.requestCode == REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            requestRuntimePermissionsIfNeeded();
            XmppConnectionService.toggleForegroundService(xmppConnectionService);
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                if (conversation == null) {
                    break;
                }
                conversation.getAccount().getPgpDecryptionService().giveUpCurrentDecryption();
                break;
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        if (conversation == null) {
            return;
        }
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                conversation.getAccount().getPgpDecryptionService().continueDecryption(data);
                break;
            case REQUEST_CHOOSE_PGP_ID:
                long id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0);
                if (id != 0) {
                    conversation.getAccount().setPgpSignId(id);
                    announcePgp(conversation.getAccount(), null, null, onOpenPGPKeyPublished);
                } else {
                    choosePgpSignId(conversation.getAccount());
                }
                break;
            case REQUEST_ANNOUNCE_PGP:
                announcePgp(conversation.getAccount(), conversation, data, onOpenPGPKeyPublished);
                break;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OmemoSetting.load(this);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);

        binding.returnToGlacierCall.setOnClickListener(v -> launchGlacierCall());

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        connectivityReceiver = new ConnectivityReceiver(this);


        if (binding.navigationConversations != null) {
            binding.navigationConversations.setSelectedItemId(R.id.chat_nav);
            this.binding.navigationConversations.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                    Intent intent = null;
                    int itemId = item.getItemId();
                    if (itemId == R.id.chat_nav) {
                    } else if (itemId == R.id.phone_nav) {
                        intent = new Intent(ConversationsActivity.this, PhoneModuleActivity.class);
                    } else if (itemId == R.id.security_hub_nav) {
                        intent = new Intent(ConversationsActivity.this, SecurityHubActivity.class);
                    } else if (itemId == R.id.contacts_nav) {
                        intent = new Intent(ConversationsActivity.this, StartConversationActivity.class);
                    }
                    else if (itemId == R.id.settings_nav) {
                        intent = new Intent(ConversationsActivity.this, SettingsActivity.class);
                    }
                    if (intent != null) {
                        startActivity(intent);
                        // Set a transition animation
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }
                    return true;
                }
            });
        }
        if (binding.avater != null){
            binding.avater.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Account account = getFirstAccount();
                    if (account != null) {
                        Intent intent = new Intent(ConversationsActivity.this, MyProfileActivity.class);
                        intent.putExtra("jid", account.getJid().asBareJid().toString());
                        startActivity(intent);
                        overridePendingTransition(0, 0);
                    }
                }
            });
        }
        this.getSupportFragmentManager().addOnBackStackChangedListener(this::invalidateActionBarTitle);
        this.getSupportFragmentManager().addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!handleBackNavigation()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        this.initializeFragments();
        this.invalidateActionBarTitle();
        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            intent = savedInstanceState.getParcelable("intent");
        }
        if (isViewOrShareIntent(intent)) {
            pendingViewIntent.push(intent);
            setIntent(createLauncherIntent(this));
        }

    }

    @Override
    public void onNetworkConnectionChanged(){
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_conversations, menu);
        return super.onCreateOptionsMenu(menu);
    }
    @Override
    public void onConversationSelected(Conversation conversation) {
        clearPendingViewIntent();
        if (ConversationFragment.getConversation(this) == conversation) {
            return;
        }
        openConversation(conversation, null);
        if (xmppConnectionServiceBound && xmppConnectionService != null) {
            xmppConnectionService.markRead(conversation);
        }
    }

    public void clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent");
        }
    }

    private void displayToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(ConversationsActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onAffiliationChangedSuccessful() {

    }

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, Jid.obfuscateForLog(jid.asBareJid().toString())));
    }

    private void openConversation(Conversation conversation, Bundle extras) {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        executePendingTransactions(fragmentManager);
        ConversationFragment conversationFragment = (ConversationFragment) fragmentManager.findFragmentById(R.id.secondary_fragment);
        final boolean mainNeedsRefresh;
        if (conversationFragment == null) {
            mainNeedsRefresh = false;
            final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
            if (mainFragment instanceof ConversationFragment) {
                conversationFragment = (ConversationFragment) mainFragment;
            } else {
                conversationFragment = new ConversationFragment();
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment);
                fragmentTransaction.addToBackStack(null);
                try {
                    fragmentTransaction.commit();
                } catch (IllegalStateException e) {
                    Log.w(Config.LOGTAG, "sate loss while opening conversation", e);
                    //allowing state loss is probably fine since view intents et all are already stored and a click can probably be 'ignored'
                    return;
                }
            }
        } else {
            mainNeedsRefresh = true;
        }
        conversationFragment.reInit(conversation, extras == null ? new Bundle() : extras);
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment);
        }
        invalidateActionBarTitle();
    }

    private static void executePendingTransactions(final FragmentManager fragmentManager) {
        try {
            fragmentManager.executePendingTransactions();
        } catch (final Exception e) {
            Log.e(Config.LOGTAG,"unable to execute pending fragment transactions");
        }
    }

    public boolean onXmppUriClicked(Uri uri) {
        XmppUri xmppUri = new XmppUri(uri);
        if (xmppUri.isValidJid() && !xmppUri.hasFingerprints()) {
            final Conversation conversation = xmppConnectionService.findUniqueConversationByJid(xmppUri);
            if (conversation != null) {
                openConversation(conversation, null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            FragmentManager fm = getSupportFragmentManager();
            if (fm.getBackStackEntryCount() > 0) {
                try {
                    fm.popBackStack();
                } catch (IllegalStateException e) {
                    Log.w(Config.LOGTAG, "Unable to pop back stack after pressing home button");
                }
                return true;
            }
        } else if (id == R.id.action_search_all_conversations) {
            startActivity(new Intent(this, SearchActivity.class));
            return true;
        } else if (id == R.id.action_search_this_conversation) {
            final Conversation conversation = ConversationFragment.getConversation(this);
            if (conversation == null) {
                return true;
            }
            final Intent intent = new Intent(this, SearchActivity.class);
            intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, conversation.getUuid());
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && keyEvent.isCtrlPressed()) {
            final ConversationFragment conversationFragment = ConversationFragment.get(this);
            if (conversationFragment != null && conversationFragment.onArrowUpCtrlPressed()) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    private boolean handleBackNavigation() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            try {
                fragmentManager.popBackStack();
            } catch (IllegalStateException e) {
                Log.w(Config.LOGTAG, "Unable to pop back stack after back gesture", e);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        final Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable("intent", pendingIntent != null ? pendingIntent : getIntent());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        mRedirectInProcess.set(false);
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

    }


    @Override
    protected void onStop () {
        unregisterReceiver(connectivityReceiver);
        super.onStop();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent();
                processViewIntent(intent);
            } else {
                pendingViewIntent.push(intent);
            }
        }
        setIntent(createLauncherIntent(this));
    }

    @Override
    public void onPause() {
        this.mActivityPaused = true;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        this.mActivityPaused = false;
        BottomNavigationView nav_conversations = findViewById(R.id.navigation_conversations);
        if (nav_conversations != null) {
            NavigationUtils.updatePhoneVisibility(nav_conversations,getPreferences().getBoolean("user_sms_enabled", false) );
            NavigationUtils.updatePhoneBadge(nav_conversations, this);
            nav_conversations.setSelectedItemId(R.id.chat_nav);
            if (xmppConnectionServiceBound) {
                NavigationUtils.updateSecurityHubBadge(nav_conversations,
                        !xmppConnectionService.getSecInfo().isSecure());
            }
        }

    }

    private void initializeFragments() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        final Fragment secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (mainFragment != null) {
            if (binding.secondaryFragment != null) {
                if (mainFragment instanceof ConversationFragment) {
                    getSupportFragmentManager().popBackStack();
                    transaction.remove(mainFragment);
                    transaction.commit();
                    fragmentManager.executePendingTransactions();
                    transaction = fragmentManager.beginTransaction();
                    transaction.replace(R.id.secondary_fragment, mainFragment);
                    transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
                    transaction.commit();
                    return;
                }
            } else {
                if (secondaryFragment instanceof ConversationFragment) {
                    transaction.remove(secondaryFragment);
                    transaction.commit();
                    getSupportFragmentManager().executePendingTransactions();
                    transaction = fragmentManager.beginTransaction();
                    transaction.replace(R.id.main_fragment, secondaryFragment);
                    transaction.addToBackStack(null);
                    transaction.commit();
                    return;
                }
            }
        } else {
            transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
        }
        if (binding.secondaryFragment != null && secondaryFragment == null) {
            transaction.replace(R.id.secondary_fragment, new ConversationFragment());
        }
        transaction.commit();
    }

    private void invalidateActionBarTitle() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment conversationFragment) {
            final Conversation conversation = conversationFragment.getConversation();
            if (conversation != null) {
                actionBar.setTitle(conversation.getName());
                actionBar.setDisplayHomeAsUpEnabled(true);
                final View.OnClickListener openDetailsClickListener =
                        (v) -> openConversationDetails(conversation);
                if (binding.detailsExpand != null) {
                    binding.detailsExpand.setVisibility(View.VISIBLE);
                    binding.detailsExpand.setOnClickListener(openDetailsClickListener);
                }
                if (binding.statusIcon != null) {
                    if (conversation.getMode() == Conversational.MODE_MULTI) {
                        binding.statusIcon.setVisibility(View.GONE);
                        binding.statusIcon.setOnClickListener(null);
                    } else {
                        final Contact contact = conversation.getContact();
                        if (contact == null) {
                            binding.statusIcon.setVisibility(View.GONE);
                            binding.statusIcon.setOnClickListener(null);
                        } else {
                            binding.statusIcon.setVisibility(View.VISIBLE);
                            binding.statusIcon.setOnClickListener(openDetailsClickListener);
                            binding.statusIcon.setImageResource(
                                    contact.getShownStatus().getStatusIcon());
                        }
                    }
                }
                ToolbarUtils.setActionBarOnClickListener(
                        binding.toolbar,
                        openDetailsClickListener
);
                binding.toolbar.setOnClickListener(openDetailsClickListener);
                return;
            }
        }
        final Fragment secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment conversationFragment) {
            final Conversation conversation = conversationFragment.getConversation();
            if (conversation != null) {
                actionBar.setTitle(conversation.getName());
            } else {
                actionBar.setTitle(R.string.chats_title);
            }
        } else {
            actionBar.setTitle(R.string.chats_title);
        }
        if (binding.detailsExpand != null) {
            binding.detailsExpand.setOnClickListener(null);
            binding.detailsExpand.setVisibility(View.GONE);
        }
        if (binding.statusIcon != null) {
            binding.statusIcon.setOnClickListener(null);
        }
        binding.toolbar.setOnClickListener(null);
        actionBar.setDisplayHomeAsUpEnabled(false);
        ToolbarUtils.resetActionBarOnClickListeners(binding.toolbar);

    }


    private void openConversationDetails(final Conversation conversation) {
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ConferenceDetailsActivity.open(this, conversation);
        } else {
            final Contact contact = conversation.getContact();
            if (contact.isSelf()) {
                switchToAccount(conversation.getAccount());
            } else {
                switchToContactDetails(contact);
            }
        }
    }

    @Override
    public void onConversationArchived(Conversation conversation) {
        if (performRedirectIfNecessary(conversation, false)) {
            return;
        }
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment) {
            try {
                fragmentManager.popBackStack();
            } catch (final IllegalStateException e) {
                Log.w(Config.LOGTAG, "state loss while popping back state after archiving conversation", e);
                //this usually means activity is no longer active; meaning on the next open we will run through this again
            }
            return;
        }
        final Fragment secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment) {
            if (((ConversationFragment) secondaryFragment).getConversation() == conversation) {
                Conversation suggestion = ConversationsOverviewFragment.getSuggestion(this, conversation);
                if (suggestion != null) {
                    openConversation(suggestion, null);
                }
            }
        }
    }

    @Override
    public void onConversationsListItemUpdated() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            ((ConversationsOverviewFragment) fragment).refresh();
        }
    }

    @Override
    public void switchToConversation(Conversation conversation) {
        openConversation(conversation, null);
    }

    @Override
    public void onConversationRead(Conversation conversation, String upToUuid) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid);
        }
    }

    @Override
    public void onAccountUpdate() {
        this.refreshUi();
    }

    @Override
    public void onConversationUpdate() {
        if (performRedirectIfNecessary(false)) {
            return;
        }
        this.refreshUi();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void OnUpdateBlocklist() {
        this.refreshUi();
    }

    @Override
    public void onShowErrorToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }

    private final View.OnClickListener mRefreshNetworkClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
           connectionToolbarAdapter.onClick();
        }
    };

    @Override
    public void receivedNewMessage(Message message) {
    }

    @Override
    public void messageSentCallback(String sid) {
    }

    @Override
    public void messageSendFailed(String sid) {
    }

    @Override
    public void reloadMessages() {
    }

    @Override
    public void showList() {
    }

    @Override
    public void notifyMessages() {
    }

    @Override
    public void onConversationsClientConnectionStateChange(ConversationsClient.ConnectionState state) {
    }

    @Override
    public void onMoreMessagesLoaded(int newMessageCount, boolean hasMore) {
    }

    @Override
    public void onJingleRtpConnectionUpdate(Account account, Jid with, String sessionId, RtpEndUserState state) {
        switch (state) {
            case CONNECTED, RECONNECTING, INCOMING_CONTENT_ADD ->
                    runOnUiThread(() -> updateReturnToGlacierCall(true));
            default -> runOnUiThread(() -> updateReturnToGlacierCall(false));
        }
    }

    @Override
    public void onAudioDeviceChanged(CallIntegration.AudioDevice selectedAudioDevice, Set<CallIntegration.AudioDevice> availableAudioDevices) { }

    private void launchGlacierCall() {
        final JingleConnectionManager manager = xmppConnectionService.getJingleConnectionManager();
        if (manager == null) {
            return;
        }
        final JingleRtpConnection jrc = manager.getOngoingRtpConnection();
        if (jrc == null) {
            return;
        }
        final Intent intent = new Intent(this, RtpSessionActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(RtpSessionActivity.EXTRA_ACCOUNT, jrc.getId().account.getJid().asBareJid().toEscapedString());
        intent.putExtra(RtpSessionActivity.EXTRA_WITH, jrc.getId().with.toEscapedString());
        intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, jrc.getId().sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
