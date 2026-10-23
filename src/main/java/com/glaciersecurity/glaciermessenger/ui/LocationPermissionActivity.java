package com.glaciersecurity.glaciermessenger.ui;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.glaciersecurity.glaciermessenger.R;

import java.util.Map;

public class LocationPermissionActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> fineLocationPermissionLauncher;
    private ActivityResultLauncher<String> backgroundLocationPermissionLauncher;
    private boolean requestedFineLocation;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupPermissionLaunchers();
        requestFineLocation();
    }

    private void setupPermissionLaunchers() {
        fineLocationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::handleFineLocationResult
        );

        backgroundLocationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                this::handleBackgroundLocationResult
        );
    }

    private void requestFineLocation() {
        if (requestedFineLocation) {
            return;
        }
        requestedFineLocation = true;
        fineLocationPermissionLauncher.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    private void handleFineLocationResult(Map<String, Boolean> result) {
        boolean fineGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
        boolean coarseGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));

        if (fineGranted || coarseGranted) {
            requestBackgroundLocationIfNeeded();
            return;
        }

        Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish();
            return;
        }
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private void handleBackgroundLocationResult(boolean granted) {
        if (!granted) {
            Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
