package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.common.base.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Changing the incoming-call ringtone.
 *
 * <p>Android will not let an app change a notification channel's sound after the channel is
 * created -- the setter is accepted and then ignored, silently. So "change the ringtone" is
 * implemented as "create a new channel and delete the old one", with an iteration counter in the
 * id ({@code incoming_calls_channel#0}, {@code #1}, ...). Nothing about that is checkable off a
 * device: it depends on the real {@code NotificationManager} refusing the update, which is the
 * exact behaviour a fake would not reproduce.
 *
 * <p>The two ways this breaks are both quiet. If the old channel is not deleted, the user
 * accumulates a channel per ringtone change in system settings, all named the same thing. If the
 * iteration is not incremented, the new channel reuses an existing id, Android treats it as an
 * update, and the ringtone simply does not change -- which is the original bug.
 */
@RunWith(AndroidJUnit4.class)
public class IncomingCallChannelTest {

    private static final String PREFIX = "incoming_calls_channel#";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private NotificationManager notificationManager() {
        return context.getSystemService(NotificationManager.class);
    }

    private List<String> incomingCallChannelIds() {
        final List<String> ids = new ArrayList<>();
        for (final NotificationChannel channel : notificationManager().getNotificationChannels()) {
            if (channel.getId() != null && channel.getId().startsWith(PREFIX)) {
                ids.add(channel.getId());
            }
        }
        return ids;
    }

    /**
     * Notification channels are owned by the system, not by the app's data directory, so
     * {@code pm clear} between tests does not remove them and they would pile up across a soak
     * run. Clear them at both ends so each test sees the same starting point whatever ran before.
     */
    private void deleteIncomingCallChannels() {
        for (final String id : incomingCallChannelIds()) {
            notificationManager().deleteNotificationChannel(id);
        }
    }

    @Before
    public void clearChannelsBefore() {
        deleteIncomingCallChannels();
        // createIncomingCallChannel puts the channel in the "calls" group, and Android throws
        // IllegalArgumentException if the group does not exist. In the app the group is created
        // by NotificationService.initializeChannels() when the connection service starts, long
        // before a user can reach the ringtone picker -- but `pm clear` between tests removes the
        // groups along with the channels, so the precondition has to be re-established here.
        notificationManager()
                .createNotificationChannelGroup(new NotificationChannelGroup("calls", "Calls"));
    }

    @After
    public void clearChannelsAfter() {
        deleteIncomingCallChannels();
    }

    private static Uri defaultRingtone() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    private static Uri notificationSound() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    @Test
    public void withNoChannelYetThereIsNoIteration() {
        final Optional<Integer> iteration =
                NotificationService.getCurrentIncomingCallChannelIteration(context);

        assertThat(iteration.isPresent()).isFalse();
    }

    @Test
    public void theFirstChannelIsIterationZero() {
        NotificationService.recreateIncomingCallChannel(context, defaultRingtone());

        assertThat(NotificationService.getCurrentIncomingCallChannelIteration(context).get())
                .isEqualTo(0);
        assertThat(incomingCallChannelIds()).containsExactly(PREFIX + "0");
    }

    @Test
    public void changingTheRingtoneMovesToTheNextIteration() {
        NotificationService.recreateIncomingCallChannel(context, defaultRingtone());

        NotificationService.recreateIncomingCallChannel(context, notificationSound());

        assertThat(NotificationService.getCurrentIncomingCallChannelIteration(context).get())
                .isEqualTo(1);
    }

    @Test
    public void theSupersededChannelIsDeletedRatherThanLeftInSettings() {
        NotificationService.recreateIncomingCallChannel(context, defaultRingtone());
        NotificationService.recreateIncomingCallChannel(context, notificationSound());

        assertWithMessage("one channel per ringtone change would accumulate in system settings")
                .that(incomingCallChannelIds())
                .containsExactly(PREFIX + "1");
    }

    @Test
    public void theNewChannelActuallyCarriesTheNewSound() {
        NotificationService.recreateIncomingCallChannel(context, defaultRingtone());

        NotificationService.recreateIncomingCallChannel(context, notificationSound());

        final NotificationChannel channel =
                notificationManager().getNotificationChannel(PREFIX + "1");
        assertThat(channel).isNotNull();
        assertWithMessage("the whole reason for recreating the channel")
                .that(channel.getSound())
                .isEqualTo(notificationSound());
    }

    @Test
    public void theIterationKeepsClimbingAcrossRepeatedChanges() {
        for (int i = 0; i < 4; i++) {
            NotificationService.recreateIncomingCallChannel(
                    context, i % 2 == 0 ? defaultRingtone() : notificationSound());
        }

        assertThat(NotificationService.getCurrentIncomingCallChannelIteration(context).get())
                .isEqualTo(3);
        assertThat(incomingCallChannelIds()).containsExactly(PREFIX + "3");
    }

    @Test
    public void theChannelIsHighImportanceSoACallCanRing() {
        NotificationService.recreateIncomingCallChannel(context, defaultRingtone());

        final NotificationChannel channel =
                notificationManager().getNotificationChannel(PREFIX + "0");
        assertThat(channel.getImportance()).isEqualTo(NotificationManager.IMPORTANCE_HIGH);
        assertWithMessage("an incoming call must not also raise a badge")
                .that(channel.canShowBadge())
                .isFalse();
    }
}
