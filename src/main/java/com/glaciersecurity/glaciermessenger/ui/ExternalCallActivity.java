package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import android.Manifest;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityTwilioCallBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.services.ExternalCallManager;
import com.glaciersecurity.glaciermessenger.services.IncomingCallNotificationService;

import com.glaciersecurity.glaciermessenger.ui.util.SoundPoolManager;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.NativeContactsUtils;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.TwilioAccessTokenParser;
import com.google.android.material.snackbar.Snackbar;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.Voice;
import com.glaciersecurity.glaciermessenger.services.CallForegroundService;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ExternalCallActivity extends XmppActivity implements ExternalCallListener, SensorEventListener {
    private ActivityTwilioCallBinding binding;

    private ExternalCallManager mExternalCallManager;
    private static final int MIC_PERMISSION_REQUEST_CODE = 1;
    public static final int MY_PERMISSIONS_REQUEST_READ_PHONE_STATE = 0;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 0x0309;
    private AudioManager audioManager;
    private boolean isBluetoothDeviceAvailable = false;
    private int savedAudioMode = AudioManager.MODE_NORMAL;
    private AudioFocusRequest activeAudioFocusRequest;

    private enum AudioRoute { EARPIECE, SPEAKER, BLUETOOTH }
    private AudioRoute selectedAudioRoute = AudioRoute.EARPIECE;
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                    || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                    || AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(action)
                    || BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                runOnUiThread(ExternalCallActivity.this::updateBluetoothAvailability);
            }
        }
    };
    private boolean isBluetoothReceiverRegistered = false;
    private Ringtone incomingRingtone;
    private boolean incomingRingtonePlaying = false;

    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;
    private NotificationManager notificationManager;
    private static PowerManager.WakeLock wakeLock;
    private SensorManager sensorManager;
    private Sensor proximity;
    private boolean isMuted = false;
    private boolean isOnHold = false;

    private boolean endingCall = false;
    private boolean returning = false;
    private boolean isOutgoingCallFlow = false;

    private static final long CALL_FAILED_AUTO_DISMISS_MS = 3000L;
    private final Handler callFailedHandler = new Handler(Looper.getMainLooper());
    private Runnable callFailedAutoDismissRunnable;

    private Account account;
    private SmsProfile selectedProfile;
    private String remoteContact;
    private String localProxy;


    private final HashMap<String, String> params = new HashMap<>();
    private Call activeCall = null;
    private CallInvite activeCallInvite;
    private int activeCallNotificationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_twilio_call);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.setTurnScreenOn(true);
        this.setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(false);
        boolean bluetoothConnected = audioManager.isBluetoothScoOn() && queryBluetoothAvailability();
        if (bluetoothConnected) {
            selectedAudioRoute = AudioRoute.BLUETOOTH;
        } else if (audioManager.isSpeakerphoneOn()) {
            selectedAudioRoute = AudioRoute.SPEAKER;
        } else {
            selectedAudioRoute = AudioRoute.EARPIECE;
        }
        registerBluetoothReceiver();

        setVoiceEdge();

        if (!checkPermissionForMicrophone()) {
            requestPermissionForMicrophone();
        }
        if (!checkPermissionForBluetooth()) {
            requestPermissionForBluetooth();
        }


        binding.endCallButton.setOnClickListener(v -> {
            SoundPoolManager.getInstance(this).playDisconnect();
            disconnect();
        });
        binding.rejectCallButton.setOnClickListener(v -> {
            reject();
        });
        binding.acceptCallButton.setOnClickListener(v -> {
            answer();
        });
        binding.muteButton.setOnClickListener(v -> {
            if (activeCall != null) {
                isMuted = !isMuted;
                activeCall.mute(isMuted);
                binding.muteButton.setSelected(isMuted);
                binding.muteText.setText(isMuted ? R.string.call_unmute : R.string.call_mute);
            }
        });


        binding.keyPadButton.setOnClickListener(v -> {
            if (activeCall != null) {
                Intent intent = new Intent(ExternalCallActivity.this, PhoneModuleActivity.class);
                startActivity(intent);
            }
        });

        binding.holdImageButton.setOnClickListener(v -> {
            if (activeCall != null) {
                isOnHold = !isOnHold;
                activeCall.hold(isOnHold);
                binding.holdImageButton.setSelected(isOnHold);
                binding.holdText.setText(isOnHold ? R.string.call_unhold : R.string.call_hold);
            }
        });


        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        binding.speakerButton.setOnClickListener(v -> showAudioRouteDialog());
        updateSpeakerButtonUI();
        updateBluetoothAvailability();


        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        voiceBroadcastReceiver = new VoiceBroadcastReceiver();
        if (getIntent().hasExtra(Config.TO_PROXY)) {
            localProxy = getIntent().getStringExtra(Config.TO_PROXY);
        }
        if (getIntent().getStringExtra("returning") != null) {
            returning = true;
            showOnCall(true);
        }


        if(getIntent().getAction() != null) {
            if (getIntent().getAction().equals(Config.ACTION_OUTGOING_CALL)) {
                isOutgoingCallFlow = true;
                showOnCall(true);
            }
            if (getIntent().getAction().equals(Config.ACTION_INCOMING_CALL) ||
                    getIntent().getAction().equals(Config.ACTION_INCOMING_CALL_NOTIFICATION)) {
                showOnCall(false);
            }
        }
        if (!returning) {
            registerReceiver();
            handleIncomingCallIntent(getIntent());
        } else {
            resetActiveCallUI();
        }
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        initializeProximityWakeLock(this);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // ExternalCallActivity is launchMode="singleTask": when an instance is already running
        // (e.g. the app is already open), a new incoming-call PendingIntent is delivered here
        // instead of onCreate(), so it must be handled the same way or the new call's extras
        // (and the ringtone/UI state that depends on them) are silently dropped.
        setIntent(intent);
        handleIncomingCallIntent(intent);
    }

    private void speakerON() {
        audioManager.setSpeakerphoneOn(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            setCommunicationDevice(this, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        }
        if (mExternalCallManager != null){
            mExternalCallManager.setSpeakerPhone(true);
        }

    }
    private void speakerOFF() {
        audioManager.setSpeakerphoneOn(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            setCommunicationDevice(this, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        }
        if (mExternalCallManager != null){
            mExternalCallManager.setSpeakerPhone(false);
        }
    }

    private void setBluetoothAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setCommunicationDevice(this, AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        }
        startBluetoothScoIfPossible();
    }

    private void showAudioRouteDialog() {
        updateBluetoothAvailability();
        List<String> options = new ArrayList<>();
        List<AudioRoute> routes = new ArrayList<>();
        if (isBluetoothDeviceAvailable) {
            options.add(getString(R.string.bluetooth));
            routes.add(AudioRoute.BLUETOOTH);
        }
        options.add(getString(R.string.speaker));
        routes.add(AudioRoute.SPEAKER);
        options.add(getString(R.string.earpiece));
        routes.add(AudioRoute.EARPIECE);

        CharSequence[] items = options.toArray(new CharSequence[0]);
        int checked = routes.indexOf(selectedAudioRoute);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_device)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    setAudioRoute(routes.get(which));
                    dialog.dismiss();
                })
                .show();
    }

    private void setAudioRoute(AudioRoute route) {
        if (route == AudioRoute.BLUETOOTH && !isBluetoothDeviceAvailable) {
            route = AudioRoute.EARPIECE;
        }
        switch (route) {
            case BLUETOOTH -> {
                if (isBluetoothDeviceAvailable) {
                    setBluetoothAudio();
                }
            }
            case SPEAKER -> {
                stopBluetoothScoIfNeeded();
                speakerON();
            }
            case EARPIECE -> {
                stopBluetoothScoIfNeeded();
                speakerOFF();
            }
        }
        selectedAudioRoute = route;
        updateSpeakerButtonUI();
    }

    private AudioRoute resolveCurrentAudioRoute() {
        if (audioManager == null) {
            return AudioRoute.EARPIECE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo device = audioManager.getCommunicationDevice();
            if (device != null) {
                if (isBluetoothDevice(device)) {
                    return AudioRoute.BLUETOOTH;
                }
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return AudioRoute.SPEAKER;
                }
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    return AudioRoute.EARPIECE;
                }
            }
        }
        if (audioManager.isBluetoothScoOn() && isBluetoothDeviceAvailable) {
            return AudioRoute.BLUETOOTH;
        }
        if (audioManager.isSpeakerphoneOn()) {
            return AudioRoute.SPEAKER;
        }
        return AudioRoute.EARPIECE;
    }

    private void updateSpeakerButtonUI() {
        selectedAudioRoute = resolveCurrentAudioRoute();
        switch (selectedAudioRoute) {
            case BLUETOOTH -> {
                binding.speakerButton.setImageResource(R.drawable.ic_bluetooth_audio_24dp);
                binding.speakerText.setText(R.string.bluetooth);
            }
            case SPEAKER -> {
                binding.speakerButton.setImageResource(R.drawable.ic_volume_up_white_24dp);
                binding.speakerText.setText(R.string.speaker);
            }
            case EARPIECE -> {
                binding.speakerButton.setImageResource(R.drawable.ic_volume_off_24dp);
                binding.speakerText.setText(R.string.earpiece);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setCommunicationDevice(Context context, Integer targetDeviceType) {
        AudioManager audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        List< AudioDeviceInfo > devices = audioManager.getAvailableCommunicationDevices();
        for (AudioDeviceInfo device: devices) {
            if (device.getType() == targetDeviceType) {
                audioManager.setCommunicationDevice(device);
            }
        }
    }


    private void setVoiceEdge(){
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String region = preferences.getString(AppSettings.PHONE, "roaming");
        String callRegion;

        switch(region){
            case "dublin":
                callRegion = "ie1";
                break;
            case "sydney":
                callRegion = "au1";
                break;
            case "ashburn":
                callRegion = "us1";
                break;
            default:
                callRegion = "gll";
                break;
        }
        Voice.setRegion(callRegion);

    }
    /**
     * Starts (or re-affirms) the while-in-use phoneCall foreground service. Called before
     * {@code Voice.connect()}/{@code CallInvite.accept()} rather than only in
     * {@code onConnected()} so mic capture is never active without foreground-service cover on
     * Android 17's stricter background audio/mic rules; it's cheap to call again once the call
     * actually connects.
     */
    private void startCallForegroundService() {
        CallForegroundService.start(this);
    }

    private void executeVoiceCall(String accessToken) {
        if (getIntent().getAction().equals(Config.ACTION_OUTGOING_CALL)) {
            if (!checkPermissionForMicrophone()) {
                requestPermissionForMicrophone();
                Toast.makeText(this, R.string.call_microphone_permission_required, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                showOnCall(true);
                this.remoteContact = getIntent().getExtras().getString("toNumber");
                if (selectedProfile == null || selectedProfile.getUnformattedNumber() == null){
                    return;
                }
                localProxy = getIntent().getStringExtra(Config.TO_PROXY);
                params.clear();
                if (remoteContact == null || localProxy == null) {
                    Log.e(Config.LOGTAG, "Missing call parameters: to=" + remoteContact + " from=" + localProxy);
                    Toast.makeText(this, R.string.call_unable_to_start, Toast.LENGTH_LONG).show();
                    return;
                }
                params.put("to", remoteContact);
                params.put("from", localProxy);

                ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                        .params(params)
                        .build();
                startCallForegroundService();
                activeCall = Voice.connect(ExternalCallActivity.this, connectOptions, this);
                if (mExternalCallManager != null) {
                    mExternalCallManager.setActiveCall(activeCall, remoteContact, localProxy);
                }

                setToolbarForProxy();

            } catch (final IllegalArgumentException e) {
                Log.e(Config.LOGTAG, "ExecuteVoiceCall exception",e);
                // startCallForegroundService() above may have already started the foreground
                // service before Voice.connect() threw; tear it down so a failed/malformed
                // connect attempt doesn't leave an "ongoing call" notification running forever.
                endingCall = true;
                clearCallState();
                showCallFailedState();
            }
        }
    }
    private void registerReceiver() {
        if (!isReceiverRegistered) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Config.ACTION_INCOMING_CALL);
            intentFilter.addAction(Config.ACTION_CANCEL_CALL);
            intentFilter.addAction(Config.ACTION_FCM_TOKEN);
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    voiceBroadcastReceiver, intentFilter);
            isReceiverRegistered = true;
        }
    }

    private void registerBluetoothReceiver() {
        if (isBluetoothReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        try {
            // ACL/adapter broadcasts come from the Bluetooth app (uid 1002), not the system, so a
            // NOT_EXPORTED receiver never sees them. They are protected, so exporting is safe.
            ContextCompat.registerReceiver(this, bluetoothReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
            isBluetoothReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterBluetoothReceiver() {
        if (!isBluetoothReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (Exception ignored) {
        }
        isBluetoothReceiverRegistered = false;
    }

    private void handleIncomingCallIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            activeCallInvite = intent.getParcelableExtra(Config.INCOMING_CALL_INVITE);
            localProxy = intent.getStringExtra(Config.TO_PROXY);

            activeCallNotificationId = intent.getIntExtra(Config.INCOMING_CALL_NOTIFICATION_ID, 0);
            if (activeCallInvite  != null) {
                String contactNumber = activeCallInvite.getFrom();
                remoteContact = contactNumber;
                setToolbarForProxy();

                String contactName = NativeContactsUtils.getContactNameIfExists(this, contactNumber);
                binding.participantSelectedIdentity.setText(contactName);
                binding.particiantNumber.setText(PhoneUtils.reformatNumber(contactNumber));
                if(PhoneUtils.areSameNumber(contactName, contactNumber)){
                    binding.particiantNumber.setVisibility(View.GONE);
                } else {
                    binding.particiantNumber.setVisibility(View.VISIBLE);
                }
            }
            switch (action) {
                case Config.ACTION_INCOMING_CALL:
                case Config.ACTION_INCOMING_CALL_NOTIFICATION:
                    showOnCall(false);
                    startIncomingRingtoneIfNeeded();
                    break;
                case Config.ACTION_CANCEL_CALL:
                    handleCancel();
                    break;
                case Config.ACTION_FCM_TOKEN:
                    registerForCallInvites();
                    break;
                case Config.ACTION_ACCEPT_CALL:
                    answer();
                    break;
                default:
                    break;
            }
        }
    }

    private void setToolbarForProxy() {
        if (mExternalCallManager != null && localProxy == null){
            localProxy= mExternalCallManager.getProxyNumber();
        }
        if (localProxy == null){
            binding.toolbar.setTitle("");
            binding.toolbar.setBackgroundColor(Color.TRANSPARENT);
            return;
        }
        if (xmppConnectionServiceBound) {
            SmsProfile to_sp = xmppConnectionService.getSmsInfo().getSMSProfilefromNumber(localProxy);
            if (to_sp != null) {
                String nickname = to_sp.getNickname();
                if (nickname != null && !nickname.isEmpty()){
                    binding.toolbar.setTitle(nickname);
                } else {
                    binding.toolbar.setTitle(to_sp.getFormattedNumber());
                }
                binding.toolbar.setBackgroundColor(to_sp.getColor());
                return;
            }
        }
            binding.toolbar.setTitle(PhoneUtils.reformatNumber(localProxy));
            binding.toolbar.setBackgroundColor(Color.TRANSPARENT);
    }

    /*
     * Register your FCM token with Twilio to receive incoming call invites
     */
    private void registerForCallInvites() {
    }


    private void showOnCall(boolean sCallScreen){
        if(sCallScreen){
            binding.callStatusIncoming.setVisibility(View.GONE);
            binding.callStatusOutgoing.setVisibility(View.VISIBLE);
        } else {
            binding.callStatusIncoming.setVisibility(View.VISIBLE);
            binding.callStatusOutgoing.setVisibility(View.GONE);

        }
    }

    private void reject() {
        stopIncomingRingtone();
        if (activeCallInvite != null) {
            Intent intent = new Intent(this, IncomingCallNotificationService.class);
            intent.setAction(Config.ACTION_REJECT_CALL);
            intent.putExtra(Config.INCOMING_CALL_INVITE, activeCallInvite);
            startService(intent);
        }
        notificationManager.cancel(activeCallNotificationId);
        NotificationManagerCompat.from(this).cancelAll();
        stopService(new Intent(getApplicationContext(), IncomingCallNotificationService.class));
        endingCall = true;
        disconnect();
    }
    private void answer() {
        stopIncomingRingtone();
        showOnCall(true);

        startCallForegroundService();
        activeCallInvite.accept(this, this);
        notificationManager.cancel(activeCallNotificationId);
        NotificationManagerCompat.from(this).cancelAll();
        stopService(new Intent(getApplicationContext(), IncomingCallNotificationService.class));
        resetActiveCallUI();

        if (mExternalCallManager != null) {
            mExternalCallManager.setActiveCallwithAccount(activeCall, remoteContact, account);
        }
    }

    private void handleCancel() {
        stopIncomingRingtone();
        endingCall = true;
        disconnect();
    }

    /**
     * Plays the user's configured incoming-call ringtone for as long as the full-screen
     * incoming-call UI is shown. The background notification (IncomingCallNotificationService)
     * only alerts once rather than looping (see FLAG_INSISTENT removal there), so this is the
     * sole source of a sustained ring while the call is actually ringing.
     */
    private void startIncomingRingtoneIfNeeded() {
        if (incomingRingtonePlaying) {
            return;
        }
        final var appSettings = new AppSettings(this);
        Uri ringtoneUri = appSettings.getRingtone();
        if (ringtoneUri == null) {
            ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        if (ringtoneUri == null) {
            return;
        }
        incomingRingtone = RingtoneManager.getRingtone(this, ringtoneUri);
        if (incomingRingtone == null) {
            return;
        }
        final AudioAttributes attrs = new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build();
        incomingRingtone.setAudioAttributes(attrs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            incomingRingtone.setLooping(true);
        }
        incomingRingtone.play();
        incomingRingtonePlaying = true;
    }

    private void stopIncomingRingtone() {
        if (!incomingRingtonePlaying) {
            return;
        }
        if (incomingRingtone != null) {
            incomingRingtone.stop();
        }
        incomingRingtonePlaying = false;
    }

    private void initializeProximityWakeLock(Context context) {
        final PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager == null ? null : powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "glacier:twiliocalllock");
        if (wakeLock != null) {
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do something here if sensor accuracy changes.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        // Null whenever POWER_SERVICE was unavailable in initializeProximityWakeLock; guarding
        // only the constructor there would just move the crash to the first proximity event.
        if (wakeLock == null) {
            return;
        }
        float distance = event.values[0];
        if (distance == 0f){
            if(!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } else {
            if(wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }
    private class VoiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && (action.equals(Config.ACTION_INCOMING_CALL))) {
                /*
                 * Handle the incoming or cancelled call invite
                 */
                handleIncomingCallIntent(intent);
            } else if (action != null && (action.equals(Config.ACTION_CANCEL_CALL))) {
                handleCancel();
            }
        }
    }

    private void unregisterReceiver() {
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceBroadcastReceiver);
            isReceiverRegistered = false;
        }
    }

    @Override
    public void onRinging(@NonNull Call call) {
        binding.callState.setText(getString(R.string.outgoing_call));
    }

    @Override
    public void onConnectFailure(@NonNull Call call, @NonNull CallException error) {
        stopIncomingRingtone();
        setAudioFocus(false);

        String message = String.format(
                Locale.US,
                "Call Error: %d, %s",
                error.getErrorCode(),
                error.getMessage());
        Log.e(Config.LOGTAG, message);
        endingCall = true;
        activeCall = call;
        clearCallState();
        showCallFailedState();
    }

    @Override
    public void onConnected(@NonNull Call call) {
        stopIncomingRingtone();
        binding.callState.setText(getString(R.string.call_connected));
        activeCall = call;
        if (mExternalCallManager != null) {
            mExternalCallManager.setActiveCallwithAccount(activeCall, remoteContact, account);
        }

        // Re-affirm the while-in-use phoneCall foreground service (already started before
        // Voice.connect()/CallInvite.accept()) before touching audio focus/mode/routing so the
        // call stays covered if it later moves to the background.
        startCallForegroundService();

        setAudioFocus(true);

        binding.chronometer.setVisibility(View.VISIBLE);
        binding.chronometer.setBase(SystemClock.elapsedRealtime());
        binding.chronometer.start();
    }

    @Override
    public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
        binding.callState.setText(getString(R.string.reconnecting));
        binding.chronometer.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onReconnected(@NonNull Call call) {
        setAudioFocus(true);

        binding.callState.setText(getString(R.string.call_connected));
        binding.chronometer.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDisconnected(@NonNull Call call, CallException error) {
        stopIncomingRingtone();
        endingCall = true;
        SoundPoolManager.getInstance(ExternalCallActivity.this).playDisconnect();
        setAudioFocus(false);
        activeCall = call;
        clearCallState();
        if (error != null) {
            String message = String.format(
                    Locale.US,
                    "Call Error: %d, %s",
                    error.getErrorCode(),
                    error.getMessage());
            Log.e(Config.LOGTAG, message);
            showCallFailedState();
        } else {
            binding.callState.setText(getString(R.string.call_disconnected));
            finish();
        }
    }

    @Override
    public void onCallStatusChange(boolean bool) {

    }

    @Override
    public void onResume() {
        super.onResume();
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onPause() {
        if(wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        sensorManager.unregisterListener(this);
        super.onPause();
    }





    @Override
    protected void refreshUiReal() {

    }


    private void getAccessToken() { // Display Progress Dialog
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
            Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
            Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                    runOnUiThread(() -> {
                        Log.e("Glacier", e.getLocalizedMessage(), e);
                        Toast.makeText(ExternalCallActivity.this, R.string.call_failed, Toast.LENGTH_LONG).show();
                        finish();
                    });
                }

                @Override
                public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response) {
                    try {
                        String responseBody = "{\"message\":\"failure\"}";
                        if (response != null && response.body() != null) {
                            responseBody = response.body().string();
                        }
                        String parsedToken = TwilioAccessTokenParser.parse(responseBody);
                        runOnUiThread(() -> {
                            if (parsedToken == null) {
                                Log.e(Config.LOGTAG, "Voice token response did not include a valid JWT");
                                Toast.makeText(ExternalCallActivity.this, R.string.call_failed, Toast.LENGTH_LONG).show();
                                finish();
                                return;
                            }
                            executeVoiceCall(parsedToken);
                        });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        runOnUiThread(() -> {
                            Log.e("Glacier", ex.getLocalizedMessage(), ex);
                            Toast.makeText(ExternalCallActivity.this, R.string.call_failed, Toast.LENGTH_LONG).show();
                            finish();
                        });
                    }
                }
            });
        } catch (Exception ex) {
            Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
            Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show();
            finish();
        }
    }


    @Override
    public void onStart() {
        super.onStart();
        Intent intent = getIntent();
        if (intent == null) {
            return;
        }

        final String action = intent.getAction();

        if (Config.ACTION_OUTGOING_CALL.equals(action)) {
            if(mExternalCallManager != null && mExternalCallManager.isOnCall()) {
                returning = true;
                setAudioFocus(true);
            } else {
                this.onOutgoingCall();
            }
        }
    }

    @Override
    public void onDestroy() {
        stopIncomingRingtone();
        cancelCallFailedAutoDismiss();
        super.onDestroy();
        SoundPoolManager.getInstance(this).release();
        unregisterBluetoothReceiver();
        if (activeCall != null && endingCall){
            activeCall.disconnect();
            mExternalCallManager.setActiveCall(null);
            unregisterReceiver();
        }


        finish();
    }


    @Override
    protected void onBackendConnected() {
        account = xmppConnectionService.getFirstAccount();
        selectedProfile = xmppConnectionService.getSmsInfo().getSelectedProfile();

        mExternalCallManager = xmppConnectionService.getExternalCallManager();
        activeCall = mExternalCallManager.getActiveCall();
        mExternalCallManager.setCallListener(this);

        if (!returning && activeCall == null) {
            getAccessToken();

        } else {
            resetActiveCallUI();
        }
        setToolbarForProxy();
    }

    private void resetActiveCallUI() {

        if (activeCall != null) {
           remoteContact = mExternalCallManager.getRemoteContact();
            if (remoteContact != null) {
                String contactName = NativeContactsUtils.getContactNameIfExists(this, remoteContact);
                binding.participantSelectedIdentity.setText(contactName);
                binding.particiantNumber.setText(PhoneUtils.reformatNumber(remoteContact));
                if(PhoneUtils.areSameNumber(contactName, remoteContact)){
                    binding.particiantNumber.setVisibility(View.GONE);
                } else {
                    binding.particiantNumber.setVisibility(View.VISIBLE);
                }
                setToolbarForProxy();
            }


            isMuted = activeCall.isMuted();
            binding.muteButton.setSelected(isMuted);
            binding.muteText.setText(isMuted ? R.string.call_unmute : R.string.call_mute);
            isOnHold = activeCall.isOnHold();
            binding.holdImageButton.setSelected(isOnHold);
            binding.holdText.setText(isOnHold ? R.string.call_unhold : R.string.call_hold);
            if (audioManager.isBluetoothScoOn() && isBluetoothDeviceAvailable) {
                selectedAudioRoute = AudioRoute.BLUETOOTH;
            } else if (audioManager.isSpeakerphoneOn()) {
                selectedAudioRoute = AudioRoute.SPEAKER;
            } else {
                selectedAudioRoute = AudioRoute.EARPIECE;
            }
            updateSpeakerButtonUI();

        }
    }

    private void onOutgoingCall() {
            String contactNumber = getIntent().getExtras().getString("toNumber");
            String contactName = NativeContactsUtils.getContactNameIfExists(this, contactNumber);
            binding.participantSelectedIdentity.setText(contactName);
            binding.particiantNumber.setText(PhoneUtils.reformatNumber(contactNumber));
            if(PhoneUtils.areSameNumber(contactName, contactNumber)){
                binding.particiantNumber.setVisibility(View.GONE);
            } else {
                binding.particiantNumber.setVisibility(View.VISIBLE);
            }
    }


    /*
     * Tear down the active call and manager state without closing the screen.
     * Split out from disconnect() so failure paths can clear the "on call" state
     * (and hide the return-to-call bar) immediately, while still holding the
     * screen open briefly to show the call-failed UI.
     */
    private void clearCallState() {
        if (activeCall != null) {
            activeCall.disconnect();
            activeCall = null;
        }
        if (mExternalCallManager != null) {
            mExternalCallManager.disconnectPhoneCall();
            mExternalCallManager.setActiveCall(null);
        }
        Intent stopSvc = new Intent(this, CallForegroundService.class);
        stopSvc.setAction(CallForegroundService.ACTION_STOP);
        startService(stopSvc);
        stopBluetoothScoIfNeeded();
    }

    /*
     * Disconnect from Call
     */
    private void disconnect() {
        clearCallState();
        finish();
    }

    private void showCallFailedState() {
        binding.controlsGrid.setVisibility(View.GONE);
        binding.callStatusIncoming.setVisibility(View.GONE);
        binding.callStatusOutgoing.setVisibility(View.GONE);
        binding.chronometer.setVisibility(View.GONE);
        binding.callState.setVisibility(View.VISIBLE);
        binding.callState.setText(getString(R.string.call_failed));
        binding.callStatusFailed.setVisibility(View.VISIBLE);

        binding.retryCallGroup.setVisibility(isOutgoingCallFlow ? View.VISIBLE : View.GONE);
        binding.retryCallButton.setOnClickListener(v -> retryCall());
        binding.dismissCallFailedButton.setOnClickListener(v -> dismissCallFailed());

        cancelCallFailedAutoDismiss();
        callFailedAutoDismissRunnable = this::dismissCallFailed;
        callFailedHandler.postDelayed(callFailedAutoDismissRunnable, CALL_FAILED_AUTO_DISMISS_MS);
    }

    private void dismissCallFailed() {
        cancelCallFailedAutoDismiss();
        finish();
    }

    private void retryCall() {
        cancelCallFailedAutoDismiss();
        binding.callStatusFailed.setVisibility(View.GONE);
        binding.controlsGrid.setVisibility(View.VISIBLE);
        endingCall = false;
        getAccessToken();
    }

    private void cancelCallFailedAutoDismiss() {
        if (callFailedAutoDismissRunnable != null) {
            callFailedHandler.removeCallbacks(callFailedAutoDismissRunnable);
            callFailedAutoDismissRunnable = null;
        }
    }


    private void setAudioFocus(boolean setFocus) {
        if (audioManager != null) {
            if (setFocus) {
                savedAudioMode = audioManager.getMode();
                // Request audio focus before making any device switch. minSdk is 30, so the
                // AudioFocusRequest builder path is always available here.
                AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                activeAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(i -> {
                        })
                        .build();
                final int focusResult = audioManager.requestAudioFocus(activeAudioFocusRequest);
                if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.e(Config.LOGTAG, "setAudioFocus: requestAudioFocus failed, result=" + focusResult);
                }
                /*
                 * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
                 * required to be in this mode when playout and/or recording starts for
                 * best possible VoIP performance. Some devices have difficulties with speaker mode
                 * if this is not set.
                 */
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                startBluetoothScoIfPossible();
            } else {
                stopBluetoothScoIfNeeded();
                audioManager.setMode(savedAudioMode);
                if (activeAudioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(activeAudioFocusRequest);
                    activeAudioFocusRequest = null;
                }
            }
        }
    }


    private boolean checkPermissionForMicrophone() {
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkPermissionForBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermissionForMicrophone() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            Snackbar.make(binding.callStatusOutgoing,
                    getString(R.string.microphone_permissions_needed),
                    Snackbar.LENGTH_LONG).show();
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MIC_PERMISSION_REQUEST_CODE);
        }
    }

    private void requestPermissionForBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT)) {
                Snackbar.make(binding.callStatusOutgoing,
                        getString(R.string.bluetooth_permissions_rational),
                        Snackbar.LENGTH_LONG).show();
            } else {
                requestPermissions(
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        BLUETOOTH_PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void startBluetoothScoIfPossible() {
        if (audioManager != null
                && isBluetoothDeviceAvailable
                && audioManager.isBluetoothScoAvailableOffCall()
                && !audioManager.isBluetoothScoOn()
                && checkPermissionForBluetooth()) {
            try {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            } catch (Exception ignored) {
            }
        }
    }

    private void stopBluetoothScoIfNeeded() {
        if (audioManager != null && audioManager.isBluetoothScoOn()) {
            try {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        /*
         * Check if microphone permissions is granted
         */
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_REQUEST_CODE && permissions.length > 0) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Snackbar.make(binding.callStatusOutgoing,
                        getString(R.string.microphone_permissions_needed),
                        Snackbar.LENGTH_LONG).show();
            }
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE && permissions.length > 0) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Snackbar.make(binding.callStatusOutgoing,
                        getString(R.string.bluetooth_permissions_rational),
                        Snackbar.LENGTH_LONG).show();
            } else {
                registerBluetoothReceiver();
                startBluetoothScoIfPossible();
                updateBluetoothAvailability();
            }
        }
    }

    private void updateBluetoothAvailability() {
        boolean available = queryBluetoothAvailability();
        if (available != isBluetoothDeviceAvailable) {
            isBluetoothDeviceAvailable = available;
            if (!available && selectedAudioRoute == AudioRoute.BLUETOOTH) {
                setAudioRoute(AudioRoute.EARPIECE);
                return;
            }
        }
        updateSpeakerButtonUI();
    }

    private boolean queryBluetoothAvailability() {
        if (audioManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !checkPermissionForBluetooth()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            for (AudioDeviceInfo device : devices) {
                if (isBluetoothDevice(device)) {
                    return true;
                }
            }
        } else {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                if (isBluetoothDevice(device)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBluetoothDevice(AudioDeviceInfo device) {
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
        }
        return false;
    }

}
