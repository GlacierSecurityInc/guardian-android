package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.core.app.NotificationManagerCompat;

import com.amplifyframework.auth.cognito.result.AWSCognitoAuthSignOutResult;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.services.SecurityHubService;
import com.glaciersecurity.glaciermessenger.services.LocationService;
import com.glaciersecurity.glaciermessenger.services.IncomingCallNotificationService;
import com.glaciersecurity.glaciermessenger.services.NotificationService;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.ui.LoginAccountActivity;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.glaciersecurity.glaciermessenger.utils.SystemSecurityInfo;
import com.glaciersecurity.glaciermessenger.utils.Tools;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import android.os.Build;
import com.glaciersecurity.glaciermessenger.worker.ExportBackupWorker;
import com.glaciersecurity.glaciermessenger.utils.FileUtils;
import android.os.Environment;
import java.io.File;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.R;

import java.util.List;
import java.util.ArrayList;

public class MainSettingsFragment extends PreferenceFragmentCompat implements RefreshablePreferenceFragment {

    private static final String LOCAL_BACKUP = "local_backup";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey);
        final var about = findPreference("about");
        final var connection = findPreference("connection");
        final var whatsNew = findPreference(getString(R.string.whatsnew_button_key));


        final var logout = findPreference("logout_key");
        if (about == null || connection == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        about.setTitle(getString(R.string.title_activity_about_x, BuildConfig.APP_NAME));
        about.setSummary(
                String.format(
                        "%s %s ",
                        BuildConfig.APP_NAME,
                        BuildConfig.VERSION_NAME));
        if (ConnectionSettingsFragment.hideChannelDiscovery()) {
            connection.setSummary(R.string.pref_connection_summary);
        }
        if (whatsNew != null) {
            whatsNew.setOnPreferenceClickListener(
                    preference -> {
                        try {
                            startActivity(preference.getIntent());
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(
                                            requireContext(),
                                            R.string.application_found_to_open_website,
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                        return true;
                    });
        }
        if (logout != null) {
            logout.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(@NonNull Preference preference) {
                    return onLogout();
                }
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        init();
    }

    private boolean onLogout() {
        final var context = getContext();
        if (context == null) {
            return true;
        }

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.logout)
                .setMessage(R.string.account_logout_confirmation)
                .setPositiveButton(R.string.create_backup, (dialog, which) -> {
                    startLocalBackupThenLogout();
                })
                .setNegativeButton(R.string.logout_anyway_button_key, (dialog, which) -> {
                    performLogout();
                })
                .show();
        return true;
    }

    private void startLocalBackupThenLogout() {
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_export_passprase, null);

        TextView passphraseTV = layout.findViewById(R.id.passphrase_value);
        TextView locationTV = layout.findViewById(R.id.download_location);
        locationTV.setText(String.format(getString(R.string.export_service_description_custom), AppSettings.getCustomBackupLocationAsPath()));

        String passphrase = Tools.getRandomString(8);
        passphraseTV.setText(passphrase);
        CheckBox isConfirmed = layout.findViewById(R.id.is_pass_verified);
        CheckBox backupMedia = layout.findViewById(R.id.backup_media_on_logout);
        AppCompatImageView copyIcon = layout.findViewById(R.id.passphrase_copy_btn);
        copyIcon.setOnClickListener(view -> {
            Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            Tools.copyToClipboard(getContext(), passphrase, "passphrase");
        });
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity()).setView(layout);
        builder.setTitle(R.string.title_activity_export_local)
                .setNegativeButton(R.string.cancel, (dialogInterface, i) -> dialogInterface.dismiss())
                .setPositiveButton(R.string.create_backup, null);

        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(isConfirmed.isChecked());

            isConfirmed.setOnCheckedChangeListener((buttonView, isChecked) -> positiveButton.setEnabled(isChecked));

            positiveButton.setOnClickListener(v -> {
                final OneTimeWorkRequest exportBackupWorkRequest =
                        new OneTimeWorkRequest.Builder(ExportBackupWorker.class)
                                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                .setInputData(
                                        new Data.Builder()
                                                .putString("passphrase", passphrase)
                                                .putBoolean("export_media", backupMedia.isChecked())
                                                .build())
                                .build();
                final WorkManager workManager = WorkManager.getInstance(requireContext());
                final long backupStartedAtMillis = System.currentTimeMillis();
                workManager.enqueueUniqueWork(
                        LOCAL_BACKUP,
                        ExistingWorkPolicy.KEEP,
                        exportBackupWorkRequest);
                // Observe the unique-work stream rather than this request's id: with
                // ExistingWorkPolicy.KEEP an already-running backup keeps its job and discards the
                // request we just built, so its id would never resolve and the logout flow would
                // wait forever.
                final LiveData<List<WorkInfo>> workInfoLiveData =
                        workManager.getWorkInfosForUniqueWorkLiveData(LOCAL_BACKUP);
                final boolean[] observedActiveBackup = {false};
                final boolean[] decisionMade = {false};
                workInfoLiveData.observe(
                        getViewLifecycleOwner(),
                        new Observer<List<WorkInfo>>() {
                            @Override
                            public void onChanged(final List<WorkInfo> workInfos) {
                                if (decisionMade[0]) {
                                    return;
                                }
                                if (LogoutBackupDecision.hasActiveWork(workInfos)) {
                                    observedActiveBackup[0] = true;
                                    return;
                                }
                                if (LogoutBackupDecision.hasFreshSuccessfulBackup(
                                        workInfos,
                                        FileBackend.getBackupDirectoryCustom(),
                                        backupStartedAtMillis)) {
                                    decisionMade[0] = true;
                                    workInfoLiveData.removeObserver(this);
                                    performLogout();
                                } else if (observedActiveBackup[0]) {
                                    // We watched the backup run to completion but it did not produce
                                    // a fresh backup file, so let the user decide whether to log out
                                    // anyway. Emissions for stale, previously-finished records are
                                    // ignored until we have seen the current backup actually run.
                                    decisionMade[0] = true;
                                    workInfoLiveData.removeObserver(this);
                                    showFailedBackupConfirmation();
                                }
                            }
                        });
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showFailedBackupConfirmation() {
        new MaterialAlertDialogBuilder(requireActivity())
                .setMessage(R.string.account_logout_confirmation_onfailed)
                .setPositiveButton(R.string.logout_anyway_button_key, (d, w) -> performLogout())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }


                private void performLogout() {
                    XmppConnectionService mXmppConnectionService = requireService();
                    mXmppConnectionService.endAllCalls();
                    mXmppConnectionService.uninitializeConversationsClient();
                    List<Conversation> conversations = mXmppConnectionService.getConversations();
                    for (int i = (conversations.size() - 1); i >= 0; i--) {
                        mXmppConnectionService.clearConversationHistory(conversations.get(i));
                    }
                    final List<Account> accounts =
                            new ArrayList<>(mXmppConnectionService.getAccounts());
                    for (final Account account : accounts) {
                        mXmppConnectionService.deleteOrg(account.getOrganization());
                        mXmppConnectionService.deleteAccount(account);
                    }
                    mXmppConnectionService.setSmsInfo(new SMSdbInfo(mXmppConnectionService));
                    mXmppConnectionService.setSecInfo(new SystemSecurityInfo(mXmppConnectionService));
                    mXmppConnectionService.deleteAllPhoneDB();
                    mXmppConnectionService.deleteAllCoreProfiles();


                    clearCachedFilesAndStorage();

        final var context = getContext();
        if (context != null) {
            NotificationManagerCompat.from(context).cancelAll();
            NotificationService.cancelIncomingCallNotification(context);
            context.stopService(new Intent(context, IncomingCallNotificationService.class));
            SecurityHubService.cancelSecurityHubUpdates(context);
            context.stopService(new Intent(context, LocationService.class));
            context.stopService(new Intent(context, XmppConnectionService.class));
        }

        final Runnable goToLogin = () -> {
            Intent editAccount = new Intent(getContext(), LoginAccountActivity.class);
            editAccount.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(editAccount);
        };

        // Amplify may not be configured (e.g. a transient startup failure). Don't let that crash
        // the logout flow or leave the user stranded — fall through to the login screen.
        if (context != null && !GlacierGuardian.ensureAmplifyConfigured(context)) {
            Log.w(Config.LOGTAG, "Amplify not configured during logout; returning to login");
            goToLogin.run();
            return;
        }

        try {
            Amplify.Auth.signOut(signOutResult -> {
                if (signOutResult instanceof AWSCognitoAuthSignOutResult.CompleteSignOut) {
                    Log.i(Config.LOGTAG, "Signed out successfully");
                    goToLogin.run();
                }
            });
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "Auth signOut failed during logout; returning to login", e);
            goToLogin.run();
        }
    }

    private void clearCachedFilesAndStorage() {
        final var context = getContext();
        if (context == null) {
            return;
        }
        FileUtils.deleteDirectory(context.getCacheDir());
        FileUtils.deleteDirectory(context.getFilesDir());
        // Conversation media now lives under the app-owned external files
        // directory, which we can always delete without storage permissions.
        FileUtils.deleteDirectory(context.getExternalFilesDir(null));

        // Best-effort cleanup of any legacy media left in public storage by
        // older builds (only effective on pre-scoped-storage devices, API <= 28).
        final List<String> storageTypes = new ArrayList<>();
        storageTypes.add(Environment.DIRECTORY_DOWNLOADS);
        storageTypes.add(Environment.DIRECTORY_PICTURES);
        storageTypes.add(Environment.DIRECTORY_MOVIES);
        storageTypes.add(Environment.DIRECTORY_DOCUMENTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            storageTypes.add(Environment.DIRECTORY_RECORDINGS);
        }
        for (final String type : storageTypes) {
            final File dir = new File(
                    Environment.getExternalStoragePublicDirectory(type),
                    context.getString(R.string.app_name));
            FileUtils.deleteDirectory(dir);
        }
    }

    protected XmppActivity requireXmppActivity() {
        final var activity = requireActivity();
        if (activity instanceof XmppActivity xmppActivity) {
            return xmppActivity;
        }
        throw new IllegalStateException();
    }

    protected XmppConnectionService requireService() {
        final var xmppActivity = requireXmppActivity();
        final var service = xmppActivity.xmppConnectionService;
        if (service != null) {
            return service;
        }
        throw new IllegalStateException();
    }

	@Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.title_activity_settings);
    }


    @Override
    public void onUserRefresh() {
        init();
    }

    private void init(){
        final var mapping = findPreference("mapping_setting");
        final var phone = findPreference("phone_setting");
        if (mapping != null) {

            mapping.setVisible(getPreferenceManager().getSharedPreferences().getBoolean("tracking_enabled", false));

        }
        if (phone != null) {

            phone.setVisible(getPreferenceManager().getSharedPreferences().getBoolean("user_sms_enabled", false));

        }
    }
}
