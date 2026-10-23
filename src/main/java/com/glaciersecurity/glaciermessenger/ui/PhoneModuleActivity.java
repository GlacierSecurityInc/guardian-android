package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.ui.PurchaseNumberActivity.PURCHASE_NUM_REQUEST;
import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import android.app.Activity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.os.Build;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.ExternalCallManager;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.ui.util.SmsUnavailableDialogHelper;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.SmsProfileAdapter;
import com.glaciersecurity.glaciermessenger.ui.fragment.phone.ContactsPhoneFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.phone.DialPhoneFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.phone.HistoryPhoneFragment;

import androidx.databinding.DataBindingUtil;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityPhoneModuleBinding;
import com.glaciersecurity.glaciermessenger.ui.fragment.phone.PhoneFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.phone.SmsPhoneFragment;
import com.glaciersecurity.glaciermessenger.ui.fragment.phone.VoicemailPhoneFragment;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSNameClickListener;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSProfileClickListener;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSRemoveClickListener;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OnSMSCompleteCallbackListener;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.glaciersecurity.glaciermessenger.utils.VoiceRegistrationStatus;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.twilio.conversations.ConversationsClient;
import com.twilio.conversations.Message;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;

import java.util.ArrayList;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


public class PhoneModuleActivity extends XmppActivity implements OnSMSConversationClickListener, OnSMSRemoveClickListener, OnSMSNameClickListener, OnSMSProfileClickListener, ConnectivityReceiver.ConnectivityReceiverListener, ConversationsManagerListener, ExternalCallListener, OnSMSCompleteCallbackListener, XmppConnectionService.OnPhoneUpdate, XmppConnectionService.OnJingleRtpConnectionUpdate, XmppConnectionService.OnConsoleStatus {
    private OnBackPressedCallback exitCallback;
    public enum Tabs {
        HISTORY, CONTACTS, SMS, DIALER, NONE
    }

    public static final String PHONE_MOD_INTENT = "phone_mod_intent";
    public static final String SMS_FRAG = "sms_frag";
    public static final String HISTORY_FRAG = "history_frag";
    public static final String DIALER_FRAG = "dialer_frag";
    public static final String CONTACT_FRAG = "contact_frag";
    public static final String VOICEMAIL_FRAG = "voicemail_frag";

    public ActivityPhoneModuleBinding binding;
    public SmsProfileAdapter smsProfileAdapter;
    public ConversationsManager conversationsManager;

    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;
    public ExternalCallManager mExternalCallManager;

    private LinearProgressIndicator twilioProgressIndicator;
    private TextView twilioStatusTextView;
    private ConversationsClient.ConnectionState conversationsClientConnectionState;
    private boolean isTwilioLoading = false;
    private boolean conversationClientSyncFailed = false;

    private String lastWaitMsg = null;
    private TextView waitTextField = null;
    private AlertDialog waitDialog = null;
    public Account account;
    public SmsProfile selectedProfile;
    private boolean suppressNavigationCallback = false;
    private final android.content.BroadcastReceiver badgeUpdateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (NotificationService.ACTION_PHONE_BADGES_UPDATED.equals(intent.getAction())) {
                updatePhoneNavigationBadges();
                if (smsProfileAdapter != null) {
                    smsProfileAdapter.notifyDataSetChanged();
                }
                invalidateOptionsMenu();
            }
        }
    };


    private void replaceFragmentSafely(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager == null) {
            return;
        }

        if (fragmentManager.isStateSaved()) {
            Log.w(Config.LOGTAG, "replaceFragmentSafely: skipping transaction because state is already saved");
            return;
        }

        fragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commitAllowingStateLoss();

    }

    private void handleProfiles(boolean isSuccess, ArrayList<SmsProfile> profiles, boolean forced) {
        runOnUiThread(() -> {
            if (isSuccess) {
                binding.consoleError.setVisibility(View.GONE);
            }
            else {
                binding.consoleError.setVisibility(View.VISIBLE);
            }
                smsProfileAdapter.setSmsProfileList(profiles);
                if (xmppConnectionServiceBound) {
                    xmppConnectionService.getSmsInfo().setDbProfs(profiles);

                    setProxyNumberAndToolbar(xmppConnectionService.getSmsInfo().getSelectedProfile(), false);
                    if (xmppConnectionService.getSmsInfo().getUserPurchasePermission() ||
                            xmppConnectionService.getSmsInfo().isSMSEnabled()) {
                        updateProfileMenuOptions();
                    }
                    xmppConnectionService.getConversationsManager().init(account, forced);

                }

        });

    }
    @Override
    public void onSelectedTwilioNumbersLoaded(boolean isSuccess, ArrayList<SmsProfile> smsProfiles, boolean forced) {
            runOnUiThread(() -> {
                smsProfileAdapter.setSmsProfileList(smsProfiles);
            });
            handleProfiles(isSuccess, smsProfiles, forced);

    }

    @Override
    public void onRefreshComplete(SMSdbInfo.PhoneSyncStatus phoneSyncStatus) {
        boolean shouldShowNoNumbers = shouldShowNoNumbers(phoneSyncStatus);
        runOnUiThread(() -> {
            showLoading(false);
            if (shouldShowNoNumbers) {
                setProxyNumberAndToolbar(null, true);
            }
        });
        if (xmppConnectionService != null) {
            xmppConnectionService.updatePhoneUi();
        }
    }

    private boolean shouldShowNoNumbers(SMSdbInfo.PhoneSyncStatus phoneSyncStatus) {
        if (phoneSyncStatus == SMSdbInfo.PhoneSyncStatus.NO_NUMBERS) {
            return true;
        }

        if (xmppConnectionService == null) {
            return selectedProfile == null && smsProfileAdapter != null
                    && smsProfileAdapter.getItemCount() == 0;
        }

        SMSdbInfo smsInfo = xmppConnectionService.getSmsInfo();
        if (smsInfo == null) {
            return false;
        }

        boolean hasSelectedProfile = smsInfo.getSelectedProfile() != null || selectedProfile != null;
        ArrayList<SmsProfile> existingProfiles = smsInfo.getExistingProfs();
        boolean hasExistingProfiles = existingProfiles != null && !existingProfiles.isEmpty();

        return !hasSelectedProfile && !hasExistingProfiles;
    }

    // True when a phone sync has settled and the account has no assigned numbers. Used to
    // suppress the "connecting" spinner, since with no numbers the Conversations client is
    // never initialized and would otherwise leave the status bar spinning forever.
    private boolean hasNoAssignedNumbers() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return selectedProfile == null && smsProfileAdapter != null
                    && smsProfileAdapter.getItemCount() == 0;
        }
        if (xmppConnectionService.isPhoneSyncInProgress()) {
            return false;
        }
        SMSdbInfo smsInfo = xmppConnectionService.getSmsInfo();
        if (smsInfo == null) {
            return false;
        }
        boolean hasSelectedProfile = smsInfo.getSelectedProfile() != null || selectedProfile != null;
        ArrayList<SmsProfile> existingProfiles = smsInfo.getExistingProfs();
        boolean hasExistingProfiles = existingProfiles != null && !existingProfiles.isEmpty();
        return !hasSelectedProfile && !hasExistingProfiles;
    }

    // True once registering for Twilio call invites has actually failed. "Not registered yet" is
    // the normal state while the app starts up and must not raise a banner, so this asks for a
    // recorded failure rather than just the absence of a registration.
    private boolean hasFailedVoiceRegistration() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return false;
        }
        if (hasNoAssignedNumbers()) {
            // No number to be called on, so nothing is being missed.
            return false;
        }
        final VoiceRegistrationStatus status = xmppConnectionService.getVoiceRegistrationStatus();
        return status != null && !status.isRegistered() && status.getLastFailure() != null;
    }

    private void showLoading(boolean isLoading){
        isTwilioLoading = isLoading;
        if (isLoading) {
            conversationClientSyncFailed = false;
        } else {
            runOnUiThread(() -> binding.swipeRefreshLayout.setRefreshing(false));
        }
        updateTwilioStatusBar();
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof SmsPhoneFragment) {
            ((SmsPhoneFragment) currentFragment).showLoading(isLoading);
        }
    }

    private void updateTwilioStatusBar() {
        runOnUiThread(() -> {
            if (binding == null || binding.twilioError == null || twilioStatusTextView == null || twilioProgressIndicator == null) {
                return;
            }

            CharSequence statusText = null;
            boolean showProgress = false;

            if (isTwilioLoading) {
                statusText = getString(R.string.twilio_sync_in_progress);
                showProgress = true;
            } else if (conversationClientSyncFailed) {
                statusText = getString(R.string.twilio_sync_failed);
            } else if ((conversationsManager == null || conversationsManager.isConversationsClientNull())
                    && !hasNoAssignedNumbers()) {
                // Only show the "connecting" spinner when we still expect a client to come up.
                // With no assigned numbers the client is never initialized, so leave the bar hidden.
                statusText = getString(R.string.twilio_connecting);
                showProgress = true;
            } else if (hasFailedVoiceRegistration()) {
                // Last in the chain deliberately: the states above are about messaging and are
                // either transient or already being retried. This one is not transient and has
                // no other symptom -- the phone simply never rings -- so it is the only thing
                // that tells the user incoming calls are not arriving.
                statusText = getString(R.string.twilio_not_receiving_calls);
            } else if (conversationsClientConnectionState != null
                    && conversationsClientConnectionState != ConversationsClient.ConnectionState.CONNECTED) {
                switch (conversationsClientConnectionState) {
                    case CONNECTING:
                        statusText = getString(R.string.twilio_connecting);
                        showProgress = true;
                        break;
                    case DISCONNECTED:
                        statusText = getString(R.string.twilio_disconnected);
                        break;
                    case DENIED, ERROR, FATAL_ERROR:
                        statusText = getString(R.string.twilio_failed);
                        break;
                    default:
                        statusText = getString(R.string.twilio_connecting);
                        showProgress = true;
                        break;
                }
            }

            final boolean show = statusText != null;

            binding.twilioError.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                twilioStatusTextView.setText(statusText);
                twilioStatusTextView.setVisibility(View.VISIBLE);
            } else {
                twilioStatusTextView.setVisibility(View.GONE);
            }
            if (show && showProgress) {
                twilioProgressIndicator.setVisibility(View.VISIBLE);
                twilioProgressIndicator.setIndeterminate(true);
            } else {
                twilioProgressIndicator.setVisibility(View.GONE);
            }
        });
    }

    private void retryConversationsClientConnection() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return;
        }
        // The banner is shared, so "tap to retry" has to retry whatever is actually broken. A
        // failed call-invite registration is independent of the messaging client: either can be
        // down while the other is fine, and re-registering is cheap.
        if (hasFailedVoiceRegistration()) {
            xmppConnectionService.retryVoiceRegistration();
        }
        if (conversationsManager == null) {
            conversationsManager = xmppConnectionService.getConversationsManager();
        }
        if (conversationsManager == null) {
            return;
        }
        boolean shouldRetry = conversationClientSyncFailed
                || conversationsManager.isConversationsClientNull()
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.DISCONNECTED
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.DENIED
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.ERROR
                || conversationsClientConnectionState == ConversationsClient.ConnectionState.FATAL_ERROR;
        if (!shouldRetry) {
            return;
        }
        Account firstAccount = xmppConnectionService.getFirstAccount();
        if (firstAccount != null) {
            showLoading(true);
            xmppConnectionService.initializeConversationsClient(firstAccount, true);
        }
    }


    @Override
    public void onJingleRtpConnectionUpdate(Account account, Jid with, String sessionId, RtpEndUserState state) {
        switch (state){
            case CONNECTED, // session-accepted and webrtc peer connection is connected
                    RECONNECTING, // session-accepted and webrtc peer connection was connected once but is currently
                    // disconnected or failed
                    INCOMING_CONTENT_ADD: {
                runOnUiThread(() -> {
                    if (!isActivityAlive()) {
                        return;
                    }
                    binding.returnToGlacierCall.setVisibility(View.VISIBLE);
                });
                break;

            }
                // 'proceed' message has been sent; but no session-initiate has been received

            case ENDING_CALL, // libwebrt says 'closed' but session-terminate has not gone through
                    ENDED, // close UI
                    DECLINED_OR_BUSY, // other party declined; no retry button
                    CONTACT_OFFLINE, // when `JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK` is true this shows up when
                    // the contact is offline, generally similar to BUSY
                    CONNECTIVITY_ERROR, // network error; retry button
                    CONNECTIVITY_LOST_ERROR, // network error but for call duration > 0
                    RETRACTED, // user pressed home or power button during 'ringing' - shows retry button
                    APPLICATION_ERROR, // something rather bad happened; libwebrtc failed or we got in IQ-error
                    SECURITY_ERROR: {
                runOnUiThread(()-> {
                    binding.returnToGlacierCall.setVisibility(View.GONE);
                });
                    break;

                }
        }
    }

    @Override
    public void onAudioDeviceChanged(CallIntegration.AudioDevice selectedAudioDevice, Set<CallIntegration.AudioDevice> availableAudioDevices) {

    }

    public int getUnreadVoicemailCount() {
        return xmppConnectionService != null ? xmppConnectionService.getUnreadVoicemailCount() : 0;
    }

    public void setUnreadVoicemailCount(int count) {
        if (xmppConnectionService != null) {
            xmppConnectionService.setUnreadVoicemailCount(count);
        }
    }

    public boolean isPhoneNumberLoaded() {
        if (smsProfileAdapter == null){
            return false;
        }
        return smsProfileAdapter.getItemCount() > 0;
    }


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_phone_module);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        binding.toolbar.setTitle(R.string.loading_phone_number);
        binding.toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        configureBackPressedCallback();
        binding.toolbar.setOnLongClickListener(v -> {
            if (selectedProfile != null && selectedProfile.getFormattedNumber() != null) {
                if (copyTextToClipboard(selectedProfile.getFormattedNumber(), R.string.phone_number)) {
                    Toast.makeText(PhoneModuleActivity.this, R.string.phone_number_copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
            return false;
        });

        twilioProgressIndicator = binding.twilioError.findViewById(R.id.twilio_progress);
        twilioStatusTextView = binding.twilioError.findViewById(R.id.twilio_status);
        binding.twilioError.setOnClickListener(v -> retryConversationsClientConnection());

        connectivityReceiver = new ConnectivityReceiver(this);
        FragmentManager fragmentManager = getSupportFragmentManager();
        LinearLayoutManager layoutManagerSMS = new LinearLayoutManager(this);
        binding.smsProfilesRecyclerView.setLayoutManager(layoutManagerSMS);
        ArrayList<SmsProfile> profiles = DatabaseBackend.getInstance(this).getAllSmsProfiles();
        smsProfileAdapter = new SmsProfileAdapter(this, this, this, this, profiles);
        binding.smsProfilesRecyclerView.setAdapter(smsProfileAdapter);
        updateProfileMenuOptions();
        binding.navigationPhone.setSelectedItemId(R.id.dialer_nav);
        binding.navigationPhone.setTransitionName("bottomNav");
        binding.navigationPhone.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int itemId = item.getItemId();
                Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
                if (!suppressNavigationCallback && itemId != R.id.home_nav) {
                    if (!xmppConnectionServiceBound) {
                        Toast.makeText(PhoneModuleActivity.this, R.string.loading_phone_number, Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    SMSdbInfo smsInfo = xmppConnectionService.getSmsInfo();
                    if (smsInfo == null || smsInfo.getSelectedProfile() == null) {
                        Toast.makeText(PhoneModuleActivity.this, R.string.no_phoneNumbers_conversations, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
                suppressNavigationCallback = false;

                if (itemId == R.id.home_nav) {
                    startActivity(new Intent(PhoneModuleActivity.this, ConversationsActivity.class));
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    return true;
                } else if (itemId == R.id.sms_nav) {
                    if (!(currentFragment instanceof SmsPhoneFragment)) {

                        fragmentManager
                                .beginTransaction()
                                .replace(R.id.fragment_container, new SmsPhoneFragment())
                                .commit();
                    }
                    SmsUnavailableDialogHelper.showIfFirstTime(PhoneModuleActivity.this);
                    return true;
                } else if (itemId == R.id.dialer_nav) {
                    if (!(currentFragment instanceof DialPhoneFragment)) {

                        fragmentManager
                                .beginTransaction()
                                .replace(R.id.fragment_container, new DialPhoneFragment())
                                .commit();
                    }
                    return true;
                } else if (itemId == R.id.contact_nav) {
                    if (!(currentFragment instanceof ContactsPhoneFragment)) {

                        fragmentManager
                                .beginTransaction()
                                .replace(R.id.fragment_container, new ContactsPhoneFragment())
                                .commit();
                    }
                    return true;
                } else if (itemId == R.id.history_nav) {

                        fragmentManager
                                .beginTransaction()
                                .replace(R.id.fragment_container, new HistoryPhoneFragment())
                                .commit();
                        clearMissedCallNotificationForProfile(selectedProfile);
                        updatePhoneNavigationBadges();

                    return true;
                }
                return false;
            }
        });
        if (savedInstanceState == null) {
        selectTab(Tabs.DIALER);
            replaceFragmentSafely(new DialPhoneFragment());
        }

        binding.swipeRefreshLayout.setOnRefreshListener(() -> {
            binding.swipeRefreshLayout.postDelayed(() -> {
                if (xmppConnectionServiceBound) {

                    startPhoneSync(true);
                }
                binding.swipeRefreshLayout.setRefreshing(false);

            }, 1000);
        });
        final float density = getResources().getDisplayMetrics().density;
        binding.swipeRefreshLayout.setDistanceToTriggerSync((int) (160 * density));
        binding.returnToExternalCall.setOnClickListener(returnToExternalCall());
        binding.returnToGlacierCall.setOnClickListener(returnToGlacierCall());
        if (xmppConnectionServiceBound) {
            connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService, true);
            binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
            connectionToolbarAdapter.updateOfflineStatusBar();
        }
        binding.releaseNumber.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                smsProfileAdapter.toggleNameOff();
                smsProfileAdapter.toggleDeleteVisible();
            }
        });

        binding.nicknameNumber.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                smsProfileAdapter.toggleDeleteOff();
                smsProfileAdapter.toggleNameVisible();
            }
        });

        binding.addNumber.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (smsProfileAdapter.getItemCount()<5) {
                    Intent intent = new Intent(PhoneModuleActivity.this, PurchaseNumberActivity.class);
                    startActivityForResult(intent, PURCHASE_NUM_REQUEST);
                }
                else {
                    runOnUiThread(
                            () -> {
                                Toast.makeText(PhoneModuleActivity.this, R.string.phone_max_numbers_reached, Toast.LENGTH_LONG).show();
                            });
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (exitCallback != null) {
            exitCallback.remove();
            exitCallback = null;
        }
    }


    private View.OnClickListener returnToGlacierCall() {
        return v -> {
            JingleConnectionManager callManager = xmppConnectionService.getJingleConnectionManager();
            if (callManager != null) {
                JingleRtpConnection jrc = callManager.getOngoingRtpConnection();
                    runOnUiThread(() -> {
                        if (!isActivityAlive()) {
                            return;
                        }

                        if (jrc != null) {

                            final Intent returnToGlacierCall =
                                    new Intent(xmppConnectionService, RtpSessionActivity.class);
                        returnToGlacierCall.setAction(Intent.ACTION_VIEW);
                        returnToGlacierCall.putExtra(
                                RtpSessionActivity.EXTRA_ACCOUNT,
                                jrc.getId().account.getJid().asBareJid().toEscapedString());
                        returnToGlacierCall.putExtra(RtpSessionActivity.EXTRA_WITH, jrc.getId().with.toEscapedString());
                        returnToGlacierCall.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, jrc.getId().sessionId)
                                .setAction(Intent.ACTION_VIEW)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(returnToGlacierCall);
                    }
                });
            }
            ;
        };
    }

    private View.OnClickListener returnToExternalCall() {
        return v -> {
            if (mExternalCallManager != null) {
                if (mExternalCallManager.isOnCall()){
                    Intent externalCallActivity = new Intent(getApplicationContext(), ExternalCallActivity.class);
                    externalCallActivity.putExtra("returning", "true");
                    startActivity(externalCallActivity);
                }
            }

            binding.returnToExternalCall.setVisibility(View.GONE);

        };
    }

    private void updateProfileMenuOptions() {
        if (xmppConnectionServiceBound) {
            smsProfileAdapter.setSmsProfileList(xmppConnectionService.getSmsInfo().getExistingProfs());
            smsProfileAdapter.setXmppConnectionService(xmppConnectionService);
        }
        if (!getPreferences().getBoolean("add_user_to_purchase_numbers", false) ) {
            binding.releaseNumber.setVisibility(View.GONE);
        } else {
            binding.releaseNumber.setVisibility(View.VISIBLE);
        }
        boolean canPurchase = getPreferences().getBoolean("add_user_to_purchase_numbers", false);
        if (!canPurchase || smsProfileAdapter.getItemCount() >= 5) {
            binding.addNumber.setVisibility(View.GONE);
        } else {
            binding.addNumber.setVisibility(View.VISIBLE);
        }
        if (smsProfileAdapter.smsProfileList.isEmpty()){
            binding.nicknameNumber.setVisibility(View.GONE);
            binding.noNumbers.setVisibility(View.VISIBLE);
            binding.releaseNumber.setVisibility(View.GONE);
        } else {
            binding.nicknameNumber.setVisibility(View.VISIBLE);
            binding.noNumbers.setVisibility(View.GONE);
        }
    }


    public boolean hasLoaded() {
        boolean hasLoaded;
        if (smsProfileAdapter == null) {
            hasLoaded = false;
        } else if (smsProfileAdapter.getItemCount() == 0) {
            FragmentManager fragmentManager = getSupportFragmentManager();
            Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
            if (!(currentFragment instanceof DialPhoneFragment)) {
                selectTab(Tabs.DIALER);
                fragmentManager
                        .beginTransaction()
                        .replace(R.id.fragment_container, new DialPhoneFragment())
                        .commit();
            }
            hasLoaded = false;
        } else {
            hasLoaded = xmppConnectionServiceBound && xmppConnectionService.hasPhoneLoaded();
        }
        runOnUiThread(()->{
            showLoading(!hasLoaded);
        });
        return hasLoaded;
    }

    @Override
    public void onNetworkConnectionChanged(){
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private void setVoicemailNumbers() {
        if (getPreferences().getBoolean("voicemail_enabled", false) && xmppConnectionService != null && selectedProfile != null) {
            setUnreadVoicemailCount(xmppConnectionService.getUnreadVoicemailsCount(selectedProfile));
        } else {
            setUnreadVoicemailCount(0);
        }
        invalidateOptionsMenu();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PURCHASE_NUM_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                String proxyData = data.getStringExtra("proxyNum");
                String sid = data.getStringExtra("sid");
                binding.smsProfilesDrawer.closeDrawer(GravityCompat.END);
                SmsProfile newProfile = new SmsProfile(proxyData, sid);
                addProfile(newProfile, xmppConnectionService);
                OnSMSProfileClick(newProfile);
                xmppConnectionService.getConversationsManager().init(account, false);

            }
        }
    }

    private void removeProfile(SmsProfile smsProfile) {

        smsProfileAdapter.removeProfile(smsProfile, xmppConnectionService);
        if (xmppConnectionServiceBound){
            xmppConnectionService.getSmsInfo().removeDbProfs(smsProfile);
        }
        OnSMSProfileClick(null);
        updateProfileMenuOptions();
        onRefreshComplete(SMSdbInfo.PhoneSyncStatus.SUCCESS);

    }

    private void nicknameProfile(String nickname, SmsProfile smsProfile) {
        binding.smsProfilesDrawer.closeDrawers();
        smsProfile.setNickname(nickname);
        smsProfileAdapter.nickname(smsProfile, xmppConnectionService);
        if (xmppConnectionService.getSmsInfo().getSelectedProfile().getId().equals(smsProfile.getId())){
            setProxyNumberAndToolbar(smsProfile, false);
        }
    }

    private void addProfile(SmsProfile smsProfile, XmppConnectionService xmppConnectionService) {
        smsProfileAdapter.addProfile(smsProfile, xmppConnectionService);
        if (xmppConnectionServiceBound){
            xmppConnectionService.getSmsInfo().addDbProfs(smsProfile);
        }
        updateProfileMenuOptions();
    }

    private void NicknameNum(String nickname, SmsProfile smsProfile) {
        runOnUiThread(() -> {

            binding.consoleError.setVisibility(View.GONE);
        });
        if (nickname == null || nickname.isEmpty() || smsProfile == null || smsProfile.getUnformattedNumber() == null){
            runOnUiThread(() ->
                    Toast.makeText(this, R.string.phone_nickname_update_failed, Toast.LENGTH_LONG).show());
            return;
        }
        String nicknameNumberUrl =
                Endpoints.base() + EndPointConfig.NICKNAME_NUMBER_URL;
        OkHttpClient client = getOkHttpClient();
        Map<String, String> formFields = new HashMap<>();
        formFields.put("twilioNumber", smsProfile.getUnformattedNumber());
        formFields.put("username", xmppConnectionService.getFirstAccount().getUsername());
        formFields.put("nickname", nickname);
        formFields.put("organization", xmppConnectionService.getFirstAccount().getOrganization());
        RequestBody requestBody = OkHttpClientProvider.buildRawFormBody(formFields);
        Request request = new Request.Builder()
                .url(nicknameNumberUrl)
                .put(requestBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            Gson gson = new Gson();
            NicknameNumResponse nicknameNumResponse;
            try {
                nicknameNumResponse = gson.fromJson(responseBody, NicknameNumResponse.class);
            } catch (JsonSyntaxException jsonSyntaxException) {
                handleWebhookJsonSyntaxException("nickname number", responseBody, jsonSyntaxException);
                runOnUiThread(() -> binding.consoleError.setVisibility(View.VISIBLE));
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.phone_nickname_update_failed, Toast.LENGTH_LONG).show());
                return;
            }
            if (nicknameNumResponse.message.equals("success")) {
                runOnUiThread(() -> {
                    nicknameProfile(nickname, smsProfile);
                });
            } else {
                runOnUiThread(() -> {

                            binding.consoleError.setVisibility(View.VISIBLE);
                        });
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.phone_nickname_update_failed, Toast.LENGTH_LONG).show());
            }
        } catch (Exception ex) {
            Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
            xmppConnectionService.checkConsoleStatus();
            runOnUiThread(() ->
                    Toast.makeText(this, R.string.phone_nickname_update_failed, Toast.LENGTH_LONG).show());
        }
    }

    private void ReleaseNum(SmsProfile releaseProfile) {
        runOnUiThread(() -> {

                    binding.consoleError.setVisibility(View.GONE);
                });
        String releaseNumberUrl =
                Endpoints.base() + EndPointConfig.RELEASE_NUMBER_URL;
        String unformattedNumber = releaseProfile.getUnformattedNumber();

        OkHttpClient client = getOkHttpClient();
        Map<String, String> releaseFields = new HashMap<>();
        releaseFields.put("releaseNumber", unformattedNumber);
        releaseFields.put("username", account.getUsername());
        releaseFields.put("organization", account.getOrganization());
        Request request = null;
        try {
            request = OkHttpClientProvider.buildJwtFormRequestBuilder(releaseNumberUrl, releaseFields)
                    .build();
        } catch (IOException e) {
            Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
            runOnUiThread(() -> {

                binding.consoleError.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.phone_release_failed, Toast.LENGTH_LONG).show();
            });
            xmppConnectionService.checkConsoleStatus();
            closeWaitDialog();
        }
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            Gson gson = new Gson();
            ReleaseNumResponse releaseNumResponse;
            try {
                releaseNumResponse = gson.fromJson(responseBody, ReleaseNumResponse.class);
            } catch (JsonSyntaxException jsonSyntaxException) {
                handleWebhookJsonSyntaxException("release number", responseBody, jsonSyntaxException);
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.phone_release_failed, Toast.LENGTH_LONG).show());
                closeWaitDialog();
                return;
            }
            if (releaseNumResponse.message.equals("success")) {
                runOnUiThread(() -> {
                    removeProfile(releaseProfile);
                });

            } else {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.phone_release_failed, Toast.LENGTH_LONG).show());
            }
            closeWaitDialog();

        } catch (Exception ex) {
            Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
            runOnUiThread(() -> {

                        binding.consoleError.setVisibility(View.VISIBLE);
                        Toast.makeText(this, R.string.phone_release_failed, Toast.LENGTH_LONG).show();
                    });
            xmppConnectionService.checkConsoleStatus();
            closeWaitDialog();

        }
    }

    private void handleWebhookJsonSyntaxException(String context, String responseBody, JsonSyntaxException exception) {
        Log.e(Config.LOGTAG, "Failed to parse " + context + " webhook response", exception);
        if (xmppConnectionService != null) {
            xmppConnectionService.checkConsoleStatus();
        }
    }

    public void showWaitDialog(String message) {
        if (this.isFinishing()) {
            return;
        }

        if (message.equalsIgnoreCase(lastWaitMsg)) {
            return;
        } else if (waitDialog != null && waitTextField != null) {
            waitTextField.setText(message);
            return;
        }

        lastWaitMsg = message;
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_wait, null);
        waitTextField = layout.findViewById(R.id.status_message);
        waitTextField.setText(message);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(layout);
        builder.setCancelable(false);
        builder.setTitle(R.string.dialog_please_wait);

        waitDialog = builder.create();
        waitDialog.show();
    }

    public void closeWaitDialog() {
        runOnUiThread(() -> {
        if (waitDialog != null) {
            waitDialog.dismiss();
            waitDialog = null;
            lastWaitMsg = null;
            waitTextField = null;
        }
        });
    }


    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                        badgeUpdateReceiver,
                        new IntentFilter(NotificationService.ACTION_PHONE_BADGES_UPDATED));
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {

        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(badgeUpdateReceiver);
    }


    @Override
    public void onRinging(@NonNull Call call) {
        if (mExternalCallManager != null){
            mExternalCallManager.setActiveCall(call);
        }
        binding.returnToExternalCall.setVisibility(View.VISIBLE);
    }

    @Override
    public void onConnectFailure(@NonNull Call call, @NonNull CallException error) {
        binding.returnToExternalCall.setVisibility(View.GONE);
    }

    @Override
    public void onConnected(@NonNull Call call) {
        if (mExternalCallManager != null){
            mExternalCallManager.setActiveCall(call);
        }
        binding.returnToExternalCall.setVisibility(View.VISIBLE);
    }

    @Override
    public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {

    }

    @Override
    public void onReconnected(@NonNull Call call) {
        binding.returnToExternalCall.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDisconnected(@NonNull Call call, CallException error) {
        if (mExternalCallManager != null){
            mExternalCallManager.setActiveCall(null);
        }
        binding.returnToExternalCall.setVisibility(View.GONE);
    }

    @Override
    public void onCallQualityWarningsChanged(Call call, Set<Call.CallQualityWarning> currentWarnings, Set<Call.CallQualityWarning> previousWarnings) {

    }

    @Override
    public void onCallStatusChange(boolean bool) {
        if (bool) {
            binding.returnToExternalCall.setVisibility(View.VISIBLE);
        } else {
            binding.returnToExternalCall.setVisibility(View.GONE);
        }

    }

    private static class NicknameNumResponse {
        @SerializedName("message")
        String message;
        @SerializedName("data")

        String data;
    }


    private static class ReleaseNumResponse {
        @SerializedName("message")

        String message;
        @SerializedName("data")

        String data;
    }

    public void selectTab(Tabs tabs) {
        BottomNavigationView nav_phone = findViewById(R.id.navigation_phone);
        if (nav_phone == null) {
            return;
        }
        int id;
        switch (tabs) {
            case HISTORY:
                id = R.id.history_nav;
                break;
            case SMS:
                id = R.id.sms_nav;
                break;
            case CONTACTS:
                id = R.id.contact_nav;
                break;
            case DIALER:
                id = R.id.dialer_nav;
                break;
            case NONE:
                nav_phone.setSelected(false);
                return;
            default:
                return;
        }
        if (nav_phone.getSelectedItemId() != id) {
            suppressNavigationCallback = true;
            nav_phone.setSelectedItemId(id);
        }
    }

    public void clearMissedCallNotificationForProfile(SmsProfile smsProfile) {
        if (smsProfile == null || xmppConnectionService == null) {
            return;
        }
        xmppConnectionService.getNotificationService()
                .clearMissedCallNotificationForProfile(smsProfile.getUnformattedNumber());
    }

    public void clearVoicemailNotificationForProfile(SmsProfile smsProfile) {
        if (smsProfile == null || xmppConnectionService == null) {
            return;
        }
        xmppConnectionService.getNotificationService()
                .clearVoicemailNotificationForProfile(smsProfile.getUnformattedNumber());
    }

    private void updatePhoneNavigationBadges() {
        if (binding == null || binding.navigationPhone == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || selectedProfile == null) {
            hidePhoneNavigationBadges();
            return;
        }
        boolean showSms = false;
        boolean showHistory = false;
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        String profileTag = PhoneUtils.getStripPhoneNumberWithCountryCode(
                selectedProfile.getUnformattedNumber());
        if (notificationManager != null) {
            StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
            if (notifications != null) {
                for (StatusBarNotification notification : notifications) {
                    if (notification == null) {
                        continue;
                    }
                    int id = notification.getId();
                    String notificationTag = PhoneUtils.getStripPhoneNumberWithCountryCode(notification.getTag());
                    if (profileTag != null && profileTag.equals(notificationTag)) {
                        if (id == NotificationService.SMS_NOTIFICATION_ID) {
                            showSms = true;
                        } else if (id == NotificationService.MISSED_PHONE_CALL_NOTIFICATION_ID) {
                            showHistory = true;
                        }
                    }
                }
            }
        }
        if (xmppConnectionService != null && xmppConnectionService.getSmsInfo() != null) {
            showSms = showSms || xmppConnectionService.getSmsInfo()
                    .hasUnreadForProfile(selectedProfile.getUnformattedNumber());
        }
        BadgeDrawable smsBadge = binding.navigationPhone.getOrCreateBadge(R.id.sms_nav);
        smsBadge.setVisible(showSms);
        if (!showSms) {
            smsBadge.clearNumber();
        }
        BadgeDrawable historyBadge = binding.navigationPhone.getOrCreateBadge(R.id.history_nav);
        historyBadge.setVisible(showHistory);
        if (!showHistory) {
            historyBadge.clearNumber();
        }
    }

    public void refreshPhoneNavigationBadges() {
        updatePhoneNavigationBadges();
    }

    private void hidePhoneNavigationBadges() {
        BadgeDrawable smsBadge = binding.navigationPhone.getOrCreateBadge(R.id.sms_nav);
        smsBadge.setVisible(false);
        smsBadge.clearNumber();
        BadgeDrawable historyBadge = binding.navigationPhone.getOrCreateBadge(R.id.history_nav);
        historyBadge.setVisible(false);
        historyBadge.clearNumber();
    }

    public void navigateToVoicemailFragment() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, new VoicemailPhoneFragment())
                .commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_dialer_menu, menu);
        MenuItem voicemailMenuItem = menu.findItem(R.id.voicemail);
        boolean voicemailEnabled = getPreferences().getBoolean("voicemail_enabled", false);
        voicemailMenuItem.setVisible(voicemailEnabled);
        voicemailMenuItem.setEnabled(voicemailEnabled && hasSelectedProxyNumber());
        super.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem voicemailItem = menu.findItem(R.id.voicemail);
        MenuItem smsAccountsItem = menu.findItem(R.id.sms_accounts);
        boolean voicemailEnabled = getPreferences().getBoolean("voicemail_enabled", false);
        if (voicemailItem != null) {
            voicemailItem.setVisible(voicemailEnabled);
            voicemailItem.setEnabled(hasSelectedProxyNumber());
            if (voicemailEnabled) {
                int unreadCount = getUnreadVoicemailCount();
                if (unreadCount > 0) {
                    voicemailItem.setIcon(R.drawable.twotone_voicemail_24); // A badge icon
                    voicemailItem.setTitle(getString(R.string.voicemail_tab_label_with_count, unreadCount));
                } else {
                    voicemailItem.setIcon(R.drawable.ic_voicemail_24dp);
                    voicemailItem.setTitle(R.string.voicemail_tab_label);
                }
            }
        }
        if (smsAccountsItem != null) {
            smsAccountsItem.setIcon(hasUnreadInOtherProfiles()
                    ? R.drawable.ic_baseline_more_vert_badge_24
                    : R.drawable.ic_baseline_more_vert_24);
        }
        return true;
    }

    private boolean hasUnreadInOtherProfiles() {
        if (smsProfileAdapter == null || smsProfileAdapter.smsProfileList == null || xmppConnectionService == null) {
            return false;
        }
        for (SmsProfile smsProfile : smsProfileAdapter.smsProfileList) {
            if (smsProfile == null) {
                continue;
            }
            if (selectedProfile != null && selectedProfile.getId() != null
                    && selectedProfile.getId().equals(smsProfile.getId())) {
                continue;
            }
            if (smsProfileAdapter.shouldShowBadgeForProfile(smsProfile)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.voicemail) {
            if (!hasSelectedProxyNumber()) {
                Toast.makeText(this, R.string.no_phoneNumbers_conversations, Toast.LENGTH_SHORT).show();
                return false;
            }
            navigateToVoicemailFragment();
        } else if (id == R.id.sms_accounts) {
            onDrawerOpened();
        }
        return super.onOptionsItemSelected(item);
    }

    protected void onDrawerOpened() {
        smsProfileAdapter.toggleNameOff();
        smsProfileAdapter.toggleDeleteOff();
        if (binding.smsProfilesDrawer.isOpen()) {
            binding.smsProfilesDrawer.closeDrawer(GravityCompat.END);
        } else {
            binding.smsProfilesDrawer.openDrawer(GravityCompat.END);
        }
        updateProfileMenuOptions();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
    }

    @Override
    public void receivedNewMessage(Message message) {
        smsProfileAdapter.setUnreadCountForProfile(message.getConversation());
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof SmsPhoneFragment) {
            ((SmsPhoneFragment) currentFragment).receivedNewMessage();
        }
    }

    @Override
    public void messageSentCallback(String sid) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof SmsPhoneFragment) {
            ((SmsPhoneFragment) currentFragment).refreshList();
        }
    }

    @Override
    public void messageSendFailed(String sid) {
    }

    private void reloadSmsProfileFrag() {
        runOnUiThread(() -> {
            if (!isActivityAlive()) {
                return;
            }
            FragmentManager fragmentManager = getSupportFragmentManager();
            Fragment currentFragment = fragmentManager.findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof SmsPhoneFragment) {
                ((SmsPhoneFragment) currentFragment).refreshList();
            }
        });
    }
    @Override
    public void reloadMessages() {
        reloadSmsProfileFrag();
    }

    @Override
    public void showList() {
        reloadSmsProfileFrag();
    }

    @Override
    public void onMoreMessagesLoaded(int newMessageCount, boolean hasMore) {
    }

    private void startPhoneSync(boolean force) {

        if (!xmppConnectionServiceBound || xmppConnectionService == null) {

            return;
        }
        if (xmppConnectionService.isPhoneSyncInProgress() && !force) {
            return;
        }
        if (!force
                && (xmppConnectionService.isPhonePrefetchInProgress()
                || xmppConnectionService.hasPhoneLoaded())) {
            return;
        }
        showLoading(true);
        xmppConnectionService.syncPhoneInfo(force);
    }

    @Override
    public void notifyMessages() {
    }

    @Override
    public void onConversationsClientConnectionStateChange(ConversationsClient.ConnectionState state) {
        conversationsClientConnectionState = state;
        if (state == ConversationsClient.ConnectionState.CONNECTED && xmppConnectionService != null) {
            isTwilioLoading = xmppConnectionService.isPhoneSyncInProgress();
            conversationClientSyncFailed = false;
        }
        updateTwilioStatusBar();
    }

    @Override
    public void onConsoleUp() {
        runOnUiThread(() -> {
            binding.consoleError.setVisibility(View.GONE);
        });
    }

    @Override
    public void onConsoleDown() {
        runOnUiThread(() -> {
            binding.consoleError.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onPhoneUpdate() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return;
        }
        runOnUiThread(() -> {
            smsProfileAdapter.setSmsProfileList(xmppConnectionService.getSmsInfo().getExistingProfs());
            smsProfileAdapter.setXmppConnectionService(xmppConnectionService);
            setProxyNumberAndToolbar(xmppConnectionService.getSmsInfo().getSelectedProfile(), true);
            showList();
            updatePhoneNavigationBadges();
            updateTwilioStatusBar();
        });
    }

    @Override
    public void onCallHistoryUpdate() {
    }

    @Override
    public void onVoicemailUpdate() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return;
        }
        runOnUiThread(() -> {
            setVoicemailNumbers();
            updatePhoneNavigationBadges();
            FragmentManager fragmentManager = getSupportFragmentManager();
            Fragment currentFragment =
                    fragmentManager != null
                            ? fragmentManager.findFragmentById(R.id.fragment_container)
                            : null;
            if (currentFragment instanceof VoicemailPhoneFragment) {
                ((VoicemailPhoneFragment) currentFragment).onSelectedVoiceMailLoaded();
            }
        });
    }

    @Override
    public void onAccessToken() {
    }

    @Override
    public void onConversationClientSync(boolean success) {
        conversationClientSyncFailed = !success;
        showLoading(false);
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        binding.smsProfilesDrawer.closeDrawer(GravityCompat.END);
        if(intent.hasExtra("proxyNum")  ){
            String proxyNum = intent.getStringExtra("proxyNum");
            SmsProfile spOfIntent = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(proxyNum);
            if (spOfIntent != null) {
                OnSMSProfileClick(spOfIntent);
            }

        }

        boolean hasRedirect = false;
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager != null && fragmentManager.isStateSaved()) {
            Log.w(Config.LOGTAG, "onNewIntent: skipping fragment transactions because state is already saved");
            return;
        }

        if(fragmentManager != null && intent.hasExtra(PHONE_MOD_INTENT)) {
            String fragIntent = intent.getExtras().getString(PHONE_MOD_INTENT);
            hasRedirect = true;
            switch (fragIntent){
                case HISTORY_FRAG:
                    selectTab(Tabs.HISTORY);
                    replaceFragmentSafely(new HistoryPhoneFragment());
                    break;
                case DIALER_FRAG:
                    selectTab(Tabs.DIALER);
                    replaceFragmentSafely(new DialPhoneFragment());
                    break;
                case SMS_FRAG:
                    selectTab(Tabs.SMS);
                    replaceFragmentSafely(new SmsPhoneFragment());
                    break;
                case CONTACT_FRAG:
                    selectTab(Tabs.CONTACTS);
                    replaceFragmentSafely(new ContactsPhoneFragment());
                    break;
                case VOICEMAIL_FRAG:
                    selectTab(Tabs.NONE);
                    replaceFragmentSafely(new VoicemailPhoneFragment());
                    break;
            }

        }

        if (fragmentManager != null) {
            Fragment currentFragment =  fragmentManager.findFragmentById(R.id.fragment_container);
            if (currentFragment == null && !hasRedirect) {
                selectTab(Tabs.DIALER);
                replaceFragmentSafely(new DialPhoneFragment());
            }
        }
    }


    @Override
    public void OnSMSNameClick(String nickname, SmsProfile smsProfile) {
        binding.smsProfilesDrawer.closeDrawer(GravityCompat.END);
        showWaitDialog("Updating nickname");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NicknameNum(nickname, smsProfile);
                } catch (Exception ignore) {}
                closeWaitDialog();
            }
        }).start();
    }

    @Override
    public void OnSMSProfileClick(SmsProfile smsProfile) {
        setProxyNumberAndToolbar(xmppConnectionService.getSmsInfo().setSelectedSmsProfile(smsProfile), false);
        binding.smsProfilesDrawer.closeDrawers();
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment currentFragment  = fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof PhoneFragment) {
            ((PhoneFragment)currentFragment).OnSMSProfileClick(smsProfile);
        }
        updatePhoneNavigationBadges();
    }

    @Override
    public void OnSMSRemoveClick(SmsProfile selectedSMSforRelease) {
        binding.smsProfilesDrawer.closeDrawers();
        showWaitDialog("Releasing number");
        new Thread(() -> {
            try {
                ReleaseNum(selectedSMSforRelease);
            } catch (Exception ignored) {
            }
            closeWaitDialog();
        }).start();
    }

    @Override
    public void OnSmsConversationClick(com.twilio.conversations.Conversation conversation) {
        Intent intent = new Intent(this, SmsConvActivity.class);
        startActivity(intent.putExtra("conv_sid", conversation.getSid()).putExtra("friendly_name", conversation.getFriendlyName()));
    }

    @Override
    public void OnSmsContactNameClick(String conv_name) {
        TwilioConversationEntity conv = xmppConnectionService.getConversationsBetweenContactAndProxy(conv_name, selectedProfile);
        Intent intent = new Intent(this, SmsConvActivity.class);
        startActivity(intent.putExtra("conv_sid", conv.getConv_sid()).putExtra("friendly_name", conv.getFriendlyName()));
    }

    @Override
    public void OnSmsSidClick(String convSid, String friendlyName) {
        // Keep unread state until the conversation screen is actually opened.
        // SmsConvActivity clears unread state after backend connection succeeds.
        Intent intent = new Intent(this, SmsConvActivity.class);
        startActivity(intent.putExtra("conv_sid", convSid).putExtra("friendly_name", PhoneUtils.getStripPhoneNumberWithCountryCode(friendlyName)));
    }

    @Override
    protected void refreshUiReal() {
    }


    @Override
    protected void onBackendConnected() {
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService, true);
        connectionToolbarAdapter.updateOfflineStatusBar();
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        binding.consoleError.setOnClickListener(v -> {
            if (xmppConnectionServiceBound) {
                xmppConnectionService.checkConsoleStatus();
            }
        });
        if (binding.consoleError.getVisibility() == View.VISIBLE) {
            binding.consoleError.setVisibility(View.GONE);
            xmppConnectionService.checkConsoleStatus();
        }
        account = xmppConnectionService.getFirstAccount();
        xmppConnectionService.setOnRtpConnectionUpdateListener(this);
        JingleConnectionManager callManager = xmppConnectionService.getJingleConnectionManager();
        if (callManager != null){
            JingleRtpConnection jrc = callManager.getOngoingRtpConnection();
            runOnUiThread(() -> {
                if (!isActivityAlive()) {
                    return;
                }

                if (jrc != null) {
                    binding.returnToGlacierCall.setVisibility(View.VISIBLE);
                }
                else{
                    binding.returnToGlacierCall.setVisibility(View.GONE);
                }
            });

        }

        conversationsManager = xmppConnectionService.getConversationsManager();
        if (conversationsManager != null) {
            conversationsManager.setOnConversationsManagerListener(this);
            conversationsClientConnectionState = conversationsManager.getConnectionState();
        } else {
            conversationsClientConnectionState = null;
        }
        isTwilioLoading = xmppConnectionService.isPhoneSyncInProgress()
                || conversationsManager == null
                || conversationsManager.isConversationsClientNull();
        updateTwilioStatusBar();
        xmppConnectionService.setOnSMSCompleteCallbackListener(this);

        Intent intent = getIntent();
        SmsProfile spOfIntent = null;
        if (xmppConnectionService.getSmsInfo() != null) {
            if (intent != null && intent.hasExtra("proxyNum")) {
                String proxyNum = intent.getStringExtra("proxyNum");
                spOfIntent = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(proxyNum);
                xmppConnectionService.getSmsInfo().setSelectedSmsProfile(spOfIntent);
            }
            smsProfileAdapter.setSmsProfileList(xmppConnectionService.getSmsInfo().getExistingProfs());
            smsProfileAdapter.setXmppConnectionService(xmppConnectionService);
        }
        SmsProfile sp = null;
        if (xmppConnectionService.getSmsInfo() != null){
            sp = xmppConnectionService.getSmsInfo().getSelectedProfile();
        }
        selectedProfile = sp;
        setProxyNumberAndToolbar(selectedProfile, false);
        showList();

        updateProfileMenuOptions();

        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager == null || fragmentManager.isStateSaved()) {
            Log.w(Config.LOGTAG, "onBackendConnected: skipping fragment transactions because state is already saved");
            return;
        }
        boolean hasRedirect = false;
        if(intent != null && intent.hasExtra(PHONE_MOD_INTENT)) {
            String fragIntent = intent.getExtras().getString(PHONE_MOD_INTENT);
            hasRedirect = true;
            switch (fragIntent){
                case HISTORY_FRAG:
                    selectTab(Tabs.HISTORY);
                    replaceFragmentSafely(new HistoryPhoneFragment());
                    break;
                case SMS_FRAG:
                    selectTab(Tabs.SMS);
                    replaceFragmentSafely(new SmsPhoneFragment());
                    break;
                case CONTACT_FRAG:
                    selectTab(Tabs.CONTACTS);
                    replaceFragmentSafely(new ContactsPhoneFragment());
                    break;
                case VOICEMAIL_FRAG:
                    selectTab(Tabs.NONE);
                    replaceFragmentSafely(new VoicemailPhoneFragment());
                    break;
            }

        }

        Fragment currentFragment =  fragmentManager.findFragmentById(R.id.fragment_container);
        if (currentFragment == null && !hasRedirect) {
            selectTab(Tabs.DIALER);
            replaceFragmentSafely(new DialPhoneFragment());
        }

        if (!xmppConnectionService.hasPhoneLoaded()) {
            showLoading(true);
            startPhoneSync(false);
        } else {
            onRefreshComplete(SMSdbInfo.PhoneSyncStatus.SUCCESS);
        }
    }

    private void refreshVoicemailState() {
        if (xmppConnectionService != null && account != null &&
                xmppConnectionService.getSmsInfo() != null) {
            for (SmsProfile smsProfile : xmppConnectionService.getSmsInfo().getExistingProfs()) {
                xmppConnectionService.getSmsInfo().listVoicemailRequest(account, smsProfile);
            }
        }
    }

    private void setProxyNumberAndToolbar(SmsProfile sp, boolean isComplete) {

        selectedProfile = sp;

        if (xmppConnectionServiceBound) {
            selectedProfile = xmppConnectionService.getSmsInfo().setSelectedSmsProfile(sp);
        }

        supportInvalidateOptionsMenu();


        if (selectedProfile == null) {
            if (conversationsManager != null){
                if (isComplete) {
                    binding.toolbar.setTitle(R.string.no_numbers_assigned);
                    twilioStatusTextView.setVisibility(View.GONE);
                    twilioProgressIndicator.setVisibility(View.GONE);


                    return;
                }
                if (conversationsManager.isConversationsClientNull()){

                    binding.toolbar.setTitle(R.string.loading_phone_number);
                    binding.toolbar.setBackgroundColor(Color.TRANSPARENT);
                    return;
                }
            }
            if (conversationsManager == null){
                    binding.toolbar.setTitle(R.string.loading_phone_number);

                binding.toolbar.setBackgroundColor(Color.TRANSPARENT);
                    return;
            }
            binding.toolbar.setTitle("No numbers assigned");
            twilioStatusTextView.setVisibility(View.GONE);
            twilioProgressIndicator.setVisibility(View.GONE);

            binding.toolbar.setBackgroundColor(Color.TRANSPARENT);
            return;
        }


        if (selectedProfile.getNickname() != null && !selectedProfile.getNickname().isEmpty()) {
            binding.toolbar.setTitle(selectedProfile.getNickname());
        } else {
            binding.toolbar.setTitle(selectedProfile.getFormattedNumber());
        }
        binding.toolbar.setBackgroundColor(selectedProfile.getColor());

        setVoicemailNumbers();

    }

    private boolean hasSelectedProxyNumber() {
        if (xmppConnectionServiceBound && xmppConnectionService != null
                && xmppConnectionService.getSmsInfo() != null) {
            return xmppConnectionService.getSmsInfo().getSelectedProfile() != null;
        }
        return selectedProfile != null;
    }


    private void configureBackPressedCallback() {
        if (exitCallback != null) {
            exitCallback.remove();
        }
        exitCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (currentFragment instanceof SmsPhoneFragment
                        || currentFragment instanceof HistoryPhoneFragment
                        || currentFragment instanceof ContactsPhoneFragment) {
                    selectTab(Tabs.DIALER);
                    replaceFragmentSafely(new DialPhoneFragment());
                    return;
                }
                Intent intent = new Intent(PhoneModuleActivity.this, ConversationsActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, exitCallback);
    }

    private final View.OnClickListener mRefreshNetworkClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            connectionToolbarAdapter.onClick();
        }
    };

    public void refresh() {
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (xmppConnectionServiceBound && xmppConnectionService.hasPhoneLoaded()) {
            refreshVoicemailState();
            onRefreshComplete(SMSdbInfo.PhoneSyncStatus.SUCCESS);
        }
        if (xmppConnectionServiceBound && binding.consoleError.getVisibility() == View.VISIBLE) {
            binding.consoleError.setVisibility(View.GONE);
            xmppConnectionService.checkConsoleStatus();
        }
        configureBackPressedCallback();
        updatePhoneNavigationBadges();

    }

    private boolean isActivityAlive() {
        if (isFinishing()) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return !isDestroyed();
        }
        return true;
    }

}
