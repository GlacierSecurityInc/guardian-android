package com.glaciersecurity.glaciermessenger.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.BoolRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.geo.GeoPoint;
import com.glaciersecurity.glaciermessenger.ui.util.LocationHelper;
import com.mapbox.geojson.Point;
import com.mapbox.maps.AnnotatedFeature;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.CameraState;
import com.mapbox.maps.MapView;
import com.mapbox.maps.MapboxMap;
import com.mapbox.maps.Style;
import com.mapbox.maps.ViewAnnotationOptions;
import com.mapbox.maps.viewannotation.ViewAnnotationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class LocationActivity extends ActionBarActivity implements LocationListener {
        protected LocationManager locationManager;
        protected boolean hasLocationFeature;

        public static final int REQUEST_CODE_CREATE = 0;
        public static final int REQUEST_CODE_FAB_PRESSED = 1;
        public static final int REQUEST_CODE_SNACKBAR_PRESSED = 2;

        protected static final String KEY_LOCATION = "loc";
        protected static final String KEY_ZOOM_LEVEL = "zoom";

        protected Location myLoc = null;
        protected MapView mapView = null;
        protected MapboxMap mapboxMap = null;

        private ViewAnnotationManager viewAnnotationManager = null;
        private final List<View> markerAnnotations = new ArrayList<>();
        private boolean mapStyleLoaded = false;
        private GeoPoint pendingCameraCenter = null;
        private Double pendingCameraZoom = null;

        private final ActivityResultLauncher<String[]> locationPermissionLauncher = registerForActivityResult(
                        new ActivityResultContracts.RequestMultiplePermissions(),
                        this::handleLocationPermissionResult);
        private int pendingPermissionRequestCode = REQUEST_CODE_CREATE;

        protected void clearMarkers() {
                if (viewAnnotationManager == null) {
                        markerAnnotations.clear();
                        return;
                }
                for (final View view : markerAnnotations) {
                        viewAnnotationManager.removeViewAnnotation(view);
                }
                markerAnnotations.clear();
        }

        protected void updateLocationMarkers() {
                clearMarkers();
                if (myLoc != null) {
                        addMarkerView(R.layout.view_map_my_location, new GeoPoint(myLoc));
                }
        }

        @Override
        protected void onCreate(final Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                final Context ctx = getApplicationContext();

                final PackageManager packageManager = ctx.getPackageManager();
                hasLocationFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) ||
                                packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ||
                                packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
                this.locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

                if (savedInstanceState == null) {
                        requestPermissions(REQUEST_CODE_CREATE);
                }
        }

        @Override
        protected void onSaveInstanceState(@NonNull final Bundle outState) {
                super.onSaveInstanceState(outState);
                final GeoPoint center = getCameraCenterForSaving();
                final Double zoom = getCameraZoomForSaving();
                if (center != null) {
                        outState.putParcelable(KEY_LOCATION, center);
                }
                if (zoom != null) {
                        outState.putDouble(KEY_ZOOM_LEVEL, zoom);
                }
        }

        @Override
        protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
                super.onRestoreInstanceState(savedInstanceState);

                if (savedInstanceState.containsKey(KEY_LOCATION)) {
                        pendingCameraCenter = savedInstanceState.getParcelable(KEY_LOCATION);
                }
                if (savedInstanceState.containsKey(KEY_ZOOM_LEVEL)) {
                        pendingCameraZoom = savedInstanceState.getDouble(KEY_ZOOM_LEVEL);
                }
                applyPendingCamera();
        }

        protected void setupMapView(final MapView mapView, final GeoPoint pos) {
                this.mapView = mapView;
                this.mapboxMap = mapView.getMapboxMap();
                this.viewAnnotationManager = mapView.getViewAnnotationManager();

                if (pos != null) {
                        moveCamera(pos, Config.Map.INITIAL_ZOOM_LEVEL);
                }

                this.mapboxMap.loadStyleUri(Style.DARK, style -> {
                        mapStyleLoaded = true;
                        applyPendingCamera();
                        updateLocationMarkers();
                });
        }

        protected View addMarkerView(@LayoutRes final int layoutRes, final GeoPoint point) {
                if (!mapStyleLoaded || viewAnnotationManager == null || mapView == null || point == null) {
                        return null;
                }
                final View view = LayoutInflater.from(this).inflate(layoutRes, mapView, false);
                final ViewAnnotationOptions options = new ViewAnnotationOptions.Builder()
                        .annotatedFeature(new AnnotatedFeature(Point.fromLngLat(point.getLongitude(), point.getLatitude())))
                                .allowOverlap(true)
                                .build();
                viewAnnotationManager.addViewAnnotation(view, options);
                markerAnnotations.add(view);
                return view;
        }

        protected void gotoLoc() {
                gotoLoc(mapAtInitialLoc());
        }

        protected abstract void gotoLoc(final boolean setZoomLevel);

        protected abstract void setMyLoc(final Location location);

        protected void requestLocationUpdates() {
                if (!hasLocationFeature || locationManager == null) {
                        return;
                }

                final Location lastKnownLocationGps;
                final Location lastKnownLocationNetwork;

                try {
                        if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                                lastKnownLocationGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

                                if (lastKnownLocationGps != null) {
                                        setMyLoc(lastKnownLocationGps);
                                }
                                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, Config.Map.LOCATION_FIX_TIME_DELTA,
                                                Config.Map.LOCATION_FIX_SPACE_DELTA, this);
                        } else {
                                lastKnownLocationGps = null;
                        }

                        if (locationManager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                                lastKnownLocationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                                if (lastKnownLocationNetwork != null && LocationHelper.isBetterLocation(lastKnownLocationNetwork,
                                                lastKnownLocationGps)) {
                                        setMyLoc(lastKnownLocationNetwork);
                                }
                                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Config.Map.LOCATION_FIX_TIME_DELTA,
                                                Config.Map.LOCATION_FIX_SPACE_DELTA, this);
                        }

                        if (locationManager.getAllProviders().contains(LocationManager.PASSIVE_PROVIDER)) {
                                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, this);
                        }
                } catch (final SecurityException ignored) {
                }
        }

        protected void pauseLocationUpdates() throws SecurityException {
                if (locationManager != null) {
                        locationManager.removeUpdates(this);
                }
        }

        @Override
        public boolean onOptionsItemSelected(final MenuItem item) {
                if (item.getItemId() == android.R.id.home) {
                        finish();
                        return true;
                }
                return super.onOptionsItemSelected(item);
        }

        @Override
        public void onStart() {
                super.onStart();
                if (mapView != null) {
                        mapView.onStart();
                }
        }

        @Override
        protected void onResume() {
                super.onResume();
                this.setMyLoc(null);
                requestLocationUpdates();
                updateLocationMarkers();
                updateUi();

                if (mapAtInitialLoc()) {
                        gotoLoc();
                }
        }

        @Override
        protected void onPause() {
                super.onPause();
                try {
                        pauseLocationUpdates();
                } catch (final SecurityException ignored) {
                }
        }

        @Override
        protected void onStop() {
                super.onStop();
                if (mapView != null) {
                        mapView.onStop();
                }
        }

        @Override
        protected void onDestroy() {
                if (mapView != null) {
                        mapView.onDestroy();
                }
                super.onDestroy();
        }

        protected abstract void updateUi();

        protected boolean mapAtInitialLoc() {
                final Double zoom;
                if (mapStyleLoaded && mapboxMap != null) {
                        final CameraState state = mapboxMap.getCameraState();
                        zoom = state != null ? state.getZoom() : null;
                } else {
                        zoom = pendingCameraZoom;
                }
                if (zoom == null) {
                        return true;
                }
                return Math.abs(zoom - Config.Map.INITIAL_ZOOM_LEVEL) < 0.01;
        }

        protected boolean hasLocationPermissions() {
                return (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);
        }

        protected void requestPermissions(final int requestCode) {
                if (!hasLocationPermissions()) {
                        pendingPermissionRequestCode = requestCode;
                        locationPermissionLauncher.launch(new String[]{
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        });
                }
        }

        private void handleLocationPermissionResult(final Map<String, Boolean> grantResults) {
                onLocationPermissionsResult(pendingPermissionRequestCode, grantResults);
        }

        protected void onLocationPermissionsResult(final int requestCode, final Map<String, Boolean> grantResults) {
                if (Boolean.TRUE.equals(grantResults.get(Manifest.permission.ACCESS_FINE_LOCATION))
                                || Boolean.TRUE.equals(grantResults.get(Manifest.permission.ACCESS_COARSE_LOCATION))) {
                        requestLocationUpdates();
                }
        }

        protected SharedPreferences getPreferences() {
                return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        }

        protected boolean getBooleanPreference(final String name, @BoolRes final int res) {
                return getPreferences().getBoolean(name, getResources().getBoolean(res));
        }

        protected boolean isLocationEnabled() {
                try {
                        final int locationMode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE);
                        return locationMode != Settings.Secure.LOCATION_MODE_OFF;
                } catch (final Settings.SettingNotFoundException e) {
                        return false;
                }
        }

        protected void moveCamera(final GeoPoint point, final Double zoom) {
                if (point == null) {
                        return;
                }

                if (!mapStyleLoaded || mapboxMap == null) {
                        pendingCameraCenter = point;
                        if (zoom != null) {
                                pendingCameraZoom = zoom;
                        }
                        return;
                }

                applyCamera(point, zoom);
        }

        private void applyPendingCamera() {
                if (!mapStyleLoaded || mapboxMap == null || pendingCameraCenter == null) {
                        return;
                }
                final GeoPoint center = pendingCameraCenter;
                final Double zoom = pendingCameraZoom;
                pendingCameraCenter = null;
                pendingCameraZoom = null;
                applyCamera(center, zoom);
        }

        private void applyCamera(final GeoPoint center, final Double zoom) {
                final CameraOptions.Builder builder = new CameraOptions.Builder()
                                .center(Point.fromLngLat(center.getLongitude(), center.getLatitude()));
                if (zoom != null) {
                        builder.zoom(zoom);
                }
                mapboxMap.setCamera(builder.build());
        }

        private GeoPoint getCameraCenterForSaving() {
                if (mapStyleLoaded && mapboxMap != null) {
                        final CameraState state = mapboxMap.getCameraState();
                        if (state != null) {
                                final Point center = state.getCenter();
                                return new GeoPoint(center.latitude(), center.longitude());
                        }
                }
                return pendingCameraCenter;
        }

        private Double getCameraZoomForSaving() {
                if (mapStyleLoaded && mapboxMap != null) {
                        final CameraState state = mapboxMap.getCameraState();
                        if (state != null) {
                                return state.getZoom();
                        }
                }
                return pendingCameraZoom;
        }
}
