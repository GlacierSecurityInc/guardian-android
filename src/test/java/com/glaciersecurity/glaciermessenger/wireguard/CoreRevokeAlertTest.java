package com.glaciersecurity.glaciermessenger.wireguard;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tells a revoke (another VPN app or system settings took the VPN) apart from the app's own
 * disconnects, which both arrive as a GoBackend DOWN, and raises the alert for a revoke.
 */
@RunWith(RobolectricTestRunner.class)
public class CoreRevokeAlertTest {

    private Application application;
    private NotificationManager notificationManager;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        notificationManager = application.getSystemService(NotificationManager.class);
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        CoreRevokeAlert.setCoreScreenVisible(false);
    }

    @After
    public void tearDown() {
        CoreRevokeAlert.clear(application);
    }

    @Test
    public void onlyAnUnannouncedDownOutsideAConnectIsARevoke() {
        assertThat(CoreRevokeAlert.isRevoke(false, false)).isTrue();
        assertThat(CoreRevokeAlert.isRevoke(true, false)).isFalse();
        // Switching profiles: GoBackend downs the old tunnel before bringing up the new one.
        assertThat(CoreRevokeAlert.isRevoke(false, true)).isFalse();
    }

    @Test
    public void expectedTeardownIsConsumedOnce() {
        final PersistentConnectionProperties props = PersistentConnectionProperties.getInstance();
        props.expectTeardown();

        assertThat(props.consumeExpectedTeardown()).isTrue();
        assertThat(props.consumeExpectedTeardown()).isFalse();
    }

    @Test
    public void revokeRecordsProfileAndPostsNotification() {
        CoreRevokeAlert.onRevoked(application, "office");

        assertThat(CoreRevokeAlert.getPendingProfile(application)).isEqualTo("office");
        assertThat(shadowOf(notificationManager).getAllNotifications()).hasSize(1);
        assertThat(notificationManager.getNotificationChannel("core_alerts").getImportance())
                .isEqualTo(NotificationManager.IMPORTANCE_HIGH);
    }

    @Test
    public void revokeWhileCoreScreenIsOpenLeavesItToTheDialog() {
        CoreRevokeAlert.setCoreScreenVisible(true);

        CoreRevokeAlert.onRevoked(application, "office");

        assertThat(CoreRevokeAlert.getPendingProfile(application)).isEqualTo("office");
        assertThat(shadowOf(notificationManager).getAllNotifications()).isEmpty();
    }

    @Test
    public void clearRemovesFlagAndNotification() {
        CoreRevokeAlert.onRevoked(application, "office");

        CoreRevokeAlert.clear(application);

        assertThat(CoreRevokeAlert.getPendingProfile(application)).isNull();
        assertThat(shadowOf(notificationManager).getAllNotifications()).isEmpty();
    }
}
