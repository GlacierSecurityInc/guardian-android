package com.glaciersecurity.glaciermessenger.ui;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiResponses;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.google.gson.Gson;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityDnsBinding;
import com.glaciersecurity.glaciermessenger.utils.Tools;

public class DNSActivity extends XmppActivity implements ConnectivityReceiver.ConnectivityReceiverListener {
    public static final String ACTION_DNS_ADDR = "dns_addr";
    public static final String EXTRA_IS_PRIVATE_DNS_ACTIVE = "is_private_dns_active";
    private boolean isPrivateDNSActive;
    private ActivityDnsBinding binding;
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;

    @Override
    protected void refreshUiReal() {

    }

    @Override
    protected void onBackendConnected() {

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_dns);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService, true);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

        // A relaunched activity can arrive with a null action or a null extras bundle even though
        // SecurityHubActivity always sets both, so read the intent defensively.
        final Intent intent = getIntent();
        if (intent != null && ACTION_DNS_ADDR.equals(intent.getAction())) {
            this.isPrivateDNSActive = intent.getBooleanExtra(EXTRA_IS_PRIVATE_DNS_ACTIVE, false);
        }

        binding.configDNSBtn.setOnClickListener(view -> {
            final Account account = xmppConnectionService == null
                    ? null
                    : xmppConnectionService.getFirstAccount();
            if (account == null) {
                Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
                return;
            }
            fetchDnsProfile(account);
        });


        if (isPrivateDNSActive) {
            binding.dnsConfiguredText.setVisibility(View.VISIBLE);
            binding.dnsImage.setImageResource(R.drawable.dns_enabled_512);
        } else {
            binding.dnsConfiguredText.setVisibility(View.GONE);
            binding.dnsImage.setImageResource(R.drawable.dns_disabled_128);
        }
        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            binding.dnsStep2.setText(R.string.nextdns_2_samsung);
        } else {
            binding.dnsStep2.setText(R.string.nextdns_2);
        }

        updatePrivateDnsStatus();

    }

    public void fetchDnsProfile(Account account) {
        if (account == null) {
            return;
        }
        new Thread(() -> {
            final String getDnsProfileUrl =
                    Endpoints.base() + EndPointConfig.GET_DNS_PROFILE_URL;
            final okhttp3.HttpUrl baseUrl = okhttp3.HttpUrl.parse(getDnsProfileUrl);
            if (baseUrl == null) {
                Log.e(Config.LOGTAG, "unable to parse DNS profile url " + getDnsProfileUrl);
                getBackupDNS(account);
                return;
            }
            final OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            final okhttp3.HttpUrl requestUrl = baseUrl
                    .newBuilder()
                    .addQueryParameter("organization", account.getOrganization())
                    .build();

            final Request request = new Request.Builder()
                    .url(requestUrl)
                    .get()
                    .build();

            String dnsId = null;
            try (Response response = client.newCall(request).execute()) {
                // the body has to be read through a local: response.body() is only guaranteed
                // to be readable once, and it can legitimately be null
                final okhttp3.ResponseBody body = response.body();
                final String responseBody = body == null ? null : body.string();
                if (responseBody != null && !responseBody.trim().isEmpty()) {
                    final MobileApiResponses.DnsProfileResponse profileResponse =
                            new Gson().fromJson(
                                    responseBody, MobileApiResponses.DnsProfileResponse.class);
                    if (profileResponse != null && "success".equals(profileResponse.message)) {
                        final String candidate = profileResponse.data == null
                                ? null
                                : profileResponse.data.trim();
                        if (GlacierOrgEntity.isValidDnsId(candidate)) {
                            dnsId = candidate;
                        } else {
                            Log.w(Config.LOGTAG, "DNS profile response carried no valid dns id");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "unable to fetch DNS profile", e);
            }

            if (dnsId == null) {
                // nothing usable from the server, fall back to the last id we stored
                getBackupDNS(account);
                return;
            }
            if (xmppConnectionService != null) {
                xmppConnectionService.databaseBackend.updateDnsId(
                        account.getOrganization(), dnsId);
            }
            applyDnsId(dnsId);
        }).start();
    }

    /**
     * Copies the device's DNS hostname to the clipboard and sends the user to the network
     * settings where they can paste it. Safe to call from a background thread.
     */
    private void applyDnsId(final String dnsId) {
        runOnUiThread(() -> {
            final String label = getResources().getString(R.string.nextdns_title);
            final String text = getDeviceDnsIdentifier(dnsId);

            Tools.copyToClipboard(DNSActivity.this, text, label);
            Toast.makeText(DNSActivity.this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT)
                    .show();
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            finish();
        });
    }

    private void getBackupDNS(Account account) {
        String dnsId = null;
        if (xmppConnectionService != null) {
            dnsId = xmppConnectionService.databaseBackend.getDnsId(account.getOrganization());
        }
        if (dnsId != null && !dnsId.isEmpty()) {
            applyDnsId(dnsId);
        } else {
            runOnUiThread(
                    () -> Toast.makeText(this, R.string.dns_error_fetching, Toast.LENGTH_SHORT)
                            .show());
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        updatePrivateDnsStatus();
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new android.content.IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    private void updatePrivateDnsStatus() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                LinkProperties props = cm.getLinkProperties(activeNetwork);
                if (props != null) {
                    isPrivateDNSActive = props.isPrivateDnsActive();
                }
            }
        }
        if (isPrivateDNSActive) {
            binding.dnsConfiguredText.setVisibility(View.VISIBLE);
            binding.dnsImage.setImageResource(R.drawable.dns_enabled_512);
        } else {
            binding.dnsConfiguredText.setVisibility(View.GONE);
            binding.dnsImage.setImageResource(R.drawable.dns_disabled_128);
        }
    }

    private String getDeviceDnsIdentifier(String dnsPrivateAddrOrg) {
        String deviceIdentity = "";
        final Account account =
                xmppConnectionService == null ? null : xmppConnectionService.getFirstAccount();
        if (account != null && account.getUsername() != null) {
            String userDevice;
            final String userJID = account.getUsername().replace("_", "") + "--";
            if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
                userDevice = "Samsung";
                if (Build.MODEL != null && Build.MODEL.length() >= 6) {
                    userDevice += "--" + Build.MODEL.substring(3, 6);
                }
            } else {
                userDevice = Build.MODEL;
            }
            userDevice += "-";
            deviceIdentity = userJID + userDevice;
            deviceIdentity = deviceIdentity.replace(" ", "--").replaceAll("[^\\w\\s\\-]", "");
        }
        return deviceIdentity + dnsPrivateAddrOrg + ".dns.glcr.me";
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private final View.OnClickListener mRefreshNetworkClickListener = v -> connectionToolbarAdapter.onClick();

}
