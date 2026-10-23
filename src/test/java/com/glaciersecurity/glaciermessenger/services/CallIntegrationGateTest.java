package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.services.CallIntegration.AudioDevice;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowBuild;

import java.util.List;
import java.util.UUID;

/**
 * Whether G2G calls run through Telecom. Upstream capped this below API 35 because Android 15
 * stopped delivering call endpoints for outgoing calls; here API 35+ has its own opt-in setting,
 * and while Telecom has sent no endpoints the integration routes with CallAudioState instead of
 * showing no audio switcher at all.
 *
 * <p>Robolectric runs these at API 37, the one level staged for it.
 */
@RunWith(RobolectricTestRunner.class)
public class CallIntegrationGateTest {

    private Application context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf(context.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_TELECOM, true);
        ShadowBuild.setDevice("komodo");
        ShadowBuild.setModel("Pixel 9 Pro");
    }

    private void setCallIntegration(final boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(AppSettings.CALL_INTEGRATION_API35, enabled)
                .commit();
    }

    @Test
    public void offByDefaultFromApi35() {
        assertThat(CallIntegration.selfManagedAvailable(context)).isTrue();
        assertThat(new AppSettings(context).isCallIntegration()).isFalse();
        assertThat(CallIntegration.selfManaged(context)).isFalse();
    }

    @Test
    public void theSettingTurnsItOnFromApi35() {
        setCallIntegration(true);
        assertThat(CallIntegration.selfManaged(context)).isTrue();
    }

    @Test
    public void aTrueCarriedOverFromBeforeApi35IsNotAnOptIn() {
        // what the switch persists on Android 14, then an upgrade or a restore onto 15+
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(AppSettings.CALL_INTEGRATION, true)
                .commit();
        assertThat(CallIntegration.selfManaged(context)).isFalse();
    }

    @Test
    public void unavailableWithoutTelecomEvenWhenEnabled() {
        setCallIntegration(true);
        shadowOf(context.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_TELECOM, false);
        assertThat(CallIntegration.selfManagedAvailable(context)).isFalse();
        assertThat(CallIntegration.selfManaged(context)).isFalse();
    }

    @Test
    public void huaweiHwags2IsExcluded() {
        ShadowBuild.setDevice("HWAGS2");
        assertThat(CallIntegration.selfManagedAvailable(context)).isFalse();
    }

    @Test
    public void sailfishAppSupportIsExcluded() {
        ShadowBuild.setModel("Xperia 10 III (AppSupport)");
        assertThat(CallIntegration.selfManagedAvailable(context)).isFalse();
    }

    private static final CallAudioState BLUETOOTH_STATE =
            new CallAudioState(
                    false,
                    CallAudioState.ROUTE_BLUETOOTH,
                    CallAudioState.ROUTE_BLUETOOTH
                            | CallAudioState.ROUTE_EARPIECE
                            | CallAudioState.ROUTE_SPEAKER);

    private static CallEndpoint endpoint(final int type) {
        return new CallEndpoint("endpoint " + type, type, new ParcelUuid(UUID.randomUUID()));
    }

    @Test
    public void withoutEndpointsCallAudioStateDrivesTheSwitcher() {
        setCallIntegration(true);
        final CallIntegration integration = new CallIntegration(context);
        final CallIntegration.Callback callback = mock(CallIntegration.Callback.class);
        integration.setCallback(callback);

        integration.onCallAudioStateChanged(BLUETOOTH_STATE);

        verify(callback)
                .onAudioDeviceChanged(
                        AudioDevice.BLUETOOTH,
                        ImmutableSet.of(
                                AudioDevice.BLUETOOTH,
                                AudioDevice.EARPIECE,
                                AudioDevice.SPEAKER_PHONE));
    }

    @Test
    public void onceEndpointsArriveCallAudioStateIsIgnoredAgain() {
        setCallIntegration(true);
        final CallIntegration integration = new CallIntegration(context);
        final CallIntegration.Callback callback = mock(CallIntegration.Callback.class);
        integration.setCallback(callback);
        integration.onAvailableCallEndpointsChanged(
                List.of(endpoint(CallEndpoint.TYPE_EARPIECE), endpoint(CallEndpoint.TYPE_SPEAKER)));

        integration.onCallAudioStateChanged(BLUETOOTH_STATE);

        verify(callback, never()).onAudioDeviceChanged(eq(AudioDevice.BLUETOOTH), any());
        assertThat(integration.getAudioDevices())
                .containsExactly(AudioDevice.EARPIECE, AudioDevice.SPEAKER_PHONE);
    }
}
