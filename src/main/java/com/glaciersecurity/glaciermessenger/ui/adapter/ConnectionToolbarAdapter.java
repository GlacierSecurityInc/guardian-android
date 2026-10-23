package com.glaciersecurity.glaciermessenger.ui.adapter;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import androidx.core.content.ContextCompat;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Presence;
import com.glaciersecurity.glaciermessenger.entities.PresenceTemplate;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.ChangeStatusActivity;
import com.glaciersecurity.glaciermessenger.ui.util.SaslDowngrade;
import com.glaciersecurity.glaciermessenger.utils.Log;

public class ConnectionToolbarAdapter {
    private final Context context;
    private final View toolbar;
    private final XmppConnectionService xmppConnectionService;
    private final boolean networkOnly;

    public ConnectionToolbarAdapter(Context context, View view, XmppConnectionService xmppConnectionService) {
        this(context, view, xmppConnectionService, false);
    }

    public ConnectionToolbarAdapter(Context context, View view, XmppConnectionService xmppConnectionService, boolean networkOnly) {
        this.context = context;
        this.toolbar = view;
        this.xmppConnectionService = xmppConnectionService;
        this.networkOnly = networkOnly;
    }


    public void onClick() {
        TextView networkStatus = toolbar.findViewById(R.id.network_status);
        networkStatus.setCompoundDrawables(null, null, null, null);
        if (networkOnly) {
            networkStatus.setText(context.getString(R.string.refreshing_network));
            updateOfflineStatusBar();
            return;
        }
        if (xmppConnectionService == null || xmppConnectionService.getAccounts().isEmpty()) {
            Log.w(Config.LOGTAG, "ConnectionToolbarAdapter.onClick invoked without an active XmppConnectionService");
            updateOfflineStatusBar();
            return;
        }

        String previousNetworkState = networkStatus.getText().toString();
        final Account account = xmppConnectionService.getAccounts().get(0);
        if (account != null) {
            // Same gesture as any other reconnect, different mechanics: a plain retry cannot fix
            // this one, so renew the credential instead of re-running the attempt that failed.
            if (SaslDowngrade.isBlocked(account)) {
                networkStatus.setText(context.getString(R.string.refreshing_connection));
                xmppConnectionService.resyncAccountCredentials(account);
                updateOfflineStatusBar();
                return;
            }
            /*
             Case 1a. PRESENCE -> OFFLINE ) "_____: tap to Reconnect"
             -> refresh to "Attempting to Connect"
             -> presence is offline, need to reenable account
             -> change presence to online
              */
            if (previousNetworkState.contains(context.getString(R.string.status_tap_to_enable))) {
                networkStatus.setText(context.getString(R.string.refreshing_connection));
                if (account.getPresenceStatus().equals(Presence.Status.OFFLINE)){
                    xmppConnectionService.enableAccount(account);
                }
                PresenceTemplate template = new PresenceTemplate(Presence.Status.ONLINE, account.getPresenceStatusMessage());
                xmppConnectionService.changeStatus(account, template, null);

            }
                /*
                 Case 1b. PRESENCE) "_____: tap to set to Available"
                 -> refresh to "Changing status to Available"
                 -> if was offline need to reenable account
                 -> change presence to online
                  */
            else if (previousNetworkState.contains(context.getString(R.string.status_tap_to_available))) {
                networkStatus.setText(context.getString(R.string.refreshing_status));
                changePresence();

                 /*
                 Case 2. ACCOUNT) "Disconnected: tap to connect"
                 -> refresh to "Attempting to Connect"
                 -> toggle account connection(ie what used to be manage accounts toggle)
                  */
            } else if (previousNetworkState.contains(context.getString(R.string.disconnect_tap_to_connect))) {
                networkStatus.setText(context.getString(R.string.refreshing_connection));
                if (!(account.getStatus().equals(Account.State.CONNECTING) || account.getStatus().equals(Account.State.ONLINE))){
                    xmppConnectionService.enableAccount(account);
                }
                 /*
                 Case 2. NETWORK) "No internet connection"
                 -> refresh to "Checking for signal"
                 -> ???
                  */
            } else if (previousNetworkState.contains(context.getString(R.string.status_no_network))) {
                networkStatus.setText(context.getString(R.string.refreshing_network));
                xmppConnectionService.enableAccount(account);
            }

            updateOfflineStatusBar();
        }

    }

    private void changePresence() {
        context.startActivity(new Intent(context, ChangeStatusActivity.class));
    }


    public void updateOfflineStatusBar () {
        if (ConnectivityReceiver.isConnected(context)) {
            if (networkOnly) {
                runStatus("", false, false, false, false);
            } else if (xmppConnectionService != null && !xmppConnectionService.getAccounts().isEmpty()) {
                final Account account = xmppConnectionService.getAccounts().get(0);
                Account.State accountStatus = account.getStatus();
                Presence.Status presenceStatus = account.getPresenceStatus();
                if (presenceStatus.equals(Presence.Status.OFFLINE)) {
                    runStatus(context.getResources().getString(R.string.status_tap_to_enable), true, true, true, true);
                } else if (!presenceStatus.equals(Presence.Status.ONLINE)) {
                    runStatus(presenceStatus.toDisplayString() + context.getResources().getString(R.string.status_tap_to_available), true, true,true, true);
                } else {
                    if (accountStatus == Account.State.ONLINE) {
                        runStatus("", false, false, false, false);
                    } else if (accountStatus == Account.State.CONNECTING) {
                        runStatus(context.getResources().getString(R.string.connecting), true, false, false, false);
                    } else {
                        runStatus(context.getResources().getString(R.string.disconnect_tap_to_connect), true, true, true, true);
                    }
                }
            } else {
                runStatus("", false, false, false, false);
            }
        } else {
            runStatus(context.getResources().getString(R.string.status_no_network), true, true, false,true);

        }
    }

    private void runStatus (String str,boolean isVisible, boolean withRed, boolean withRefresh, boolean hasText){
        LinearProgressIndicator progressIndicator = toolbar.findViewById(R.id.network_progress);
        if (isVisible) {
            toolbar.setVisibility(View.VISIBLE);
            if (progressIndicator != null) {
                progressIndicator.setVisibility(View.VISIBLE);
                if (withRed) {
                    progressIndicator.setIndeterminate(false);
                    progressIndicator.setProgressCompat(100, false);
                    progressIndicator.setIndicatorColor(ContextCompat.getColor(context, R.color.md_theme_error));
                } else {
                    progressIndicator.setIndeterminate(true);
                    progressIndicator.setIndicatorColor(ContextCompat.getColor(context, R.color.md_theme_primary));
                }
            }
        } else {
            toolbar.setVisibility(View.GONE);
            if (progressIndicator != null) {
                progressIndicator.setVisibility(View.GONE);
            }
        }
        reconfigureOfflineText(str, withRefresh, hasText);
    }

    public void checkNetworkConnection(){
        updateOfflineStatusBar();
    }

    private void reconfigureOfflineText (String str,boolean withRefresh, boolean hasText){
        TextView networkStatus = toolbar.findViewById(R.id.network_status);
        if (networkStatus != null) {
            if (str == null || str.isEmpty() || !hasText) {
                networkStatus.setVisibility(View.GONE);
            } else {
                networkStatus.setVisibility(View.VISIBLE);
                networkStatus.setText(str);
                if (withRefresh) {
                    Drawable refreshIcon = ContextCompat.getDrawable(context, R.drawable.ic_refresh_24dp);
                    networkStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(refreshIcon, null, null, null);
                } else {
                    networkStatus.setCompoundDrawables(null, null, null, null);
                }
            }
        }
    }
}


