package com.glaciersecurity.glaciermessenger.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.common.base.Strings;
import com.google.common.primitives.Doubles;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityShowLocationBinding;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;
import com.glaciersecurity.glaciermessenger.ui.util.LocationHelper;
import com.glaciersecurity.glaciermessenger.ui.util.UriHelper;
import com.glaciersecurity.glaciermessenger.utils.GeoHelper;
import com.glaciersecurity.glaciermessenger.utils.LocationProvider;

import java.util.Map;

public class ShowLocationActivity extends LocationActivity {

    private GeoPoint loc = LocationProvider.FALLBACK;
    private ActivityShowLocationBinding binding;

    private Uri createGeoUri() {
        return Uri.parse("geo:" + this.loc.getLatitude() + "," + this.loc.getLongitude());
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_show_location);
        setSupportActionBar(binding.toolbar);

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());

        configureActionBar(getSupportActionBar());
        setupMapView(this.binding.map, this.loc);

        this.binding.fab.setOnClickListener(view -> startNavigation());


        this.binding.fabCenterTarget.setOnClickListener(view -> gotoLoc(true));

        this.binding.fabCenterMyLocation.setOnClickListener(view -> {
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
                moveCamera(new GeoPoint(this.myLoc), Config.Map.FINAL_ZOOM_LEVEL);
            }
        });

        final Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        switch (Strings.nullToEmpty(action)) {
            case "eu.siacs.conversations.location.show":
                if (intent.hasExtra("longitude") && intent.hasExtra("latitude")) {
                    final double longitude = intent.getDoubleExtra("longitude", 0);
                    final double latitude = intent.getDoubleExtra("latitude", 0);
                    this.loc = new GeoPoint(latitude, longitude);
                }
                break;
            case Intent.ACTION_VIEW:
                final Uri uri = intent.getData();
                if (uri == null) {
                    break;
                }
                final GeoPoint point;
                try {
                    point = GeoHelper.parseGeoPoint(uri);
                } catch (final Exception e) {
                    break;
                }
                this.loc = point;
                final Map<String, String> query = UriHelper.parseQueryString(uri.getQuery());
                final String z = query.get("z");
                final Double zoom = Strings.isNullOrEmpty(z) ? null : Doubles.tryParse(z);
                if (zoom != null) {
                    moveCamera(this.loc, zoom);
                }
                break;
        }
        moveCamera(this.loc, null);
        updateLocationMarkers();
        updateUi();
    }

    @Override
    protected void gotoLoc(final boolean setZoomLevel) {
        if (this.loc != null) {
            moveCamera(this.loc, setZoomLevel ? Config.Map.FINAL_ZOOM_LEVEL : null);
        }
    }

    @Override
    protected void onLocationPermissionsResult(final int requestCode, final Map<String, Boolean> grantResults) {
        super.onLocationPermissionsResult(requestCode, grantResults);
        updateUi();
    }

    @Override
    protected void setMyLoc(final Location location) {
        this.myLoc = location;
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_show_location, menu);
        updateUi();
        return true;
    }

    @Override
    protected void updateLocationMarkers() {
        super.updateLocationMarkers();
        if (this.loc != null) {
            addMarkerView(R.layout.view_map_marker, this.loc);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final var itemId = item.getItemId();
        if (itemId == R.id.action_copy_location) {
            final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            final ClipData clip = ClipData.newPlainText("location", createGeoUri().toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.url_copied_to_clipboard, Toast.LENGTH_SHORT).show();
            return true;
        } else if (itemId == R.id.action_share_location) {
            final Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_TEXT, createGeoUri().toString());
            shareIntent.setType("text/plain");
            try {
                startActivity(Intent.createChooser(shareIntent, getText(R.string.share_with)));
            } catch (final ActivityNotFoundException e) {
                // This should happen only on faulty androids because normally chooser is always
                // available
                Toast.makeText(this, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT)
                        .show();
            }
            return true;
        } else if (itemId == R.id.action_open_with) {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(createGeoUri());
            try {
                startActivity(Intent.createChooser(intent, getText(R.string.open_with)));
            } catch (final ActivityNotFoundException e) {
                // This should happen only on faulty androids because normally chooser is always
                // available
                Toast.makeText(this, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT)
                        .show();
            }
            return true;

        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void startNavigation() {
        final Intent intent = getStartNavigationIntent();
        startActivity(intent);
    }

    private Intent getStartNavigationIntent() {
        return new Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                        "google.navigation:q="
                                + this.loc.getLatitude()
                                + ","
                                + this.loc.getLongitude()));
    }

    @Override
    protected void updateUi() {
        final Intent intent = getStartNavigationIntent();
        final ActivityInfo activityInfo = intent.resolveActivityInfo(getPackageManager(), 0);
        this.binding.fab.setVisibility(activityInfo == null ? View.GONE : View.VISIBLE);
        final boolean hasSharedLocation = this.loc != null;
        final boolean hasPermissions = hasLocationPermissions();
        final boolean canUseMyLocation = hasLocationFeature && hasPermissions && isLocationEnabled();
        final boolean hasFix = this.myLoc != null;

        runOnUiThread(() -> {
            this.binding.fabCenterTarget.setEnabled(hasSharedLocation);
            this.binding.fabCenterTarget.setAlpha(hasSharedLocation ? 1f : 0.5f);

            if (canUseMyLocation) {
                this.binding.fabCenterMyLocation.setVisibility(View.VISIBLE);
                this.binding.fabCenterMyLocation.setEnabled(hasFix);
                this.binding.fabCenterMyLocation.setAlpha(hasFix ? 1f : 0.5f);
            } else {
                this.binding.fabCenterMyLocation.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onLocationChanged(@NonNull final Location location) {
        if (LocationHelper.isBetterLocation(location, this.myLoc)) {
            this.myLoc = location;
            updateLocationMarkers();
        }
        updateUi();
    }

    @Override
    public void onStatusChanged(final String provider, final int status, final Bundle extras) {}

    @Override
    public void onProviderEnabled(@NonNull final String provider) {}

    @Override
    public void onProviderDisabled(@NonNull final String provider) {}
}
