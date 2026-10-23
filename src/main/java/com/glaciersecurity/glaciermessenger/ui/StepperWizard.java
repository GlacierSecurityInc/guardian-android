package com.glaciersecurity.glaciermessenger.ui;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.app.NotificationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;

import android.transition.Slide;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityStepperWizardColorBinding;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.DirectConnectionUtils;

import java.util.ArrayList;
import java.util.List;


public class StepperWizard extends ActionBarActivity {

    RelativeLayout relativeLayout;
    private boolean showingPermissionsScreen;
    private OnBackPressedCallback backToWelcomeCallback;

    protected static final int REQUEST_BATTERY_OP = 0x49ff;
    private static final int REQUEST_NOTIFICATION_SETTINGS = 0x52ff;
    private static final int REQUEST_FULL_SCREEN_INTENT = 0x53ff;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityStepperWizardColorBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_stepper_wizard_color);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setupOnboardingToolbar(binding.toolbar, false);

        backToWelcomeCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showWelcomeScreen();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToWelcomeCallback);

        initComponent();

    }

    private void setupOnboardingToolbar(Toolbar toolbar, boolean upNavigation) {
        setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();
        configureActionBar(actionBar, upNavigation);
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
        }
    }

    private void initComponent() {

        Button btn_got_it = findViewById(R.id.btn_got_it);
        btn_got_it.setOnClickListener(v -> {
            if(!hasAllPermissionGranted()){
                showPermissionsScreen();
            } else {
                continueOnboarding();
            }

        });

    }

    private void showWelcomeScreen() {
        showingPermissionsScreen = false;
        setContentView(R.layout.activity_stepper_wizard_color);
        ViewCompat.requestApplyInsets(getWindow().getDecorView());
        setupOnboardingToolbar(findViewById(R.id.toolbar), false);
        backToWelcomeCallback.setEnabled(false);
        initComponent();
    }

    private void showPermissionsScreen() {
        showingPermissionsScreen = true;
        setContentView(R.layout.activity_stepper_permission_ex);
        ViewCompat.requestApplyInsets(getWindow().getDecorView());
        setupOnboardingToolbar(findViewById(R.id.toolbar), true);
        backToWelcomeCallback.setEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            relativeLayout = findViewById(R.id.relativeLayout);
            Slide slide = new Slide();
            slide.setSlideEdge(Gravity.START);
            TransitionManager.beginDelayedTransition(relativeLayout, slide);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home && showingPermissionsScreen) {
            showWelcomeScreen();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public boolean hasAllPermissionGranted() {
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == PackageManager.PERMISSION_GRANTED &&
                hasStoragePermission() &&
                hasNotificationPermission() &&
                hasFullScreenIntentPermission() &&
                hasLocalNetworkPermission();
    }

    private boolean hasLocalNetworkPermission() {
        return Build.VERSION.SDK_INT < DirectConnectionUtils.ACCESS_LOCAL_NETWORK_SDK_INT
                || checkSelfPermission(DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION)
                        == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true;
        }
        final NotificationManager nm = getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }


    public void continuePerm(View view) {
        askForPermissions();
    }

    /**
     * PERMISSIONS - Ask for permissions
     */
    private void askForPermissions() {
        final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;

        final List<String> permissionsList = new ArrayList<>();
        addPermission(permissionsList, Manifest.permission.RECORD_AUDIO);
        addPermission(permissionsList, Manifest.permission.CAMERA);
        addPermission(permissionsList, Manifest.permission.READ_PHONE_STATE);
        addPermission(permissionsList, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        if (!hasNotificationPermission()){
            addPermission(permissionsList, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= DirectConnectionUtils.ACCESS_LOCAL_NETWORK_SDK_INT) {
            addPermission(permissionsList, DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION);
        }
        if (!permissionsList.isEmpty()) {
            requestPermissions(permissionsList.toArray(new String[0]),
                    REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
        } else {
            continueOnboarding();
        }

    }

    /**
     * Walks the remaining onboarding steps in order and starts the login screen once nothing is
     * outstanding. Every step that leaves the app comes back through onActivityResult, which calls
     * this again, so the chain resumes instead of stranding the user on the permissions screen.
     */
    private void continueOnboarding() {
        if (!hasNotificationPermission()) {
            openNotificationSettings();
        } else if (!hasFullScreenIntentPermission()) {
            requestFullScreenIntentPermissionIfNeeded();
        } else if (isBatteryOptimizationDialogNeeded()) {
            openBatteryOptimizationDialogIfNeeded();
        } else {
            startLogin();
        }
    }

    private void startLogin() {
        Intent intent = new Intent(getApplicationContext(), LoginAccountActivity.class);
        startActivity(intent);
    }

    protected boolean hasStoragePermission() {
        return true;
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds") String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    public SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    private boolean isBatteryOptimizationDialogNeeded(){
        return (isOptimizingBattery()
                && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true));
    }
    private void openBatteryOptimizationDialogIfNeeded() {
        if (isBatteryOptimizationDialogNeeded()) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            Uri uri = Uri.parse("package:" + getPackageName());
            intent.setData(uri);
            try {
                startActivityForResult(intent, REQUEST_BATTERY_OP);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.device_does_not_support_battery_op, Toast.LENGTH_SHORT).show();
                setNeverAskForBatteryOptimizationsAgain();
                startLogin();
            }
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivityForResult(intent, REQUEST_NOTIFICATION_SETTINGS);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.unsupported_operation, Toast.LENGTH_SHORT).show();
            onNotificationSettingsClosed();
        }
    }

    private void requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && !nm.canUseFullScreenIntent()) {
            final Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, REQUEST_FULL_SCREEN_INTENT);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.unsupported_operation, Toast.LENGTH_SHORT).show();
                onFullScreenIntentSettingsClosed();
            }
        }
    }

    // Neither settings screen is required, so a user who leaves them without granting anything
    // still moves on to the next step rather than getting the same screen offered forever.
    private void onNotificationSettingsClosed() {
        if (!hasFullScreenIntentPermission()) {
            requestFullScreenIntentPermissionIfNeeded();
        } else {
            onFullScreenIntentSettingsClosed();
        }
    }

    private void onFullScreenIntentSettingsClosed() {
        if (isBatteryOptimizationDialogNeeded()) {
            openBatteryOptimizationDialogIfNeeded();
        } else {
            startLogin();
        }
    }

    protected boolean isOptimizingBattery() {
        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null
                && !pm.isIgnoringBatteryOptimizations(getPackageName());
    }


    private void addPermission(List<String> permissionsList, String permission) {
        if (this.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        continueOnboarding();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_NOTIFICATION_SETTINGS) {
            onNotificationSettingsClosed();
        } else if (requestCode == REQUEST_FULL_SCREEN_INTENT) {
            onFullScreenIntentSettingsClosed();
        } else if (requestCode == REQUEST_BATTERY_OP) {
            startLogin();
        }
    }

}