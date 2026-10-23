package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.utils.PermissionUtils.getFirstDenied;

import static java.util.Arrays.asList;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.TooltipCompat;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityRtpSessionBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.services.CallIntegrationConnectionService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.ui.util.MainThreadExecutor;
import com.glaciersecurity.glaciermessenger.ui.util.Rationals;
import com.glaciersecurity.glaciermessenger.utils.PermissionUtils;
import com.glaciersecurity.glaciermessenger.utils.TimeFrameUtils;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.AbstractJingleConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.ContentAddition;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.Media;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.OngoingRtpSession;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpCapability;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class RtpSessionActivity extends XmppActivity
        implements XmppConnectionService.OnJingleRtpConnectionUpdate,
                com.glaciersecurity.glaciermessenger.ui.widget.AspectRatioSurfaceViewRenderer.OnAspectRatioChanged {

    public static final String EXTRA_WITH = "with";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_PROPOSED_SESSION_ID = "proposed_session_id";
    public static final String EXTRA_LAST_REPORTED_STATE = "last_reported_state";
    public static final String EXTRA_LAST_ACTION = "last_action";
    public static final String ACTION_ACCEPT_CALL = "action_accept_call";
    public static final String ACTION_MAKE_VOICE_CALL = "action_make_voice_call";
    public static final String ACTION_MAKE_VIDEO_CALL = "action_make_video_call";

    private static final int CALL_DURATION_UPDATE_INTERVAL = 333;

    private static final List<RtpEndUserState> END_CARD =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.SECURITY_ERROR,
                    RtpEndUserState.DECLINED_OR_BUSY,
                    RtpEndUserState.CONTACT_OFFLINE,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                    RtpEndUserState.RETRACTED);
    private static final List<RtpEndUserState> STATES_SHOWING_HELP_BUTTON =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.SECURITY_ERROR);
    private static final List<RtpEndUserState> STATES_SHOWING_SWITCH_TO_CHAT =
            Arrays.asList(
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.CONNECTED,
                    RtpEndUserState.RECONNECTING,
                    RtpEndUserState.INCOMING_CONTENT_ADD);
    private static final List<RtpEndUserState> STATES_CONSIDERED_CONNECTED =
            Arrays.asList(RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_PIP_PLACEHOLDER =
            Arrays.asList(
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_SPEAKER_CONFIGURATION =
            new ImmutableList.Builder<RtpEndUserState>()
                    .add(RtpEndUserState.FINDING_DEVICE)
                    .add(RtpEndUserState.RINGING)
                    .add(RtpEndUserState.ACCEPTING_CALL)
                    .add(RtpEndUserState.CONNECTING)
                    .addAll(STATES_CONSIDERED_CONNECTED)
                    .build();
    private static final List<RtpEndUserState> STATES_SHOWING_AVATAR_RING =
            Arrays.asList(
                    RtpEndUserState.INCOMING_CALL,
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.FINDING_DEVICE,
                    RtpEndUserState.RINGING,
                    RtpEndUserState.CONNECTING);
    private static final int[] EQUALIZER_BAR_DURATIONS = {900, 990, 1080, 1170, 1260};
    private static final int[] EQUALIZER_BAR_DELAYS = {0, 180, 90, 260, 130};
    private static final String PROXIMITY_WAKE_LOCK_TAG = "conversations:in-rtp-session";
    private static final int REQUEST_ACCEPT_CALL = 0x1111;
    private static final int REQUEST_ACCEPT_CONTENT = 0x1112;
    private static final int REQUEST_ADD_CONTENT = 0x1113;
    private WeakReference<JingleRtpConnection> rtpConnectionReference;

    private ActivityRtpSessionBinding binding;
    private PowerManager.WakeLock mProximityWakeLock;
    private Animator[] ringAnimators;
    private List<Animator> equalizerAnimators;

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    updateCallDuration();
                    mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
                }
            };

    public static Set<Media> actionToMedia(final String action) {
        if (ACTION_MAKE_VIDEO_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO, Media.VIDEO);
        } else if (ACTION_MAKE_VOICE_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO);
        } else {
            Log.w(
                    Config.LOGTAG,
                    "actionToMedia can not get media set from unknown action " + action);
            return Collections.emptySet();
        }
    }

    private static void addSink(
            final VideoTrack videoTrack, final SurfaceViewRenderer surfaceViewRenderer) {
        try {
            videoTrack.addSink(surfaceViewRenderer);
        } catch (final IllegalStateException e) {
            Log.e(
                    Config.LOGTAG,
                    "possible race condition on trying to display video track. ignoring",
                    e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow()
                .addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_rtp_session);
        setSupportActionBar(binding.toolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isConnected()) {
                    if (switchToPictureInPicture()) {
                        return;
                    }
                } else {
                    endCall();
                }
                finish();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_rtp_session, menu);
        final MenuItem help = menu.findItem(R.id.action_help);
        final MenuItem gotoChat = menu.findItem(R.id.action_goto_chat);
        final MenuItem switchToVideo = menu.findItem(R.id.action_switch_to_video);
        final MenuItem switchToAudio = menu.findItem(R.id.action_switch_to_audio);
        help.setVisible(Config.HELP != null && isHelpButtonVisible());
        gotoChat.setVisible(isSwitchToConversationVisible());
        switchToVideo.setVisible(isSwitchToVideoVisible());
        switchToAudio.setVisible(isSwitchToAudioVisible());
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (xmppConnectionService != null) {
                if (xmppConnectionService.getNotificationService().stopSoundAndVibration()) {
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isHelpButtonVisible() {
        try {
            return STATES_SHOWING_HELP_BUTTON.contains(requireRtpConnection().getEndUserState());
        } catch (IllegalStateException e) {
            final Intent intent = getIntent();
            final String state =
                    intent != null ? intent.getStringExtra(EXTRA_LAST_REPORTED_STATE) : null;
            if (state != null) {
                return STATES_SHOWING_HELP_BUTTON.contains(RtpEndUserState.valueOf(state));
            } else {
                return false;
            }
        }
    }

    private boolean isSwitchToConversationVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return connection != null
                && STATES_SHOWING_SWITCH_TO_CHAT.contains(connection.getEndUserState());
    }

    private boolean isSwitchToVideoVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return false;
        }
        return connection.isSwitchToVideoAvailable();
    }

    private boolean isSwitchToAudioVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return false;
        }
        return connection.getMedia().contains(Media.VIDEO)
                && STATES_CONSIDERED_CONNECTED.contains(connection.getEndUserState())
                && connection.isVideoEnabled();
    }

    private void switchToConversation() {
        final Contact contact = getWith();
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), false, true);
        switchToConversation(conversation);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final var itemItem = item.getItemId();
        if (itemItem == R.id.action_help) {
            launchHelpInBrowser();
            return true;
        } else if (itemItem == R.id.action_goto_chat) {
            switchToConversation();
            return true;
        } else if (itemItem == R.id.action_switch_to_video) {
            requestPermissionAndSwitchToVideo();
            return true;
        } else if (itemItem == R.id.action_switch_to_audio) {
            disableVideo();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void launchHelpInBrowser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Config.HELP);
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void endCall(View view) {
        endCall();
    }

    private void endCall() {
        if (this.rtpConnectionReference == null) {
            retractSessionProposal();
            finish();
        } else {
            requireRtpConnection().endCall();
        }
    }

    private void retractSessionProposal() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String state = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
        if (!Intent.ACTION_VIEW.equals(action)
                || state == null
                || !END_CARD.contains(RtpEndUserState.valueOf(state))) {
            final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
            resetIntent(account, with, RtpEndUserState.RETRACTED, media);
        }
        xmppConnectionService
                .getJingleConnectionManager()
                .retractSessionProposal(account, with.asBareJid());
    }

    private void rejectCall(View view) {
        requireRtpConnection().rejectCall();
        finish();
    }

    private void acceptCall(View view) {
        requestPermissionsAndAcceptCall();
    }

    private void acceptContentAdd() {
        try {
            final ContentAddition pendingContentAddition =
                    requireRtpConnection().getPendingContentAddition();
            if (pendingContentAddition == null) {
                return;
            }
            requireRtpConnection().acceptContentAdd(pendingContentAddition.summary);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, Strings.nullToEmpty(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPermissionAndSwitchToVideo() {
        final List<String> permissions = permissions(ImmutableSet.of(Media.VIDEO, Media.AUDIO));
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ADD_CONTENT)) {
            switchToVideo();
        }
    }

    private void switchToVideo() {
        try {
            requireRtpConnection().addMedia(Media.VIDEO);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void acceptContentAdd(final ContentAddition contentAddition) {
        if (contentAddition == null
                || contentAddition.direction != ContentAddition.Direction.INCOMING) {
            return;
        }
        requestPermissionAndAcceptContentAdd(contentAddition);
    }

    private void requestPermissionAndAcceptContentAdd(final ContentAddition contentAddition) {
        final List<String> permissions = permissions(contentAddition.media());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CONTENT)) {
            try {
                requireRtpConnection().acceptContentAdd(contentAddition.summary);
            } catch (final IllegalStateException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void rejectContentAdd(final View view) {
        requireRtpConnection().rejectContentAdd();
    }

    private void requestPermissionsAndAcceptCall() {
        final List<String> permissions = permissions(getMedia());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CALL)) {
            putScreenInCallMode();
            acceptCall();
        }
    }

    private static List<String> permissions(final Set<Media> media) {
        return PermissionUtils.rtpPermissions(media.contains(Media.VIDEO));
    }

    private void acceptCall() {
        try {
            requireRtpConnection().acceptCall();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void putScreenInCallMode() {
        putScreenInCallMode(requireRtpConnection().getMedia());
    }

    private void putScreenInCallMode(final Set<Media> media) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Media.audioOnly(media)) {
            final JingleRtpConnection rtpConnection =
                    rtpConnectionReference != null ? rtpConnectionReference.get() : null;
            final CallIntegration callIntegration =
                    rtpConnection == null ? null : rtpConnection.getCallIntegration();
            if (callIntegration == null
                    || callIntegration.getSelectedAudioDevice()
                            == CallIntegration.AudioDevice.EARPIECE) {
                acquireProximityWakeLock();
            }
        }
        lockOrientation();
    }

    private void lockOrientation() {
        // Keep orientation unrestricted to support large-screen devices and multi-window posture
        // changes.
    }

    @SuppressLint("WakelockTimeout")
    private void acquireProximityWakeLock() {
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Log.e(Config.LOGTAG, "power manager not available");
            return;
        }
        if (isFinishing()) {
            Log.e(Config.LOGTAG, "do not acquire wakelock. activity is finishing");
            return;
        }
        if (this.mProximityWakeLock == null) {
            this.mProximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_WAKE_LOCK_TAG);
        }
        if (!this.mProximityWakeLock.isHeld()) {
            this.mProximityWakeLock.acquire();
        }
    }

    private void releaseProximityWakeLock() {
        if (this.mProximityWakeLock != null && mProximityWakeLock.isHeld()) {
            this.mProximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            this.mProximityWakeLock = null;
        }
    }

    private void putProximityWakeLockInProperState(final CallIntegration.AudioDevice audioDevice) {
        if (audioDevice == CallIntegration.AudioDevice.EARPIECE) {
            acquireProximityWakeLock();
        } else {
            releaseProximityWakeLock();
        }
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        if (xmppConnectionService == null) {
            return;
        }
        initializeWithIntent(Event.ON_NEW_INTENT, intent);
    }

    @Override
    protected void onBackendConnected() {
        final var intent = getIntent();
        if (intent == null) {
            return;
        }
        initializeWithIntent(Event.ON_BACKEND_CONNECTED, intent);
    }

    private void initializeWithIntent(final Event event, @NonNull final Intent intent) {
        final String action = intent.getAction();
        final Account account = extractAccount(intent);
        final var extraWith = intent.getStringExtra(EXTRA_WITH);
        final Jid with = Strings.isNullOrEmpty(extraWith) ? null : Jid.ofEscaped(extraWith);
        if (with == null || account == null) {
            Log.e(Config.LOGTAG, "intent is missing extras (account or with)");
            return;
        }
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null) {
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return;
            }
            if (ACTION_ACCEPT_CALL.equals(intent.getAction())) {
                requestPermissionsAndAcceptCall();
                resetIntent(intent.getExtras());
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            final String proposedSessionId = intent.getStringExtra(EXTRA_PROPOSED_SESSION_ID);
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, proposedSessionId);
            if (terminatedRtpSession != null) {
                // termination (due to message error or 'busy' was faster than opening the activity
                initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
                return;
            }
            final String extraLastState = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
            final RtpEndUserState state =
                    extraLastState == null ? null : RtpEndUserState.valueOf(extraLastState);
            if (state != null) {
                updateButtonConfiguration(state);
                updateVerifiedShield(false);
                updateStateDisplay(state);
                updateHeroAvatar(state, account.getRoster().getContact(with));
                invalidateOptionsMenu();
            }
            setWith(account.getRoster().getContact(with));
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .fireJingleRtpConnectionStateUpdates()) {
                return;
            }
            if (END_CARD.contains(state)) {
                return;
            }
            final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
            final Set<Media> media = actionToMedia(lastAction);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .hasMatchingProposal(account, with)) {
                putScreenInCallMode(media);
                return;
            }
            finish();
        }
    }

    private void setWith(final Contact contact) {
        binding.with.setText(contact.getDisplayName());
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeOptionalRtpPermissions(permissions, grantResults);
        if (PermissionUtils.allGranted(permissionResult.grantResults)) {
            if (requestCode == REQUEST_ACCEPT_CALL) {
                acceptCall();
            } else if (requestCode == REQUEST_ACCEPT_CONTENT) {
                acceptContentAdd();
            } else if (requestCode == REQUEST_ADD_CONTENT) {
                switchToVideo();
            }
        } else {
            @StringRes int res;
            final String firstDenied =
                    getFirstDenied(permissionResult.grantResults, permissionResult.permissions);
            if (firstDenied == null) {
                return;
            }
            if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                res = R.string.no_microphone_permission;
            } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                res = R.string.no_camera_permission;
            } else {
                throw new IllegalStateException("Invalid permission result request");
            }
            Toast.makeText(this, getString(res, getString(R.string.app_name)), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
        this.binding.remoteVideo.setOnAspectRatioChanged(this);
    }

    @Override
    public void onStop() {
        mHandler.removeCallbacks(mTickExecutor);
        stopCallAnimations();
        binding.remoteVideo.release();
        binding.remoteVideo.setOnAspectRatioChanged(null);
        binding.localVideoSurface.release();
        final WeakReference<JingleRtpConnection> weakReference = this.rtpConnectionReference;
        final JingleRtpConnection jingleRtpConnection =
                weakReference == null ? null : weakReference.get();
        if (jingleRtpConnection != null) {
            releaseVideoTracks(jingleRtpConnection);
        }
        releaseProximityWakeLock();
        super.onStop();
    }

    private void releaseVideoTracks(final JingleRtpConnection jingleRtpConnection) {
        final Optional<VideoTrack> remoteVideo = jingleRtpConnection.getRemoteVideoTrack();
        if (remoteVideo.isPresent()) {
            remoteVideo.get().removeSink(binding.remoteVideo);
        }
        final Optional<VideoTrack> localVideo = jingleRtpConnection.getLocalVideoTrack();
        if (localVideo.isPresent()) {
            localVideo.get().removeSink(binding.localVideoSurface);
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (switchToPictureInPicture()) {
            return;
        }
        if (emptyReference(rtpConnectionReference) && xmppConnectionService != null) {
            retractSessionProposal();
        }
    }

    private boolean isConnected() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final RtpEndUserState endUserState =
                connection == null ? null : connection.getEndUserState();
        return STATES_CONSIDERED_CONNECTED.contains(endUserState)
                || endUserState == RtpEndUserState.INCOMING_CONTENT_ADD;
    }

    private boolean switchToPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceSupportsPictureInPicture()) {
            if (shouldBePictureInPicture()) {
                startPictureInPicture();
                return true;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startPictureInPicture() {
        try {
            final Rational rational = this.binding.remoteVideo.getAspectRatio();
            final Rational clippedRational = Rationals.clip(rational);
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        } catch (final IllegalStateException e) {
            // this sometimes happens on Samsung phones (possibly when Knox is enabled)
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        }
    }

    @Override
    public void onAspectRatioChanged(final Rational rational) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPictureInPicture()) {
            final Rational clippedRational = Rationals.clip(rational);
            setPictureInPictureParams(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        }
    }

    private boolean deviceSupportsPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    private boolean shouldBePictureInPicture() {
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            return rtpConnection.getMedia().contains(Media.VIDEO)
                    && Arrays.asList(
                                    RtpEndUserState.ACCEPTING_CALL,
                                    RtpEndUserState.CONNECTING,
                                    RtpEndUserState.CONNECTED)
                            .contains(rtpConnection.getEndUserState());
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean initializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        final WeakReference<JingleRtpConnection> reference =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .findJingleRtpConnection(account, with, sessionId);
        JingleRtpConnection rtpConnection = reference == null ? null : reference.get();
        // Set when we fall back to the ongoing call below. The caller must not go on to apply the
        // intent's action to a session the intent did not name.
        boolean adoptedOngoingSession = false;
        if (rtpConnection == null) {
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, sessionId);
            if (terminatedRtpSession != null) {
                initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
                return true;
            }
            // The intent named a session neither the connection map nor the terminated-session
            // cache knows about. Every route into this activity that is not the activity's own
            // saved intent is a deep link built from identifiers captured earlier - the two
            // ongoing-call notifications, CallIntegrationConnectionService - and any of them can
            // name a session that has since been re-keyed (an outgoing proposal gains a full JID
            // when a device answers) or forgotten (the service restarted).
            //
            // Closing the call screen on that miss is right only when there is no call. While one
            // is up it strands the user: the notification's whole job is to get back to the call,
            // and finishing here drops them on whatever was behind it, which reads as the tap
            // doing nothing at all. Only one RTP session can be live at a time -
            // JingleConnectionManager.isBusy() enforces that - so an ongoing connection is
            // unambiguously the call the user is asking to see. Adopt it and correct our intent.
            final JingleRtpConnection ongoing =
                    xmppConnectionService.getJingleConnectionManager().getOngoingRtpConnection();
            if (ongoing == null) {
                Log.w(
                        Config.LOGTAG,
                        "failed to initialize activity with running rtp session. session "
                                + sessionId
                                + " not found and no call is ongoing. finishing");
                finish();
                return true;
            }
            final AbstractJingleConnection.Id ongoingId = ongoing.getId();
            Log.w(
                    Config.LOGTAG,
                    "session "
                            + sessionId
                            + " with "
                            + Jid.obfuscateForLog(with)
                            + " not found; showing the ongoing session "
                            + ongoingId.sessionId
                            + " with "
                            + Jid.obfuscateForLog(ongoingId.with)
                            + " instead");
            rtpConnection = ongoing;
            adoptedOngoingSession = true;
            resetIntent(ongoingId.account, ongoingId.with, ongoingId.sessionId);
        }
        this.rtpConnectionReference = new WeakReference<>(rtpConnection);
        final RtpEndUserState currentState = requireRtpConnection().getEndUserState();
        final boolean verified = requireRtpConnection().isVerified();
        if (currentState == RtpEndUserState.ENDED) {
            finish();
            return true;
        }
        final Set<Media> media = getMedia();
        final ContentAddition contentAddition = getPendingContentAddition();
        if (currentState == RtpEndUserState.INCOMING_CALL) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                requireRtpConnection().getState())) {
            putScreenInCallMode();
        }
        setWith(getWith());
        updateVideoViews(currentState);
        updateStateDisplay(currentState, media, contentAddition);
        updateVerifiedShield(verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(currentState));
        updateButtonConfiguration(currentState, media, contentAddition);
        updateHeroAvatar(currentState);
        invalidateOptionsMenu();
        // Returning true stops initializeWithIntent() from handling the intent's action. On the
        // fallback path the action was aimed at a session we could not find, so applying it to the
        // call we adopted would act on the wrong call - an ACTION_ACCEPT_CALL would answer a
        // caller the user never chose to answer. resetIntent() above cannot prevent that: it
        // replaces the activity's stored intent, not the local one the caller is still reading.
        return adoptedOngoingSession;
    }

    private void initializeWithTerminatedSessionState(
            final Account account,
            final Jid with,
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession) {
        if (terminatedRtpSession.state == RtpEndUserState.ENDED) {
            finish();
            return;
        }
        final RtpEndUserState state = terminatedRtpSession.state;
        resetIntent(account, with, terminatedRtpSession.state, terminatedRtpSession.media);
        updateButtonConfiguration(state);
        updateStateDisplay(state);
        updateHeroAvatar(state, account.getRoster().getContact(with));
        updateCallDuration();
        updateVerifiedShield(false);
        invalidateOptionsMenu();
        setWith(account.getRoster().getContact(with));
    }

    private void reInitializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        runOnUiThread(() -> initializeActivityWithRunningRtpSession(account, with, sessionId));
        resetIntent(account, with, sessionId);
    }

    private void resetIntent(final Account account, final Jid with, final String sessionId) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toEscapedString());
        intent.putExtra(EXTRA_WITH, with.toEscapedString());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        setIntent(intent);
    }

    private void ensureSurfaceViewRendererIsSetup(final SurfaceViewRenderer surfaceViewRenderer) {
        surfaceViewRenderer.setVisibility(View.VISIBLE);
        try {
            surfaceViewRenderer.init(requireRtpConnection().getEglBaseContext(), null);
        } catch (final IllegalStateException ignored) {
            // SurfaceViewRenderer was already initialized
        } catch (final RuntimeException e) {
            if (Throwables.getRootCause(e) instanceof GLException glException) {
                Log.w(Config.LOGTAG, "could not set up hardware renderer", glException);
            }
        }
        surfaceViewRenderer.setEnableHardwareScaler(true);
    }

    private void updateStateDisplay(final RtpEndUserState state) {
        updateStateDisplay(state, Collections.emptySet(), null);
    }

    private void updateStateDisplay(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        switch (state) {
            case INCOMING_CALL -> {
                Preconditions.checkArgument(media.size() > 0, "Media must not be empty");
                if (media.contains(Media.VIDEO)) {
                    setStateDisplay(R.string.rtp_state_incoming_video_call);
                } else {
                    setStateDisplay(R.string.rtp_state_incoming_call);
                }
            }
            case INCOMING_CONTENT_ADD -> {
                if (contentAddition != null && contentAddition.media().contains(Media.VIDEO)) {
                    setStateDisplay(R.string.rtp_state_content_add_video);
                } else {
                    setStateDisplay(R.string.rtp_state_content_add);
                }
            }
            case CONNECTING -> setStateDisplay(R.string.rtp_state_connecting);
            case CONNECTED -> setStateDisplay(R.string.rtp_state_connected);
            case RECONNECTING -> setStateDisplay(R.string.rtp_state_reconnecting);
            case ACCEPTING_CALL -> setStateDisplay(R.string.rtp_state_accepting_call);
            case ENDING_CALL -> setStateDisplay(R.string.rtp_state_ending_call);
            case FINDING_DEVICE -> setStateDisplay(R.string.rtp_state_finding_device);
            case RINGING -> setStateDisplay(R.string.calling);
            case DECLINED_OR_BUSY -> setStateDisplay(R.string.rtp_state_declined_or_busy);
            case CONTACT_OFFLINE -> setStateDisplay(R.string.rtp_state_contact_offline);
            case CONNECTIVITY_ERROR -> setStateDisplay(R.string.rtp_state_connectivity_error);
            case CONNECTIVITY_LOST_ERROR -> setStateDisplay(
                    R.string.rtp_state_connectivity_lost_error);
            case RETRACTED -> setStateDisplay(R.string.rtp_state_retracted);
            case APPLICATION_ERROR -> setStateDisplay(R.string.rtp_state_application_failure);
            case SECURITY_ERROR -> setStateDisplay(R.string.rtp_state_security_error);
            case ENDED -> throw new IllegalStateException(
                    "Activity should have called finishAndReleaseWakeLock();");
            default -> throw new IllegalStateException(
                    String.format("State %s has not been handled in UI", state));
        }
        binding.stateCaption.setVisibility(
                state == RtpEndUserState.CONNECTED ? View.GONE : View.VISIBLE);
    }

    private void setStateDisplay(@StringRes final int resId) {
        setTitle(resId);
        binding.stateCaption.setText(resId);
    }

    private void updateVerifiedShield(final boolean verified) {
        final int visibility =
                !isPictureInPicture() && verified ? View.VISIBLE : View.GONE;
        this.binding.verified.setVisibility(visibility);
        this.binding.encryptedLabel.setVisibility(visibility);
    }

    private void updateHeroAvatar(final RtpEndUserState state) {
        updateHeroAvatar(state, null);
    }

    private void updateHeroAvatar(final RtpEndUserState state, final Contact contact) {
        if (state == RtpEndUserState.INCOMING_CALL || state == RtpEndUserState.ACCEPTING_CALL) {
            final Account account = contact == null ? getWith().getAccount() : contact.getAccount();
            binding.usingAccount.setText(
                    getString(
                            R.string.using_account,
                            account.getJid().asBareJid().toEscapedString()));
        } else {
            binding.usingAccount.setVisibility(View.GONE);
        }
        if (isPictureInPicture()) {
            binding.contactPhoto.setVisibility(View.GONE);
            stopCallAnimations();
            return;
        }
        // The avatar is the hero element across every call state (incoming, outgoing,
        // connected); a live video feed simply gets drawn on top of it once available.
        final boolean show = getResources().getBoolean(R.bool.show_avatar_incoming_call);
        if (show) {
            binding.contactPhoto.setVisibility(View.VISIBLE);
            if (contact == null) {
                AvatarWorkerTask.loadAvatar(
                        getWith(), binding.contactPhoto, R.dimen.call_hero_avatar_size);
            } else {
                AvatarWorkerTask.loadAvatar(
                        contact, binding.contactPhoto, R.dimen.call_hero_avatar_size);
            }
        } else {
            binding.contactPhoto.setVisibility(View.GONE);
        }
        updateCallAnimations(state, show, contact);
    }

    private int callToneColor(final Contact contact) {
        final String seed = contact == null ? null : contact.getDisplayName();
        return UIHelper.getColorForName(seed);
    }

    private void updateCallAnimations(
            final RtpEndUserState state, final boolean avatarShown, final Contact contact) {
        binding.callGlow.setVisibility(avatarShown ? View.VISIBLE : View.GONE);
        if (avatarShown) {
            final int tone = callToneColor(contact);
            binding.callGlow.getDrawable().mutate().setColorFilter(tone, PorterDuff.Mode.SRC_IN);
            if (STATES_SHOWING_AVATAR_RING.contains(state)) {
                tintRing(binding.callRingOuter, tone);
                tintRing(binding.callRingInner, tone);
                startRingAnimation();
                return;
            }
        }
        stopRingAnimation();
    }

    private static void tintRing(final View ring, final int color) {
        final Drawable background = ring.getBackground();
        if (background instanceof GradientDrawable gradientDrawable) {
            final int strokeWidth =
                    ring.getResources().getDimensionPixelSize(R.dimen.call_avatar_ring_stroke);
            gradientDrawable.mutate();
            gradientDrawable.setStroke(strokeWidth, color);
        }
    }

    private void startRingAnimation() {
        if (ringAnimators != null) {
            return;
        }
        binding.callRingOuter.setVisibility(View.VISIBLE);
        binding.callRingInner.setVisibility(View.VISIBLE);
        ringAnimators =
                new Animator[] {
                    createRingPulse(binding.callRingOuter, 0),
                    createRingPulse(binding.callRingInner, 1100),
                };
        for (final Animator animator : ringAnimators) {
            animator.start();
        }
    }

    private static Animator createRingPulse(final View ring, final long startDelay) {
        ring.setScaleX(1f);
        ring.setScaleY(1f);
        ring.setAlpha(0.55f);
        final ObjectAnimator animator =
                ObjectAnimator.ofPropertyValuesHolder(
                        ring,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.7f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.7f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f));
        animator.setDuration(2200);
        animator.setStartDelay(startDelay);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        return animator;
    }

    private void stopRingAnimation() {
        if (ringAnimators != null) {
            for (final Animator animator : ringAnimators) {
                animator.cancel();
            }
            ringAnimators = null;
        }
        binding.callRingOuter.setVisibility(View.GONE);
        binding.callRingInner.setVisibility(View.GONE);
    }

    private void startEqualizer() {
        if (equalizerAnimators != null) {
            return;
        }
        binding.equalizer.setVisibility(View.VISIBLE);
        equalizerAnimators = new ArrayList<>();
        final int count = binding.equalizer.getChildCount();
        for (int i = 0; i < count; i++) {
            final View bar = binding.equalizer.getChildAt(i);
            final ObjectAnimator animator =
                    ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.34f, 1f);
            animator.setDuration(EQUALIZER_BAR_DURATIONS[i % EQUALIZER_BAR_DURATIONS.length]);
            animator.setStartDelay(EQUALIZER_BAR_DELAYS[i % EQUALIZER_BAR_DELAYS.length]);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.start();
            equalizerAnimators.add(animator);
        }
    }

    private void stopEqualizer() {
        if (equalizerAnimators != null) {
            for (final Animator animator : equalizerAnimators) {
                animator.cancel();
            }
            equalizerAnimators = null;
        }
        binding.equalizer.setVisibility(View.GONE);
    }

    private void stopCallAnimations() {
        stopRingAnimation();
        stopEqualizer();
    }

    private Set<Media> getMedia() {
        return requireRtpConnection().getMedia();
    }

    public ContentAddition getPendingContentAddition() {
        return requireRtpConnection().getPendingContentAddition();
    }

    private void updateButtonConfiguration(final RtpEndUserState state) {
        updateButtonConfiguration(state, Collections.emptySet(), null);
    }

    @SuppressLint("RestrictedApi")
    private void updateButtonConfiguration(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        if (state == RtpEndUserState.ENDING_CALL || isPictureInPicture()) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.rejectCallLabel.setVisibility(View.INVISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCallLabel.setVisibility(View.INVISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CALL) {
            this.binding.rejectCall.setContentDescription(getString(R.string.dismiss_call));
            this.binding.rejectCall.setOnClickListener(this::rejectCall);
            this.binding.rejectCall.setImageResource(R.drawable.ic_call_end_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.rejectCallLabel.setText(R.string.decline);
            this.binding.rejectCallLabel.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.answer_call));
            this.binding.acceptCall.setOnClickListener(this::acceptCall);
            this.binding.acceptCall.setImageResource(R.drawable.ic_call_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
            this.binding.acceptCallLabel.setText(R.string.accept);
            this.binding.acceptCallLabel.setVisibility(View.VISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CONTENT_ADD) {
            this.binding.rejectCall.setContentDescription(
                    getString(R.string.reject_switch_to_video));
            this.binding.rejectCall.setOnClickListener(this::rejectContentAdd);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.rejectCallLabel.setText(R.string.decline);
            this.binding.rejectCallLabel.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.accept));
            this.binding.acceptCall.setOnClickListener((v -> acceptContentAdd(contentAddition)));
            this.binding.acceptCall.setImageResource(R.drawable.ic_check_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
            this.binding.acceptCallLabel.setText(R.string.accept);
            this.binding.acceptCallLabel.setVisibility(View.VISIBLE);
        } else if (asList(RtpEndUserState.DECLINED_OR_BUSY, RtpEndUserState.CONTACT_OFFLINE)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.rejectCallLabel.setText(R.string.exit);
            this.binding.rejectCallLabel.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.record_voice_mail));
            this.binding.acceptCall.setOnClickListener(this::recordVoiceMail);
            this.binding.acceptCall.setImageResource(R.drawable.ic_voicemail_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
            this.binding.acceptCallLabel.setText(R.string.record_voice_mail);
            this.binding.acceptCallLabel.setVisibility(View.VISIBLE);
        } else if (asList(
                        RtpEndUserState.CONNECTIVITY_ERROR,
                        RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                        RtpEndUserState.APPLICATION_ERROR,
                        RtpEndUserState.RETRACTED,
                        RtpEndUserState.SECURITY_ERROR)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.rejectCallLabel.setText(R.string.exit);
            this.binding.rejectCallLabel.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.try_again));
            this.binding.acceptCall.setOnClickListener(this::retry);
            this.binding.acceptCall.setImageResource(R.drawable.ic_replay_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
            this.binding.acceptCallLabel.setText(R.string.try_again);
            this.binding.acceptCallLabel.setVisibility(View.VISIBLE);
        } else {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.rejectCallLabel.setVisibility(View.INVISIBLE);
            this.binding.endCall.setContentDescription(getString(R.string.hang_up));
            this.binding.endCall.setOnClickListener(this::endCall);
            this.binding.endCall.setImageResource(R.drawable.ic_call_end_24dp);
            this.binding.endCall.setVisibility(View.VISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCallLabel.setVisibility(View.INVISIBLE);
        }
        updateInCallButtonConfiguration(state, media);
    }

    private boolean isPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return isInPictureInPictureMode();
        } else {
            return false;
        }
    }

    private void updateInCallButtonConfiguration() {
        updateInCallButtonConfiguration(
                requireRtpConnection().getEndUserState(), requireRtpConnection().getMedia());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfiguration(
            final RtpEndUserState state, final Set<Media> media) {
        if (STATES_CONSIDERED_CONNECTED.contains(state)
                && Media.audioOnly(media)
                && !isPictureInPicture()) {
            startEqualizer();
        } else {
            stopEqualizer();
        }
        if (STATES_CONSIDERED_CONNECTED.contains(state) && !isPictureInPicture()) {
            Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty");
            if (media.contains(Media.VIDEO)) {
                updateInCallButtonConfigurationVideo(requireRtpConnection().isCameraSwitchable());
            } else {
                final CallIntegration callIntegration = requireRtpConnection().getCallIntegration();
                updateInCallButtonConfigurationSpeaker(
                        callIntegration.getSelectedAudioDevice(),
                        callIntegration.getAudioDevices());
            }
            if (media.contains(Media.AUDIO)) {
                updateInCallButtonConfigurationMicrophone(
                        requireRtpConnection().isMicrophoneEnabled());
            } else {
                this.binding.inCallActionLeft.setVisibility(View.GONE);
                this.binding.inCallActionLeftLabel.setVisibility(View.GONE);
            }
        } else if (STATES_SHOWING_SPEAKER_CONFIGURATION.contains(state)
                && !isPictureInPicture()
                && Media.audioOnly(media)) {
            final CallIntegration callIntegration;
            try {
                callIntegration = requireCallIntegration();
            } catch (final IllegalStateException e) {
                Log.e(Config.LOGTAG, "can not update InCallButtonConfiguration in state " + state);
                return;
            }
            updateInCallButtonConfigurationSpeaker(
                    callIntegration.getSelectedAudioDevice(),
                    callIntegration.getAudioDevices());
        } else {
            this.binding.inCallActionLeft.setVisibility(View.GONE);
            this.binding.inCallActionLeftLabel.setVisibility(View.GONE);
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionRightLabel.setVisibility(View.GONE);
        }
    }

    // Picker order: what the user most likely wants first.
    private static final List<CallIntegration.AudioDevice> AUDIO_OUTPUT_ORDER =
            ImmutableList.of(
                    CallIntegration.AudioDevice.BLUETOOTH,
                    CallIntegration.AudioDevice.WIRED_HEADSET,
                    CallIntegration.AudioDevice.EARPIECE,
                    CallIntegration.AudioDevice.SPEAKER_PHONE);

    /**
     * Two outputs (earpiece/speaker, or wired headset/speaker): a tap switches to the other one.
     * Three or more, i.e. a Bluetooth headset is connected: a tap opens a picker.
     */
    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationSpeaker(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final Set<CallIntegration.AudioDevice> availableAudioDevices) {
        final boolean picker = availableAudioDevices.size() > 2;
        final int icon;
        final int description;
        switch (selectedAudioDevice) {
            case BLUETOOTH -> {
                icon = R.drawable.ic_bluetooth_audio_24dp;
                description = R.string.call_is_using_bluetooth;
            }
            case WIRED_HEADSET -> {
                icon = R.drawable.ic_headset_mic_24dp;
                description = R.string.call_is_using_wired_headset;
            }
            case SPEAKER_PHONE -> {
                icon = R.drawable.ic_volume_up_24dp;
                description = R.string.call_is_using_speaker;
            }
            default -> {
                // with only earpiece/speaker the button reads as a speaker toggle that is off
                icon = picker ? R.drawable.ic_smartphone_24dp : R.drawable.ic_volume_off_24dp;
                description = R.string.call_is_using_earpiece;
            }
        }
        final var button = this.binding.inCallActionRight;
        button.setImageResource(icon);
        final String state = getString(description);
        if (picker) {
            button.setContentDescription(state + " " + getString(R.string.tap_to_choose_audio_output));
            button.setOnClickListener(this::showAudioOutputPicker);
        } else if (availableAudioDevices.size() == 2) {
            final var other =
                    Iterables.find(availableAudioDevices, d -> d != selectedAudioDevice, null);
            if (other == null) {
                button.setContentDescription(state);
                button.setOnClickListener(null);
                button.setClickable(false);
            } else {
                button.setContentDescription(
                        state + " " + getString(R.string.tap_to_switch_to, audioOutputName(other)));
                button.setOnClickListener(this::switchToOtherAudioOutput);
            }
        } else {
            button.setContentDescription(state);
            button.setOnClickListener(null);
            button.setClickable(false);
        }
        if (availableAudioDevices.size() >= 2) {
            button.setOnLongClickListener(this::showAudioOutputPickerOnLongClick);
        } else {
            button.setOnLongClickListener(null);
            button.setLongClickable(false);
        }
        // FloatingActionButton turns the content description into a press-and-hold tooltip;
        // here press-and-hold means the picker, and TalkBack still reads the description
        TooltipCompat.setTooltipText(button, null);
        button.setVisibility(View.VISIBLE);
        this.binding.inCallActionRightLabel.setText(
                switch (selectedAudioDevice) {
                    case BLUETOOTH -> R.string.bluetooth;
                    case WIRED_HEADSET -> R.string.headset;
                    case SPEAKER_PHONE -> R.string.speaker;
                    default -> picker ? R.string.audio_route_phone : R.string.speaker;
                });
        this.binding.inCallActionRightLabel.setVisibility(View.VISIBLE);
    }

    /** The two-output toggle; reads the current state on tap rather than at render time. */
    private void switchToOtherAudioOutput(final View view) {
        final CallIntegration callIntegration;
        try {
            callIntegration = requireCallIntegration();
        } catch (final IllegalStateException e) {
            Toast.makeText(view.getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        final var selected = callIntegration.getSelectedAudioDevice();
        final var other =
                Iterables.find(callIntegration.getAudioDevices(), d -> d != selected, null);
        if (other != null) {
            selectAudioOutput(other);
        }
    }

    private boolean showAudioOutputPickerOnLongClick(final View view) {
        showAudioOutputPicker(view);
        return true;
    }

    private void showAudioOutputPicker(final View view) {
        final CallIntegration callIntegration;
        try {
            callIntegration = requireCallIntegration();
        } catch (final IllegalStateException e) {
            Toast.makeText(view.getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        final Set<CallIntegration.AudioDevice> available = callIntegration.getAudioDevices();
        final CallIntegration.AudioDevice selected = callIntegration.getSelectedAudioDevice();
        // Bluetooth gets one row per device: a watch and earbuds are both headsets.
        final List<CallIntegration.BluetoothOutput> bluetoothOutputs =
                available.contains(CallIntegration.AudioDevice.BLUETOOTH)
                        ? callIntegration.getBluetoothOutputs()
                        : ImmutableList.of();
        final String selectedBluetooth = callIntegration.getSelectedBluetoothOutputId();
        final List<CharSequence> names = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        int checked = -1;
        for (final var output : AUDIO_OUTPUT_ORDER) {
            if (!available.contains(output)) {
                continue;
            }
            if (output == CallIntegration.AudioDevice.BLUETOOTH && !bluetoothOutputs.isEmpty()) {
                for (final var bluetooth : bluetoothOutputs) {
                    if (selected == output && bluetooth.id.equals(selectedBluetooth)) {
                        checked = names.size();
                    }
                    names.add(
                            Strings.isNullOrEmpty(bluetooth.name)
                                    ? audioOutputName(output)
                                    : bluetooth.name);
                    actions.add(() -> selectBluetoothOutput(bluetooth));
                }
            } else {
                if (selected == output) {
                    checked = names.size();
                }
                names.add(audioOutputName(output));
                actions.add(() -> selectAudioOutput(output));
            }
        }
        new MaterialAlertDialogBuilder(view.getContext())
                .setTitle(R.string.audio_output)
                .setSingleChoiceItems(
                        names.toArray(new CharSequence[0]),
                        checked,
                        (dialog, which) -> {
                            actions.get(which).run();
                            dialog.dismiss();
                        })
                .show();
    }

    private void selectBluetoothOutput(final CallIntegration.BluetoothOutput output) {
        final boolean routed;
        try {
            routed = requireCallIntegration().selectBluetoothOutputByUser(output.id);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (routed) {
            putProximityWakeLockInProperState(CallIntegration.AudioDevice.BLUETOOTH);
            return;
        }
        // Android only lets this call use the active headset; switching is a system setting.
        final String name =
                Strings.isNullOrEmpty(output.name)
                        ? getString(R.string.audio_output_bluetooth)
                        : output.name;
        Toast.makeText(
                        this,
                        getString(R.string.make_bluetooth_device_active_for_calls, name),
                        Toast.LENGTH_LONG)
                .show();
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (final ActivityNotFoundException e) {
            Log.w(Config.LOGTAG, "no Bluetooth settings screen", e);
        }
    }

    private String audioOutputName(final CallIntegration.AudioDevice device) {
        return getString(
                switch (device) {
                    case BLUETOOTH -> R.string.audio_output_bluetooth;
                    case WIRED_HEADSET -> R.string.audio_output_wired_headset;
                    case SPEAKER_PHONE -> R.string.audio_output_speaker;
                    default -> R.string.audio_output_phone;
                });
    }

    private void selectAudioOutput(final CallIntegration.AudioDevice device) {
        try {
            requireCallIntegration().selectAudioDeviceByUser(device);
            putProximityWakeLockInProperState(device);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationVideo(final boolean isCameraSwitchable) {
        // Dropping video entirely is a "Switch to audio" menu action, not a persistent button.
        if (isCameraSwitchable) {
            this.binding.inCallActionRight.setImageResource(
                    R.drawable.ic_flip_camera_android_24dp);
            this.binding.inCallActionRight.setVisibility(View.VISIBLE);
            this.binding.inCallActionRight.setOnClickListener(this::switchCamera);
            this.binding.inCallActionRight.setContentDescription(getString(R.string.flip_camera));
            this.binding.inCallActionRightLabel.setText(R.string.flip);
            this.binding.inCallActionRightLabel.setVisibility(View.VISIBLE);
        } else {
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionRightLabel.setVisibility(View.GONE);
        }
    }

    private void switchCamera(final View view) {
        Futures.addCallback(
                requireRtpConnection().switchCamera(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(@Nullable Boolean isFrontCamera) {
                        binding.localVideoSurface.setMirror(Boolean.TRUE.equals(isFrontCamera));
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_switch_camera,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                },
                MainThreadExecutor.getInstance());
    }

    private void disableVideo() {
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        final ContentAddition pending = rtpConnection.getPendingContentAddition();
        if (pending != null && pending.direction == ContentAddition.Direction.OUTGOING) {
            rtpConnection.retractContentAdd();
            return;
        }
        try {
            requireRtpConnection().setVideoEnabled(false);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_disable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(requireRtpConnection().isCameraSwitchable());
        invalidateOptionsMenu();
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationMicrophone(final boolean microphoneEnabled) {
        if (microphoneEnabled) {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::disableMicrophone);
            this.binding.inCallActionLeftLabel.setText(R.string.mute);
        } else {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_off_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::enableMicrophone);
            this.binding.inCallActionLeftLabel.setText(R.string.call_unmute);
        }
        this.binding.inCallActionLeft.setVisibility(View.VISIBLE);
        this.binding.inCallActionLeftLabel.setVisibility(View.VISIBLE);
        this.binding.equalizer.setAlpha(microphoneEnabled ? 1f : 0.25f);
    }

    private void updateCallDuration() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null || connection.getMedia().contains(Media.VIDEO)) {
            this.binding.duration.setVisibility(View.GONE);
            return;
        }
        if (connection.zeroDuration()) {
            this.binding.duration.setVisibility(View.GONE);
        } else {
            this.binding.duration.setText(
                    TimeFrameUtils.formatElapsedTime(connection.getCallDuration(), false));
            this.binding.duration.setVisibility(View.VISIBLE);
        }
    }

    private void updateVideoViews(final RtpEndUserState state) {
        if (END_CARD.contains(state) || state == RtpEndUserState.ENDING_CALL) {
            binding.localVideo.setVisibility(View.GONE);
            binding.localVideoSurface.release();
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.remoteVideo.release();
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            if (isPictureInPicture()) {
                binding.appBarLayout.setVisibility(View.GONE);
                binding.pipPlaceholder.setVisibility(View.VISIBLE);
                if (Arrays.asList(
                                RtpEndUserState.APPLICATION_ERROR,
                                RtpEndUserState.CONNECTIVITY_ERROR,
                                RtpEndUserState.SECURITY_ERROR)
                        .contains(state)) {
                    binding.pipWarning.setVisibility(View.VISIBLE);
                    binding.pipWaiting.setVisibility(View.GONE);
                } else {
                    binding.pipWarning.setVisibility(View.GONE);
                    binding.pipWaiting.setVisibility(View.GONE);
                }
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                binding.pipPlaceholder.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }
        if (isPictureInPicture() && STATES_SHOWING_PIP_PLACEHOLDER.contains(state)) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.appBarLayout.setVisibility(View.GONE);
            binding.pipPlaceholder.setVisibility(View.VISIBLE);
            binding.pipWarning.setVisibility(View.GONE);
            binding.pipWaiting.setVisibility(View.VISIBLE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            return;
        }
        final Optional<VideoTrack> localVideoTrack = getLocalVideoTrack();
        if (localVideoTrack.isPresent() && !isPictureInPicture()) {
            ensureSurfaceViewRendererIsSetup(binding.localVideoSurface);
            binding.localVideo.setVisibility(View.VISIBLE);
            // paint local view over remote view
            binding.localVideoSurface.setZOrderMediaOverlay(true);
            binding.localVideoSurface.setMirror(requireRtpConnection().isFrontCamera());
            addSink(localVideoTrack.get(), binding.localVideoSurface);
        } else {
            binding.localVideo.setVisibility(View.GONE);
        }
        final Optional<VideoTrack> remoteVideoTrack = getRemoteVideoTrack();
        if (remoteVideoTrack.isPresent()) {
            ensureSurfaceViewRendererIsSetup(binding.remoteVideo);
            addSink(remoteVideoTrack.get(), binding.remoteVideo);
            binding.remoteVideo.setScalingType(
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                    RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            if (state == RtpEndUserState.CONNECTED) {
                binding.appBarLayout.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.VISIBLE);
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.GONE);
            }
            if (isPictureInPicture() && !requireRtpConnection().isMicrophoneEnabled()) {
                binding.pipLocalMicOffIndicator.setVisibility(View.VISIBLE);
            } else {
                binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
        }
    }

    private Optional<VideoTrack> getLocalVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getLocalVideoTrack();
    }

    private Optional<VideoTrack> getRemoteVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getRemoteVideoTrack();
    }

    private void disableMicrophone(View view) {
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        if (rtpConnection.setMicrophoneEnabled(false)) {
            updateInCallButtonConfiguration();
        }
    }

    private void enableMicrophone(View view) {
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        if (rtpConnection.setMicrophoneEnabled(true)) {
            updateInCallButtonConfiguration();
        }
    }

    private void retry(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final String action = intent.getAction();
        final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
        this.rtpConnectionReference = null;
        CallIntegrationConnectionService.placeCall(xmppConnectionService, account, with, media);
    }

    private void exit(final View view) {
        finish();
    }

    private void recordVoiceMail(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(account, with, false, true);
        final Intent launchIntent = new Intent(this, ConversationsActivity.class);
        launchIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        launchIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        launchIntent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(
                ConversationsActivity.EXTRA_POST_INIT_ACTION,
                ConversationsActivity.POST_ACTION_RECORD_VOICE);
        startActivity(launchIntent);
        finish();
    }

    private Contact getWith() {
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final Account account = id.account;
        return account.getRoster().getContact(id.with);
    }

    private JingleRtpConnection requireRtpConnection() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            throw new IllegalStateException("No RTP connection found");
        }
        return connection;
    }

    private CallIntegration requireCallIntegration() {
        return requireOngoingRtpSession().getCallIntegration();
    }

    private OngoingRtpSession requireOngoingRtpSession() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection != null) {
            return connection;
        }
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        final var account = extractAccount(currentIntent);
        if (withExtra == null) {
            throw new IllegalStateException("Current intent has no EXTRA_WITH");
        }
        final var matching =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .matchingProposal(account, Jid.of(withExtra));
        if (matching.isPresent()) {
            return matching.get();
        }
        throw new IllegalStateException("No matching session proposal");
    }

    @Override
    public void onJingleRtpConnectionUpdate(
            Account account, Jid with, final String sessionId, RtpEndUserState state) {
        if (END_CARD.contains(state)) {
            releaseProximityWakeLock();
            runOnUiThread(
                    () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
        if (with.isBareJid()) {
            updateRtpSessionProposalState(account, with, state);
            return;
        }
        if (emptyReference(this.rtpConnectionReference)) {
            if (END_CARD.contains(state)) {
                return;
            }
            // this happens when going from proposed session to actual session
            reInitializeActivityWithRunningRtpSession(account, with, sessionId);
            return;
        }
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final boolean verified = requireRtpConnection().isVerified();
        final Set<Media> media = getMedia();
        lockOrientation();
        final ContentAddition contentAddition = getPendingContentAddition();
        final Contact contact = getWith();
        if (account == id.account && id.with.equals(with) && id.sessionId.equals(sessionId)) {
            if (state == RtpEndUserState.ENDED) {
                finish();
                return;
            }
            runOnUiThread(
                    () -> {
                        updateStateDisplay(state, media, contentAddition);
                        updateVerifiedShield(
                                verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(state));
                        updateButtonConfiguration(state, media, contentAddition);
                        updateVideoViews(state);
                        updateHeroAvatar(state, contact);
                        invalidateOptionsMenu();
                    });
            if (END_CARD.contains(state)) {
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                resetIntent(account, with, state, rtpConnection.getMedia());
                releaseVideoTracks(rtpConnection);
                this.rtpConnectionReference = null;
            }
        }
    }

    @Override
    public void onAudioDeviceChanged(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final Set<CallIntegration.AudioDevice> availableAudioDevices) {
        try {
            final OngoingRtpSession ongoingRtpSession = requireOngoingRtpSession();
            final RtpEndUserState endUserState;
            if (ongoingRtpSession instanceof JingleRtpConnection jingleRtpConnection) {
                endUserState = jingleRtpConnection.getEndUserState();
            } else {
                // for session proposals all end user states are functionally the same
                endUserState = RtpEndUserState.RINGING;
            }
            final Set<Media> media = ongoingRtpSession.getMedia();
            if (END_CARD.contains(endUserState)) {
            } else {
                if (Media.audioOnly(media)
                        && STATES_SHOWING_SPEAKER_CONFIGURATION.contains(endUserState)) {
                    final CallIntegration callIntegration = requireCallIntegration();
                    updateInCallButtonConfigurationSpeaker(
                            callIntegration.getSelectedAudioDevice(),
                            callIntegration.getAudioDevices());
                }
                putProximityWakeLockInProperState(selectedAudioDevice);
            }
        } catch (final IllegalStateException e) {
        }
    }

    private void updateRtpSessionProposalState(
            final Account account, final Jid with, final RtpEndUserState state) {
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        if (withExtra == null) {
            return;
        }
        final Set<Media> media = actionToMedia(currentIntent.getStringExtra(EXTRA_LAST_ACTION));
        if (Jid.ofEscaped(withExtra).asBareJid().equals(with)) {
            runOnUiThread(
                    () -> {
                        updateVerifiedShield(false);
                        updateStateDisplay(state);
                        updateButtonConfiguration(state, media, null);
                        updateHeroAvatar(state, account.getRoster().getContact(with));
                        invalidateOptionsMenu();
                    });
            resetIntent(account, with, state, media);
        }
    }

    private void resetIntent(final Bundle extras) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtras(extras);
        setIntent(intent);
    }

    private void resetIntent(
            final Account account, Jid with, final RtpEndUserState state, final Set<Media> media) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toEscapedString());
        if (RtpCapability.jmiSupport(account.getRoster().getContact(with))) {
            intent.putExtra(EXTRA_WITH, with.asBareJid().toEscapedString());
        } else {
            intent.putExtra(EXTRA_WITH, with.toEscapedString());
        }
        intent.putExtra(EXTRA_LAST_REPORTED_STATE, state.toString());
        intent.putExtra(
                EXTRA_LAST_ACTION,
                media.contains(Media.VIDEO) ? ACTION_MAKE_VIDEO_CALL : ACTION_MAKE_VOICE_CALL);
        setIntent(intent);
    }

    private static boolean emptyReference(final WeakReference<?> weakReference) {
        return weakReference == null || weakReference.get() == null;
    }

    private enum Event {
        ON_BACKEND_CONNECTED,
        ON_NEW_INTENT
    }
}
