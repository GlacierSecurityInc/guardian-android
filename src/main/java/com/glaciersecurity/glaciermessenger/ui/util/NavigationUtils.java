package com.glaciersecurity.glaciermessenger.ui.util;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.glaciersecurity.glaciermessenger.ui.XmppActivity;

/**
 * Utility helpers for bottom navigation operations.
 */
public final class NavigationUtils {
    private NavigationUtils() {}

    /**
     * Display a red dot over the Security Hub icon when issues are present.
     *
     * @param navigationView the BottomNavigationView containing the menu item
     * @param show           true to show the indicator, false to hide
     */
    public static void updateSecurityHubBadge(@NonNull BottomNavigationView navigationView, boolean show) {
        BadgeDrawable badge = navigationView.getOrCreateBadge(R.id.security_hub_nav);
        badge.setVisible(show);
        if (!show) {
            badge.clearNumber();
        }
    }

    /**
     * Show or hide the phone navigation item depending on sms settings.
     *
     * @param navigationView the BottomNavigationView containing the menu item
     * @param show           true to display the item, false to hide
     */
    public static void updatePhoneVisibility(@NonNull BottomNavigationView navigationView, boolean show) {
        MenuItem phone = navigationView.getMenu().findItem(R.id.phone_nav);
        if (phone != null) {
            phone.setVisible(show);
        }
    }

    /**
     * Display a red dot over the Phone icon when phone-related notifications are present.
     *
     * @param navigationView the BottomNavigationView containing the menu item
     * @param show           true to show the indicator, false to hide
     */
    public static void updatePhoneBadge(@NonNull BottomNavigationView navigationView, boolean show) {
        BadgeDrawable badge = navigationView.getOrCreateBadge(R.id.phone_nav);
        badge.setVisible(show);
        if (!show) {
            badge.clearNumber();
        }
    }

    /**
     * Update phone badge based on active notifications.
     *
     * @param navigationView the BottomNavigationView containing the menu item
     * @param context        the context used to fetch notifications
     */
    public static void updatePhoneBadge(@NonNull BottomNavigationView navigationView, @Nullable Context context) {
        updatePhoneBadge(navigationView, hasPhoneNotifications(context));
    }

    /**
     * Check if there are any phone-related notifications.
     *
     * @param context used to access the NotificationManager
     * @return true if any phone-related notification is active
     */
    public static boolean hasPhoneNotifications(@Nullable Context context) {
        if (context == null) {
            return false;
        }
        if (hasPhoneNotificationEntries(context)) {
            return true;
        }
        if (context instanceof XmppActivity xmppActivity) {
            XmppConnectionService xmppConnectionService = xmppActivity.xmppConnectionService;
            if (xmppConnectionService != null) {
                return xmppConnectionService.hasAnyUnreadVoicemail()
                        || xmppConnectionService.hasAnyUnreadSmsMessages();
            }
        }
        return false;
    }

    private static boolean hasPhoneNotificationEntries(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return false;
        }
        StatusBarNotification[] notifications = notificationManager.getActiveNotifications();
        if (notifications == null) {
            return false;
        }
        for (StatusBarNotification notification : notifications) {
            if (notification == null) {
                continue;
            }
            int id = notification.getId();
            if (id == NotificationService.MISSED_PHONE_CALL_NOTIFICATION_ID
                    || id == NotificationService.VOICEMAIL_NOTIFICATION_ID
                    || id == NotificationService.SMS_NOTIFICATION_ID) {
                return true;
            }
        }
        return false;
    }
}
