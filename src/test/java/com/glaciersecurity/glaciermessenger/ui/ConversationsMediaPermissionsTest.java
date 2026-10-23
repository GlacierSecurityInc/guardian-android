package com.glaciersecurity.glaciermessenger.ui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import com.glaciersecurity.glaciermessenger.databinding.ActivityConversationsBinding;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.DirectConnectionUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the real startup/refresh permission entry points without starting app services. */
@RunWith(RobolectricTestRunner.class)
public class ConversationsMediaPermissionsTest {

    private Application application;
    private XmppConnectionService service;
    private ConversationsActivity activity;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS,
                DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION,
                Manifest.permission.READ_MEDIA_AUDIO);
        shadowOf(application).denyPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        // A bare service: no database, no network. Media access used to be requested off the
        // startup scan's stranded-file count; nothing may request it now, whatever the scan.
        service = new XmppConnectionService();
        activity = newActivity();
    }

    private ConversationsActivity newActivity() {
        // Attach a real activity, but do not call onCreate: that would start authentication,
        // database and network work unrelated to this permission regression.
        final ConversationsActivity result =
                spy(Robolectric.buildActivity(ConversationsActivity.class).get());
        result.xmppConnectionService = service;
        ReflectionHelpers.setField(result, "binding", mock(ActivityConversationsBinding.class));
        doNothing().when(result).invalidateOptionsMenu();
        doReturn(null).when(result).getSupportActionBar();
        doNothing().when(result).requestPermissions(any(String[].class), anyInt());
        return result;
    }

    private void requestStartupPermissions(final ConversationsActivity target) {
        ReflectionHelpers.callInstanceMethod(target, "requestRuntimePermissionsIfNeeded");
    }

    private void assertNoPermissionRequest(final ConversationsActivity target) {
        verify(target, never()).requestPermissions(any(String[].class), anyInt());
    }

    @Test
    public void selectedPhotoAccessDoesNotReopenPickerOnLaunchOrRefresh() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        requestStartupPermissions(activity);
        activity.refreshUiReal();
        activity.refreshUiReal();

        assertNoPermissionRequest(activity);
    }

    @Test
    public void deniedMediaAccessDoesNotPromptOnLaunchOrRefresh() {
        shadowOf(application).denyPermissions(Manifest.permission.READ_MEDIA_AUDIO);

        requestStartupPermissions(activity);
        activity.refreshUiReal();

        assertNoPermissionRequest(activity);
    }

    @Test
    public void fullMediaAccessDoesNotPromptOnLaunchOrRefresh() {
        shadowOf(application).grantPermissions(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);

        requestStartupPermissions(activity);
        activity.refreshUiReal();

        assertNoPermissionRequest(activity);
    }

    @Test
    public void recreatingActivityDoesNotRequestMoreThanSelectedPhotoAccess() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        requestStartupPermissions(activity);
        activity.refreshUiReal();

        final ConversationsActivity reopened = newActivity();
        requestStartupPermissions(reopened);
        reopened.refreshUiReal();

        assertNoPermissionRequest(activity);
        assertNoPermissionRequest(reopened);
    }

    @Test
    public void notificationResultDoesNotChainIntoMediaRequest() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        activity.onRequestPermissionsResult(
                XmppActivity.REQUEST_POST_NOTIFICATION,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                new int[] {PackageManager.PERMISSION_GRANTED});

        assertNoPermissionRequest(activity);
    }

    @Test
    public void localNetworkResultDoesNotChainIntoMediaRequest() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);

        activity.onRequestPermissionsResult(
                XmppActivity.REQUEST_LOCAL_NETWORK_ACCESS,
                new String[] {DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION},
                new int[] {PackageManager.PERMISSION_GRANTED});

        assertNoPermissionRequest(activity);
    }

    @Test
    public void notificationPermissionStillRequestsOnlyNotifications() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);

        requestStartupPermissions(activity);

        verify(activity).requestPermissions(
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                XmppActivity.REQUEST_POST_NOTIFICATION);
    }

    @Test
    public void localNetworkPermissionStillRequestsOnlyLocalNetwork() {
        shadowOf(application).denyPermissions(DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION);

        requestStartupPermissions(activity);

        verify(activity).requestPermissions(
                new String[] {DirectConnectionUtils.ACCESS_LOCAL_NETWORK_PERMISSION},
                XmppActivity.REQUEST_LOCAL_NETWORK_ACCESS);
    }
}
