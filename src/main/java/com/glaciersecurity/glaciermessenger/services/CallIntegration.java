package com.glaciersecurity.glaciermessenger.services;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.util.MainThreadExecutor;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.Media;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CallIntegration extends Connection {

    /**
     * Samsung Galaxy Tab A claims to have FEATURE_CONNECTION_SERVICE but then throws
     * SecurityException when invoking placeCall(). Both Stock and LineageOS have this problem.
     *
     * <p>Lenovo Yoga Smart Tab YT-X705F claims to have FEATURE_CONNECTION_SERVICE but throws
     * SecurityException
     */
    private static final List<String> BROKEN_DEVICE_MODELS =
            Arrays.asList("gtaxlwifi", "a5y17lte", "YT-X705F", "HWAGS2");

    /**
     * all Realme devices at least up to and including Android 11 are broken
     *
     * <p>we are relatively sure that old Oppo devices are broken too. We get reports of 'number not
     * sent' from Oppo R15x (Android 10)
     *
     * <p>OnePlus 6 (Android 8.1-11) Device is buggy and always starts the OS call screen even
     * though we want to be self managed
     *
     * <p>a bunch of OnePlus devices are broken in other ways
     */
    private static final List<String> BROKEN_MANUFACTURES_UP_TO_11 =
            Arrays.asList("realme", "oppo", "oneplus");

    public static final int DEFAULT_TONE_VOLUME = 60;
    // the connect chime and call-ended beep play straight into the user's ear; keep them soft
    private static final int CONNECTED_SOUND_VOLUME = 6;
    private static final int CALL_ENDED_TONE_VOLUME = 5;

    private final Context context;

    /**
     * Read without the monitor by main-thread audio/UI callbacks whenever {@link #selfManaged}
     * reads false, so it is volatile: writers assign it before flipping that flag, and volatile
     * keeps the publication correct rather than resting on write ordering alone.
     */
    private volatile AppRTCAudioManager appRTCAudioManager;

    private AudioDevice initialAudioDevice = null;

    /**
     * Whether this particular call is being handled by the OS Telecom stack. Starts out matching
     * {@link #selfManaged(Context)}, but a device can advertise Telecom support and still refuse an
     * individual call -- an unregistered or disabled PhoneAccount, or an OEM restriction. {@link
     * #demoteToAppManaged()} flips this so the session keeps running on the app's own audio routing
     * and ringer rather than being declined.
     */
    private final AtomicBoolean selfManaged = new AtomicBoolean(false);

    /**
     * Written under the instance monitor together with {@link #selfManaged} so a routing request
     * and a demotion cannot each conclude the other handled the foreground service. Volatile
     * because audio callbacks read it without the monitor.
     */
    private volatile boolean isAudioRoutingRequested = false;
    private final AtomicBoolean initialAudioDeviceConfigured = new AtomicBoolean(false);
    private final AtomicBoolean delayedDestructionInitiated = new AtomicBoolean(false);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean foregroundCallServiceStarted = new AtomicBoolean(false);

    /**
     * Serialises every transition of {@link #foregroundCallServiceStarted} with the
     * JingleCallForegroundService call it implies, so a start, a notification refresh and a
     * shutdown cannot interleave. Held only across those service calls, which never reach back
     * into this object, so it takes part in no lock ordering.
     */
    private final Object foregroundCallServiceLock = new Object();

    /**
     * Set once the call is being torn down, so a routing request that arrives after the stop
     * cannot start the service again. Guarded by {@link #foregroundCallServiceLock}; distinct
     * from {@link #isDestroyed}, which other callers read and which is only set once
     * destruction has finished.
     */
    private boolean foregroundCallServiceShutDown = false;

    private volatile List<CallEndpoint> availableEndpoints = Collections.emptyList();
    private final long createdAt = SystemClock.elapsedRealtime();
    private boolean isMicrophoneEnabled = true;

    private Callback callback = null;

    // volatile: written from the Jingle connection thread as a session is set up, read by
    // startAudioRouting() (and the refresh below) which can run on a different thread
    @Nullable private volatile String jingleAccount = null;
    @Nullable private volatile String jingleWith = null;
    @Nullable private volatile String jingleSessionId = null;

    public CallIntegration(final Context context) {
        this.context = context.getApplicationContext();
        final boolean selfManaged = selfManaged(this.context);
        if (selfManaged) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
            } else {
                throw new AssertionError(
                        "Trying to set connection properties on unsupported version");
            }
        } else {
            initializeAppRtcAudioManager();
        }
        // set last, for the same reason demoteToAppManaged() does: anything reading this flag as
        // false expects appRTCAudioManager to already be there
        this.selfManaged.set(selfManaged);
        setRingbackRequested(true);
        setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_RESPOND_VIA_TEXT);
    }

    public void setCallback(final Callback callback) {
        this.callback = callback;
    }

    /**
     * Identifies the Jingle session this CallIntegration belongs to, so that {@link
     * JingleCallForegroundService}'s notification can deep link back into the call. Safe to call
     * more than once, and meant to be: an outgoing call learns the answering device's full jid
     * only when the proposal is accepted, and a notification that is already showing is re-posted
     * with the new identifiers.
     */
    public void setJingleIdentifiers(
            @Nullable final String account,
            @Nullable final String with,
            @Nullable final String sessionId) {
        if (Objects.equal(this.jingleAccount, account)
                && Objects.equal(this.jingleWith, with)
                && Objects.equal(this.jingleSessionId, sessionId)) {
            return;
        }
        this.jingleAccount = account;
        this.jingleWith = with;
        this.jingleSessionId = sessionId;
        synchronized (this.foregroundCallServiceLock) {
            // Decide and act under the lock. A 'proceed' arriving as the call is torn down could
            // otherwise see the service running, have destroyCallIntegration() clear the flag and
            // enqueue ACTION_STOP underneath it, and then enqueue ACTION_START after that stop --
            // restarting the foreground service for a call that has ended and stranding its
            // ongoing-call notification in the shade for good.
            if (!foregroundCallServiceStarted.get()) {
                return;
            }
            // The identifiers are baked into the deep link of the notification this CallIntegration's
            // foreground service is already showing, and RtpSessionActivity looks the session up by
            // the exact (account, with, sessionId) triple. An outgoing call starts out as a session
            // proposal addressed to the contact's *bare* jid, and only once the contact answers do we
            // learn the full jid of the device that took the call -- which is what the live session is
            // keyed on. Without this refresh the notification keeps deep linking to the bare jid, the
            // lookup misses, and tapping 'Return to ongoing call' closes the call screen again instead
            // of showing the call.
            JingleCallForegroundService.start(context, account, with, sessionId);
        }
    }

    @Override
    public void onShowIncomingCallUi() {
        this.callback.onCallIntegrationShowIncomingCallUi();
    }

    @Override
    public void onAnswer() {
        this.callback.onCallIntegrationAnswer();
    }

    @Override
    public void onDisconnect() {
        this.callback.onCallIntegrationDisconnect();
    }

    @Override
    public void onReject() {
        this.callback.onCallIntegrationReject();
    }

    @Override
    public void onReject(final String replyMessage) {
        this.callback.onCallIntegrationReject();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onAvailableCallEndpointsChanged(@NonNull List<CallEndpoint> availableEndpoints) {
        if (this.availableEndpoints.isEmpty()) {
        }
        this.availableEndpoints = availableEndpoints;
        this.onAudioDeviceChanged(
                getAudioDeviceUpsideDownCake(getCurrentCallEndpoint()),
                ImmutableSet.copyOf(
                        Lists.transform(
                                availableEndpoints,
                                CallIntegration::getAudioDeviceUpsideDownCake)));
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onCallEndpointChanged(@NonNull final CallEndpoint callEndpoint) {
        this.onAudioDeviceChanged(
                getAudioDeviceUpsideDownCake(callEndpoint),
                ImmutableSet.copyOf(
                        Lists.transform(
                                this.availableEndpoints,
                                CallIntegration::getAudioDeviceUpsideDownCake)));
    }

    @Override
    public void onCallAudioStateChanged(final CallAudioState state) {
        if (selfManaged()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && !this.availableEndpoints.isEmpty()) {
            return;
        }
        setMicrophoneEnabled(!state.isMuted());
        this.onAudioDeviceChanged(getAudioDeviceOreo(state), getAudioDevicesOreo(state));
    }

    @Override
    public void onMuteStateChanged(final boolean isMuted) {
        setMicrophoneEnabled(!isMuted);
    }

    private void setMicrophoneEnabled(final boolean enabled) {
        this.isMicrophoneEnabled = enabled;
        this.callback.onCallIntegrationMicrophoneEnabled(enabled);
    }

    public boolean isMicrophoneEnabled() {
        return this.isMicrophoneEnabled;
    }

    public Set<AudioDevice> getAudioDevices() {
        if (notSelfManaged()) {
            return getAudioDevicesFallback();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return getAudioDevicesUpsideDownCake();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getAudioDevicesOreo();
        } else {
            throw new AssertionError("Trying to get audio devices on unsupported version");
        }
    }

    public AudioDevice getSelectedAudioDevice() {
        if (notSelfManaged()) {
            return getAudioDeviceFallback();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return getAudioDeviceUpsideDownCake();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getAudioDeviceOreo();
        } else {
            throw new AssertionError("Trying to get selected audio device on unsupported version");
        }
    }

    public void setAudioDevice(final AudioDevice audioDevice) {
        if (notSelfManaged()) {
            setAudioDeviceFallback(audioDevice);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setAudioDeviceUpsideDownCake(audioDevice);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setAudioDeviceOreo(audioDevice);
        } else {
            throw new AssertionError("Trying to set audio devices on unsupported version");
        }
    }

    /**
     * The user picked this device in the call UI. setAudioDevice() is for the app's own choices;
     * on the app-managed path those never override a connected headset, but a user's pick must.
     */
    public void selectAudioDeviceByUser(final AudioDevice audioDevice) {
        if (notSelfManaged()) {
            final var audioManager = requireAppRtcAudioManager();
            audioManager.executeOnMain(() -> audioManager.selectAudioDeviceByUser(audioDevice));
        } else {
            setAudioDevice(audioDevice);
        }
    }

    /** One Bluetooth device the call can use; the picker lists each, not one "Bluetooth" row. */
    public static final class BluetoothOutput {
        public final String id;
        @Nullable public final String name;

        BluetoothOutput(final String id, @Nullable final String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Every Bluetooth device the call could use; empty if none or they can't be read. */
    @SuppressLint("MissingPermission")
    public List<BluetoothOutput> getBluetoothOutputs() {
        try {
            if (notSelfManaged()) {
                return ImmutableList.copyOf(
                        Lists.transform(
                                requireAppRtcAudioManager().getBluetoothDevices(),
                                CallIntegration::bluetoothOutput));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && !this.availableEndpoints.isEmpty()) {
                final ImmutableList.Builder<BluetoothOutput> outputs = ImmutableList.builder();
                for (final CallEndpoint endpoint : this.availableEndpoints) {
                    if (endpoint.getEndpointType() == CallEndpoint.TYPE_BLUETOOTH) {
                        outputs.add(
                                new BluetoothOutput(
                                        endpoint.getIdentifier().toString(),
                                        endpoint.getEndpointName().toString()));
                    }
                }
                return outputs.build();
            } else {
                final var audioState = getCallAudioState();
                if (audioState == null) {
                    return ImmutableList.of();
                }
                return ImmutableList.copyOf(
                        Collections2.transform(
                                audioState.getSupportedBluetoothDevices(),
                                CallIntegration::bluetoothOutput));
            }
        } catch (final SecurityException | IllegalStateException e) {
            return ImmutableList.of();
        }
    }

    /** The Bluetooth device the call uses, or would use when Bluetooth is picked; null if none. */
    @Nullable
    public String getSelectedBluetoothOutputId() {
        try {
            if (notSelfManaged()) {
                final var device = requireAppRtcAudioManager().getBluetoothDevice();
                return device == null ? null : device.getAddress();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && !this.availableEndpoints.isEmpty()) {
                final var endpoint = getCurrentCallEndpoint();
                return endpoint == null || endpoint.getEndpointType() != CallEndpoint.TYPE_BLUETOOTH
                        ? null
                        : endpoint.getIdentifier().toString();
            } else {
                final var audioState = getCallAudioState();
                final var device = audioState == null ? null : audioState.getActiveBluetoothDevice();
                return device == null ? null : device.getAddress();
            }
        } catch (final SecurityException | IllegalStateException e) {
            return null;
        }
    }

    /**
     * The user picked this Bluetooth device. Returns false if the app can't route to it: on the
     * app-managed path Android only lets an app use the active headset, so the user has to make
     * this one active in Bluetooth settings first.
     */
    public boolean selectBluetoothOutputByUser(final String id) {
        if (notSelfManaged()) {
            if (!id.equals(getSelectedBluetoothOutputId())) {
                return false;
            }
            selectAudioDeviceByUser(AudioDevice.BLUETOOTH);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && !this.availableEndpoints.isEmpty()) {
            final var endpoint =
                    Iterables.tryFind(
                            this.availableEndpoints,
                            e -> e.getEndpointType() == CallEndpoint.TYPE_BLUETOOTH
                                    && e.getIdentifier().toString().equals(id));
            if (endpoint.isPresent()) {
                requestCallEndpointChange(
                        endpoint.get(),
                        MainThreadExecutor.getInstance(),
                        result -> {});
            } else {
                Log.w(Config.LOGTAG, "Bluetooth endpoint is gone");
            }
        } else {
            final var audioState = getCallAudioState();
            final var device =
                    audioState == null
                            ? null
                            : Iterables.tryFind(
                                            audioState.getSupportedBluetoothDevices(),
                                            d -> d.getAddress().equals(id))
                                    .orNull();
            if (device != null) {
                requestBluetoothAudio(device);
            } else {
                Log.w(Config.LOGTAG, "Bluetooth device is gone");
            }
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private static BluetoothOutput bluetoothOutput(final BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
        } catch (final SecurityException e) {
            name = null;
        }
        return new BluetoothOutput(device.getAddress(), name);
    }

    public void setAudioDeviceWhenAvailable(final AudioDevice audioDevice) {
        final var available = getAudioDevices();
        if (available.contains(audioDevice) && !available.contains(AudioDevice.BLUETOOTH)) {
            this.setAudioDevice(audioDevice);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private Set<AudioDevice> getAudioDevicesUpsideDownCake() {
        if (this.availableEndpoints.isEmpty()) {
            return getAudioDevicesOreo();
        }
        return ImmutableSet.copyOf(
                Lists.transform(
                        this.availableEndpoints, CallIntegration::getAudioDeviceUpsideDownCake));
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private AudioDevice getAudioDeviceUpsideDownCake() {
        if (this.availableEndpoints.isEmpty()) {
            return getAudioDeviceOreo();
        }
        return getAudioDeviceUpsideDownCake(getCurrentCallEndpoint());
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static AudioDevice getAudioDeviceUpsideDownCake(final CallEndpoint callEndpoint) {
        if (callEndpoint == null) {
            return AudioDevice.NONE;
        }
        final var endpointType = callEndpoint.getEndpointType();
        return switch (endpointType) {
            case CallEndpoint.TYPE_BLUETOOTH -> AudioDevice.BLUETOOTH;
            case CallEndpoint.TYPE_EARPIECE -> AudioDevice.EARPIECE;
            case CallEndpoint.TYPE_SPEAKER -> AudioDevice.SPEAKER_PHONE;
            case CallEndpoint.TYPE_WIRED_HEADSET -> AudioDevice.WIRED_HEADSET;
            case CallEndpoint.TYPE_STREAMING -> AudioDevice.STREAMING;
            case CallEndpoint.TYPE_UNKNOWN -> AudioDevice.NONE;
            default -> throw new IllegalStateException("Unknown endpoint type " + endpointType);
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void setAudioDeviceUpsideDownCake(final AudioDevice audioDevice) {
        if (this.availableEndpoints.isEmpty()) {
            setAudioDeviceOreo(audioDevice);
            return;
        }
        final var callEndpointOptional =
                Iterables.tryFind(
                        this.availableEndpoints,
                        e -> getAudioDeviceUpsideDownCake(e) == audioDevice);
        if (callEndpointOptional.isPresent()) {
            final var endpoint = callEndpointOptional.get();
            requestCallEndpointChange(
                    endpoint,
                    MainThreadExecutor.getInstance(),
                    result -> {});
        } else {
            Log.w(Config.LOGTAG, "no endpoint found matching " + audioDevice);
        }
    }

    private Set<AudioDevice> getAudioDevicesOreo() {
        final var audioState = getCallAudioState();
        if (audioState == null) {
            return Collections.emptySet();
        }
        return getAudioDevicesOreo(audioState);
    }

    private static Set<AudioDevice> getAudioDevicesOreo(final CallAudioState callAudioState) {
        final ImmutableSet.Builder<AudioDevice> supportedAudioDevicesBuilder =
                new ImmutableSet.Builder<>();
        final var supportedRouteMask = callAudioState.getSupportedRouteMask();
        if ((supportedRouteMask & CallAudioState.ROUTE_BLUETOOTH)
                == CallAudioState.ROUTE_BLUETOOTH) {
            supportedAudioDevicesBuilder.add(AudioDevice.BLUETOOTH);
        }
        if ((supportedRouteMask & CallAudioState.ROUTE_EARPIECE) == CallAudioState.ROUTE_EARPIECE) {
            supportedAudioDevicesBuilder.add(AudioDevice.EARPIECE);
        }
        if ((supportedRouteMask & CallAudioState.ROUTE_SPEAKER) == CallAudioState.ROUTE_SPEAKER) {
            supportedAudioDevicesBuilder.add(AudioDevice.SPEAKER_PHONE);
        }
        if ((supportedRouteMask & CallAudioState.ROUTE_WIRED_HEADSET)
                == CallAudioState.ROUTE_WIRED_HEADSET) {
            supportedAudioDevicesBuilder.add(AudioDevice.WIRED_HEADSET);
        }
        return supportedAudioDevicesBuilder.build();
    }

    private AudioDevice getAudioDeviceOreo() {
        final var audioState = getCallAudioState();
        if (audioState == null) {
            return AudioDevice.NONE;
        }
        return getAudioDeviceOreo(audioState);
    }

    private static AudioDevice getAudioDeviceOreo(final CallAudioState audioState) {
        // technically we get a mask here; maybe we should query the mask instead
        return switch (audioState.getRoute()) {
            case CallAudioState.ROUTE_BLUETOOTH -> AudioDevice.BLUETOOTH;
            case CallAudioState.ROUTE_EARPIECE -> AudioDevice.EARPIECE;
            case CallAudioState.ROUTE_SPEAKER -> AudioDevice.SPEAKER_PHONE;
            case CallAudioState.ROUTE_WIRED_HEADSET -> AudioDevice.WIRED_HEADSET;
            default -> AudioDevice.NONE;
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setAudioDeviceOreo(final AudioDevice audioDevice) {
        switch (audioDevice) {
            case EARPIECE -> setAudioRoute(CallAudioState.ROUTE_EARPIECE);
            case BLUETOOTH -> setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
            case WIRED_HEADSET -> setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET);
            case SPEAKER_PHONE -> setAudioRoute(CallAudioState.ROUTE_SPEAKER);
        }
    }

    private Set<AudioDevice> getAudioDevicesFallback() {
        return requireAppRtcAudioManager().getAudioDevices();
    }

    private AudioDevice getAudioDeviceFallback() {
        final var audioDevice = requireAppRtcAudioManager().getSelectedAudioDevice();
        return audioDevice == null ? AudioDevice.NONE : audioDevice;
    }

    private void setAudioDeviceFallback(final AudioDevice audioDevice) {
        final var audioManager = requireAppRtcAudioManager();
        audioManager.executeOnMain(() -> audioManager.setDefaultAudioDevice(audioDevice));
    }

    @NonNull
    private AppRTCAudioManager requireAppRtcAudioManager() {
        if (this.appRTCAudioManager == null) {
            throw new IllegalStateException(
                    "You are trying to access the fallback audio manager on a modern device");
        }
        return this.appRTCAudioManager;
    }

    @Override
    public void onSilence() {
        this.callback.onCallIntegrationSilence();
    }

    @Override
    public void onStateChanged(final int state) {
        if (notSelfManaged()) {
            if (state == STATE_DIALING) {
                requireAppRtcAudioManager().startRingBack();
            } else {
                requireAppRtcAudioManager().stopRingBack();
            }
        }
        if (state == STATE_ACTIVE) {
            playConnectedSound();
        } else if (state == STATE_DISCONNECTED) {
            final var audioManager = this.appRTCAudioManager;
            if (audioManager != null) {
                audioManager.executeOnMain(audioManager::stop);
            }
        }
    }

    private void playConnectedSound() {
        final var audioAttributes =
                new AudioAttributes.Builder()
                        .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                        .build();
        final var mediaPlayer =
                android.media.MediaPlayer.create(
                        context,
                        R.raw.connected,
                        audioAttributes,
                        AudioManager.AUDIO_SESSION_ID_GENERATE);
        mediaPlayer.setVolume(
                CONNECTED_SOUND_VOLUME / 100f, CONNECTED_SOUND_VOLUME / 100f);
        mediaPlayer.start();
    }

    public void success() {
        startTone(CALL_ENDED_TONE_VOLUME, ToneGenerator.TONE_CDMA_CALLDROP_LITE, 375);
        this.destroyWithDelay(new DisconnectCause(DisconnectCause.LOCAL, null), 375);
    }

    public void accepted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            this.destroyWith(new DisconnectCause(DisconnectCause.ANSWERED_ELSEWHERE, null));
        } else {
            this.destroyWith(new DisconnectCause(DisconnectCause.CANCELED, null));
        }
    }

    public void error() {
        startTone(CALL_ENDED_TONE_VOLUME, ToneGenerator.TONE_CDMA_CALLDROP_LITE, 375);
        this.destroyWithDelay(new DisconnectCause(DisconnectCause.ERROR, null), 375);
    }

    public void retracted() {
        // an alternative cause would be LOCAL
        this.destroyWith(new DisconnectCause(DisconnectCause.CANCELED, null));
    }

    public void rejected() {
        this.destroyWith(new DisconnectCause(DisconnectCause.REJECTED, null));
    }

    public void busy() {
        startTone(80, ToneGenerator.TONE_CDMA_NETWORK_BUSY, 2500);
        this.destroyWithDelay(new DisconnectCause(DisconnectCause.BUSY, null), 2500);
    }

    private void destroyWithDelay(final DisconnectCause disconnectCause, final int delay) {
        if (this.delayedDestructionInitiated.compareAndSet(false, true)) {
            JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(
                    () -> {
                        this.setDisconnected(disconnectCause);
                        this.destroyCallIntegration();
                    },
                    delay,
                    TimeUnit.MILLISECONDS);
        } else {
            Log.w(Config.LOGTAG, "CallIntegration destruction has already been scheduled!");
        }
    }

    private void destroyWith(final DisconnectCause disconnectCause) {
        if (this.getState() == STATE_DISCONNECTED || this.delayedDestructionInitiated.get()) {
            return;
        }
        this.setDisconnected(disconnectCause);
        this.destroyCallIntegration();
    }

    private void startTone(final int volume, final int toneType, final int durationMs) {
        final ToneGenerator toneGenerator;
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "could not initialize tone generator", e);
            return;
        }
        toneGenerator.startTone(toneType, durationMs);
    }

    public static Uri address(final Jid contact) {
        return Uri.parse(String.format("xmpp:%s", contact.toEscapedString()));
    }

    public void verifyDisconnected() {
        if (this.getState() == STATE_DISCONNECTED || this.delayedDestructionInitiated.get()) {
            return;
        }
        throw new AssertionError("CallIntegration has not been disconnected");
    }

    private void onAudioDeviceChanged(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final Set<CallIntegration.AudioDevice> availableAudioDevices) {
        if (isAudioRoutingRequested) {
            configureInitialAudioDevice(availableAudioDevices);
        }
        final var callback = this.callback;
        if (callback == null) {
            return;
        }
        callback.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
    }

    private void configureInitialAudioDevice(final Set<AudioDevice> availableAudioDevices) {
        final var initialAudioDevice = this.initialAudioDevice;
        if (initialAudioDevice == null) {
            return;
        }
        final var target = this.initialAudioDevice;
        if (this.initialAudioDeviceConfigured.compareAndSet(false, true)) {
            if (availableAudioDevices.contains(target)
                    && !availableAudioDevices.contains(AudioDevice.BLUETOOTH)) {
                setAudioDevice(target);
            }
        }
    }

    private boolean selfManaged() {
        return this.selfManaged.get();
    }

    private boolean notSelfManaged() {
        return !this.selfManaged.get();
    }

    /** Whether the OS Telecom stack is handling this specific call. */
    public boolean isSelfManaged() {
        return this.selfManaged.get();
    }

    /**
     * Telecom advertised support but then refused this specific call -- typically because our
     * self-managed PhoneAccount is not registered or has been disabled, or because the OEM
     * restricts self-managed connections. Take the call over ourselves instead.
     *
     * <p>The alternative is declining the session, which the *caller* sees as {@code
     * RtpEndUserState.DECLINED_OR_BUSY} -- i.e. a "Busy" screen on a device that is not busy at
     * all. Everything needed to run the call without Telecom already exists; it is the same path
     * used on Android 15+ and on the broken-device allow-list.
     *
     * @return true if this integration now runs app-managed, false if it already did.
     */
    public boolean demoteToAppManaged() {
        final boolean routeAudio;
        // This runs on the XMPP thread while audio/UI callbacks read the flag on the main thread.
        // The moment selfManaged reads false those readers take the fallback branch and dereference
        // appRTCAudioManager, so the manager has to be created *and* safely published before the
        // flag flips -- otherwise they hit requireAppRtcAudioManager() and throw, killing the call
        // this method exists to rescue. Holding the monitor across both makes the transition
        // atomic; assigning the manager before the volatile write to selfManaged is what publishes
        // it to readers that never take the monitor.
        synchronized (this) {
            if (!this.selfManaged.get()) {
                return false;
            }
            if (this.appRTCAudioManager == null) {
                initializeAppRtcAudioManager();
            }
            this.selfManaged.set(false);
            routeAudio = this.isAudioRoutingRequested;
        }
        Log.w(
                Config.LOGTAG,
                "Telecom refused the call; CallIntegration falling back to app-managed audio");
        if (routeAudio) {
            // re-run so the app-managed branches (foreground service, AppRTCAudioManager) that were
            // skipped while we believed Telecom owned the call actually take effect. Deliberately
            // outside the monitor: it dispatches to the main thread and starts a foreground
            // service, neither of which should run under a lock the main thread may contend on.
            startAudioRouting();
        }
        return true;
    }

    private void initializeAppRtcAudioManager() {
        final var audioManager = new AppRTCAudioManager(context);
        audioManager.setAudioManagerEvents(this::onAudioDeviceChanged);
        this.appRTCAudioManager = audioManager;
    }

    /**
     * Whether Telecom can run our calls at all. Upstream also stopped at API 35 (b45a3eeaf): on
     * Android 15 Telecom did not call onAvailableCallEndpointsChanged() for outgoing calls. Here
     * the user decides via {@link AppSettings#isCallIntegration()}, an explicit opt-in from API 35,
     * and a missing endpoint list falls back to CallAudioState instead of breaking the switcher.
     */
    public static boolean selfManagedAvailable(final Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && hasSystemFeature(context)
                && isDeviceModelSupported();
    }

    public static boolean selfManaged(final Context context) {
        return selfManagedAvailable(context) && new AppSettings(context).isCallIntegration();
    }

    public static boolean hasSystemFeature(final Context context) {
        final var packageManager = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM);
        } else {
            //noinspection deprecation
            return packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
        }
    }

    private static boolean isDeviceModelSupported() {
        final var manufacturer = Strings.nullToEmpty(Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        if (BROKEN_DEVICE_MODELS.contains(Build.DEVICE)) {
            return false;
        }
        if (BROKEN_MANUFACTURES_UP_TO_11.contains(manufacturer)
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            return false;
        }
        // we only know of one Umidigi device (BISON_GT2_5G) that doesn't work (audio is not being
        // routed properly) However with those devices being extremely rare it's impossible to gauge
        // how many might be effected and no Naomi Wu around to clarify with the company directly
        if ("umidigi".equals(manufacturer) && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            return false;
        }
        // SailfishOS's AppSupport do not support Call Integration
        if (Build.MODEL.endsWith("(AppSupport)")) {
            return false;
        }
        return true;
    }

    public static boolean notSelfManaged(final Context context) {
        return !selfManaged(context);
    }

    public void setInitialAudioDevice(final AudioDevice audioDevice) {
        this.initialAudioDevice = audioDevice;
    }

    public void startAudioRouting() {
        final boolean selfManaged;
        synchronized (this) {
            // Record the request and read the mode as one step, under the monitor
            // demoteToAppManaged() uses. A request that lands mid-demotion is then either visible
            // to the demotion -- which re-runs us with the app-managed mode -- or reads the already
            // demoted flag itself. Every branch below uses this one snapshot rather than re-reading
            // the flag, which previously let a routing request see "self managed" for the
            // foreground-service decision and "app managed" for the audio decision, while the
            // concurrent demotion saw no request yet and skipped its re-run. The call then ran
            // app-managed with no foreground service holding it up.
            this.isAudioRoutingRequested = true;
            selfManaged = this.selfManaged.get();
        }
        if (selfManaged) {
            if (Build.VERSION.SDK_INT >= 35 && !startForegroundCallService()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && this.availableEndpoints.isEmpty()) {
                Log.w(
                        Config.LOGTAG,
                        "Telecom has sent no call endpoints yet; using CallAudioState for routing");
            }
            final var devices = getAudioDevices();
            if (devices.isEmpty()) {
                return;
            }
            configureInitialAudioDevice(devices);
            return;
        }
        // App-managed CallIntegrations never register with Telecom, so audio routing, ringback, and
        // call tones are all driven directly by app code with no OS-provided foreground guarantee.
        // Hold our own phoneCall foreground service for as long as this session is live so
        // backgrounding the app doesn't silently kill audio.
        if (!startForegroundCallService()) {
            return;
        }
        final var audioManager = requireAppRtcAudioManager();
        audioManager.executeOnMain(
                () -> {
                    audioManager.start();
                    this.onAudioDeviceChanged(
                            audioManager.getSelectedAudioDevice(), audioManager.getAudioDevices());
                });
    }

    /**
     * Starts, or refreshes, this call's phoneCall foreground service. Safe to call repeatedly
     * (e.g. once the session id becomes known). Returns false once the call is being torn down.
     * Also used on the Telecom path from API 35, which upstream never ran and whose background
     * survival there is unverified without it.
     */
    private boolean startForegroundCallService() {
        synchronized (this.foregroundCallServiceLock) {
            // Setting the flag after the start used to leave a window where a teardown landing in
            // between saw it still false, skipped the stop, and left the service foreground for a
            // call nobody was on. Raise it with the start, and refuse to start once torn down.
            if (this.foregroundCallServiceShutDown) {
                return false;
            }
            JingleCallForegroundService.start(context, jingleAccount, jingleWith, jingleSessionId);
            foregroundCallServiceStarted.set(true);
            return true;
        }
    }

    private void destroyCallIntegration() {
        synchronized (this.foregroundCallServiceLock) {
            this.foregroundCallServiceShutDown = true;
            if (foregroundCallServiceStarted.compareAndSet(true, false)) {
                JingleCallForegroundService.stop(context);
            }
        }
        super.destroy();
        this.isDestroyed.set(true);
    }

    public boolean isDestroyed() {
        return this.isDestroyed.get();
    }

    public enum AudioDevice {
        NONE,
        SPEAKER_PHONE,
        WIRED_HEADSET,
        EARPIECE,
        BLUETOOTH,
        STREAMING
    }

    public static AudioDevice initialAudioDevice(final Set<Media> media) {
        if (Media.audioOnly(media)) {
            return AudioDevice.EARPIECE;
        } else {
            return AudioDevice.SPEAKER_PHONE;
        }
    }

    public interface Callback {
        void onCallIntegrationShowIncomingCallUi();

        void onCallIntegrationDisconnect();

        void onAudioDeviceChanged(
                CallIntegration.AudioDevice selectedAudioDevice,
                Set<CallIntegration.AudioDevice> availableAudioDevices);

        void onCallIntegrationReject();

        void onCallIntegrationAnswer();

        void onCallIntegrationSilence();

        void onCallIntegrationMicrophoneEnabled(boolean enabled);

    }
}
