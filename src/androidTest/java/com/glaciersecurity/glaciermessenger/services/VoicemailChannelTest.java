package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

/**
 * notifyVoicemail posts to {@code voicemail_channel}, and Android drops a notification whose
 * channel does not exist. The channel's creation was lost from initializeChannels() once and only
 * fresh installs noticed, because a channel survives app updates.
 */
@RunWith(AndroidJUnit4.class)
public class VoicemailChannelTest {

    private static final String VOICEMAIL_CHANNEL = "voicemail_channel";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private NotificationManager notificationManager() {
        return context.getSystemService(NotificationManager.class);
    }

    @Before
    public void startFromAFreshInstall() {
        notificationManager().deleteNotificationChannel(VOICEMAIL_CHANNEL);
    }

    private void initializeChannels() throws Exception {
        final XmppConnectionService service = new XmppConnectionService();
        final Method attach =
                ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true);
        attach.invoke(service, context);
        new NotificationService(service).initializeChannels();
    }

    @Test
    public void initializeChannelsRegistersTheVoicemailChannel() throws Exception {
        initializeChannels();

        final NotificationChannel channel =
                notificationManager().getNotificationChannel(VOICEMAIL_CHANNEL);
        assertWithMessage("notifyVoicemail posts to this channel").that(channel).isNotNull();
        assertThat(channel.getImportance()).isEqualTo(NotificationManager.IMPORTANCE_HIGH);
        assertThat(channel.getGroup()).isEqualTo("calls");
    }
}
