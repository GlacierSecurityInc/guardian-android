package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import static com.glaciersecurity.glaciermessenger.AppSettings.TRACKING_FREQUENCY;
import static com.glaciersecurity.glaciermessenger.AppSettings.FORCE_TRACKING;
import static com.glaciersecurity.glaciermessenger.AppSettings.LIVE_LOCATION_TRACKING;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;
import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.buildJwtJsonRequestBuilder;
import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.LocationService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
public class MappingSettingsFragment extends XmppPreferenceFragment implements RefreshablePreferenceFragment {
    private XmppConnectionService mXmppConnectionService;
    private Account account;
    private String deviceId = "";
    private long tracking_frequency;
    private boolean isEnabled;
    private SwitchPreferenceCompat liveLocationTracking;
    private boolean awaitingLocationPermissionFromToggle;
    private boolean launchedAppSettingsForLocation;
    private boolean awaitingOneTimeLocationPermission;
    private boolean launchedAppSettingsForOneTimeLocation;
    private boolean forceTrackingPermissionDialogCancelled;
    private boolean trackingPermissionDialogShown;
    private AlertDialog backgroundLocationPermissionAlert;
    private ActivityResultLauncher<String> fineLocationPermissionLauncher;
    private ActivityResultLauncher<String> backgroundLocationPermissionLauncher;
    private ActivityResultLauncher<String> oneTimeLocationPermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupPermissionLaunchers();
    }


    @Override
    public void onBackendConnected() {
        isEnabled = getPreferences(getContext()).getBoolean("live_location_tracking", getResources().getBoolean(R.bool.live_location_tracking));
        if (isEnabled) {
            startService();
        } else {
            stopLocationService();
        }
    }


    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_mapping, rootKey);
        getDeviceInfo();
        init();

    }

    private void init() {

        liveLocationTracking = findPreference("live_location_tracking");
        isEnabled = getPreferences(getContext()).getBoolean("live_location_tracking", getResources().getBoolean(R.bool.live_location_tracking));
        reloadTrackingFrequency();

        if (liveLocationTracking != null) {
            updateSummary(liveLocationTracking);
            if (getPreferences(getContext()).getBoolean("force_tracking", getResources().getBoolean(R.bool.force_tracking))) {
                liveLocationTracking.setChecked(true);
                liveLocationTracking.setEnabled(false);
            } else {
                liveLocationTracking.setChecked(isEnabled);
                liveLocationTracking.setEnabled(true);
                liveLocationTracking.setOnPreferenceChangeListener((preference, newValue) -> {
                    boolean enableTracking = (Boolean) newValue;
                    if (enableTracking) {
                        if (hasLocationPermissions()) {
                            startService();
                            return true;
                        }
                        awaitingLocationPermissionFromToggle = true;
                        requestLocationPermission(true);
                        return false;
                    }
                    stopLocationService();
                    return true;
                });
            }
        }
    }

    /**
     * Re-reads the tracking interval from preferences. startService() decides whether to restart
     * the service by comparing LocationService.getFrequency() against this field, so it has to be
     * refreshed before that comparison -- not only once from init().
     */
    private void reloadTrackingFrequency() {
        tracking_frequency = LocationService.DEFAULT_INTERVAL;
        try {
            String freqpref = getPreferences(getContext()).getString("tracking_frequency", getResources().getString(R.string.tracking_frequency));
            tracking_frequency = Integer.parseInt(freqpref) * 1000L;
        } catch (Exception e) {

        }
    }

    private void updateSummary(SwitchPreferenceCompat preference) {
        preference.setSummary(String.format(getString(R.string.pref_live_location_activity_summary_dynamic), toDisplayTimeFreq()));
    }


    private String toDisplayTimeFreq() {
        long totalSecs = tracking_frequency / 1000;
        return DateUtils.formatElapsedTime(totalSecs);
    }

    private void setupPermissionLaunchers() {
        fineLocationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                requestBackgroundLocationPermissionIfNeeded();
            } else {
                handleFineLocationDenied();
            }
        });

        backgroundLocationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                if (awaitingLocationPermissionFromToggle && liveLocationTracking != null) {
                    liveLocationTracking.setChecked(true);
                }
                awaitingLocationPermissionFromToggle = false;
                launchedAppSettingsForLocation = false;
                startLocationService();
            } else {
                handleBackgroundLocationDenied();
            }
        });

        oneTimeLocationPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                awaitingOneTimeLocationPermission = false;
                launchedAppSettingsForOneTimeLocation = false;
                sendLastLocation();
            } else {
                handleOneTimeLocationDenied();
            }
        });
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        boolean enabled = getPreferences(getContext()).getBoolean("live_location_tracking", getResources().getBoolean(R.bool.live_location_tracking));
        boolean forced = getPreferences(getContext()).getBoolean("force_tracking", getResources().getBoolean(R.bool.force_tracking));

        // Each case breaks. Falling through meant an admin turning force_tracking on ran
        // FORCE_TRACKING's startService() and then landed in LIVE_LOCATION_TRACKING, where the
        // user's own live_location_tracking pref is still false -- so tracking was stopped again
        // in the same callback and forced tracking never actually ran.
        switch (key) {
            case FORCE_TRACKING:
                liveLocationTracking = findPreference("live_location_tracking");
                if (liveLocationTracking != null) {
                    liveLocationTracking.setChecked(forced | enabled);
                    liveLocationTracking.setEnabled(!forced);
                }
                applyTrackingState(enabled | forced);
                break;
            case TRACKING_FREQUENCY:
                // The new interval has to be read back before updateSummary()/startService(),
                // which both work off the tracking_frequency field.
                reloadTrackingFrequency();
                SwitchPreferenceCompat frequencyPreference = findPreference("live_location_tracking");
                if (frequencyPreference != null) {
                    updateSummary(frequencyPreference);
                }

                if (enabled | forced) {
                    startService();
                }
                break;
            case LIVE_LOCATION_TRACKING:
                applyTrackingState(enabled | forced);
                break;
        }
    }

    private void applyTrackingState(final boolean trackingOn) {
        if (trackingOn) {
            startService();
        } else {
            stopLocationService();
        }
    }

    private void startService() {
        if (!LocationService.isRunning() || LocationService.getFrequency() != tracking_frequency) {
            if (hasLocationPermissions()) {
                startLocationService();
            } else {
                requestLocationPermission(false);
            }
        }
    }


    private void requestLocationPermission(boolean fromToggle) {
        awaitingLocationPermissionFromToggle = fromToggle;
        if (fromToggle) {
            launchedAppSettingsForLocation = false;
        }
        fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
                getContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED) {
            if (awaitingLocationPermissionFromToggle && liveLocationTracking != null) {
                liveLocationTracking.setChecked(true);
            }
            awaitingLocationPermissionFromToggle = false;
            launchedAppSettingsForLocation = false;
            startLocationService();
            return;
        }
        if (awaitingLocationPermissionFromToggle) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.background_location_permission_title)
                    .setMessage(getString(R.string.background_location_permission_message))
                    .setPositiveButton(android.R.string.ok, (dialog, which) ->
                            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        awaitingLocationPermissionFromToggle = false;
                        if (liveLocationTracking != null) {
                            liveLocationTracking.setChecked(false);
                        }
                    })
                    .show();
            return;
        }
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private void openAppSettings() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", context.getPackageName(), null));
        startActivity(intent);
    }


    private void handleFineLocationDenied() {
        if (awaitingLocationPermissionFromToggle) {
            if (!launchedAppSettingsForLocation && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                launchedAppSettingsForLocation = true;
                openAppSettings();
            } else {
                awaitingLocationPermissionFromToggle = false;
                launchedAppSettingsForLocation = false;
                if (liveLocationTracking != null) {
                    liveLocationTracking.setChecked(false);
                }
                Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
            }
        } else {
            if (trackingPermissionDialogShown) {
                return;
            }
            trackingPermissionDialogShown = true;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.permission_required_title)
                    .setMessage(R.string.location_permission_rationale)
                    .setPositiveButton(R.string.dialog_ok, (dialog, which) -> openAppSettings())
                    .setNegativeButton(R.string.cancel, (dialog, which) -> {
                        forceTrackingPermissionDialogCancelled = true;
                        dialog.dismiss();
                        Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }
    }

    private void handleBackgroundLocationDenied() {
        if (awaitingLocationPermissionFromToggle) {
            if (!launchedAppSettingsForLocation && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                launchedAppSettingsForLocation = true;
                openAppSettings();
            } else {
                awaitingLocationPermissionFromToggle = false;
                launchedAppSettingsForLocation = false;
                if (liveLocationTracking != null) {
                    liveLocationTracking.setChecked(false);
                }
                Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
            }
        } else {
            if (backgroundLocationPermissionAlert != null && backgroundLocationPermissionAlert.isShowing()) {
                return;
            }

            if (backgroundLocationPermissionAlert == null) {
                backgroundLocationPermissionAlert = new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.permission_required_title)
                        .setPositiveButton(R.string.dialog_ok, (dialog, which) -> requestBackgroundLocationPermissionIfNeeded())
                        .setNegativeButton(R.string.cancel, null)
                        .create();
            }

            backgroundLocationPermissionAlert.setMessage(getString(R.string.location_background_permission_rationale));
            backgroundLocationPermissionAlert.show();
        }
    }

    private void handleOneTimeLocationDenied() {
        if (awaitingOneTimeLocationPermission) {
            if (!launchedAppSettingsForOneTimeLocation && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                launchedAppSettingsForOneTimeLocation = true;
                openAppSettings();
            } else {
                awaitingOneTimeLocationPermission = false;
                launchedAppSettingsForOneTimeLocation = false;
                Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestOneTimeLocationPermission() {
        awaitingOneTimeLocationPermission = true;
        launchedAppSettingsForOneTimeLocation = false;
        oneTimeLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
    }


    private void startLocationService() {
        if (mXmppConnectionService == null || mXmppConnectionService.getAccounts() == null || !(mXmppConnectionService.getAccounts().size() > 0)) {
            return;
        }

        account = mXmppConnectionService.getFirstAccount();

        Intent serviceIntent = new Intent(getActivity(), LocationService.class);
        serviceIntent.putExtra("user", account.getUsername());
        serviceIntent.putExtra("org", account.getOrganization());
        try {
            serviceIntent.putExtra(LocationService.EXTRA_LOCATION_SERVICE, 1);
            mXmppConnectionService.startForegroundService(serviceIntent);

        } catch (SecurityException e) {
            Log.w(Config.LOGTAG, "Failed to start foreground service with location", e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_mapping);
        Preference sendOneTimeLocation = (Preference) findPreference("send_location_manually");
        if (sendOneTimeLocation != null) {
            sendOneTimeLocation.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(@NonNull Preference preference) {
                    getLastLocationNewMethod();
                    return false;
                }
            });
        }
    }

    private void stopLocationService() {
        if (mXmppConnectionService == null  || !LocationService.isRunning()){
            return;
        }
        Intent serviceIntent = new Intent(mXmppConnectionService, LocationService.class);
        serviceIntent.putExtra(LocationService.EXTRA_LOCATION_SERVICE, 2);
        mXmppConnectionService.startService(serviceIntent);

    }

    private void getDeviceInfo() {

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deviceId = Settings.Secure.getString(
                        getContext().getContentResolver(),
                        Settings.Secure.ANDROID_ID);

            } else {
                deviceId = Settings.Secure.getString(
                        getContext().getContentResolver(),
                        Settings.Secure.ANDROID_ID);

            }
        } catch (Exception e) {
        }
    }

    private void getLastLocationNewMethod() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestOneTimeLocationPermission();
            return;
        }
        awaitingOneTimeLocationPermission = false;
        launchedAppSettingsForOneTimeLocation = false;
        sendLastLocation();
    }

    private void sendLastLocation() {

        Context context = getContext();
        if (context == null) {
            return;
        }

        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        Toast.makeText(getContext(), R.string.location_provider_unavailable, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (mXmppConnectionService == null || mXmppConnectionService.getAccounts() == null || !(mXmppConnectionService.getAccounts().size() > 0)) {
                        return;
                    }

                    account = mXmppConnectionService.getFirstAccount();
                    new Thread(() -> SendTrackingRequest(location)).start();
                })
                .addOnFailureListener(e -> {
                    Log.w(Config.LOGTAG, "Failed to retrieve current location", e);
                    Toast.makeText(getContext(), R.string.location_update_failed, Toast.LENGTH_SHORT).show();
                });

}

    @Override
    public void onResume() {
        super.onResume();
        final var xmppActivity = requireXmppActivity();
        if (xmppActivity.xmppConnectionService != null) {
            mXmppConnectionService = xmppActivity.xmppConnectionService;
        }
        boolean enabled = getPreferences(getContext()).getBoolean("live_location_tracking", getResources().getBoolean(R.bool.live_location_tracking));
        boolean forced = getPreferences(getContext()).getBoolean("force_tracking", getResources().getBoolean(R.bool.force_tracking));
        boolean hasFineLocationPermission = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (awaitingOneTimeLocationPermission) {
            if (hasFineLocationPermission) {
                awaitingOneTimeLocationPermission = false;
                launchedAppSettingsForOneTimeLocation = false;
                sendLastLocation();
            } else if (launchedAppSettingsForOneTimeLocation) {
                awaitingOneTimeLocationPermission = false;
                launchedAppSettingsForOneTimeLocation = false;
                Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
            }
        }
        if (hasLocationPermissions()) {
            if (awaitingLocationPermissionFromToggle) {
                awaitingLocationPermissionFromToggle = false;
                launchedAppSettingsForLocation = false;
                if (liveLocationTracking != null) {
                    liveLocationTracking.setChecked(true);
                }
            }
            if (enabled | forced) {
                startService();
            } else {
                stopLocationService();
            }
            if (liveLocationTracking != null) {
                liveLocationTracking.setChecked(enabled | forced);
            }
        } else {
            if (awaitingLocationPermissionFromToggle && launchedAppSettingsForLocation) {
                awaitingLocationPermissionFromToggle = false;
                launchedAppSettingsForLocation = false;
                if (liveLocationTracking != null) {
                    liveLocationTracking.setChecked(false);
                }
                Toast.makeText(getContext(), R.string.location_permission_required, Toast.LENGTH_SHORT).show();
            }
            if (!forced){
                stopLocationService();
                if (liveLocationTracking != null) {
                    liveLocationTracking.setChecked(false);
                }
            }

        }
        this.onBackendConnected();
    }

    public void SendTrackingRequest(Location markerPoint) {
        final OkHttpClient client = getOkHttpClient();
        final String sendTrackingUrl = EndPointConfig.SEND_TRACKING_URL;
        if (markerPoint == null) {
            return;
        }

        final String jsonPayload;
        try {
            final String compositeDeviceId =
                    account.getUsername() + "_" + deviceId;

            final JSONObject coordinates = new JSONObject();
            coordinates.put("latitude",  markerPoint.getLatitude());   // double — no injection risk
            coordinates.put("longitude", markerPoint.getLongitude());  // double — no injection risk

            final JSONObject payload = new JSONObject();
            payload.put("coordinates",  coordinates);
            payload.put("timestamp",    parseTimestamp()); // fixed-format timestamp string
            payload.put("device_id",    compositeDeviceId);          // escaped by JSONObject.put()
            payload.put("username",     account.getUsername());       // escaped by JSONObject.put()
            payload.put("organization", account.getOrganization());   // escaped by JSONObject.put()

            jsonPayload = payload.toString();
        } catch (final JSONException e) {
            // JSONException is thrown only for NaN/Infinity doubles, which
            // Location.getLatitude()/getLongitude() never produce.
            Log.e(Config.LOGTAG, "SendTrackingRequest: failed to build JSON payload", e);
            return;
        }
        // ─────────────────────────────────────────────────────────────────────

        final Request request;
        try {
            request = buildJwtJsonRequestBuilder(sendTrackingUrl, jsonPayload).build();
        } catch (final IOException e) {
            runOnUiThread(() ->
                    Toast.makeText(getContext(), R.string.location_update_failed, Toast.LENGTH_SHORT).show());
            return;
        }

        try (final Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                runOnUiThread(() ->
                        Toast.makeText(getContext(), R.string.location_update_sent, Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() ->
                        Toast.makeText(getContext(), R.string.location_update_failed, Toast.LENGTH_SHORT).show());
            }
        } catch (final IOException e) {
            runOnUiThread(() ->
                    Toast.makeText(getContext(), R.string.location_update_failed, Toast.LENGTH_SHORT).show());
        }
    }
    protected boolean hasLocationPermissions() {
        boolean fine = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine && background;
    }

    public String parseTimestamp()  {
        LocalDateTime dateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        return dateTime.format(formatter);

    }


    @Override
    public void onUserRefresh() {
        init();
    }
}
