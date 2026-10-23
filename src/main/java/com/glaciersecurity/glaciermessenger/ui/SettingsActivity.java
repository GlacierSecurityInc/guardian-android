package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.GlacierGuardian.getContext;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceFragmentCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.fragment.settings.RefreshablePreferenceFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.settings.SecuritySettingsFragment;
import com.glaciersecurity.glaciermessenger.utils.FileUtils;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.glaciersecurity.glaciermessenger.ui.util.NavigationUtils;
import com.google.common.collect.ImmutableSet;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;

import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivitySettingsBinding;
import com.glaciersecurity.glaciermessenger.ui.fragment.settings.MainSettingsFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.settings.NotificationsSettingsFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.settings.PrivacySettingsFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.settings.XmppPreferenceFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class SettingsActivity extends XmppActivity implements XmppConnectionService.OnOrganizationUpdate, XmppConnectionService.OnJingleRtpConnectionUpdate, ConnectivityReceiver.ConnectivityReceiverListener{

    public static final String SETTINGS_FRAG_INTENT = "settings_frag_intent";
    public static final String SECURITY_FRAG = "security_frag";
    public static final String PRIVACY_FRAG = "privacy_frag";
    public static final String SCROLL_TO_PREFERENCE = "scroll_to_preference";
    private static final String NOTIFICATION_PREFERENCES_ALIAS =
            "com.glaciersecurity.glaciermessenger.ui.NotificationPreferencesActivity";
    private ActivitySettingsBinding binding;
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;

    @Override
    protected int getPhoneBadgeNavigationViewId() {
        return R.id.navigation_conversations;
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        final var fragmentManager = getSupportFragmentManager();
        final var currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof XmppPreferenceFragment xmppPreferenceFragment) {
            xmppPreferenceFragment.onBackendConnected();
        }
        NavigationUtils.updateSecurityHubBadge(binding.navigationConversations,
                !xmppConnectionService.getSecInfo().isSecure());

        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(jingleManager != null && jingleManager.getOngoingRtpConnection() != null);
    }

    @Override
    public void onResume() {
        super.onResume();
        BottomNavigationView nav_conversations = findViewById(R.id.navigation_conversations);
        if (nav_conversations != null) {
            NavigationUtils.updatePhoneVisibility(nav_conversations,
                    getPreferences().getBoolean("user_sms_enabled", false));
            NavigationUtils.updatePhoneBadge(nav_conversations, this);
            if (xmppConnectionServiceBound) {
                NavigationUtils.updateSecurityHubBadge(nav_conversations,
                        !xmppConnectionService.getSecInfo().isSecure());
            }
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_settings);
        binding.returnToGlacierCall.setOnClickListener(v -> launchGlacierCall());
        setSupportActionBar(binding.materialToolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

        binding.navigationConversations.setSelectedItemId(R.id.settings_nav);
        binding.navigationConversations.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                Intent intent = null;
                int itemId = item.getItemId();
                if (itemId == R.id.chat_nav) {
                    intent = new Intent(SettingsActivity.this, ConversationsActivity.class);
                } else if (itemId == R.id.phone_nav) {
                    intent = new Intent(SettingsActivity.this, PhoneModuleActivity.class);
                } else if (itemId == R.id.security_hub_nav) {
                    intent = new Intent(SettingsActivity.this, SecurityHubActivity.class);
                } else if (itemId == R.id.contacts_nav) {
                    intent = new Intent(SettingsActivity.this, StartConversationActivity.class);
                }else if (itemId == R.id.settings_nav) {
                    intent = new Intent(SettingsActivity.this, SettingsActivity.class);

                }
                if (intent != null) {
                    startActivity(intent);
                    // Set a transition animation
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }
                return true;
            }
        });


        final var intent = getIntent();
        final boolean notificationPreferencesLaunch = isNotificationPreferencesLaunch(intent);
        if (isNotificationPreferencesAliasLaunch(intent) && !notificationPreferencesLaunch) {
            finish();
            return;
        }
        final PreferenceFragmentCompat preferenceFragment;

        if (notificationPreferencesLaunch) {
            preferenceFragment = new NotificationsSettingsFragment();
        } else if(intent != null && intent.hasExtra(SettingsActivity.SETTINGS_FRAG_INTENT) && intent.getStringExtra(SettingsActivity.SETTINGS_FRAG_INTENT).equals(SettingsActivity.SECURITY_FRAG)){
            preferenceFragment = new SecuritySettingsFragment();
        } else if (intent != null && intent.hasExtra(SettingsActivity.SETTINGS_FRAG_INTENT) && intent.getStringExtra(SettingsActivity.SETTINGS_FRAG_INTENT).equals(SettingsActivity.PRIVACY_FRAG)) {
            final PrivacySettingsFragment privacyFragment = new PrivacySettingsFragment();
            if (intent.hasExtra(SettingsActivity.SCROLL_TO_PREFERENCE)) {
                final android.os.Bundle args = new android.os.Bundle();
                args.putString(SettingsActivity.SCROLL_TO_PREFERENCE,
                        intent.getStringExtra(SettingsActivity.SCROLL_TO_PREFERENCE));
                privacyFragment.setArguments(args);
            }
            preferenceFragment = privacyFragment;
        } else {
            preferenceFragment = new MainSettingsFragment();
        }

        final var fragmentManager = getSupportFragmentManager();
        final var currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment == null) {
            fragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_container, preferenceFragment)
                    .commit();
        }
        binding.materialToolbar.setNavigationOnClickListener(
                view -> {
                    if (fragmentManager.getBackStackEntryCount() == 0) {
                        finish();
                    } else {
                        fragmentManager.popBackStack();
                    }
                });
        binding.swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                xmppConnectionService.queryGlacierUsers(xmppConnectionService.getFirstAccount(), false);
            }
        });
    }


    private static boolean isNotificationPreferencesLaunch(final Intent intent) {
        final var categories = intent == null ? Collections.emptySet() : intent.getCategories();
        return ImmutableSet.of(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES).equals(categories);
    }

    private static boolean isNotificationPreferencesAliasLaunch(final Intent intent) {
        if (intent == null) {
            return false;
        }
        final ComponentName component = intent.getComponent();
        return component != null && NOTIFICATION_PREFERENCES_ALIAS.equals(component.getClassName());
    }

    private void refreshFromFragment() {
        NavigationUtils.updatePhoneVisibility(binding.navigationConversations,
                getPreferences().getBoolean("user_sms_enabled", false));
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof RefreshablePreferenceFragment) {

            ((RefreshablePreferenceFragment) currentFragment).onUserRefresh();

            }
        }

    @Override
    public void onOrganizationUpdate() {
        runOnUiThread(()->{
        refreshFromFragment();
            binding.swipeRefreshLayout.setRefreshing(false);
        });
    }

    @Override
    public void onNoOrganizationUpdate() {
        runOnUiThread(()->{
            refreshFromFragment();
            binding.swipeRefreshLayout.setRefreshing(false);
        });
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

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private final View.OnClickListener mRefreshNetworkClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            connectionToolbarAdapter.onClick();
        }
    };

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

    public void doWipe(){
        new Thread(() -> {
            List<Conversation> conversations = xmppConnectionService.getConversations();

            boolean hadMulti = false;
            for (int i = (conversations.size() - 1); i >= 0; i--) {
                if (endAllChatCheck && conversations.get(i).getMode() == Conversation.MODE_MULTI) {
                    sendLeavingGroupMessage(conversations.get(i));
                    hadMulti = true;
                }
            }
            if (hadMulti) {
                // sleep required so message goes out before conversation thread stopped
                try { Thread.sleep(3000); } catch (InterruptedException ie) {}
            }

            for (int i = (conversations.size() - 1); i >= 0; i--) {

                // delete messages
                if (deleteAllMessagesEachChatCheck) {
                    xmppConnectionService.clearConversationHistory(conversations.get(i));
                }

                // end chat
                if (endAllChatCheck) {
                    xmppConnectionService.archiveConversation(conversations.get(i));
                }
            }

            // delete everything in cache
            if (deleteAllCachedFilesCheck) {
                clearCachedFilesAndStorage();
            }
        }).start();
    }

    public void sendLeavingGroupMessage(final Conversation conversation) {
        // Single implementation lives in XmppConnectionService so the overflow "Leave group"
        // action and this wipe-all flow broadcast the departure identically.
        this.xmppConnectionService.sendLeavingGroupMessage(conversation);
        // sleep required so message goes out before conversation thread stopped
        // maybe show a spinner?
        //try { Thread.sleep(2000); } catch (InterruptedException ie) {} //moved to each place
    }


    boolean deleteAllMessagesEachChatCheck;
    boolean endAllChatCheck;
    boolean deleteAllCachedFilesCheck;
    @SuppressLint("InflateParams")
    public void wipeAllHistoryDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.action_wipe_all_history));
        View dialogView = this.getLayoutInflater().inflate(
                R.layout.dialog_wipe_all_history, null);
        final CheckBox deleteAllMessagesEachChatCheckBox = (CheckBox) dialogView
                .findViewById(R.id.delete_all_messages_each_chat_checkbox);
        final CheckBox endAllChatCheckBox = (CheckBox) dialogView
                .findViewById(R.id.end_all_chat_checkbox);
        final CheckBox deleteAllCachedFilesCheckBox = (CheckBox) dialogView
                .findViewById(R.id.delete_all_cached_files_checkbox);
        // default to true
        deleteAllMessagesEachChatCheckBox.setChecked(true);
        endAllChatCheckBox.setChecked(true);
        deleteAllCachedFilesCheckBox.setChecked(true);

        builder.setView(dialogView);
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.wipe_all_history), (dialog, which) -> {
            deleteAllMessagesEachChatCheck = deleteAllMessagesEachChatCheckBox.isChecked();
            endAllChatCheck = endAllChatCheckBox.isChecked();
            deleteAllCachedFilesCheck = deleteAllCachedFilesCheckBox.isChecked();

            // go through each conversation and either delete or end each chat depending
            // on what was checked.
            doWipe();

        });
        builder.create().show();
    }


    private void clearCachedFilesAndStorage() {
        final var context = getContext();
        if (context == null) {
            return;
        }
        FileUtils.deleteDirectory(context.getCacheDir());
        FileUtils.deleteDirectory(context.getFilesDir());
        // Conversation media now lives under the app-owned external files
        // directory, which we can always delete without storage permissions.
        FileUtils.deleteDirectory(context.getExternalFilesDir(null));

        // Best-effort cleanup of any legacy media left in public storage by
        // older builds (only effective on pre-scoped-storage devices, API <= 28).
        final List<String> storageTypes = new ArrayList<>();
        storageTypes.add(Environment.DIRECTORY_DOWNLOADS);
        storageTypes.add(Environment.DIRECTORY_PICTURES);
        storageTypes.add(Environment.DIRECTORY_MOVIES);
        storageTypes.add(Environment.DIRECTORY_DOCUMENTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            storageTypes.add(Environment.DIRECTORY_RECORDINGS);
        }
        for (final String type : storageTypes) {
            final File dir = new File(
                    Environment.getExternalStoragePublicDirectory(type),
                    context.getString(R.string.app_name));
            FileUtils.deleteDirectory(dir);
        }
    }


    public static boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
            return dir.delete();
        } else if(dir!= null && dir.isFile()) {
            return dir.delete();
        } else {
            return false;
        }
    }

    public void deleteOmemoIdentities() {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.pref_delete_glacier_identities);
        builder.setMessage(R.string.pref_reset_omemo_message);

        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.reset, (dialog, which) -> { // to reset
            for(Account account : xmppConnectionService.getAccounts()) {
                if (account != null) {
                    account.getAxolotlService().regenerateKeys(true);
                }
            }

        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }


}


