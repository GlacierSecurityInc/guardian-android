package com.glaciersecurity.glaciermessenger.conversations.ui;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Environment;

import com.amplifyframework.auth.AuthException;
import com.amplifyframework.auth.AuthUserAttribute;
import com.amplifyframework.auth.cognito.result.AWSCognitoAuthSignOutResult;
import com.amplifyframework.auth.result.step.AuthSignInStep;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.entities.GlacierUserEntity;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiClient;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiResponses;
import com.glaciersecurity.glaciermessenger.utils.AuthFailures;
import com.glaciersecurity.glaciermessenger.utils.CognitoClientMigrationManager;
import com.glaciersecurity.glaciermessenger.utils.Log;

import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.preference.PreferenceManager;

import com.glaciersecurity.glaciermessenger.ui.ActionBarActivity;
import com.glaciersecurity.glaciermessenger.ui.Activities;
import com.glaciersecurity.glaciermessenger.ui.ConversationActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityImportBackupBinding;
import com.glaciersecurity.glaciermessenger.databinding.DialogEnterPasswordBinding;
import com.glaciersecurity.glaciermessenger.conversations.services.ImportBackupService;
import com.glaciersecurity.glaciermessenger.conversations.ui.adapter.BackupFileAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.MainThreadExecutor;
import com.glaciersecurity.glaciermessenger.utils.BackupFileHeader;
import com.glaciersecurity.glaciermessenger.utils.ContentUriValidator;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.provider.DocumentsContract;
import java.io.File;

public class ImportBackupActivity extends ActionBarActivity
        implements ServiceConnection,
                ImportBackupService.OnBackupFilesLoaded,
                BackupFileAdapter.OnItemClickedListener,
                ImportBackupService.OnBackupProcessed {

    private ActivityImportBackupBinding binding;

    private BackupFileAdapter backupFileAdapter;
    private ImportBackupService service;

    private String cognitoUsername;
    private String cognitoPassword;
    private String cognitoOrganization;
    // The Glacier user record fetched by the restore sign-in. The dialog already collected the
    // account password and authenticated with it, so these credentials belong to this session
    // rather than to the backup file, which deliberately carries none.
    private GlacierUserEntity mGlacierUser;
    private String mPassphrase;
    private ImportBackupService.BackupFile mBackupFile;

    /** The backup folder we hold a persisted read grant on, so it can be released later. */
    private static final String PREF_BACKUP_FOLDER_URI = "backup_folder_uri";

    private boolean mLoadingState = false;
    private boolean isServiceBound = false;
    private boolean importFlowInitialized = false;

    /**
     * A folder the user picked before the import service was bound, reported on once it is.
     *
     * Not persisted: losing it across a process death costs only the empty-folder message, and
     * the grant itself is in SharedPreferences where loadBackups() will still find it.
     */
    private Uri pendingChosenFolder;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_import_backup);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        setLoadingState(
                savedInstanceState != null
                        && savedInstanceState.getBoolean("loading_state", false));
        this.backupFileAdapter = new BackupFileAdapter();
        this.binding.list.setAdapter(this.backupFileAdapter);
        this.binding.browseFiles.setOnClickListener(ignored -> openBackupFolder());
        this.backupFileAdapter.setOnItemClickedListener(this);
    }



    @Override
    public void onSaveInstanceState(Bundle bundle) {
        bundle.putBoolean("loading_state", this.mLoadingState);
        super.onSaveInstanceState(bundle);
    }

    @Override
    public void onStart() {
        super.onStart();
        // A restore that finished while this activity was stopped left its completion marker
        // behind, because onStop() had already dropped the OnBackupProcessed listener. Coming back
        // through Recents lands here, where the eligibility check would see the account the
        // restore just created, call showExistingAccountWarning() and finish onto the login screen
        // underneath. Consume the marker and go where the user was headed instead.
        if (consumeRestoreCompleted()) {
            goToApp();
            return;
        }
        this.importFlowInitialized = false;
        checkImportEligibility();
    }

    private boolean consumeRestoreCompleted() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (!preferences.getBoolean(ImportBackupService.PREF_RESTORE_COMPLETED, false)) {
            return false;
        }
        preferences.edit().remove(ImportBackupService.PREF_RESTORE_COMPLETED).apply();
        return true;
    }

    /**
     * Leaves the restore screen for the conversation list, clearing the task so the login screen
     * this activity was launched from does not stay behind it.
     */
    private void goToApp() {
        startActivity(
                new Intent(this, ConversationActivity.class)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (this.service != null) {
            this.service.removeOnBackupProcessedListener(this);
        }
        if (this.isServiceBound) {
            unbindService(this);
            this.isServiceBound = false;
        }
        this.service = null;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ImportBackupService.ImportBackupServiceBinder binder =
                (ImportBackupService.ImportBackupServiceBinder) service;
        this.service = binder.getService();
        this.service.addOnBackupProcessedListener(this);
        setLoadingState(this.service.getLoadingState());
        final Uri chosen = this.pendingChosenFolder;
        this.pendingChosenFolder = null;
        if (chosen == null) {
            loadBackups(this.service);
        } else {
            // A folder picked while unbound, now reported on as the deliberate choice it was.
            loadBackupFolder(this.service, chosen, true);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        this.isServiceBound = false;
        this.service = null;
    }

    // Eligibility is about local data a restore would overwrite, so it asks the account table and
    // not Cognito: a user who has just restored has accounts and no session, and must still be
    // stopped from restoring over the top.
    private void checkImportEligibility() {
        if (isFinishing()) {
            return;
        }
        if (DatabaseBackend.getInstance(this).getAccounts().isEmpty()) {
            initializeImportFlow();
        } else {
            showExistingAccountWarning();
        }
    }

    /**
     * Returns true only if the URI is from a trusted local source.
     * Rejects http/https URIs that could be used to deliver crafted files via phishing links.
     */
    private boolean isUriTrusted(Uri uri) {
        if (uri == null) return false;
        final String scheme = uri.getScheme();
        if (scheme == null) return false;
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case "content":
            case "file":
                // The scheme on its own proves nothing: file:// can still name
                // /data/data/<our package>/..., and content:// one of our unexported providers.
                if (!ContentUriValidator.isSafeToResolve(this, uri)) {
                    Log.w(
                            Config.LOGTAG,
                            "ImportBackupActivity: rejecting URI into private storage");
                    return false;
                }
                return true;
            case "http":
            case "https":
                Log.w(Config.LOGTAG, "ImportBackupActivity: rejecting remote URI scheme: " + scheme);
                return false;
            default:
                Log.w(Config.LOGTAG, "ImportBackupActivity: rejecting unknown URI scheme: " + scheme);
                return false;
        }
    }

    private void initializeImportFlow() {
        if (importFlowInitialized || isFinishing()) {
            return;
        }
        importFlowInitialized = true;
        this.isServiceBound =
                bindService(new Intent(this, ImportBackupService.class), this, Context.BIND_AUTO_CREATE);
        final Intent intent = getIntent();
        if (intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction())
                && !this.mLoadingState) {
            Uri uri = intent.getData();
            if (uri != null) {
                if (!isUriTrusted(uri)) {
                    Toast.makeText(this, R.string.backup_external_uri_rejected, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                showExternalImportConfirmationDialog(uri);
                return;
            }
        }
        // No permission request here on purpose. This screen used to ask for READ_MEDIA_IMAGES /
        // VIDEO / AUDIO, inherited from upstream, which is the wrong permission for the job: a
        // backup is a .ceb, and READ_MEDIA_* only covers images, video and audio through
        // MediaStore. Granting or denying it changes nothing about reading one, so the prompt
        // could only ever be answered "no" without consequence -- while looking to the user like
        // the restore had been refused access it needed. Users reported exactly that: an access
        // error on a restore that then worked perfectly.
        //
        // The three routes that do reach a backup are unaffected: the on-disk scan sees files in
        // Download this app wrote itself, which scoped storage allows regardless; the folder
        // picker carries its own persisted tree grant; and a shared-in document carries a per-uri
        // grant. The media zip is unpacked into app-owned getExternalFilesDir().
        //
        // The list does not depend on it either -- onServiceConnected() calls loadBackups() as
        // soon as the binding lands, whatever the user would have answered.
        //
        // Nothing else asks for them either: with "selected photos" access the request opens the
        // photo picker. Stranded legacy media is picked up by the foreground rescan once
        // the user grants access in system settings.
    }

    private void showExternalImportConfirmationDialog(Uri uri) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_backup)
                .setMessage(R.string.backup_external_open_warning)
                .setPositiveButton(R.string.confirm, (d, w) -> openBackupFileFromUri(uri, true))
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void showExistingAccountWarning() {
        if (binding == null) {
            finish();
            return;
        }
        binding.browseFiles.setEnabled(false);
        binding.list.setEnabled(false);
        final Snackbar snackbar =
                Snackbar.make(
                                binding.coordinator,
                                R.string.import_backup_unavailable_account_exists,
                                Snackbar.LENGTH_LONG)
                        .setDuration(Snackbar.LENGTH_LONG);
        snackbar.addCallback(
                new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar transientBottomBar, int event) {
                        // Restoring is genuinely unavailable once an account exists, but finishing
                        // bare used to reveal the login screen beneath. Send them into the app.
                        goToApp();
                    }
                });
        snackbar.show();
    }

    @Override
    public void onBackupFilesLoaded(final List<ImportBackupService.BackupFile> files) {
        runOnUiThread(
                () -> {
                    backupFileAdapter.setFiles(files);
                    binding.list.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
                });
    }

    @Override
    public void onClick(final ImportBackupService.BackupFile backupFile) {
        showEnterPasswordDialog(backupFile, false);
    }

    private void openBackupFileFromUri(final Uri uri, final boolean finishOnCancel) {
        final var backupFileFuture = ImportBackupService.read(this, uri);
        Futures.addCallback(
                backupFileFuture,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final ImportBackupService.BackupFile backupFile) {
                        showEnterPasswordDialog(backupFile, finishOnCancel);
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        showBackupThrowable(throwable);
                    }
                },
                MainThreadExecutor.getInstance());
    }

    private void showBackupThrowable(final Throwable throwable) {
        if (throwable instanceof BackupFileHeader.OutdatedBackupFileVersion) {
            Snackbar.make(
                            binding.coordinator,
                            R.string.outdated_backup_file_format,
                            Snackbar.LENGTH_LONG)
                    .show();
        } else if (throwable instanceof IOException
                || throwable instanceof IllegalArgumentException) {
            Snackbar.make(binding.coordinator, R.string.not_a_backup_file, Snackbar.LENGTH_LONG)
                    .show();
        } else if (throwable instanceof SecurityException) {
            Snackbar.make(
                            binding.coordinator,
                            R.string.sharing_application_not_grant_permission,
                            Snackbar.LENGTH_LONG)
                    .show();
        }
    }

    /**
     * Reports a failed restore sign-in.
     *
     * These failures used to funnel into onBackupDecryptionFailed(), which says the backup could
     * not be decrypted and asks whether the password is right. Nothing has been decrypted at this
     * point -- the passphrase is not used until the service reads the file -- so a dropped network
     * or a backend error told the user their credentials were wrong and left them retyping a
     * correct passphrase. Say which of the two actually failed instead.
     */
    private void OnFailedLogin(final AuthFailures.Kind kind) {
        final int message =
                switch (kind) {
                    case CREDENTIALS -> R.string.restore_sign_in_incorrect_password;
                    case NETWORK -> R.string.restore_sign_in_no_connection;
                    case UNKNOWN -> R.string.restore_sign_in_failed;
                };
        runOnUiThread(
                () -> {
                    setLoadingState(false);
                    Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG).show();
                });
        signOutAfterFailedLogin();
    }

    private void OnFailedLogin(final AuthException error) {
        OnFailedLogin(AuthFailures.classify(error));
    }

    /**
     * The user backed out of the sign-in themselves; return to the file list without an error.
     */
    private void onSignInCancelled() {
        runOnUiThread(() -> setLoadingState(false));
        signOutAfterFailedLogin();
    }

    private void signOutAfterFailedLogin() {
        if (GlacierGuardian.isAmplifyConfigured()) {
            try {
                Amplify.Auth.signOut(signOutResult -> {});
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "Auth signOut failed", e);
            }
        }
    }

    private void showEnterPasswordDialog(
            final ImportBackupService.BackupFile backupFile, final boolean finishOnCancel) {
        final DialogEnterPasswordBinding enterPasswordBinding =
                DataBindingUtil.inflate(
                        LayoutInflater.from(this), R.layout.dialog_enter_password, null, false);
        enterPasswordBinding.explain.setText(
                getString(
                        R.string.enter_password_to_restore,
                        backupFile.getHeader().getJid().getEscapedLocal()));
        enterPasswordBinding.accountPassword.setText("");
        enterPasswordBinding.accountPassphrase.setText("");

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setView(enterPasswordBinding.getRoot());
        builder.setTitle(R.string.restore_account);
        builder.setNegativeButton(
                R.string.cancel,
                (dialog, which) -> {
                    if (finishOnCancel) {
                        finish();
                    }
                });
        builder.setPositiveButton(R.string.restore, null);
        builder.setCancelable(false);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(
                (d) -> {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    v -> {
                                        final String password =
                                                enterPasswordBinding
                                                        .accountPassword
                                                        .getEditableText()
                                                        .toString();
                                        if (password.isEmpty()) {
                                            enterPasswordBinding.accountPasswordLayout.setError(
                                                    getString(R.string.please_enter_password));
                                            return;
                                        }
                                        final String passphrase =
                                                enterPasswordBinding
                                                        .accountPassphrase
                                                        .getEditableText()
                                                        .toString();
                                        if (passphrase.isEmpty()) {
                                            final String passphraseError =
                                                    getString(R.string.please_enter_passphrase);
                                            enterPasswordBinding.accountPassphrase.setError(
                                                    passphraseError);
                                            enterPasswordBinding.accountPassphraseLayout.setError(
                                                    passphraseError);
                                            return;
                                        }
                                        mPassphrase = passphrase;
                                        cognitoPassword = password;
                                        mBackupFile = backupFile;
                                        setLoadingState(true);
                                        cognitoLogin();
                                        d.dismiss();
                                    });
                });
        dialog.show();
    }

    private void cognitoLogin() {
        // Amplify may have failed to configure at startup; make sure it is ready before touching
        // Amplify.Auth, otherwise the framework throws an uncaught IllegalStateException.
        if (!GlacierGuardian.ensureAmplifyConfigured(getApplicationContext())) {
            Log.e(Config.LOGTAG, "Amplify is not configured; cannot sign in");
            OnFailedLogin(AuthFailures.Kind.UNKNOWN);
            return;
        }
        try {
            Amplify.Auth.signOut(signOutResult -> {
                logIn();
            });
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "Auth signOut before sign-in failed; continuing", e);
            logIn();
        }




    }

    private void logIn(){
        try {
            cognitoUsername = mBackupFile.getHeader().getJid().getEscapedLocal();
            Amplify.Auth.signIn(
                    cognitoUsername,
                    cognitoPassword,
                    result -> {
                        if (result.isSignedIn()) {
                            continueSignIn();

                            Log.i(Config.LOGTAG, "Sign in succeeded");
                        } else if (result.getNextStep().getSignInStep().equals(AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE)) {
                            Log.i(Config.LOGTAG, "Confirm sign-in with MFA code");
                            runOnUiThread(this::showMfaInputDialog);
                        } else if (result.getNextStep().getSignInStep().equals(AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD)) {
                            // Restore has no password-reset flow of its own. This used to log and
                            // return, leaving the spinner up for good; send the user to the login
                            // screen to reset instead.
                            Log.i(Config.LOGTAG, "Password reset required");
                            runOnUiThread(
                                    () -> {
                                        setLoadingState(false);
                                        Snackbar.make(
                                                        binding.coordinator,
                                                        R.string.reauthentication_additional_step_required,
                                                        Snackbar.LENGTH_LONG)
                                                .show();
                                    });
                            signOutAfterFailedLogin();
                        } else {
                            Log.i(Config.LOGTAG, "Further sign-in step required: " + result.getNextStep().getSignInStep().toString());
                            OnFailedLogin(AuthFailures.Kind.UNKNOWN);
                        }
                    },
                    error -> {
                        Log.e(Config.LOGTAG, "Sign in failed", error);
                        OnFailedLogin(error);
                    }
);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Sign in failed", e);
            OnFailedLogin(AuthFailures.classifyThrowable(e));
        }
    }

    private void showMfaInputDialog() {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.mfa_verification_title);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        builder.setView(layout);


        // Set up the input
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.mfa_enter_code_hint));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(6) }); // Optional: limit to 6 digits
        layout.addView(input);

        // Set up the buttons
        builder.setPositiveButton(R.string.mfa_confirm_button, (dialog, which) -> {
            String code = input.getText().toString().trim();
            if (!code.isEmpty()) {
                continueMFA(code);
            } else {
                Toast.makeText(this, R.string.auth_code_cannot_be_empty, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
            dialog.cancel();
            onSignInCancelled();
        });

        builder.show();
    }


    private void continueMFA(String mfaCode) {
        Amplify.Auth.confirmSignIn(
                mfaCode,
                confirmResult -> {
                    if (confirmResult.isSignedIn()) {
                        continueSignIn();

                        Log.i(Config.LOGTAG, "MFA Sign in complete");
                    } else {
                        Log.i(Config.LOGTAG, "MFA step incomplete");
                        OnFailedLogin(AuthFailures.Kind.UNKNOWN);

                    }
                },
                error -> {
                    Log.e(Config.LOGTAG, "Failed to complete MFA sign-in", error);
                    OnFailedLogin(error);
                }
);
    }

    public void continueSignIn() {

        Amplify.Auth.fetchUserAttributes(
                attributes -> {
                    int attributeCount = 0;
                    for (AuthUserAttribute attribute : attributes) {
                        attributeCount++;
                        if (attribute.getKey().getKeyString().equals("custom:organization")) {
                            cognitoOrganization = attribute.getValue();
                            break;
                        }

                    }
                    Log.i(Config.LOGTAG, "Fetched user attributes (" + attributeCount + ")");
                    queryGlacierUsersTable(cognitoUsername, cognitoOrganization);

                } ,
                error -> {
                    Log.e(Config.LOGTAG, "Failed to fetch user attributes", error);
                    OnFailedLogin(error);
                }


);
    }

    private void queryGlacierUsersTable(String user, String organization) {
            MobileApiClient.service().getGlacierUser(organization, user).enqueue(new retrofit2.Callback<MobileApiResponses.GlacierUserResponse>() {
                @Override
                public void onResponse(retrofit2.Call<MobileApiResponses.GlacierUserResponse> call,
                                       retrofit2.Response<MobileApiResponses.GlacierUserResponse> response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.e(Config.LOGTAG, "Glacier user lookup failed with code " + response.code());
                        // The Cognito sign-in already succeeded, so a 401/403 here is the session
                        // being rejected rather than the password the user typed; everything else
                        // is a backend problem. Neither is worth sending them back to the password
                        // field, so both report as a service failure.
                        OnFailedLogin(AuthFailures.Kind.UNKNOWN);
                        return;
                    }
                    try {
                        final GlacierUserEntity userData = MobileApiClient.toGlacierUsers(response.body().user);
                        if (userData == null) {
                            Log.e(Config.LOGTAG, "Glacier user lookup returned no user");
                            OnFailedLogin(AuthFailures.Kind.UNKNOWN);
                            return;
                        }
                        mGlacierUser = userData;
                        startImport(mBackupFile, mPassphrase);
                    } catch (Exception e){
                        Log.e(Config.LOGTAG, "Glacier user lookup failed", e);
                        OnFailedLogin(AuthFailures.classifyThrowable(e));

                    }
                }

                @Override
                public void onFailure(retrofit2.Call<MobileApiResponses.GlacierUserResponse> call, Throwable t) {
                    // Retrofit reports every transport failure here -- no connectivity, DNS,
                    // timeout -- so this is the network bucket unless it is something stranger.
                    Log.e(Config.LOGTAG, "Glacier user lookup failed", t);
                    OnFailedLogin(AuthFailures.classifyThrowable(t));

                }
            });
        }

    private void startImport(ImportBackupService.BackupFile backupFile, String passphrase){
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .edit()
                .remove(ImportBackupService.PREF_RESTORE_COMPLETED)
                .apply();
        final Intent intent = getIntent(backupFile, passphrase);
        setLoadingState(true);
        ContextCompat.startForegroundService(this, intent);
    }

    @NonNull
    private Intent getIntent(ImportBackupService.BackupFile backupFile, String passphrase) {
        final Uri uri = backupFile.getUri();
        Intent intent = new Intent(this, ImportBackupService.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra("passphrase", passphrase);
        // Hand the service the credentials this restore already authenticated with. The service
        // outlives this activity, so the restore finishes -- account enabled, credentials in
        // place -- even when the activity was stopped while the import ran and its
        // OnBackupProcessed listener was torn down in onStop().
        // Plain extras rather than the entity itself: an Intent to a service is parceled through
        // ActivityManager, and a Serializable payload that fails to parcel would take down the
        // restore before it started.
        final GlacierUserEntity user = mGlacierUser;
        if (user != null) {
            intent.putExtra("xmpp_token", user.getXmppToken());
            intent.putExtra("first_name", user.getFirstName());
            intent.putExtra("last_name", user.getLastName());
            intent.putExtra("add_to_core_vpn", Boolean.TRUE.equals(user.getAddToCoreVpn()));
            intent.putExtra(
                    "add_user_to_purchase_numbers",
                    Boolean.TRUE.equals(user.getAddUserToPurchaseNumbers()));
            intent.putExtra("is_sms_enabled", Boolean.TRUE.equals(user.getIsSmsEnabled()));
            final List<String> coreRegion = user.getCoreRegion();
            intent.putStringArrayListExtra(
                    "core_region",
                    coreRegion == null ? new ArrayList<>() : new ArrayList<>(coreRegion));
        }
        intent.putExtra("cognito_username", cognitoUsername);
        intent.putExtra("cognito_password", cognitoPassword);
        intent.putExtra("organization", cognitoOrganization);
        if ("file".equals(uri.getScheme())) {
            intent.putExtra("file", uri.getPath());
        } else {
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        final Uri mediaZipUri = backupFile.getMediaZipUri();
        if (mediaZipUri != null) {
            intent.putExtra("media_zip_uri", mediaZipUri.toString());
            // An extra carries no grant of its own; ClipData is what the read flag travels on.
            intent.setClipData(ClipData.newRawUri(null, mediaZipUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }

    private void setLoadingState(final boolean loadingState) {
        runOnUiThread(()->{
            binding.coordinator.setVisibility(loadingState ? View.GONE : View.VISIBLE);
            binding.inProgress.setVisibility(loadingState ? View.VISIBLE : View.GONE);
            setTitle(loadingState ? R.string.restoring_backup : R.string.restore_backup);
            Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
            configureActionBar(getSupportActionBar(), !loadingState);
            this.mLoadingState = loadingState;
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (resultCode != RESULT_OK || requestCode != 0xbac || intent == null) {
            return;
        }
        final Uri treeUri = intent.getData();
        if (treeUri == null || !isUriTrusted(treeUri)) {
            Toast.makeText(this, R.string.backup_external_uri_rejected, Toast.LENGTH_LONG).show();
            return;
        }
        // The restore runs in a service that outlives this activity, and a user who has to
        // retry should not have to pick the folder again, so hold the grant rather than
        // leaning on the one this task happens to carry.
        takeBackupFolderPermission(treeUri);
        final var importService = this.service;
        if (importService == null) {
            // Not a failure, and it happens on every single pick. Opening the picker stopped this
            // activity, and onStop() unbinds and nulls the service; coming back, onStart() only
            // *starts* an async eligibility check that binds later, and onActivityResult() runs
            // long before either that round trip or the binding completes. Reporting "could not
            // read that folder" here told the user their folder was unreadable moments before the
            // very same folder loaded and restored correctly.
            //
            // The grant is already persisted above, so loadBackups() would pick this folder up on
            // its own. Hold the choice only so the binding can report an empty folder or an
            // unusable file, which is the part that needs to know the user chose deliberately.
            this.pendingChosenFolder = treeUri;
            return;
        }
        // A folder that turns out to hold no backup has to say so. onBackupFilesLoaded() only
        // hides the list, which reads as nothing happening at all when the user has just
        // deliberately chosen where to look.
        loadBackupFolder(importService, treeUri, true);
    }

    /**
     * Fills the list, preferring the folder the user granted over the on-disk scan.
     *
     * The scan only sees files this app wrote, so on the phone being migrated *to* it finds
     * nothing at all; the granted folder sees everything in it. Reading the grant back here is
     * what makes persisting it worth anything -- the permission survived rotation and process
     * death while the list did not, so a user retrying a restore had to go find the folder again.
     */
    private void loadBackups(final ImportBackupService importService) {
        final Uri treeUri = persistedBackupFolder();
        if (treeUri == null) {
            importService.loadBackupFiles(this);
        } else {
            loadBackupFolder(importService, treeUri, false);
        }
    }

    /** The stored backup folder, or null if nothing is stored or the grant is no longer held. */
    private Uri persistedBackupFolder() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        final String stored = preferences.getString(PREF_BACKUP_FOLDER_URI, null);
        if (stored == null) {
            return null;
        }
        final Uri treeUri = Uri.parse(stored);
        for (final var permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isReadPermission()) {
                return treeUri;
            }
        }
        // Revoked from system settings, or the volume it lived on is gone.
        preferences.edit().remove(PREF_BACKUP_FOLDER_URI).apply();
        return null;
    }

    /**
     * @param chosen whether the user just picked this folder, which decides whether an empty or
     *     refused result is worth interrupting them over. Reporting on every launch would put a
     *     snackbar in front of anyone whose granted folder has since been emptied.
     */
    private void loadBackupFolder(
            final ImportBackupService importService, final Uri treeUri, final boolean chosen) {
        importService.loadBackupFilesFromTree(
                treeUri,
                (files, rejection) -> {
                    if (files.isEmpty() && chosen) {
                        reportEmptyBackupFolder(rejection);
                    }
                    onBackupFilesLoaded(files);
                });
    }

    private void reportEmptyBackupFolder(final Throwable rejection) {
        if (rejection == null) {
            showFolderSnackbar(R.string.restore_no_backup_in_folder);
        } else if (isReportable(rejection)) {
            // A folder holding one unusable backup is not a folder-picking mistake: name what is
            // wrong with the file, so the user gets the same "update the app" or "not a backup"
            // steer the single-file route gave them.
            runOnUiThread(() -> showBackupThrowable(rejection));
        } else {
            showFolderSnackbar(R.string.restore_folder_unreadable);
        }
    }

    /**
     * Whether showBackupThrowable() has something to say about this.
     *
     * It matches on type and has no final else, so handing it anything else would show the user
     * nothing at all -- the failure mode this screen has too much of already.
     */
    private static boolean isReportable(final Throwable throwable) {
        return throwable instanceof BackupFileHeader.OutdatedBackupFileVersion
                || throwable instanceof IOException
                || throwable instanceof IllegalArgumentException
                || throwable instanceof SecurityException;
    }

    private void showFolderSnackbar(final int message) {
        runOnUiThread(
                () -> Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG).show());
    }

    /**
     * Holds a lasting read grant on the chosen folder, releasing the one before it.
     *
     * Persisted grants are capped per app, so taking one on every pick and never letting go
     * would eventually start throwing -- and the folder from a restore two phones ago is of no
     * use to anyone.
     */
    private void takeBackupFolderPermission(final Uri treeUri) {
        final var resolver = getContentResolver();
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        final String previous = preferences.getString(PREF_BACKUP_FOLDER_URI, null);
        // Release the previous folder by name. Sweeping every persisted grant and dropping the
        // ones that do not match would also throw away grants taken elsewhere in the app --
        // nothing takes one today, which is exactly what makes it a trap for whoever adds the
        // first.
        if (previous != null && !previous.equals(treeUri.toString())) {
            try {
                resolver.releasePersistableUriPermission(
                        Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (final SecurityException e) {
            }
        }
        try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            preferences.edit().putString(PREF_BACKUP_FOLDER_URI, treeUri.toString()).apply();
        } catch (final SecurityException e) {
            // Not fatal: the grant this task already carries lasts long enough for a restore
            // that is not interrupted.
            Log.w(Config.LOGTAG, "could not persist read access to the backup folder", e);
        }
    }

    @Override
    public void onAccountAlreadySetup() {
        runOnUiThread(
                () -> {
                    setLoadingState(false);
                    Snackbar.make(
                                    binding.coordinator,
                                    R.string.account_already_setup,
                                    Snackbar.LENGTH_LONG)
                            .show();
                });
    }

    @Override
    public void onBackupRestored() {
        runOnUiThread(
                () -> {
                    CognitoClientMigrationManager.markMigrationReauthV1Completed(
                            getApplicationContext());
                    Intent intent = new Intent(this, ConversationActivity.class);
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
    }

    @Override
    public void onBackupDecryptionFailed() {
        runOnUiThread(
                () -> {
                    setLoadingState(false);
                    Snackbar.make(
                                    binding.coordinator,
                                    R.string.unable_to_decrypt_backup,
                                    Snackbar.LENGTH_LONG)
                            .show();
                });
    }

    @Override
    public void onBackupRestoreFailed(final ImportBackupService.RestoreFailure failure) {
        // Every restore failure used to read "Could not restore backup", which told the user
        // nothing about whether to pick a different file, update the app, or call support.
        final int message =
                switch (failure) {
                    case FILE_UNREADABLE -> R.string.restore_failed_file_unreadable;
                    case NOT_A_BACKUP -> R.string.not_a_backup_file;
                    case INCOMPATIBLE_FORMAT -> R.string.restore_failed_incompatible;
                    case DATABASE_REJECTED -> R.string.restore_failed_database;
                    case NO_ACCOUNT_IN_BACKUP -> R.string.restore_failed_no_account;
                    case UNKNOWN -> R.string.unable_to_restore_backup;
                };
        runOnUiThread(
                () -> {
                    setLoadingState(false);
                    Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG).show();
                });
    }


    /**
     * Asks for the backup *folder* rather than a single backup file.
     *
     * Picking one file gives a grant on that file alone, which is why a restore off this screen
     * used to bring back messages and none of their photos: the media archive sits beside the
     * backup and there was no permission to reach it, and no way to even see it. It also left
     * the list above empty on a new phone -- scoped storage hides files this app did not write,
     * so a backup carried over from another device is invisible to the on-disk scan. A folder
     * grant answers both: the list can be built from the folder, and the archive travels with
     * the backup it belongs to.
     */
    private void openBackupFolder() {
        final File directory = FileBackend.getBackupDirectoryCustom();
        directory.mkdirs();
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        final Uri initialUri = getBackupDirectoryDocumentUri(directory);
        if (initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        startActivityForResult(intent, 0xbac);
    }

    private Uri getBackupDirectoryDocumentUri(final File directory) {
        final File downloadsDirectory =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        final String downloadsPath = downloadsDirectory.getAbsolutePath();
        final String directoryPath = directory.getAbsolutePath();
        if (!directoryPath.startsWith(downloadsPath)) {
            return null;
        }
        final String relativePath = directoryPath.substring(downloadsPath.length());
        final String documentId =
                "primary:Download" + relativePath.replace(File.separatorChar, '/');
        return DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", documentId);
    }

}
