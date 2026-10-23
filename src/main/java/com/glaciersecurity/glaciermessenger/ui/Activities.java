package com.glaciersecurity.glaciermessenger.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;

public final class Activities {

    private Activities() {}

    public static void setStatusAndNavigationBarColors(final ComponentActivity activity, final View view) {
        // Edge-to-edge on every API level, so screens look the same either side of Android 15
        // (where targetSdk 35+ forces it). Explicit light/dark styles rather than auto keep the
        // bars fully transparent with no 3-button-nav scrim, matching AppTheme on API 30-34.
        final SystemBarStyle style =
                isLightMode(activity)
                        ? SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        : SystemBarStyle.dark(Color.TRANSPARENT);
        EdgeToEdge.enable(activity, style, style);
        // Content now runs under the bars, so the root must paint that area itself.
        if (view.getBackground() == null) {
            view.setBackgroundColor(getAppBackgroundColor(activity));
        }
    }

    private static int getAppBackgroundColor(final Context context) {
        final TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                return context.getColor(typedValue.resourceId);
            }
            return typedValue.data;
        }
        return isLightMode(context) ? Color.WHITE : Color.BLACK;
    }

    private static boolean isLightMode(final Context context) {
        final int nightModeFlags =
                context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags != Configuration.UI_MODE_NIGHT_YES;
    }

    public static boolean isNightMode(final Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
