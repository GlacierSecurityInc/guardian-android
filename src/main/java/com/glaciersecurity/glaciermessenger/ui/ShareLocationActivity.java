package com.glaciersecurity.glaciermessenger.ui;

import android.Manifest;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.snackbar.Snackbar;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityShareLocationBinding;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;
import com.glaciersecurity.glaciermessenger.ui.util.LocationHelper;
import com.glaciersecurity.glaciermessenger.utils.LocationProvider;
import com.google.common.math.DoubleMath;

import java.math.RoundingMode;
import java.util.Map;

public class ShareLocationActivity extends LocationActivity {

    private Snackbar snackBar;
    private ActivityShareLocationBinding binding;
    private Boolean noAskAgain = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_share_location);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setupMapView(binding.map, LocationProvider.getGeoPoint(this));

        this.binding.cancelButton.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        this.snackBar = Snackbar.make(this.binding.snackbarCoordinator, R.string.location_disabled, Snackbar.LENGTH_INDEFINITE);
        this.snackBar.setAction(R.string.enable, view -> handleSnackbarAction());

        this.binding.shareButton.setOnClickListener(this::shareLocation);

        this.binding.fab.setOnClickListener(view -> {
            if (!hasLocationFeature) {
                return;
            }
            if (!hasLocationPermissions()) {
                requestPermissions(REQUEST_CODE_FAB_PRESSED);
                return;
            }
            if (!isLocationEnabled()) {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }
            if (this.myLoc != null) {
                gotoLoc(true);
            }
        });

        updateUi();
    }

    private void shareLocation(final View view) {
        if (!hasLocationPermissions()) {
            requestPermissions(REQUEST_CODE_SNACKBAR_PRESSED);
            return;
        }

        final Intent result = new Intent();
        if (myLoc != null) {
            result.putExtra("latitude", myLoc.getLatitude());
            result.putExtra("longitude", myLoc.getLongitude());
            result.putExtra("altitude", myLoc.getAltitude());
            result.putExtra("accuracy", DoubleMath.roundToInt(myLoc.getAccuracy(), RoundingMode.HALF_UP));
        } else {
            GeoPoint target = null;
            if (mapboxMap != null) {
                final var cameraState = mapboxMap.getCameraState();
                if (cameraState != null) {
                    final var center = cameraState.getCenter();
                    target = new GeoPoint(center.latitude(), center.longitude());
                }
            }
            if (target == null) {
                target = LocationProvider.getGeoPoint(this);
            }
            result.putExtra("latitude", target.getLatitude());
            result.putExtra("longitude", target.getLongitude());
        }
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onLocationPermissionsResult(final int requestCode, final Map<String, Boolean> grantResults) {
        super.onLocationPermissionsResult(requestCode, grantResults);

        final boolean granted = Boolean.TRUE.equals(grantResults.get(Manifest.permission.ACCESS_FINE_LOCATION))
                || Boolean.TRUE.equals(grantResults.get(Manifest.permission.ACCESS_COARSE_LOCATION));

        if (granted) {
            noAskAgain = false;
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            noAskAgain = true;
        }

        if (!noAskAgain && requestCode == REQUEST_CODE_SNACKBAR_PRESSED && !isLocationEnabled() && hasLocationPermissions()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }
        updateUi();
    }

    @Override
    protected void gotoLoc(final boolean setZoomLevel) {
        if (this.myLoc != null) {
            moveCamera(new GeoPoint(this.myLoc), setZoomLevel ? Config.Map.FINAL_ZOOM_LEVEL : null);
        }
    }

    @Override
    protected void setMyLoc(final Location location) {
        this.myLoc = location;
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onLocationChanged(@NonNull final Location location) {
        if (LocationHelper.isBetterLocation(location, this.myLoc)) {
            final Location oldLoc = this.myLoc;
            this.myLoc = location;

            // Don't jump back to the users location if they're not moving (more or less).
            if (oldLoc == null || (mapAtInitialLoc() && this.myLoc.distanceTo(oldLoc) > 1)) {
                gotoLoc();
            }

            updateLocationMarkers();
        }
        updateUi();
    }

    @Override
    public void onStatusChanged(final String provider, final int status, final Bundle extras) {

    }

    @Override
    public void onProviderEnabled(final String provider) {

    }

    @Override
    public void onProviderDisabled(final String provider) {

    }

    private void handleSnackbarAction() {
        if (!hasLocationFeature) {
            return;
        }

        if (!hasLocationPermissions()) {
            if (noAskAgain) {
                openLocationPermissionSettings();
            } else {
                requestPermissions(REQUEST_CODE_SNACKBAR_PRESSED);
            }
            return;
        }

        if (!isLocationEnabled()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        updateUi();
    }

    private void openLocationPermissionSettings() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        final Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        startActivity(intent);
    }

    @Override
    protected void updateUi() {
        final boolean hasPermissions = hasLocationPermissions();
        final boolean canUseLocation = hasLocationFeature && hasPermissions && isLocationEnabled();
        final boolean hasFix = this.myLoc != null;

        if (!hasLocationFeature) {
            this.snackBar.dismiss();
        } else if (!hasPermissions) {
            this.snackBar.setText(R.string.location_permission_required);
            this.snackBar.setAction(noAskAgain ? R.string.open_settings : R.string.enable, view -> handleSnackbarAction());
            this.snackBar.show();
        } else if (!isLocationEnabled()) {
            this.snackBar.setText(R.string.location_disabled);
            this.snackBar.setAction(R.string.enable, view -> handleSnackbarAction());
            this.snackBar.show();
        } else {
            this.snackBar.dismiss();
        }

        runOnUiThread(() -> {
            this.binding.shareButton.setEnabled(hasPermissions);
            this.binding.shareButton.setAlpha(hasPermissions ? 1f : 0.5f);

            if (canUseLocation) {
                this.binding.fab.setVisibility(View.VISIBLE);
                this.binding.fab.setEnabled(hasFix);
                this.binding.fab.setAlpha(hasFix ? 1f : 0.5f);
            } else {
                this.binding.fab.setVisibility(View.GONE);
            }
        });
    }

}
