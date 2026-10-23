package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.utils.VoiceRegistrationStatus.Stage;

import org.junit.Test;

/**
 * The state that stands in for an error message the user never sees. If registration for call
 * invites fails the phone simply stops ringing, so the only way anyone finds out is by reading
 * this back.
 */
public class VoiceRegistrationStatusTest {

    private static final long WHEN = 1753212981000L;

    @Test
    public void startsOutNotRegisteredAndWithNothingToReport() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();

        assertThat(status.isRegistered()).isFalse();
        assertThat(status.getLastFailure()).isNull();
        assertThat(status.describe()).contains("not registered");
    }

    @Test
    public void successMakesTheDeviceRegistered() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();

        status.recordRegistered();

        assertThat(status.isRegistered()).isTrue();
        assertThat(status.describe()).isEqualTo("registered for call invites");
    }

    @Test
    public void aFailureIsKeptWithEnoughDetailToActOn() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();

        status.recordFailure(Stage.TWILIO_REGISTER, 20101, "Invalid Access Token", WHEN);

        assertThat(status.isRegistered()).isFalse();
        assertThat(status.getLastFailure().getStage()).isEqualTo(Stage.TWILIO_REGISTER);
        assertThat(status.getLastFailure().getErrorCode()).isEqualTo(20101);
        assertThat(status.getLastFailure().getMessage()).isEqualTo("Invalid Access Token");
        assertThat(status.getLastFailure().getTimestamp()).isEqualTo(WHEN);
    }

    @Test
    public void describeNamesTheStageAndTheProviderCode() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();

        status.recordFailure(Stage.ACCESS_TOKEN, 200, "voice token response carried no usable JWT", WHEN);

        // A bug report has to be readable without the reader knowing this class exists.
        assertThat(status.describe()).contains("ACCESS_TOKEN");
        assertThat(status.describe()).contains("200");
        assertThat(status.describe()).contains("no usable JWT");
    }

    @Test
    public void aFailureWithNoMessageStillDescribesItself() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();

        status.recordFailure(Stage.FCM_TOKEN, 0, null, WHEN);

        assertThat(status.describe()).contains("FCM_TOKEN");
        assertThat(status.describe()).doesNotContain("null");
    }

    @Test
    public void recoveringClearsTheStaleFailure() {
        // Otherwise a transient failure would make a working device look broken forever.
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();
        status.recordFailure(Stage.FCM_TOKEN, 0, "no network", WHEN);

        status.recordRegistered();

        assertThat(status.isRegistered()).isTrue();
        assertThat(status.getLastFailure()).isNull();
    }

    @Test
    public void aLaterFailureUnregistersAndReplacesTheEarlierOne() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();
        status.recordRegistered();

        status.recordFailure(Stage.TWILIO_REGISTER, 20104, "Access Token expired", WHEN + 1000);

        assertThat(status.isRegistered()).isFalse();
        assertThat(status.getLastFailure().getErrorCode()).isEqualTo(20104);
        assertThat(status.getLastFailure().getTimestamp()).isEqualTo(WHEN + 1000);
    }

    /** The exact shape a bug report carries, so a format change is a deliberate one. */
    @Test
    public void aFailureRendersAsStageCodeAndMessage() {
        final VoiceRegistrationStatus status = new VoiceRegistrationStatus();

        status.recordFailure(Stage.ACCESS_TOKEN, 0, null, WHEN);

        assertThat(status.getLastFailure().toString())
                .isEqualTo("ACCESS_TOKEN failed: 0 (no message)");
    }
}
