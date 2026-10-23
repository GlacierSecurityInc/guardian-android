package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.ui.DNSActivity.ACTION_DNS_ADDR;
import static com.glaciersecurity.glaciermessenger.ui.DNSActivity.EXTRA_IS_PRIVATE_DNS_ACTIVE;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivitySecurityHubBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.CoreConnectionTileService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ExpandableListItem;
import com.glaciersecurity.glaciermessenger.ui.adapter.SecurityHubListAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import android.content.IntentFilter;

import com.glaciersecurity.glaciermessenger.ui.util.NavigationUtils;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.glaciersecurity.glaciermessenger.wireguard.WireGuardStateMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SecurityHubActivity extends XmppActivity implements XmppConnectionService.OnOrganizationUpdate, XmppConnectionService.OnAmplifySync, XmppConnectionService.OnJingleRtpConnectionUpdate, XmppConnectionService.OnCoreStatusUpdate, ConnectivityReceiver.ConnectivityReceiverListener {

    private boolean isPrivateDNSActive = false;
    private String dnsPrivateAddrDevice;
    private String dnsPrivateAddrAlternateFormatDevice;
    private String deviceDNSAddr;
    private boolean isGlacierDNSEnabled = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallbackVpn;
    private ConnectivityManager.NetworkCallback networkCallbackDns;
    private ActivitySecurityHubBinding binding;
    private SecurityHubListAdapter securityHubAdapter;
    private RecyclerView.AdapterDataObserver adapterObserver;
    private ArrayList<ExpandableListItem> sechubList = new ArrayList<>();
    private boolean orgSyncComplete = false;
    private boolean latestVersionComplete = false;
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;
    private final BroadcastReceiver coreTileStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshVpnUi();
        }
    };
    private boolean coreTileStateReceiverRegistered;

    @Override
    protected int getPhoneBadgeNavigationViewId() {
        return R.id.navigation_conversations;
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_security_hub);
        binding.returnToGlacierCall.setOnClickListener(v -> launchGlacierCall());
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setSecurityHubTitle();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        binding.navigationConversations.setSelectedItemId(R.id.security_hub_nav);
        binding.navigationConversations.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                    Intent intent = null;
                    int itemId = item.getItemId();
                    if (itemId == R.id.chat_nav) {
                        intent = new Intent(SecurityHubActivity.this, ConversationsActivity.class);
                    } else if (itemId == R.id.phone_nav) {
                        intent = new Intent(SecurityHubActivity.this, PhoneModuleActivity.class);
                    } else if (itemId == R.id.contacts_nav) {
                        intent = new Intent(SecurityHubActivity.this, StartConversationActivity.class);
                    }
                    else if (itemId == R.id.settings_nav) {
                        intent = new Intent(SecurityHubActivity.this, SettingsActivity.class);
                    }
                    if (intent != null) {
                        startActivity(intent);
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }
                    return true;
                }
            });

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setHasFixedSize(true);
        securityHubAdapter = new SecurityHubListAdapter(this, sechubList);
        adapterObserver = new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
            }
        };
        securityHubAdapter.registerAdapterDataObserver(adapterObserver);
        binding.recyclerView.setAdapter(securityHubAdapter);
        binding.swipeRefreshLayout.setOnRefreshListener(() -> {
            xmppConnectionService.queryLatestVersion();
            xmppConnectionService.queryGlacierOrganization(xmppConnectionService.getFirstAccount(), false);
        });
        securityHubAdapter.setOnItemClickListener((obj, position) -> {
            if (obj.isExpandable) {
                if (getString(R.string.system_unsafe).equals(obj.name)) {
                    obj.isExpanded = !obj.isExpanded;
                    securityHubAdapter.notifyItemChanged(position);
                    return;
                }
                switch (obj.name) {
                    case ("Core Connection"):
                        Intent coreConnectionActivity = new Intent(getApplicationContext(), CoreConnectionActivity.class);
                        startActivity(coreConnectionActivity);
                        break;
                    case ("Mobile Threat Defense"):
                            Intent dnsActivity = new Intent(getApplicationContext(), DNSActivity.class);
                            dnsActivity.setAction(ACTION_DNS_ADDR);
                            dnsActivity.putExtra(EXTRA_IS_PRIVATE_DNS_ACTIVE, isUsingDNSaddr());
                            startActivity(dnsActivity);

                        break;
                    case ("Latest Glacier"):
                        openAppInPlayStore(getResources().getString(R.string.namespace));
                        break;
                    case ("Latest OS"):
                        openSoftwareUpdate();
                        break;
                    case("Biometric Lock"):
                        openLockScreenSettings();
                        break;
                }
            }
        });


        startVpnMonitoring();
        startDnsMonitoring();

        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

    }

    private void setSecurityHubTitle() {
        setTitle(R.string.title_activity_security_hub);
        binding.toolbar.setTitle(R.string.title_activity_security_hub);
    }

    private void openSoftwareUpdate() {
        try {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        } catch (Exception e) {
            Log.e(Config.LOGTAG, e.getMessage(), e);
        }
    }

    private void openAppInPlayStore(String packageName) {
        try {
            // Create an Intent to open the Play Store with the app's package name
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
            intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse("com.android.vending"));
            startActivity(intent);
        } catch (Exception e) {
            // If Google Play Store is not available, open the Play Store in a web browser
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://play.google.com/store/apps/details?id=" + packageName));
            try {
                startActivity(webIntent);
            } catch (ActivityNotFoundException e1) {
                Toast.makeText(this, R.string.application_found_to_open_website, Toast.LENGTH_LONG)
                        .show();
            }
        }
    }

    private void openLockScreenSettings() {
        try {
            Intent intent = intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, e.getMessage(), e);
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        registerCoreTileStateReceiver();
        final BottomNavigationView navConversations = findViewById(R.id.navigation_conversations);
        if (navConversations != null) {
            navConversations.setSelectedItemId(R.id.security_hub_nav);
            NavigationUtils.updatePhoneVisibility(navConversations,
                    getPreferences().getBoolean("user_sms_enabled", false));
            NavigationUtils.updatePhoneBadge(navConversations, this);
        }
        binding.swipeRefreshLayout.setRefreshing(false);
        setSecurityHubTitle();

    }

    @Override
    public void onPause() {
        unregisterCoreTileStateReceiver();
        super.onPause();
    }

    private void registerCoreTileStateReceiver() {
        if (coreTileStateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(CoreConnectionTileService.ACTION_CORE_CONNECTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, coreTileStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        coreTileStateReceiverRegistered = true;
    }

    private void unregisterCoreTileStateReceiver() {
        if (!coreTileStateReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(coreTileStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "Core tile receiver already unregistered", e);
        }
        coreTileStateReceiverRegistered = false;
    }

    private void refreshVpnUi() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            securityHubAdapter.updateCoreConnect(isGlacierVpnActive());
            return;
        }
        WireGuardStateMonitor.notifyStateChanged(() -> runOnUiThread(() -> {
            if (!xmppConnectionServiceBound || xmppConnectionService == null) {
                return;
            }
            final BottomNavigationView navConversations = binding.navigationConversations;
            if (navConversations != null) {
                NavigationUtils.updateSecurityHubBadge(navConversations,
                        !xmppConnectionService.getSecInfo().isSecure());
            }
            securityHubAdapter.updateCoreConnect(isGlacierVpnActive());
        }));
    }

    private String getDNS_id(){
        if (xmppConnectionService == null) {
            return null;
        }
        final Account account = xmppConnectionService.getFirstAccount();
        if (account == null) {
            return null;
        }
        return xmppConnectionService.databaseBackend.getDnsId(account.getOrganization());
    }


    private ArrayList<ExpandableListItem> initSecurityCheck() {
        ArrayList<ExpandableListItem> security_results = new ArrayList<>();
        security_results.add(anomaliesDetectedListItem());
        if (getPreferences().getBoolean("dns_enabled", false)){
            security_results.add(nxtDNSListItem());
        }
        security_results.add(coreConnectionListItem());
        security_results.add(screenLockListItem());
        security_results.add(latestGlacierListItem());
        security_results.add(latestOsListItem());
        security_results.add(deviceBioLockListItem());


        isSecure();

        return security_results;
    }

    private boolean isUsingDNSaddr() {
        final String expectedPrimary = normalizeDnsHost(dnsPrivateAddrDevice);
        final String expectedAlternate = normalizeDnsHost(dnsPrivateAddrAlternateFormatDevice);
        final String currentDeviceDns = normalizeDnsHost(deviceDNSAddr);

        if (currentDeviceDns == null || currentDeviceDns.isEmpty()) {
            return false;
        }
        if (expectedPrimary != null && expectedPrimary.equals(currentDeviceDns)) {
            return true;
        }
        final boolean isUsingGlacierDNS =  expectedAlternate != null && expectedAlternate.equals(currentDeviceDns);
        return isUsingGlacierDNS;
    }

    private String normalizeDnsHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterCoreTileStateReceiver();
        securityHubAdapter.unregisterAdapterDataObserver(adapterObserver);
        try {
            stopVpnMonitoring();
            stopDnsMonitoring();
        } catch (Exception ignored){

        }
    }


    private void getDeviceDnsIdentifier(){
        String userJID;
        String userDevice;
        String deviceIdentity = "";
        if(xmppConnectionService !=null)

        {
            userJID = xmppConnectionService.getAccounts().get(0).getUsername().replace("_", "") + "--";
            if (Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
                userDevice = "Samsung";
                try {
                    userDevice += "--" + Build.MODEL.substring(3, 6);
                } catch (Exception ignored) {
                }
            } else {
                userDevice = Build.MODEL;
            }
            userDevice += "-";
            deviceIdentity = userJID + userDevice;
            deviceIdentity = deviceIdentity.replace(" ", "--").replaceAll("[^\\w\\s\\-]","");
        }
        String dnsID = getDNS_id();
        if (dnsID != null && !dnsID.isEmpty()) {
            dnsPrivateAddrDevice = deviceIdentity + dnsID + ".dns.glcr.me";
            dnsPrivateAddrAlternateFormatDevice = dnsPrivateAddrDevice.replace("--", "-");
        } else {
            dnsPrivateAddrDevice = null;
            dnsPrivateAddrAlternateFormatDevice = null;
        }

    }


    // Every row below renders the snapshot the background sweep just computed. Calling the
    // live probes here would put the package walk, the forked system-property reads and the
    // biometric Binder call back on the main thread -- the ANR this screen is meant to avoid.

    private boolean isNoAnomaliesDetected(){
        return !xmppConnectionService.getSecInfo().lastKnownAnomalies();
    }

    private boolean hasScreenLock(){
        return xmppConnectionService.getSecInfo().lastKnownScreenLock();
    }

    private boolean isBioLock() {
        return xmppConnectionService.getSecInfo().lastKnownBioLock();
    }

    private boolean isLatestOs() {
        return xmppConnectionService.getSecInfo().lastKnownLatestOs();
    }

    private boolean isLatestGlacier() {
        return xmppConnectionService.getSecInfo().lastKnownLatestGlacier();
    }

    private boolean isCoreConnection() {
        return isGlacierVpnActive();
    }

    private ExpandableListItem anomaliesDetectedListItem(){
        if (isNoAnomaliesDetected()) {
            return new ExpandableListItem(R.drawable.box_icon_shield, getString(R.string.system_safe),getString(R.string.no_anomalies));
        } else {
            ExpandableListItem item = new ExpandableListItem(
                    R.drawable.box_icon_warning_red,
                    getString(R.string.system_unsafe),
                    getString(R.string.tap_to_expand_findings),
                    true,
                    true);
            item.withExpandedDescription(getBulletedSecurityFindings());
            return item;
        }
    }

    private String getBulletedSecurityFindings() {
        List<String> findings = xmppConnectionService.getSecInfo().lastKnownFindings();
        if (findings == null || findings.isEmpty()) {
            return getString(R.string.no_anomalies);
        }

        StringBuilder builder = new StringBuilder();
        for (String finding : findings) {
            builder.append(finding).append('\n');
        }
        builder.setLength(builder.length() - 1);
        return builder.toString();
    }

    private ExpandableListItem screenLockListItem(){
        if (hasScreenLock()) {
            return new ExpandableListItem(R.drawable.box_icon_alt_loc, getString(R.string.screen_lock),getString(R.string.screen_lock_enabled));
        } else {
            return new ExpandableListItem(R.drawable.box_icon_alt_lock_disabled, getString(R.string.screen_lock),getString(R.string.screen_lock_disabled), true);
        }
    }

    private ExpandableListItem latestGlacierListItem(){
        if (isLatestGlacier()) {
            return new ExpandableListItem(R.drawable.box_alt_arrow, getString(R.string.latest_updates_app),getString(R.string.up_to_date_app));
        }
        return new ExpandableListItem(R.drawable.box_alt_arrow_disabled, getString(R.string.latest_updates_app),getString(R.string.update_glacier), true, true);

    }

    private ExpandableListItem latestOsListItem(){
        if (isLatestOs()) {
            return new ExpandableListItem(R.drawable.box_icon_shield, getString(R.string.latest_updates_os),getString(R.string.up_to_date_os));
        }
        return new ExpandableListItem(R.drawable.box_icon_shield_disabled, getString(R.string.latest_updates_os),getString(R.string.update_os), true);
    }

    private ExpandableListItem deviceBioLockListItem(){
        if (isBioLock() && isBiometricEnrolled(this)) {
            return new ExpandableListItem(
                    R.drawable.box_fingerprint,
                    getString(R.string.biometrics_dev),
                    getEnabledBiometricDescription(),
                    true,
                    false);
        } else {
            return new ExpandableListItem(
                    R.drawable.box_fingerprint_disabled,
                    getString(R.string.biometrics_dev),
                    getString(R.string.bio_lock_disabled),
                    true,
                    false);
        }
    }

    private String getEnabledBiometricDescription() {
        if (!isBiometricEnrolled(this) || isBiometricSupportedButNotEnrolled(this)) {
            return getString(R.string.bio_lock_disabled);
        }

        final PackageManager packageManager = getPackageManager();
        final boolean hasFingerprintHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        final boolean hasFaceHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && packageManager.hasSystemFeature(PackageManager.FEATURE_FACE);
        final boolean fingerprintEnrolled = isFingerprintEnrolled(this);

        // Conservative modality reporting: only show a modality when enrollment can be inferred.
        if (fingerprintEnrolled) {
            return getString(R.string.bio_lock_enabled_fingerprint);
        }

        if (hasFaceHardware && (!hasFingerprintHardware || !fingerprintEnrolled)) {
            return getString(R.string.bio_lock_enabled_face);
        }

        return getString(R.string.bio_lock_enabled);
    }

    public static boolean isBiometricSupportedButNotEnrolled(Context context) {
        BiometricManager biometricManager = BiometricManager.from(context);
        int result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return result == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
    }

    private static boolean isBiometricEnrolled(Context context) {
        BiometricManager biometricManager = BiometricManager.from(context);
        int result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private static boolean isFingerprintEnrolled(Context context) {
        // FingerprintManager is deprecated; androidx BiometricManager is the supported API.
        // It doesn't expose per-modality enrollment, so require fingerprint hardware and a
        // successful strong-biometric check to infer that a fingerprint is enrolled.
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return false;
        }
        return BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private ExpandableListItem nxtDNSListItem(){
        if (isGlacierDNSEnabled) {
            return new ExpandableListItem(R.drawable.box_dns,  getString(R.string.nextdns_title), getString(R.string.dns_enabled), true, false);
        } else {
            return new ExpandableListItem(R.drawable.box_dns_disabled, getString(R.string.nextdns_title), getString(R.string.dns_disabled), true, false);
        }
    }

    private ExpandableListItem coreConnectionListItem(){
        if (isCoreConnection()) {
            return new ExpandableListItem(R.drawable.key_vpn_enabled,  getString(R.string.core_connect), getString(R.string.core_connect_enabled), true, false);
        } else {
            return new ExpandableListItem(R.drawable.key_vpn_disabled, getString(R.string.core_connect), getString(R.string.core_connect_disabled), true, false);
        }
    }

    private void isSecure(){
        if (xmppConnectionService.getSecInfo().isSecure()){
            binding.issuesTitle.setText(R.string.no_issues_found);
            binding.issuesIcon.setImageResource(R.drawable.box_icon_shield_green);
        } else {
            binding.issuesTitle.setText(R.string.issues_found);
            binding.issuesIcon.setImageResource(R.drawable.box_icon_warning);
        }
    }
    public void getDnsServers() {
        if (connectivityManager != null) {
            // Get the active network
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                // Get the LinkProperties of the active network
                LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
                if (linkProperties != null) {
                    // Get DNS servers
                    deviceDNSAddr = linkProperties.getPrivateDnsServerName();
                    isPrivateDNSActive = linkProperties.isPrivateDnsActive();
                    isGlacierDNSEnabled = isUsingDNSaddr() && isPrivateDNSActive;
                }
            }
        }
    }


    @Override
    protected void refreshUiReal() {

    }

    private void refreshView(){
        orgSyncComplete = false;
        latestVersionComplete = false;
        runCheck();

    }

    @Override
    protected void onBackendConnected() {
        runCheck();
        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(jingleManager != null && jingleManager.getOngoingRtpConnection() != null);
        refreshVpnUi();
        if (getPreferences().getBoolean("dns_enabled", false)) {
            getDeviceDnsIdentifier();
            getDnsServers();
            securityHubAdapter.updateDns(isGlacierDNSEnabled);
        }
    }

    private void runCheck(){
        // The sweep blocks on the keyguard and biometric system services, so it runs on the
        // service's security executor; the UI work still happens on the main thread once it
        // has finished, which is what the runOnUiThread below was always for.
        xmppConnectionService.refreshSecurityInfo(() -> runOnUiThread(()->{
            // The refresh is no longer synchronous, so the activity can be on its way out
            // or already unbound by the time it lands.
            if (isFinishing() || isDestroyed() || xmppConnectionService == null) {
                return;
            }
            if (getPreferences().getBoolean("dns_enabled", false)) {
                getDeviceDnsIdentifier();
                getDnsServers();
            }
            sechubList = initSecurityCheck();
            securityHubAdapter.updateSechubList(sechubList);
            binding.swipeRefreshLayout.setRefreshing(false);
            setSecurityHubTitle();
            if (xmppConnectionServiceBound) {
                NavigationUtils.updateSecurityHubBadge(binding.navigationConversations,
                        !xmppConnectionService.getSecInfo().isSecure());

                if (getPreferences().getBoolean("securityhub_data_enabled", false)) {
                    xmppConnectionService.getSecInfo().trySecurityInfoUpload();
                }
            }

        }));
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        } else {
            Toast.makeText(getApplicationContext(), item.getTitle(), Toast.LENGTH_SHORT).show();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onOrganizationUpdate() {
        orgSyncComplete = true;
        if (latestVersionComplete){
            refreshView();
        }
    }

    @Override
    public void onNoOrganizationUpdate() {
        orgSyncComplete = true;
        if (latestVersionComplete){
            refreshView();
        }
    }

    @Override
    public void onCoreStatusUpdated(boolean isCoreConnected) {
        runOnUiThread(() -> {
            securityHubAdapter.updateCoreConnect(isCoreConnected);
            if (xmppConnectionServiceBound && xmppConnectionService != null) {
                final var securityInfo = xmppConnectionService.getSecInfo();
                boolean showBadge = securityInfo != null && !securityInfo.isSecure();
                NavigationUtils.updateSecurityHubBadge(binding.navigationConversations, showBadge);
            }
        });
    }


    public void startVpnMonitoring() {
        // Define a NetworkRequest for monitoring VPN connections
        NetworkRequest networkRequest = WireGuardStateMonitor.vpnNetworkRequest();

        networkCallbackVpn = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                onVpnStatusChanged(isGlacierVpnActive());
            }

            @Override
            public void onLost(Network network) {
                super.onLost(network);
                onVpnStatusChanged(isGlacierVpnActive());
            }

        };

        connectivityManager.registerNetworkCallback(networkRequest, networkCallbackVpn);

        // Initialize current VPN status
        onVpnStatusChanged(isGlacierVpnActive());

    }

    private boolean isGlacierVpnActive() {
        try {
            return PersistentConnectionProperties.getInstance().isRunningTunnel();
        } catch (Exception e) {
            return false;
        }
    }

    private void startDnsMonitoring() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Listen for networks with internet capability
                .build();

        // Create a NetworkCallback to handle network changes
        networkCallbackDns = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties);
                deviceDNSAddr = linkProperties.getPrivateDnsServerName();
                isPrivateDNSActive = linkProperties.isPrivateDnsActive();
                isGlacierDNSEnabled = isUsingDNSaddr() && isPrivateDNSActive;
                securityHubAdapter.updateDns(isGlacierDNSEnabled);
            }
        };
        connectivityManager.registerNetworkCallback(networkRequest, networkCallbackDns);

    }

    public void stopVpnMonitoring() {
        if (networkCallbackVpn != null) {
            connectivityManager.unregisterNetworkCallback(networkCallbackVpn);
        }
    }

    public void stopDnsMonitoring() {
        if (networkCallbackDns != null) {
            connectivityManager.unregisterNetworkCallback(networkCallbackDns);
        }
    }

    // Also called from network callbacks, which arrive on ConnectivityThread.
    private void onVpnStatusChanged(boolean isVpnActive) {
        runOnUiThread(() -> securityHubAdapter.updateCoreConnect(isVpnActive));

    }

    @Override
    public void onOrgSyncComplete() {
        orgSyncComplete = true;
        if (latestVersionComplete){
            refreshView();
        }

    }

    @Override
    public void onUserSyncComplete() {

    }

    @Override
    public void onLatestVersionSyncComplete() {
        latestVersionComplete = true;
        if (orgSyncComplete){
            refreshView();
        }

    }

    @Override
    public void onJingleRtpConnectionUpdate(Account account, Jid with, String sessionId, RtpEndUserState state) {
        switch (state) {
            case CONNECTED, RECONNECTING, INCOMING_CONTENT_ADD ->
                    runOnUiThread(() -> updateReturnToGlacierCall(true));
            default -> runOnUiThread(() -> updateReturnToGlacierCall(false));
        }
    }

    @Override
    public void onAudioDeviceChanged(CallIntegration.AudioDevice selectedAudioDevice, Set<CallIntegration.AudioDevice> availableAudioDevices) { }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private final View.OnClickListener mRefreshNetworkClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            connectionToolbarAdapter.onClick();
        }
    };

    private void launchGlacierCall() {
        final JingleConnectionManager manager = xmppConnectionService.getJingleConnectionManager();
        if (manager == null) {
            return;
        }
        final JingleRtpConnection jrc = manager.getOngoingRtpConnection();
        if (jrc == null) {
            return;
        }
        final Intent intent = new Intent(this, RtpSessionActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(RtpSessionActivity.EXTRA_ACCOUNT, jrc.getId().account.getJid().asBareJid().toEscapedString());
        intent.putExtra(RtpSessionActivity.EXTRA_WITH, jrc.getId().with.toEscapedString());
        intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, jrc.getId().sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
