package com.glaciersecurity.glaciermessenger.ui;

import static com.wireguard.android.backend.Tunnel.State.DOWN;
import static com.wireguard.android.backend.Tunnel.State.UP;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityWgConnectBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.services.CoreConnectionTileService;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.CoreProfileAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.NavigationUtils;
import com.glaciersecurity.glaciermessenger.ui.widget.ConnectionOrbView;
import com.glaciersecurity.glaciermessenger.utils.ContentUriValidator;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.wireguard.CoreRevokeAlert;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties.CoreConnectionState;
import com.glaciersecurity.glaciermessenger.wireguard.WireGuardStateMonitor;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

public class CoreConnectionActivity extends XmppActivity implements XmppConnectionService.OnAmplifySync, XmppConnectionService.OnCoreStatusUpdate, ConnectivityReceiver.ConnectivityReceiverListener {

    private static final int WIRE_GUARD_PERMISSION_REQUEST = 17;
    private static final int REQUEST_CODE_CREATE_FILE = 18;
    private static final String PREFS_CORE_CONNECTION = "core_connection_ui";
    private static final String PREF_LAST_CONNECTED_AT = "last_connected_at";
    private static final String PREF_TOTAL_PROTECTED = "total_protected";
    private static final String PREF_LAST_PROFILE = "last_profile";
    private static final long SESSION_TICK_MS = 1100L;

    private ActivityWgConnectBinding binding;
    private CoreProfileAdapter coreProfileAdaptor;
    private Backend backend = PersistentConnectionProperties.getInstance().getBackend();
    private CoreProfile mCoreProfile;
    private CoreProfile profileToDownload;
    private CoreProfile selectedProfile;
    private boolean autoConnectAfterPermission;
    private boolean pendingTileAutoConnect;
    private ConnectivityManager.NetworkCallback externalVpnNetworkCallback;

    // this activity is exported (required for the Quick Settings
    // long-press-to-open-app affordance), so an auto-connect request delivered via Intent
    // extras cannot be trusted on its own -- it must carry the single-use token that
    // CoreConnectionTileService generated for this specific tile action.
    private static volatile String pendingAutoConnectToken;

    public static void registerPendingAutoConnectToken(@NonNull String token) {
        pendingAutoConnectToken = token;
    }
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;
    private boolean waitingForUserSync = false;
    private SharedPreferences corePreferences;
    private MenuItem importMenuItem;
    private boolean importInProgress;

    // Live session metering (connected only).
    private final Handler sessionHandler = new Handler(Looper.getMainLooper());
    private String runningProfileName;
    private long lastRx = -1L;
    private long lastTx = -1L;
    // Wall-clock of the last reading where the byte counters actually advanced.
    private long lastStatsAtNanos = 0L;
    // Last computed throughput (bytes/sec), held between backend refreshes.
    private double lastDownBps = 0d;
    private double lastUpBps = 0d;
    private final Runnable sessionTick = this::onSessionTick;
    private static final String SESSION_STATS_TAG = "CoreSessionStats";
    // The WireGuard backend refreshes Statistics roughly every ~3s; treat the
    // tunnel as idle (rate 0) once the counters have been flat longer than this.
    private static final long SESSION_STALE_NANOS = 4_000_000_000L;

    private final BroadcastReceiver coreTileStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(() -> {
                setCoreIconEnabled();
                BottomNavigationView navConversations = findViewById(R.id.navigation_conversations);
                if (navConversations != null) {
                    refreshProfilesAfterVpnSync(navConversations);
                }
                updateExternalVpnWarning();
                maybeShowRevokedAlert();
            });
        }
    };
    private boolean coreTileStateReceiverRegistered;
    private boolean revokedAlertShowing;
    // Set once loadFromDb has filled the profile list, which the revoke dialog reconnects from.
    private boolean profilesLoaded;

    @Override
    protected int getPhoneBadgeNavigationViewId() {
        return R.id.navigation_conversations;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_wg_connect);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        corePreferences = getSharedPreferences(PREFS_CORE_CONNECTION, MODE_PRIVATE);
        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService, true);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

        binding.connectionStatusIndicator.setOnClickListener(v -> handlePrimaryToggle());

        try {
            backend.getRunningTunnelNames();
        } catch (NullPointerException e) {
            PersistentConnectionProperties.getInstance().setBackend(new GoBackend(this));
            backend = PersistentConnectionProperties.getInstance().getBackend();
        }
        setCoreIconEnabled();

        String text = getResources().getString(R.string.enable_core_connection);
        SpannableString spannable = new SpannableString(text);

        int start = text.indexOf("Learn more");
        int end = start + "Learn more".length();
        if (start >= 0) {
            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://glacier.chat/core"));
                    try {
                        widget.getContext().startActivity(browserIntent);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(
                                        widget.getContext(),
                                        R.string.application_found_to_open_website,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(ContextCompat.getColor(CoreConnectionActivity.this, R.color.cc_accent));
                    ds.setUnderlineText(false);
                }
            };
            spannable.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        binding.coreLink.setText(spannable);
        binding.coreLink.setMovementMethod(LinkMovementMethod.getInstance());

        // Server selector card → modern bottom-sheet picker.
        binding.serverCard.setOnClickListener(v -> openServerSheet());

        coreProfileAdaptor = new CoreProfileAdapter(this, this);

        binding.buttonRenameProfile.setOnClickListener(v -> {
            if (selectedProfile != null) {
                nicknameProfDialog(selectedProfile);
            }
        });
        binding.buttonDeleteProfile.setOnClickListener(v -> {
            if (selectedProfile != null) {
                deleteProfDialog(selectedProfile);
            }
        });
        binding.buttonDownloadProfile.setOnClickListener(v -> {
            if (selectedProfile != null) {
                downloadProfDialog(selectedProfile);
            }
        });

        consumeTileIntentFlags(getIntent());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.core_connection, menu);
        importMenuItem = menu.findItem(R.id.action_import_profiles);
        if (importMenuItem != null) {
            importMenuItem.setEnabled(!importInProgress);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import_profiles) {
            importProfilesAlert();
            return true;
        }
        if (id == R.id.action_vpn_settings) {
            mVpnInfo();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeTileIntentFlags(intent);
    }

    private void consumeTileIntentFlags(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(CoreConnectionTileService.EXTRA_OPEN_CORE_PAGE, false)) {
            final boolean autoConnectRequested =
                    intent.getBooleanExtra(CoreConnectionTileService.EXTRA_AUTO_CONNECT, false);
            final String suppliedToken =
                    intent.getStringExtra(CoreConnectionTileService.EXTRA_AUTO_CONNECT_TOKEN);
            // this activity is exported for the Quick Settings long-press
            // affordance, so only honor auto-connect when the request carries the single-use
            // token CoreConnectionTileService generated for this exact tile action -- an
            // external app spoofing this intent cannot know that in-memory value. Consume the
            // token now regardless of outcome so it cannot be replayed.
            final String expectedToken = pendingAutoConnectToken;
            pendingAutoConnectToken = null;
            pendingTileAutoConnect =
                    autoConnectRequested
                            && expectedToken != null
                            && expectedToken.equals(suppliedToken);
            intent.removeExtra(CoreConnectionTileService.EXTRA_OPEN_CORE_PAGE);
            intent.removeExtra(CoreConnectionTileService.EXTRA_AUTO_CONNECT);
            intent.removeExtra(CoreConnectionTileService.EXTRA_AUTO_CONNECT_TOKEN);
        }
    }

    private void maybeRunTileAutoConnect() {
        if (!pendingTileAutoConnect || !xmppConnectionServiceBound || xmppConnectionService == null) {
            return;
        }
        pendingTileAutoConnect = false;

        CoreProfile running = getRunningCoreProfileSafely();
        if (running != null && isProfileTunnelUp(running)) {
            return;
        }

        CoreProfile preferred = findProfileByName(corePreferences != null ?
                corePreferences.getString(PREF_LAST_PROFILE, null) : null);
        if (preferred == null && coreProfileAdaptor != null && !coreProfileAdaptor.profileList.isEmpty()) {
            preferred = coreProfileAdaptor.profileList.get(0);
        }
        if (preferred != null) {
            connectAlertToggle(preferred, true);
        }
    }

    @Override
    public void onOrgSyncComplete() {
    }

    @Override
    public void onUserSyncComplete() {
        if (waitingForUserSync) {
            waitingForUserSync = false;
            new Thread(() -> importProfiles(() -> runOnUiThread(() ->
                    loadFromDb(() -> setWaitLayout(false))
            ))).start();
        }
    }

    @Override
    public void onLatestVersionSyncComplete() {
    }

    @Override
    public void onCoreStatusUpdated(boolean isCoreConnected) {
        runOnUiThread(() -> {
            setCoreIconEnabled();
            if (coreProfileAdaptor != null) {
                coreProfileAdaptor.notifyDataSetChanged();
            }
            updateExternalVpnWarning();
        });
    }

    // ── Nickname dialog — modern centered popup with an inline clear (×) ──────
    private void nicknameProfDialog(CoreProfile prof) {
        final View dialogView = getLayoutInflater().inflate(R.layout.dialog_rename_vpn, null);
        final TextInputEditText displayNameEt = dialogView.findViewById(R.id.displayname_et);
        final TextView subtitle = dialogView.findViewById(R.id.rename_subtitle);
        final MaterialButton cancel = dialogView.findViewById(R.id.rename_cancel);
        final MaterialButton save = dialogView.findViewById(R.id.rename_save);

        subtitle.setText(prof.getProfName());
        displayNameEt.setText(prof.getDisplayName());
        if (displayNameEt.getText() != null) {
            displayNameEt.setSelection(displayNameEt.getText().length());
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(CoreConnectionActivity.this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        final Runnable refreshSave = () -> {
            boolean empty = displayNameEt.getText() == null
                    || displayNameEt.getText().toString().trim().isEmpty();
            save.setEnabled(!empty);
            save.setAlpha(empty ? 0.5f : 1f);
        };
        displayNameEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { refreshSave.run(); }
        });
        refreshSave.run();

        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            nicknameProfile(prof, displayNameEt.getText() == null ? "" : displayNameEt.getText().toString());
            dialog.dismiss();
        });
        dialog.show();
    }

    private void nicknameProfile(CoreProfile edit_vep, String nickname) {
        String trimmedNickname = nickname == null ? "" : nickname.trim();
        edit_vep.setDisplayName(trimmedNickname);
        boolean isRunning = false;
        if (backend != null) {
            try {
                isRunning = backend.getState(PersistentConnectionProperties.getInstance().getTunnel(edit_vep.getProfName())) == UP;
            } catch (Exception ignored) {
            }
        }
        if (!isRunning && xmppConnectionService != null) {
            try {
                CoreProfile runningProfile = xmppConnectionService.getRunningCoreProfile();
                isRunning = runningProfile != null && runningProfile.getProfName().equals(edit_vep.getProfName());
            } catch (Exception ignored) {
            }
        }
        edit_vep.setRunning(isRunning);
        // Update the in-memory adapter list first; the connection card reads the
        // display name from there, so the name under the connect button refreshes
        // immediately rather than waiting on the async DB write (which is why the
        // rename previously only took effect after leaving and returning).
        coreProfileAdaptor.editProfile(edit_vep);
        if (selectedProfile != null && selectedProfile.getProfName().equals(edit_vep.getProfName())) {
            selectProfile(edit_vep, true);
        }
        if (xmppConnectionService != null) {
            xmppConnectionService.addCoreProfile(edit_vep);
        }
        runOnUiThread(this::setCoreIconEnabled);
    }

    // ── Shared modern confirm dialog (connect / switch / disconnect / download /
    //    delete / import) — matches the Rename-profile dialog's style. ─────────
    private void showCoreConfirm(int iconRes, int iconColorRes, int tileTintRes,
                                 String title, @Nullable CharSequence subtitle, @Nullable CharSequence message,
                                 @Nullable String[][] chips,
                                 String confirmLabel, int confirmIconRes, int confirmBgRes, int confirmInkRes,
                                 @Nullable Runnable onConfirm, @Nullable Runnable onCancel) {
        showCoreConfirm(iconRes, iconColorRes, tileTintRes, title, subtitle, message, chips,
                confirmLabel, confirmIconRes, confirmBgRes, confirmInkRes, null, onConfirm, onCancel);
    }

    private void showCoreConfirm(int iconRes, int iconColorRes, int tileTintRes,
                                 String title, @Nullable CharSequence subtitle, @Nullable CharSequence message,
                                 @Nullable String[][] chips,
                                 String confirmLabel, int confirmIconRes, int confirmBgRes, int confirmInkRes,
                                 @Nullable String cancelLabel,
                                 @Nullable Runnable onConfirm, @Nullable Runnable onCancel) {
        final View view = getLayoutInflater().inflate(R.layout.dialog_core_confirm, null);
        final View tile = view.findViewById(R.id.confirm_icon_tile);
        final ImageView icon = view.findViewById(R.id.confirm_icon);
        final TextView titleTv = view.findViewById(R.id.confirm_title);
        final TextView subtitleTv = view.findViewById(R.id.confirm_subtitle);
        final TextView messageTv = view.findViewById(R.id.confirm_message);
        final LinearLayout chipsRow = view.findViewById(R.id.confirm_chips);
        final MaterialButton cancel = view.findViewById(R.id.confirm_cancel);
        final MaterialButton action = view.findViewById(R.id.confirm_action);

        tile.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, tileTintRes)));
        icon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(ContextCompat.getColor(this, iconColorRes)));

        titleTv.setText(title);
        if (!TextUtils.isEmpty(subtitle)) {
            subtitleTv.setText(subtitle);
            subtitleTv.setVisibility(View.VISIBLE);
        }
        if (!TextUtils.isEmpty(message)) {
            messageTv.setText(message);
            messageTv.setVisibility(View.VISIBLE);
        }

        if (chips != null && chips.length > 0) {
            chipsRow.setVisibility(View.VISIBLE);
            int gap = Math.round(8 * getResources().getDisplayMetrics().density);
            for (int i = 0; i < chips.length; i++) {
                View chip = getLayoutInflater().inflate(R.layout.item_core_chip, (ViewGroup) chipsRow, false);
                ((TextView) chip.findViewById(R.id.chip_label)).setText(chips[i][0]);
                ((TextView) chip.findViewById(R.id.chip_value)).setText(chips[i][1]);
                if (i > 0 && chip.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) chip.getLayoutParams()).setMarginStart(gap);
                }
                chipsRow.addView(chip);
            }
        }

        if (cancelLabel != null) {
            cancel.setText(cancelLabel);
        }
        action.setText(confirmLabel);
        action.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, confirmBgRes)));
        int ink = ContextCompat.getColor(this, confirmInkRes);
        action.setTextColor(ink);
        if (confirmIconRes != 0) {
            action.setIconResource(confirmIconRes);
            action.setIconTint(ColorStateList.valueOf(ink));
        } else {
            action.setIcon(null);
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(CoreConnectionActivity.this)
                .setView(view)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        if (onCancel != null) {
            dialog.setOnCancelListener(d -> onCancel.run());
        }
        cancel.setOnClickListener(b -> {
            dialog.dismiss();
            if (onCancel != null) {
                onCancel.run();
            }
        });
        action.setOnClickListener(b -> {
            dialog.dismiss();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        dialog.show();
    }

    // Modern connect / disconnect confirmation (mirrors the design's connect sheet).
    private void showConnectOrDisconnectConfirm(@NonNull CoreProfile prof, boolean isCurrentWireGuard) {
        if (isCurrentWireGuard) {
            showCoreConfirm(R.drawable.ic_cc_power, R.color.cc_danger, R.color.cc_danger_soft,
                    getString(R.string.core_disconnect_title), prof.getDisplayName(),
                    getString(R.string.core_disconnect_message), null,
                    getString(R.string.disconnect), R.drawable.ic_cc_power,
                    R.color.cc_danger, R.color.cc_on_danger,
                    () -> toggle(prof, false), null);
        } else {
            // Protocol chip intentionally omitted — at decision time users care
            // about the profile, not the transport. The string is kept for a
            // possible future Settings surface.
            showCoreConfirm(R.drawable.ic_cc_shield_check, R.color.cc_green, R.color.cc_green_soft,
                    getString(R.string.core_connect_title), prof.getDisplayName(), null,
                    null,
                    getString(R.string.connect), R.drawable.ic_cc_power,
                    R.color.cc_green, R.color.cc_on_green,
                    () -> toggle(prof, true), null);
        }
    }

    private void deleteProfDialog(CoreProfile prof) {
        showCoreConfirm(R.drawable.ic_delete_24dp, R.color.cc_danger, R.color.cc_danger_soft,
                getString(R.string.profile_delete_title), prof.getDisplayName(),
                getText(R.string.delete_vpn_profile) + " " + prof.getDisplayName() + "?", null,
                getString(R.string.profile_delete_button), 0,
                R.color.cc_danger, R.color.cc_on_danger,
                () -> deleteProfile(prof), null);
    }

    private void deleteProfile(CoreProfile coreProfile) {
        // Capture whether the profile being deleted is the currently connected one
        // before mutating any state; the connection card reads this source of truth.
        boolean wasRunning = isProfileTunnelUp(coreProfile);
        boolean removed = coreProfileAdaptor.deleteCoreProfile(coreProfile);
        if (removed && xmppConnectionService != null) {
            xmppConnectionService.deleteCoreProfile(coreProfile);
        }
        if (corePreferences != null && coreProfile.getProfName().equals(corePreferences.getString(PREF_LAST_PROFILE, null))) {
            corePreferences.edit().remove(PREF_LAST_PROFILE).apply();
        }
        if (selectedProfile != null && selectedProfile.getProfName().equals(coreProfile.getProfName())) {
            selectedProfile = null;
        }
        restorePreferredProfileSelection();
        updateProfileDropdownAvailability();

        Tunnel tunnel = PersistentConnectionProperties.getInstance().getTunnel(coreProfile.getProfName());
        AsyncTask.execute(() -> {
            boolean broughtDown = false;
            try {
                // Honor the running source of truth captured above rather than
                // re-deriving the teardown from backend.getState(). The Go backend
                // can return a stale DOWN on the first query after a long idle
                // period; gating the disconnect on that read left the tunnel up
                // while we cleared the running state and refreshed the UI, so VPN
                // traffic kept flowing after the connected profile was deleted.
                // Mirror the disconnect path and issue setState(DOWN) whenever the
                // deleted profile was the running one (still falling back to a
                // backend UP read when the source of truth disagrees).
                if (wasRunning || backend.getState(tunnel) == UP) {
                    markConnectionInactive();
                    PersistentConnectionProperties.getInstance().expectTeardown();
                    backend.setState(tunnel, DOWN, null);
                    broughtDown = true;
                }
            } catch (Exception ignored) {
            }
            final Runnable refreshUi = () -> runOnUiThread(() -> {
                setCoreIconEnabled();
                if (coreProfileAdaptor != null) {
                    coreProfileAdaptor.notifyDataSetChanged();
                }
            });
            // Deleting the connected profile tears down the tunnel, so clear the
            // running source of truth as well. Without this the big button kept
            // showing green / "Connected" because isRunningTunnel() still reported
            // the now-deleted profile as up.
            if (wasRunning || broughtDown) {
                if (xmppConnectionService != null) {
                    xmppConnectionService.clearRunningCoreProfiles();
                } else {
                    PersistentConnectionProperties.getInstance().markAllDisconnected();
                }
                // Same reboot hazard as the disconnect path: clearing the running
                // state moves the source of truth into DISCONNECTING, which is only
                // resolved by a backend reconciliation. After a reboot setState(DOWN)
                // is a no-op (the tunnel is already down) and emits no onStateChange
                // callback, so kick the monitor explicitly to query the backend and
                // clear the transition. The monitor broadcasts
                // ACTION_CORE_CONNECTION_STATE_CHANGED when DISCONNECTING clears,
                // refreshing this card through coreTileStateReceiver.
                WireGuardStateMonitor.notifyStateChanged();
            }
            refreshUi.run();
        });
    }

    private void downloadProfDialog(CoreProfile prof) {
        showCoreConfirm(R.drawable.ic_download_24dp, R.color.cc_accent, R.color.cc_accent_soft,
                getString(R.string.profile_download_title), prof.getDisplayName(),
                getText(R.string.download_vpn_profile) + " " + prof.getDisplayName() + "?", null,
                getString(R.string.profile_download_button), R.drawable.ic_download_24dp,
                R.color.cc_accent, R.color.cc_accent_ink,
                () -> copyFileToSD(prof), null);
    }

    private void copyFileToSD(CoreProfile profile) {
        try {
            String profilePath = profile.getProfName() + ".conf";
            profileToDownload = profile;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_TITLE, profilePath);
            startActivityForResult(intent, REQUEST_CODE_CREATE_FILE);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Profile download failed", e);
            runOnUiThread(() -> Toast.makeText(this, R.string.profile_download_failed, Toast.LENGTH_SHORT).show());
        }
    }

    /** Build a short 2-letter code chip from a profile's display name. */
    private String codeFor(@Nullable CoreProfile profile) {
        if (profile == null) {
            return "";
        }
        String name = profile.getDisplayName();
        if (TextUtils.isEmpty(name)) {
            return "··";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 2; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.length() == 0 ? "··" : sb.toString();
    }

    private void selectProfile(@Nullable CoreProfile profile, boolean persistSelection) {
        selectedProfile = profile;
        if (profile != null) {
            binding.serverCardName.setText(profile.getDisplayName());
            binding.serverCardCode.setText(codeFor(profile));
            binding.selectedProfileCard.setVisibility(View.VISIBLE);
            binding.buttonRenameProfile.setEnabled(true);
            binding.buttonDeleteProfile.setEnabled(true);
            binding.buttonDownloadProfile.setEnabled(true);
            if (persistSelection && corePreferences != null) {
                corePreferences.edit().putString(PREF_LAST_PROFILE, profile.getProfName()).apply();
            }
        } else {
            binding.serverCardName.setText(R.string.core_profile_spinner_hint);
            binding.serverCardCode.setText("");
            binding.selectedProfileCard.setVisibility(View.GONE);
            binding.buttonRenameProfile.setEnabled(false);
            binding.buttonDeleteProfile.setEnabled(false);
            binding.buttonDownloadProfile.setEnabled(false);
        }
        updateSelectedProfileCard();
    }

    // ── Server picker bottom sheet (replaces the cramped inline dropdown) ─────
    private void openServerSheet() {
        if (coreProfileAdaptor == null || coreProfileAdaptor.profileList.isEmpty()) {
            importProfilesAlert();
            return;
        }
        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        final View sheet = getLayoutInflater().inflate(R.layout.sheet_core_servers, null);
        dialog.setContentView(sheet);

        View close = sheet.findViewById(R.id.sheet_close);
        if (close != null) {
            close.setOnClickListener(v -> dialog.dismiss());
        }

        LinearLayout list = sheet.findViewById(R.id.server_list);
        LayoutInflater inflater = getLayoutInflater();
        String runningName = runningProfileName;
        String selectedName = selectedProfile != null ? selectedProfile.getProfName() : null;

        for (CoreProfile profile : coreProfileAdaptor.profileList) {
            View row = inflater.inflate(R.layout.item_core_server, (ViewGroup) list, false);
            TextView code = row.findViewById(R.id.server_item_code);
            TextView name = row.findViewById(R.id.server_item_name);
            View check = row.findViewById(R.id.server_item_check);

            code.setText(codeFor(profile));
            name.setText(profile.getDisplayName());

            boolean isRunning = runningName != null && runningName.equals(profile.getProfName());
            boolean isSelected = isRunning
                    || (runningName == null && selectedName != null && selectedName.equals(profile.getProfName()));
            row.setBackgroundResource(isSelected
                    ? R.drawable.cc_server_item_bg_active : R.drawable.cc_server_item_bg);
            check.setVisibility(isRunning ? View.VISIBLE : View.GONE);

            row.setOnClickListener(v -> {
                dialog.dismiss();
                handleProfileSelection(profile);
            });
            list.addView(row);
        }
        dialog.show();
    }

    private void handleProfileSelection(@NonNull CoreProfile profile) {
        CoreProfile previousSelection = selectedProfile;
        CoreProfile runningProfile = getRunningCoreProfileSafely();
        boolean vpnConnected = PersistentConnectionProperties.getInstance().isRunningTunnel();
        if (vpnConnected && shouldWarnAboutProfileChange(profile, runningProfile)) {
            showProfileChangeWarning(previousSelection, profile, runningProfile);
        } else {
            selectProfile(profile, true);
        }
    }

    private boolean shouldWarnAboutProfileChange(@NonNull CoreProfile newProfile, @Nullable CoreProfile runningProfile) {
        if (runningProfile == null) {
            runningProfile = selectedProfile;
        }
        return runningProfile != null && !runningProfile.getProfName().equals(newProfile.getProfName());
    }

    private void showProfileChangeWarning(@Nullable CoreProfile previousSelection,
                                          @NonNull CoreProfile newSelection,
                                          @Nullable CoreProfile runningProfile) {
        showCoreConfirm(R.drawable.ic_cc_shield_check, R.color.cc_green, R.color.cc_green_soft,
                getString(R.string.core_switch_title), newSelection.getDisplayName(),
                getText(R.string.core_profile_change_disconnect_warning) + " "
                        + (previousSelection != null ? previousSelection.getDisplayName() : "") + "?",
                null,
                getString(R.string.core_switch_action), R.drawable.ic_cc_power,
                R.color.cc_green, R.color.cc_on_green,
                () -> performProfileSwitch(runningProfile, newSelection),
                () -> restoreProfileSelection(previousSelection));
    }

    private void restoreProfileSelection(@Nullable CoreProfile profile) {
        selectProfile(profile, false);
    }

    @Nullable
    private CoreProfile getRunningCoreProfileSafely() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            return null;
        }
        try {
            return xmppConnectionService.getRunningCoreProfile();
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Unable to determine running core profile", e);
        }
        return null;
    }

    private void performProfileSwitch(@Nullable CoreProfile runningProfile, @NonNull CoreProfile newSelection) {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            selectProfile(newSelection, true);
            connectAlertToggle(newSelection);
            return;
        }
        if (runningProfile != null && !runningProfile.getProfName().equals(newSelection.getProfName())) {
            toggle(runningProfile, false);
        }
        selectProfile(newSelection, true);
        connectAlertToggle(newSelection, true);
    }

    private void updateProfileDropdownAvailability() {
        boolean hasProfiles = coreProfileAdaptor != null && !coreProfileAdaptor.profileList.isEmpty();
        if (!hasProfiles) {
            selectProfile(null, false);
            binding.serverCardName.setText(R.string.core_no_profiles_available);
        }
    }

    private void restorePreferredProfileSelection() {
        if (coreProfileAdaptor == null) {
            return;
        }
        if (coreProfileAdaptor.profileList.isEmpty()) {
            selectProfile(null, false);
            return;
        }
        CoreProfile candidate = null;
        if (selectedProfile != null) {
            candidate = findProfileByName(selectedProfile.getProfName());
        }
        if (candidate == null && xmppConnectionServiceBound) {
            try {
                CoreProfile running = xmppConnectionService.getRunningCoreProfile();
                if (running != null) {
                    candidate = findProfileByName(running.getProfName());
                }
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Unable to restore running core profile selection", e);
            }
        }
        if (candidate == null && corePreferences != null) {
            candidate = findProfileByName(corePreferences.getString(PREF_LAST_PROFILE, null));
        }
        if (candidate == null && !coreProfileAdaptor.profileList.isEmpty()) {
            candidate = coreProfileAdaptor.profileList.get(0);
        }
        selectProfile(candidate, false);
    }

    private void updateSelectedProfileCard() {
        // No per-profile config is surfaced in the UI.
    }

    private boolean isProfileRunning(@NonNull CoreProfile profile) {
        return PersistentConnectionProperties.getInstance().isProfileRunning(profile.getProfName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.updateOfflineStatusBar();
        }
        registerCoreTileStateReceiver();
        BottomNavigationView nav_conversations = findViewById(R.id.navigation_conversations);
        if (nav_conversations != null) {
            NavigationUtils.updatePhoneVisibility(nav_conversations,
                    getPreferences().getBoolean("user_sms_enabled", false));
            NavigationUtils.updatePhoneBadge(nav_conversations, this);
            refreshProfilesAfterVpnSync(nav_conversations);
        }

        startExternalVpnMonitoring();
        binding.externalVpnWarning.setVisibility(View.GONE);
        CoreRevokeAlert.setCoreScreenVisible(true);
        maybeShowRevokedAlert();
    }

    // Another VPN app (or system settings) took the VPN from Core while it was connected.
    private void maybeShowRevokedAlert() {
        if (isFinishing()) {
            return;
        }
        final String profileName = CoreRevokeAlert.getPendingProfile(this);
        if (profileName == null) {
            return;
        }
        if (PersistentConnectionProperties.getInstance().isRunningTunnel()) {
            CoreRevokeAlert.clear(this);
            return;
        }
        // The banner is redundant once the user is looking at the dialog.
        CoreRevokeAlert.dismissNotification(this);
        if (revokedAlertShowing || !profilesLoaded) {
            // A fresh screen resumes before its profiles load; loadFromDb calls back here.
            return;
        }
        final CoreProfile profile = findProfileByName(profileName);
        revokedAlertShowing = true;
        showCoreConfirm(R.drawable.ic_cc_power, R.color.cc_danger, R.color.cc_danger_soft,
                getString(R.string.core_revoked_title),
                profile != null ? profile.getDisplayName() : null,
                getString(R.string.core_revoked_body),
                null,
                getString(R.string.core_revoked_turn_back_on), R.drawable.ic_cc_power,
                R.color.cc_accent, R.color.cc_on_accent,
                getString(R.string.core_revoked_keep_off),
                () -> {
                    revokedAlertShowing = false;
                    CoreRevokeAlert.clear(this);
                    final CoreProfile target = reconnectProfileFor(profileName);
                    if (target != null) {
                        connectAlertToggle(target, true);
                    }
                },
                () -> {
                    revokedAlertShowing = false;
                    CoreRevokeAlert.clear(this);
                });
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

    private void refreshProfilesAfterVpnSync(@Nullable BottomNavigationView navConversations) {
        refreshProfilesAfterVpnSync(navConversations, null);
    }

    private void refreshProfilesAfterVpnSync(@Nullable BottomNavigationView navConversations, @Nullable Runnable afterLoaded) {
        if (!xmppConnectionServiceBound) {
            return;
        }
        WireGuardStateMonitor.notifyStateChanged(() -> runOnUiThread(() -> {
            if (!xmppConnectionServiceBound) {
                return;
            }
            loadFromDb(() -> {
                if (navConversations != null) {
                    NavigationUtils.updateSecurityHubBadge(navConversations,
                            !xmppConnectionService.getSecInfo().isSecure());
                }
                if (afterLoaded != null) {
                    afterLoaded.run();
                }
            });
        }));
    }

    @Override
    public void onPause() {
        CoreRevokeAlert.setCoreScreenVisible(false);
        unregisterCoreTileStateReceiver();
        stopExternalVpnMonitoring();
        stopSessionUpdates();
        super.onPause();
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        unregisterCoreTileStateReceiver();
        stopSessionUpdates();
        super.onDestroy();
    }

    private void startExternalVpnMonitoring() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        NetworkRequest request = WireGuardStateMonitor.vpnNetworkRequest();
        externalVpnNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            // Network callbacks arrive on ConnectivityThread; the warning is a view.
            public void onAvailable(Network network) {
                runOnUiThread(() -> updateExternalVpnWarning());
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> updateExternalVpnWarning());
            }
        };
        cm.registerNetworkCallback(request, externalVpnNetworkCallback);
    }

    private void stopExternalVpnMonitoring() {
        if (externalVpnNetworkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(externalVpnNetworkCallback);
                } catch (Exception ignored) {
                }
            }
            externalVpnNetworkCallback = null;
        }
    }

    private void updateExternalVpnWarning() {
        ExternalVpnInfo info = getExternalVpnInfo();
        if (info.active) {
            String message = getString(R.string.external_vpn_always_on_alert);
            binding.externalVpnWarning.setText(message);
            binding.externalVpnWarning.setTextColor(ContextCompat.getColor(this, R.color.md_theme_error));
            binding.externalVpnWarning.setVisibility(View.VISIBLE);
        } else {
            binding.externalVpnWarning.setVisibility(View.GONE);
        }
    }

    private void flashExternalVpnWarning() {
        AlphaAnimation anim = new AlphaAnimation(0.0f, 1.0f);
        anim.setDuration(200);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(2);
        binding.externalVpnWarning.startAnimation(anim);
    }

    private ExternalVpnInfo getExternalVpnInfo() {
        ExternalVpnInfo info = new ExternalVpnInfo();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return info;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return info;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return info;
        }
        try {
            if (backend != null) {
                Set<String> names = backend.getRunningTunnelNames();
                if (!names.isEmpty()) {
                    return info;
                }
            }
        } catch (Exception e) {
        }
        int uid = caps.getOwnerUid();
        if (uid != Process.INVALID_UID) {
            PackageManager pm = getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                for (String pkg : packages) {
                    if (pkg.equals(getPackageName())) {
                        return info;
                    }
                }
                String pkg = packages[0];
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    info.appName = pm.getApplicationLabel(ai).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    info.appName = pkg;
                }
            }
        }
        info.active = true;
        return info;
    }

    private static class ExternalVpnInfo {
        boolean active = false;
        String appName;
        String profileName;
    }

    private void importProfilesAlert() {
        showCoreConfirm(R.drawable.baseline_import_export_24, R.color.cc_accent, R.color.cc_accent_soft,
                getString(R.string.profile_import_title), null,
                getText(R.string.import_vpn_profile) + "?", null,
                getString(R.string.dialog_continue), R.drawable.baseline_import_export_24,
                R.color.cc_accent, R.color.cc_accent_ink,
                this::startImportThread, null);
    }

    private void startImportThread() {
        if (xmppConnectionServiceBound) {
            Account account = xmppConnectionService.getFirstAccount();
            if (account == null) {
                Log.w(Config.LOGTAG, "Unable to import core profiles without an account");
                return;
            }
            setWaitLayout(true);
            waitingForUserSync = true;
            xmppConnectionService.queryGlacierUsers(account, false);
        }
    }

    private void importProfiles(@Nullable Runnable onComplete) {
        if (!xmppConnectionServiceBound) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        Account account = xmppConnectionService.getFirstAccount();
        if (account == null) {
            Log.w(Config.LOGTAG, "Unable to import core profiles without an account");
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        xmppConnectionService.importCoreProfiles(account, () -> {
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private void connectAlertToggle(CoreProfile prof) {
        connectAlertToggle(prof, false);
    }

    private void connectAlertToggle(CoreProfile prof, boolean autoConnect) {
        if (prof.getConfig() == null) {
            Toast.makeText(this, R.string.missing_vpn_config, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intentPrepare = GoBackend.VpnService.prepare(this);
        autoConnectAfterPermission = autoConnect;
        if (intentPrepare != null) {
            mCoreProfile = prof;
            startActivityForResult(intentPrepare, WIRE_GUARD_PERMISSION_REQUEST);
            return;
        }
        try {
            boolean isCurrentWireGuard = isProfileTunnelUp(prof);
            if (autoConnect) {
                autoConnectAfterPermission = false;
                if (isCurrentWireGuard) {
                    toggle(prof, false);
                } else {
                    toggle(prof, true);
                }
                return;
            }
            autoConnectAfterPermission = false;
            showConnectOrDisconnectConfirm(prof, isCurrentWireGuard);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "CoreConnection error ", e);
        }
    }

    private void loadFromDb() {
        loadFromDb(null);
    }

    private void loadFromDb(Runnable onLoaded) {
        new Thread(() -> {
            ArrayList<CoreProfile> profiles = xmppConnectionService.restoreCoreProfilesFromDatabase();
            runOnUiThread(() -> {
                coreProfileAdaptor.setProfileList(profiles);
                restorePreferredProfileSelection();
                updateProfileDropdownAvailability();
                setCoreIconEnabled();
                profilesLoaded = true;
                maybeShowRevokedAlert();
                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        }).start();
    }

    private void toggle(CoreProfile prof, boolean isConnect) {
        Tunnel tunnel = PersistentConnectionProperties.getInstance().getTunnel(prof.getProfName());
        AsyncTask.execute(() -> {
            try {
                // Honor the explicit intent decided by the caller (via the robust
                // isProfileTunnelUp(), which has a DB fallback) rather than
                // re-deriving the action from backend.getState(). The Go backend can
                // return a stale DOWN on the first query after a long idle period, so
                // re-deriving here caused the first disconnect tap to silently
                // re-establish the tunnel; only a second tap (once the state warmed
                // up) would actually tear it down.
                if (!isConnect) {
                    markConnectionInactive();
                    runOnUiThread(this::setDisconnectingUi);
                    PersistentConnectionProperties.getInstance().expectTeardown();
                    backend.setState(tunnel, DOWN, null);
                    // Hold the source of truth in DISCONNECTING until the backend
                    // confirms DOWN/empty, so a stale UP snapshot cannot flip the UI
                    // or persisted state back to Connected.
                    xmppConnectionService.clearRunningCoreProfiles();
                    // Proactively reconcile against the backend instead of waiting
                    // for the WireGuard onStateChange callback to drive it. That
                    // callback only fires when setState actually changes the tunnel
                    // state: after a reboot the backend already considers the tunnel
                    // DOWN, so setState(DOWN) is a no-op and emits no callback, and no
                    // OS connectivity change arrives either (the VPN was never really
                    // up post-reboot). Without this kick the DISCONNECTING transition
                    // never resolves and the spinner hangs. The monitor broadcasts
                    // ACTION_CORE_CONNECTION_STATE_CHANGED when the reconcile (now or
                    // via its recheck loop / 15s timeout) clears DISCONNECTING, which
                    // refreshes this card through coreTileStateReceiver.
                    WireGuardStateMonitor.notifyStateChanged();
                    runOnUiThread(() -> {
                        setCoreIconEnabled();
                        coreProfileAdaptor.notifyDataSetChanged();
                    });
                } else {
                    markConnectionInactive();
                    PersistentConnectionProperties.getInstance().markProfileConnecting(prof.getProfName());
                    runOnUiThread(this::setConnectingUi);
                    backend.setState(tunnel, UP, prof.getConfig());
                    xmppConnectionService.setRunningCoreProfile(prof);
                    markConnectionActive(prof);
                    runOnUiThread(() -> {
                        setCoreIconEnabled();
                        coreProfileAdaptor.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                PersistentConnectionProperties.getInstance().markConnectionTransitionFailed();
                Log.e(Config.LOGTAG, "CoreConnection error", e);
                runOnUiThread(() -> {
                    setCoreIconEnabled();
                    updateExternalVpnWarning();
                    if (getExternalVpnInfo().active) {
                        flashExternalVpnWarning();
                        xmppConnectionService.clearRunningCoreProfiles();
                        coreProfileAdaptor.notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private void setConnectingUi() {
        setTransitionUi(R.string.core_status_connecting);
    }

    private void setDisconnectingUi() {
        setTransitionUi(R.string.core_status_disconnecting);
    }

    private void setTransitionUi(int statusTextResId) {
        binding.connectionOrb.setState(ConnectionOrbView.STATE_CONNECTING);
        binding.connectionStatusText.setText(statusTextResId);
        ImageViewCompat.setImageTintList(binding.connectionIcon,
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.cc_accent)));
    }

    public void setCoreIconEnabled() {
        refreshConnectionCard();
    }

    private void refreshConnectionCard() {
        // Resolve the running profile from the single source of truth so the card's
        // connected flag and the profile it displays always come from the same place.
        // The adapter list is the in-memory copy that rename/delete update
        // synchronously, so reading the display name from there (rather than a
        // separate getRunningCoreProfile() DB query) removes the transient
        // disagreement window — and the main-thread DB read — that previously left
        // the card showing a stale nickname or a deleted profile.
        runOnUiThread(() -> {
            String runningName = PersistentConnectionProperties.getInstance().getPrimaryRunningProfileName();
            updateConnectionCardUi(findProfileByName(runningName));
        });
    }

    private void updateConnectionCardUi(@Nullable CoreProfile runningProfile) {
        CoreConnectionState connectionState = PersistentConnectionProperties.getInstance().getConnectionState();
        boolean connected = connectionState == CoreConnectionState.CONNECTED;
        boolean connecting = connectionState == CoreConnectionState.CONNECTING;
        boolean disconnecting = connectionState == CoreConnectionState.DISCONNECTING;
        boolean transitioning = connecting || disconnecting;
        boolean hasProfiles = coreProfileAdaptor != null && !coreProfileAdaptor.profileList.isEmpty();
        CoreProfile detailProfile = runningProfile;
        if (detailProfile == null) {
            detailProfile = findProfileByName(corePreferences != null ? corePreferences.getString(PREF_LAST_PROFILE, null) : null);
        }

        if (connected) {
            markConnectionActive(runningProfile != null ? runningProfile : detailProfile);
        } else {
            markConnectionInactive();
        }

        binding.coreHeader.setActivated(connected);

        // Orb + power glyph.
        binding.connectionOrb.setState(transitioning
                ? ConnectionOrbView.STATE_CONNECTING
                : (connected ? ConnectionOrbView.STATE_CONNECTED : ConnectionOrbView.STATE_OFF));
        int glyphTint = transitioning ? R.color.cc_accent : (connected ? R.color.cc_green : R.color.cc_muted);
        ImageViewCompat.setImageTintList(binding.connectionIcon,
                ColorStateList.valueOf(ContextCompat.getColor(this, glyphTint)));

        // Big status label.
        binding.connectionStatusText.setText(connecting
                ? R.string.core_status_connecting
                : (disconnecting
                ? R.string.core_status_disconnecting
                : (connected ? R.string.core_status_connected : R.string.core_status_disconnected)));
        binding.connectionStatusSubtitle.setVisibility(View.GONE);

        // Status pill — country chip + profile name, themed by state.
        String locationText;
        if (detailProfile != null) {
            locationText = detailProfile.getDisplayName();
        } else if (hasProfiles) {
            locationText = getString(R.string.core_no_active_profile);
        } else {
            locationText = getString(R.string.core_no_profiles_available);
        }
        binding.locationValue.setText(locationText);
        binding.statusPill.setBackgroundResource(connected
                ? R.drawable.cc_status_pill_on : R.drawable.cc_status_pill_off);
        binding.statusPillCode.setText(detailProfile != null ? codeFor(detailProfile) : "");
        binding.statusPillCode.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, connected ? R.color.cc_green : R.color.cc_border_strong)));
        binding.statusPillCode.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.cc_on_green : R.color.cc_muted));
        binding.locationValue.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.cc_green : R.color.cc_muted));

        // Session card permanently hidden; show protection prose instead.
        binding.sessionCard.setVisibility(View.GONE);
        binding.proseCard.setVisibility(connected ? View.GONE : View.VISIBLE);

        // Protection strip tint follows protected state.
        int stripTint = ContextCompat.getColor(this, connected ? R.color.cc_accent : R.color.cc_dim);
        ImageViewCompat.setImageTintList(binding.protectionStripIcon, ColorStateList.valueOf(stripTint));
        binding.protectionStripText.setTextColor(stripTint);

        // Drive live metering.
        if (connected) {
            CoreProfile meteredProfile = runningProfile != null ? runningProfile : detailProfile;
            runningProfileName = meteredProfile != null ? meteredProfile.getProfName() : null;
            startSessionUpdates();
        } else {
            runningProfileName = null;
            stopSessionUpdates();
        }

        updateSelectedProfileCard();
    }

    // ── Live session metering ─────────────────────────────────────────────────
    private void startSessionUpdates() {
        sessionHandler.removeCallbacks(sessionTick);
        resetSessionMeter();
        sessionHandler.post(sessionTick);
    }

    private void stopSessionUpdates() {
        sessionHandler.removeCallbacks(sessionTick);
        resetSessionMeter();
        if (binding != null) {
            binding.sparkline.reset();
        }
    }

    private void resetSessionMeter() {
        lastRx = -1L;
        lastTx = -1L;
        lastStatsAtNanos = 0L;
        lastDownBps = 0d;
        lastUpBps = 0d;
    }

    private void onSessionTick() {
        if (binding == null || !PersistentConnectionProperties.getInstance().isRunningTunnel()) {
            return;
        }
        // Duration from the connection timestamp.
        long base = corePreferences != null ? corePreferences.getLong(PREF_LAST_CONNECTED_AT, 0L) : 0L;
        if (base == 0L) {
            base = System.currentTimeMillis();
        }
        long secs = Math.max(0L, (System.currentTimeMillis() - base) / 1000L);
        binding.sessionDuration.setText(formatDuration(secs));

        final String profileName = runningProfileName;
        AsyncTask.execute(() -> {
            final long[] stats = readTunnelStats(profileName);
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                if (stats == null) {
                    binding.sessionDown.setText(R.string.core_stat_unavailable);
                    binding.sessionUp.setText(R.string.core_stat_unavailable);
                    binding.sessionRate.setText("0");
                } else {
                    long rx = stats[0];
                    long tx = stats[1];
                    long now = System.nanoTime();
                    // The tunnel exposes cumulative byte counters that the backend
                    // only refreshes every few seconds, so derive throughput from
                    // the change since the last reading where they actually moved,
                    // measured over the real elapsed time. Between refreshes hold
                    // the last rate; zero it once the counters go stale (idle).
                    if (lastRx < 0L || lastTx < 0L) {
                        lastDownBps = 0d;
                        lastUpBps = 0d;
                        lastRx = rx;
                        lastTx = tx;
                        lastStatsAtNanos = now;
                    } else if (rx != lastRx || tx != lastTx) {
                        double seconds = (now - lastStatsAtNanos) / 1_000_000_000d;
                        if (seconds > 0.05d) {
                            lastDownBps = Math.max(0L, rx - lastRx) / seconds;
                            lastUpBps = Math.max(0L, tx - lastTx) / seconds;
                        }
                        lastRx = rx;
                        lastTx = tx;
                        lastStatsAtNanos = now;
                    } else if (now - lastStatsAtNanos > SESSION_STALE_NANOS) {
                        lastDownBps = 0d;
                        lastUpBps = 0d;
                    }
                    binding.sessionDown.setText(formatRate(lastDownBps));
                    binding.sessionUp.setText(formatRate(lastUpBps));
                    binding.sessionRate.setText(rateValue(lastDownBps));
                    binding.sessionRateUnit.setText(rateUnit(lastDownBps));
                    binding.sparkline.push((float) lastDownBps);
                }
            });
        });

        sessionHandler.postDelayed(sessionTick, SESSION_TICK_MS);
    }

    /**
     * Read total rx/tx for the running tunnel via the WireGuard backend.
     * Uses reflection so the screen degrades gracefully (stats simply read as
     * unavailable) if the backend build doesn't expose statistics.
     */
    @Nullable
    private long[] readTunnelStats(@Nullable String profileName) {
        if (backend == null || TextUtils.isEmpty(profileName)) {
            return null;
        }
        try {
            Tunnel tunnel = PersistentConnectionProperties.getInstance().getTunnel(profileName);
            Object stats = backend.getClass()
                    .getMethod("getStatistics", Tunnel.class)
                    .invoke(backend, tunnel);
            if (stats == null) {
                return null;
            }
            long rx = (long) stats.getClass().getMethod("totalRx").invoke(stats);
            long tx = (long) stats.getClass().getMethod("totalTx").invoke(stats);
            return new long[]{rx, tx};
        } catch (Throwable t) {
            Log.w(SESSION_STATS_TAG, "readTunnelStats: failed for " + profileName, t);
            return null;
        }
    }

    private String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    /** Format a throughput rate (bytes/sec) for the Down/Up tiles, e.g. "1.2 MB/s". */
    private String formatRate(double bps) {
        return rateValue(bps) + " " + rateUnit(bps);
    }

    /** Auto-scaled numeric portion of a throughput rate (bytes/sec). */
    private static String rateValue(double bps) {
        bps = Math.max(0d, bps);
        if (bps >= 1024d * 1024d) {
            return String.format(Locale.US, "%.1f", bps / (1024d * 1024d));
        }
        if (bps >= 1024d) {
            return String.format(Locale.US, "%.1f", bps / 1024d);
        }
        return String.format(Locale.US, "%.0f", bps);
    }

    /** Auto-scaled unit for a throughput rate (bytes/sec). */
    private static String rateUnit(double bps) {
        if (bps >= 1024d * 1024d) {
            return "MB/s";
        }
        if (bps >= 1024d) {
            return "KB/s";
        }
        return "B/s";
    }

    private void markConnectionActive(@Nullable CoreProfile profile) {
        if (corePreferences == null) {
            return;
        }
        SharedPreferences.Editor editor = null;
        if (profile != null) {
            editor = corePreferences.edit();
            editor.putString(PREF_LAST_PROFILE, profile.getProfName());
        }
        if (corePreferences.getLong(PREF_LAST_CONNECTED_AT, 0L) == 0L) {
            if (editor == null) {
                editor = corePreferences.edit();
            }
            editor.putLong(PREF_LAST_CONNECTED_AT, System.currentTimeMillis());
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private void markConnectionInactive() {
        if (corePreferences == null) {
            return;
        }
        long last = corePreferences.getLong(PREF_LAST_CONNECTED_AT, 0L);
        if (last == 0L) {
            return;
        }
        long total = corePreferences.getLong(PREF_TOTAL_PROTECTED, 0L);
        long now = System.currentTimeMillis();
        long delta = Math.max(0L, now - last);
        corePreferences.edit()
                .putLong(PREF_TOTAL_PROTECTED, total + delta)
                .remove(PREF_LAST_CONNECTED_AT)
                .apply();
    }

    // The revoked profile, else the one the tile would pick (it may have been deleted since).
    @Nullable
    private CoreProfile reconnectProfileFor(@Nullable String profileName) {
        CoreProfile profile = findProfileByName(profileName);
        if (profile == null) {
            profile = findProfileByName(corePreferences != null
                    ? corePreferences.getString(PREF_LAST_PROFILE, null) : null);
        }
        if (profile == null && coreProfileAdaptor != null && !coreProfileAdaptor.profileList.isEmpty()) {
            profile = coreProfileAdaptor.profileList.get(0);
        }
        return profile;
    }

    private CoreProfile findProfileByName(@Nullable String profileName) {
        if (profileName == null || coreProfileAdaptor == null) {
            return null;
        }
        for (CoreProfile profile : coreProfileAdaptor.profileList) {
            if (profileName.equals(profile.getProfName())) {
                return profile;
            }
        }
        return null;
    }

    private boolean isProfileTunnelUp(@Nullable CoreProfile profile) {
        if (profile == null) {
            return false;
        }
        // Read the single source of truth rather than the Go backend. The backend
        // can report a stale state for a short window after a state change, which
        // is exactly what made the connect/disconnect intent disagree with the
        // screen; the source of truth is updated on the tap so the dialog action
        // stays consistent with what the card shows.
        return PersistentConnectionProperties.getInstance().isProfileRunning(profile.getProfName());
    }

    private void handlePrimaryToggle() {
        if (!xmppConnectionServiceBound || xmppConnectionService == null) {
            Toast.makeText(this, R.string.core_connection_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        CoreProfile running = getRunningCoreProfileSafely();
        if (running != null && isProfileTunnelUp(running)) {
            connectAlertToggle(running);
            return;
        }

        CoreProfile preferred = findProfileByName(corePreferences != null ?
                corePreferences.getString(PREF_LAST_PROFILE, null) : null);
        if (preferred == null && coreProfileAdaptor != null && !coreProfileAdaptor.profileList.isEmpty()) {
            preferred = coreProfileAdaptor.profileList.get(0);
        }
        if (preferred != null) {
            connectAlertToggle(preferred);
        } else if (PersistentConnectionProperties.getInstance().isRunningTunnel()) {
            Toast.makeText(this, R.string.core_no_active_profile, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.core_no_profiles_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WIRE_GUARD_PERMISSION_REQUEST) {
            if (mCoreProfile != null) {
                try {
                    boolean isCurrentWireGuard = isProfileTunnelUp(mCoreProfile);
                    boolean autoConnect = autoConnectAfterPermission;
                    autoConnectAfterPermission = false;
                    if (autoConnect) {
                        if (isCurrentWireGuard) {
                            toggle(mCoreProfile, false);
                        } else {
                            toggle(mCoreProfile, true);
                        }
                        return;
                    }
                    showConnectOrDisconnectConfirm(mCoreProfile, isCurrentWireGuard);
                } catch (Exception e) {
                    Log.e(Config.LOGTAG, "CoreConnection error ", e);
                }
            }
        }
        if (requestCode == REQUEST_CODE_CREATE_FILE && resultCode == RESULT_OK) {
            if (profileToDownload == null) {
                Log.e(Config.LOGTAG, "Error downloading core profile");
                return;
            }
            String fileContent = profileToDownload.getConfig().toWgQuickString();
            profileToDownload = null;
            Uri uri = data.getData();
            // A WireGuard config carries the private key, and the destination is whatever the
            // picker's result said. Write it only to a Uri that is not one of ours.
            if (!ContentUriValidator.isSafeToResolve(this, uri)) {
                Log.e(Config.LOGTAG, "Error downloading core profile: untrusted destination");
                return;
            }
            try {
                OutputStream outputStream = getContentResolver().openOutputStream(uri);
                if (outputStream != null) {
                    outputStream.write(fileContent.getBytes());
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(Config.LOGTAG, "Error downloading core profile", e);
            }
        }
    }

    private void setWaitLayout(boolean isWait) {
        importInProgress = isWait;
        if (importMenuItem != null) {
            importMenuItem.setEnabled(!isWait);
        }
        binding.topProgressBar.setVisibility(isWait ? View.VISIBLE : View.GONE);
    }

    private void mVpnInfo() {
        Intent intent = new Intent("android.net.vpn.SETTINGS");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    protected void refreshUiReal() {
    }

    @Override
    protected void onBackendConnected() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.updateOfflineStatusBar();
        }
        refreshProfilesAfterVpnSync(
                findViewById(R.id.navigation_conversations),
                this::maybeRunTileAutoConnect
        );
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private final View.OnClickListener mRefreshNetworkClickListener = v -> connectionToolbarAdapter.onClick();
}
