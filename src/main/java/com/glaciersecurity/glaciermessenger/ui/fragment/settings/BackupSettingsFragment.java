package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import android.content.DialogInterface;
import android.os.Bundle;

import com.glaciersecurity.glaciermessenger.AppSettings;
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
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.glaciersecurity.glaciermessenger.utils.Tools;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;
import com.google.common.primitives.Longs;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.worker.ExportBackupWorker;

import java.util.concurrent.TimeUnit;

public class BackupSettingsFragment extends XmppPreferenceFragment implements RefreshablePreferenceFragment{

    private static final String RECURRING_BACKUP = "recurring_backup";
    private static final String LOCAL_BACKUP = "local_backup";


    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_backup, rootKey);
        init();
    }

    private void init(){
        final ListPreference recurringBackup = findPreference(RECURRING_BACKUP);
        final var createLocalBackup = findPreference(LOCAL_BACKUP);
        final var backupLocation = findPreference(AppSettings.BACKUP_LOCATION);
        if (recurringBackup == null || backupLocation == null || createLocalBackup == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        final var appSettings = new AppSettings(requireContext());
        backupLocation.setSummary(
                getString(
                        R.string.pref_create_backup_summary,
                        appSettings.getCustomBackupLocationAsPath()));
        backupLocation.setOnPreferenceClickListener(this::onBackupLocationPreferenceClicked);
        createLocalBackup.setOnPreferenceClickListener(this::onLocalBackupPreferenceClicked);

        setValues(
                recurringBackup,
                R.array.recurring_backup_values,
                value -> timeframeValueToName(requireContext(), value));
    }

    private boolean onBackupLocationPreferenceClicked(final Preference preference) {
        return true;
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        if (RECURRING_BACKUP.equals(key)) {
            final var sharedPreferences = getPreferenceManager().getSharedPreferences();
            if (sharedPreferences == null) {
                return;
            }
            final Long recurringBackupInterval =
                    Longs.tryParse(
                            Strings.nullToEmpty(
                                    sharedPreferences.getString(RECURRING_BACKUP, null)));
            if (recurringBackupInterval == null) {
                return;
            }
            final var workManager = WorkManager.getInstance(requireContext());
            if (recurringBackupInterval <= 0) {
                workManager.cancelUniqueWork(RECURRING_BACKUP);
            } else {
                final Constraints constraints =
                        new Constraints.Builder()
                                .setRequiresBatteryNotLow(true)
                                .setRequiresStorageNotLow(true)
                                .build();

                final PeriodicWorkRequest periodicWorkRequest =
                        new PeriodicWorkRequest.Builder(
                                ExportBackupWorker.class,
                                recurringBackupInterval,
                                TimeUnit.SECONDS)
                                .setConstraints(constraints)
                                .setInputData(
                                        new Data.Builder()
                                                .putBoolean("recurring_backup", true)
                                                .build())
                                .build();
                workManager.enqueueUniquePeriodicWork(
                        RECURRING_BACKUP, ExistingPeriodicWorkPolicy.UPDATE, periodicWorkRequest);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.backup);
    }

    private boolean onLocalBackupPreferenceClicked(final Preference preference) {
        startLocalBackup();
        return true;
    }



    private void startLocalBackup() {
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_export_passprase, null);

        TextView passphraseTV = layout.findViewById(R.id.passphrase_value);
        TextView locationTV = layout.findViewById(R.id.download_location);
        locationTV.setText(String.format(getString(R.string.export_service_description_custom), AppSettings.getCustomBackupLocationAsPath()));

        String passphrase = Tools.getRandomString(8);
        passphraseTV.setText(passphrase);
        CheckBox isConfirmed = layout.findViewById(R.id.is_pass_verified);
        CheckBox exportMedia = layout.findViewById(R.id.backup_media_on_logout);
        TextView mediaLabel = layout.findViewById(R.id.backup_media_label);
        mediaLabel.setText(R.string.export_media_files);
        AppCompatImageView copyIcon = layout.findViewById(R.id.passphrase_copy_btn);
        copyIcon.setOnClickListener(view -> {
            Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            Tools.copyToClipboard(getContext(), passphrase, "passphrase");
        });
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity()).setView(layout);
        builder.setTitle(R.string.title_activity_export_local).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        }).setPositiveButton(R.string.create_backup, null);

        AlertDialog dialog = builder.create();

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(isConfirmed.isChecked());

            isConfirmed.setOnCheckedChangeListener((buttonView, isChecked) -> {
                positiveButton.setEnabled(isChecked);
            });

            positiveButton.setOnClickListener(v -> {
                    final OneTimeWorkRequest exportBackupWorkRequest =
                            new OneTimeWorkRequest.Builder(ExportBackupWorker.class)
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .setInputData(
                                            new Data.Builder()
                                                    .putString("passphrase", passphrase)
                                                    .putBoolean("export_media", exportMedia.isChecked())
                                                    .build())
                                    .build();
                    WorkManager.getInstance(requireContext())
                            .enqueueUniqueWork(
                                    LOCAL_BACKUP, ExistingWorkPolicy.KEEP, exportBackupWorkRequest);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    @Override
    public void onUserRefresh() {
        init();
    }
}
