package com.glaciersecurity.glaciermessenger.ui;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.ui.util.SettingsUtils;

public abstract class BaseActivity extends AppCompatActivity {
    private Boolean isDynamicColors;

    @Override
    public void onStart() {
        super.onStart();
        final int desiredNightMode = GlacierGuardian.getDesiredNightMode(this);
        if (setDesiredNightMode(desiredNightMode)) {
            return;
        }
        final boolean isDynamicColors = GlacierGuardian.isDynamicColorsDesired();
        setDynamicColors(isDynamicColors);
    }

    @Override
    protected void onResume(){
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
    }

    public void setDynamicColors(final boolean isDynamicColors) {
        if (this.isDynamicColors == null) {
            this.isDynamicColors = isDynamicColors;
        } else {
            if (this.isDynamicColors != isDynamicColors) {
                Log.i(Config.LOGTAG,
                        "Recreating {} because dynamic color setting has changed");
                recreate();
            }
        }
    }

    public boolean setDesiredNightMode(final int desiredNightMode) {
        if (desiredNightMode == AppCompatDelegate.getDefaultNightMode()) {
            return false;
        }
        AppCompatDelegate.setDefaultNightMode(desiredNightMode);
        Log.i(Config.LOGTAG,"Recreating {} because desired night mode has changed");
        recreate();
        return true;
    }
}
