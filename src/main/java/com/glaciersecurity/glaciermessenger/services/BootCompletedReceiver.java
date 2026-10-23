package com.glaciersecurity.glaciermessenger.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.receiver.SystemEventReceiver;
import com.glaciersecurity.glaciermessenger.ui.util.SmsUnavailableDialogHelper;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;


public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent != null) {

            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                SmsUnavailableDialogHelper.resetNoticeShown(context);
                DatabaseBackend dbHelper = DatabaseBackend.getInstance(context);
                CoreProfile coreProfile = dbHelper.getRunningCoreProfile();
                if (coreProfile != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                    startLastUsedTunnel(context, coreProfile);
                }

                if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {

                    Intent xmppIntent = new Intent(context, XmppConnectionService.class);
                    xmppIntent.setAction(Intent.ACTION_MY_PACKAGE_REPLACED);
                    xmppIntent.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true);
                    Compatibility.startService(context, xmppIntent);
                }
            }
        }
    }

    public static void startLastUsedTunnel(Context context, CoreProfile coreProfile) {
        PersistentConnectionProperties.getInstance().setBackend(new GoBackend(context));
        Backend backend = PersistentConnectionProperties.getInstance().getBackend();
        try {
            Tunnel tunnel = PersistentConnectionProperties.getInstance().getTunnel(coreProfile.getProfName());
            GoBackend.VpnService.prepare(context);

            AsyncTask.execute(() -> {
                try {
                    backend.setState(tunnel, Tunnel.State.UP, coreProfile.getConfig());
                } catch (Exception ignore) {
                }
            });
        } catch (Exception e) {
        }
    }
}
