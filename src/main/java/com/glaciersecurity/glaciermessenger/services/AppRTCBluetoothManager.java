/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package com.glaciersecurity.glaciermessenger.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.collect.ImmutableList;

import org.webrtc.ThreadUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.AppRTCUtils;

/** AppRTCProximitySensor manages functions related to Bluetoth devices in the AppRTC demo. */
public class AppRTCBluetoothManager {
    // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;
    // Once the headset reports SCO connecting, how long it gets to finish. Headsets that must
    // pause A2DP music first can take several seconds; giving up tears their link down.
    private static final int BLUETOOTH_SCO_CONNECTING_TIMEOUT_MS = 10000;
    // Maximum number of SCO connection attempts.
    private static final int MAX_SCO_CONNECTION_ATTEMPTS = 2;
    private final Context apprtcContext;
    private final AppRTCAudioManager apprtcAudioManager;
    @Nullable private final AudioManager audioManager;
    private final Handler handler;
    private final BluetoothProfile.ServiceListener bluetoothServiceListener;
    private final BroadcastReceiver bluetoothHeadsetReceiver;
    int scoConnectionAttempts;
    private State bluetoothState;
    @Nullable private BluetoothAdapter bluetoothAdapter;
    @Nullable private BluetoothHeadset bluetoothHeadset;
    @Nullable private BluetoothDevice bluetoothDevice;
    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private final Runnable bluetoothTimeoutRunnable =
            new Runnable() {
                @Override
                public void run() {
                    bluetoothTimeout();
                }
            };

    protected AppRTCBluetoothManager(Context context, AppRTCAudioManager audioManager) {
        apprtcContext = context;
        apprtcAudioManager = audioManager;
        this.audioManager = getAudioManager(context);
        bluetoothState = State.UNINITIALIZED;
        bluetoothServiceListener = new BluetoothServiceListener();
        bluetoothHeadsetReceiver = new BluetoothHeadsetBroadcastReceiver();
        handler = new Handler(Looper.getMainLooper());
    }

    /** Construction. */
    static AppRTCBluetoothManager create(Context context, AppRTCAudioManager audioManager) {
        return new AppRTCBluetoothManager(context, audioManager);
    }

    /** Returns the internal state. */
    public State getState() {
        ThreadUtils.checkIsOnMainThread();
        return bluetoothState;
    }

    /**
     * Activates components required to detect Bluetooth devices and to enable BT SCO (audio is
     * routed via BT SCO) for the headset profile. The end state will be HEADSET_UNAVAILABLE but a
     * state machine has started which will start a state change sequence where the final outcome
     * depends on if/when the BT headset is enabled. Example of state change sequence when start()
     * is called while BT device is connected and enabled: UNINITIALIZED --> HEADSET_UNAVAILABLE -->
     * HEADSET_AVAILABLE --> SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO.
     * Note that the AppRTCAudioManager is also involved in driving this state change.
     */
    public void start() {
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothState != State.UNINITIALIZED) {
            Log.w(Config.LOGTAG, "Invalid BT state");
            return;
        }
        bluetoothHeadset = null;
        bluetoothDevice = null;
        scoConnectionAttempts = 0;
        // Get a handle to the default local Bluetooth adapter.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.w(Config.LOGTAG, "Device does not support Bluetooth");
            return;
        }
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (this.audioManager == null || !audioManager.isBluetoothScoAvailableOffCall()) {
            Log.e(Config.LOGTAG, "Bluetooth SCO audio is not available off call");
            return;
        }
        // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
        // Hands-Free) proxy object and install a listener.
        if (!getBluetoothProfileProxy(
                apprtcContext, bluetoothServiceListener, BluetoothProfile.HEADSET)) {
            Log.e(Config.LOGTAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed");
            return;
        }
        // Register receivers for BluetoothHeadset change notifications.
        IntentFilter bluetoothHeadsetFilter = new IntentFilter();
        // Register receiver for change in connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        // Register receiver for change in audio connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        registerReceiver(bluetoothHeadsetReceiver, bluetoothHeadsetFilter);
        bluetoothState = State.HEADSET_UNAVAILABLE;
    }

    /** Stops and closes all components related to Bluetooth audio. */
    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothAdapter == null) {
            return;
        }
        // Stop BT SCO connection with remote device if needed.
        stopScoAudio();
        // Close down remaining BT resources.
        if (bluetoothState == State.UNINITIALIZED) {
            return;
        }
        unregisterReceiver(bluetoothHeadsetReceiver);
        cancelTimer();
        if (bluetoothHeadset != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            bluetoothHeadset = null;
        }
        bluetoothAdapter = null;
        bluetoothDevice = null;
        bluetoothState = State.UNINITIALIZED;
    }

    /**
     * Starts Bluetooth SCO connection with remote device. Note that the phone application always
     * has the priority on the usage of the SCO connection for telephony. If this method is called
     * while the phone is in call it will be ignored. Similarly, if a call is received or sent while
     * an application is using the SCO connection, the connection will be lost for the application
     * and NOT returned automatically when the call ends. Also note that: up to and including API
     * version JELLY_BEAN_MR1, this method initiates a virtual voice call to the Bluetooth headset.
     * After API version JELLY_BEAN_MR2 only a raw SCO audio connection is established.
     */
    public boolean startScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.e(Config.LOGTAG, "BT SCO connection fails - no more attempts");
            return false;
        }
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Log.e(Config.LOGTAG, "BT SCO connection fails - no headset available");
            return false;
        }
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be
        // SCO_AUDIO_STATE_CONNECTED.
        bluetoothState = State.SCO_CONNECTING;
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        scoConnectionAttempts++;
        startTimer();
        return true;
    }

    /** Stops Bluetooth SCO connection with remote device. */
    public void stopScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) {
            return;
        }
        cancelTimer();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);
        bluetoothState = State.SCO_DISCONNECTING;
    }

    /**
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset Service via IPC) to
     * update the list of connected devices for the HEADSET profile. The internal state will change
     * to HEADSET_UNAVAILABLE or to HEADSET_AVAILABLE and |bluetoothDevice| will be mapped to the
     * connected device if available.
     */
    @SuppressLint("MissingPermission")
    public void updateDevice() {
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }
        // Get connected devices for the headset profile. Returns the set of
        // devices which are in state STATE_CONNECTED. The BluetoothDevice class
        // is just a thin wrapper for a Bluetooth hardware address.
        final List<BluetoothDevice> devices;
        if (hasBluetoothConnectPermission()) {
            devices = bluetoothHeadset.getConnectedDevices();
        } else {
            devices = ImmutableList.of();
        }
        if (devices.isEmpty()) {
            bluetoothDevice = null;
            bluetoothState = State.HEADSET_UNAVAILABLE;
        } else {
            bluetoothDevice = activeDevice(devices);
            bluetoothState = State.HEADSET_AVAILABLE;
        }
    }

    /**
     * The headset Android sends call audio to. startBluetoothSco() always uses this one, so with a
     * watch and earbuds both connected the first in the list may not be it.
     */
    @SuppressLint("MissingPermission")
    private BluetoothDevice activeDevice(final List<BluetoothDevice> devices) {
        for (final BluetoothDevice device : devices) {
            if (bluetoothHeadset != null && bluetoothHeadset.isAudioConnected(device)) {
                return device;
            }
        }
        if (audioManager != null) {
            // audio policy only has an SCO device for the active headset
            final List<AudioDeviceInfo> infos =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? audioManager.getAvailableCommunicationDevices()
                            : Arrays.asList(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
            for (final AudioDeviceInfo info : infos) {
                if (info.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    continue;
                }
                for (final BluetoothDevice device : devices) {
                    if (device.getAddress().equalsIgnoreCase(info.getAddress())) {
                        return device;
                    }
                }
            }
        }
        return devices.get(0);
    }

    /** Every headset connected for calls, the active one included. */
    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> getConnectedDevices() {
        final BluetoothHeadset headset = bluetoothHeadset;
        if (headset == null || !hasBluetoothConnectPermission()) {
            return ImmutableList.of();
        }
        try {
            return ImmutableList.copyOf(headset.getConnectedDevices());
        } catch (final SecurityException e) {
            return ImmutableList.of();
        }
    }

    /**
     * The headset startScoAudio() connects to, or null if there is none. Re-read each time: the
     * user can change the active headset in Bluetooth settings without any broadcast we listen to.
     */
    @Nullable
    public BluetoothDevice getDevice() {
        if (bluetoothDevice != null) {
            final List<BluetoothDevice> devices = getConnectedDevices();
            if (!devices.isEmpty()) {
                bluetoothDevice = activeDevice(devices);
            }
        }
        return bluetoothDevice;
    }

    /** Stubs for test mocks. */
    @Nullable
    protected AudioManager getAudioManager(Context context) {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    protected void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        // Headset broadcasts come from the Bluetooth app (uid 1002), which a NOT_EXPORTED
        // receiver silently drops. They are protected broadcasts, so exporting is safe.
        ContextCompat.registerReceiver(apprtcContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    protected void unregisterReceiver(BroadcastReceiver receiver) {
        apprtcContext.unregisterReceiver(receiver);
    }

    protected boolean getBluetoothProfileProxy(
            Context context, BluetoothProfile.ServiceListener listener, int profile) {
        return bluetoothAdapter.getProfileProxy(context, listener, profile);
    }

    protected boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(
                            apprtcContext, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    /** Ensures that the audio manager updates its list of available audio devices. */
    private void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        apprtcAudioManager.updateAudioDeviceState();
    }

    /** Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds. */
    private void startTimer() {
        ThreadUtils.checkIsOnMainThread();
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS);
    }

    /** Cancels any outstanding timer tasks. */
    private void cancelTimer() {
        ThreadUtils.checkIsOnMainThread();
        handler.removeCallbacks(bluetoothTimeoutRunnable);
    }

    /**
     * Called when start of the BT SCO channel takes too long time. Usually happens when the BT
     * device has been turned on during an ongoing call.
     */
    @SuppressLint("MissingPermission")
    private void bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }
        if (bluetoothState != State.SCO_CONNECTING) {
            return;
        }
        // Bluetooth SCO should be connecting; check the latest result.
        boolean scoConnected = false;
        final List<BluetoothDevice> devices;
        if (hasBluetoothConnectPermission()) {
            devices = bluetoothHeadset.getConnectedDevices();
        } else {
            devices = Collections.emptyList();
        }
        // SCO may be up on any connected headset (e.g. a watch is listed before the earbuds)
        for (final BluetoothDevice device : devices) {
            if (bluetoothHeadset.isAudioConnected(device)) {
                bluetoothDevice = device;
                scoConnected = true;
                break;
            }
        }
        if (!scoConnected && !devices.isEmpty()) {
            bluetoothDevice = activeDevice(devices);
        }
        if (scoConnected) {
            // We thought BT had timed out, but it's actually on; updating state.
            bluetoothState = State.SCO_CONNECTED;
            scoConnectionAttempts = 0;
        } else {
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Log.w(Config.LOGTAG, "BT failed to connect after timeout");
            stopScoAudio();
        }
        updateAudioDeviceState();
    }

    /** Checks whether audio uses Bluetooth SCO. */
    private boolean isScoOn() {
        return audioManager.isBluetoothScoOn();
    }

    /** Converts BluetoothAdapter states into local string representations. */
    private String stateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothAdapter.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothAdapter.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothAdapter.STATE_DISCONNECTING:
                return "DISCONNECTING";
            case BluetoothAdapter.STATE_OFF:
                return "OFF";
            case BluetoothAdapter.STATE_ON:
                return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF:
                // Indicates the local Bluetooth adapter is turning off. Local clients should
                // immediately
                // attempt graceful disconnection of any remote links.
                return "TURNING_OFF";
            case BluetoothAdapter.STATE_TURNING_ON:
                // Indicates the local Bluetooth adapter is turning on. However local clients should
                // wait
                // for STATE_ON before attempting to use the adapter.
                return "TURNING_ON";
            default:
                return "INVALID";
        }
    }

    // Bluetooth connection state.
    public enum State {
        // Bluetooth is not available; no adapter or Bluetooth is off.
        UNINITIALIZED,
        // Bluetooth error happened when trying to start Bluetooth.
        ERROR,
        // Bluetooth proxy object for the Headset profile exists, but no connected headset devices,
        // SCO is not started or disconnected.
        HEADSET_UNAVAILABLE,
        // Bluetooth proxy object for the Headset profile connected, connected Bluetooth headset
        // present, but SCO is not started or disconnected.
        HEADSET_AVAILABLE,
        // Bluetooth audio SCO connection with remote device is closing.
        SCO_DISCONNECTING,
        // Bluetooth audio SCO connection with remote device is initiated.
        SCO_CONNECTING,
        // Bluetooth audio SCO connection with remote device is established.
        SCO_CONNECTED
    }

    /**
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private class BluetoothServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return;
            }
            // Android only supports one connected Bluetooth Headset at a time.
            bluetoothHeadset = (BluetoothHeadset) proxy;
            updateAudioDeviceState();
        }

        @Override
        /** Notifies the client when the proxy object has been disconnected from the service. */
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return;
            }
            stopScoAudio();
            bluetoothHeadset = null;
            bluetoothDevice = null;
            bluetoothState = State.HEADSET_UNAVAILABLE;
            updateAudioDeviceState();
        }
    }

    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private class BluetoothHeadsetBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (bluetoothState == State.UNINITIALIZED) {
                return;
            }
            final String action = intent.getAction();
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                final int state =
                        intent.getIntExtra(
                                BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    scoConnectionAttempts = 0;
                    updateAudioDeviceState();
                } else if (state == BluetoothHeadset.STATE_CONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTING) {
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    // Bluetooth is probably powered off during the call.
                    stopScoAudio();
                    updateAudioDeviceState();
                }
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                final int state =
                        intent.getIntExtra(
                                BluetoothHeadset.EXTRA_STATE,
                                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer();
                    if (bluetoothState == State.SCO_CONNECTING) {
                        bluetoothState = State.SCO_CONNECTED;
                        scoConnectionAttempts = 0;
                        updateAudioDeviceState();
                    } else {
                        Log.w(
                                Config.LOGTAG,
                                "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED");
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    if (bluetoothState == State.SCO_CONNECTING) {
                        cancelTimer();
                        handler.postDelayed(
                                bluetoothTimeoutRunnable, BLUETOOTH_SCO_CONNECTING_TIMEOUT_MS);
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    if (isInitialStickyBroadcast()) {
                        return;
                    }
                    updateAudioDeviceState();
                }
            }
        }
    }
}
