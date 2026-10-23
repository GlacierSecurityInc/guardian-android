package com.glaciersecurity.glaciermessenger.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.security.KeyChainAliasCallback;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.amplifyframework.auth.AuthUserAttribute;
import com.amplifyframework.auth.cognito.result.AWSCognitoAuthSignOutResult;
import com.amplifyframework.auth.result.step.AuthSignInStep;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.conversations.ui.ImportBackupActivity;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.databinding.ActivityLoginAccountBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.entities.CognitoAccount;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.entities.GlacierUserEntity;
import com.glaciersecurity.glaciermessenger.entities.PresenceTemplate;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiClient;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiResponses;
import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService.OnAccountUpdate;
import com.glaciersecurity.glaciermessenger.services.XmppTokenCredentials;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.SmsUnavailableDialogHelper;
import com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.CognitoClientMigrationManager;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.SerialSingleThreadExecutor;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnKeyStatusUpdated;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


public class LoginAccountActivity extends OmemoActivity
        implements OnAccountUpdate,
        OnKeyStatusUpdated,
        KeyChainAliasCallback,
        XmppConnectionService.OnShowErrorToast,
        XmppConnectionService.OnMamPreferencesFetched {

    public static final String EXTRA_OPENED_FROM_NOTIFICATION = "opened_from_notification";
    public static final String EXTRA_FORCE_REGISTER = "force_register";
    private static final int REQUEST_IMPORT_BACKUP = 0x63fb;
    private static final int REQUEST_RESET_PASSWORD = 0x7a51;
    private static final int REQUEST_MFA_VERIFY = 0x7a52;
    // continueLogin() performs SQLite writes and AndroidKeyStore/Tink crypto (first-time key
    // generation); it is invoked from Retrofit's main-thread callback, so that work is run here
    // instead of blocking the UI thread.
    private static final SerialSingleThreadExecutor LOGIN_FINALIZE_EXECUTOR =
            new SerialSingleThreadExecutor("LoginFinalize");


    private static final int REQUEST_DATA_SAVER = 0xf244;
    private static final int REQUEST_CHANGE_STATUS = 0xee11;
    private final PendingItem<PresenceTemplate> mPendingPresenceTemplate = new PendingItem<>();
    private Account mAccount;
    // Set when the user backs out of login while LOGIN_FINALIZE_EXECUTOR is still working, so the
    // queued task can skip (or undo) account creation instead of finishing setup out from under them.
    private final AtomicBoolean loginFinalizeCancelled = new AtomicBoolean(false);
    // One-shot guard for the reconcile-and-retry below, so a disagreement between the service and
    // the database can never turn into a login loop.
    private final AtomicBoolean reconciledForLogin = new AtomicBoolean(false);
    // Held across the two decisions that must not interleave: abandoning the login (setting
    // loginFinalizeCancelled and claiming accountCreatedByThisLogin) and committing it (reading
    // that flag and pruning the other rows). Both only ever claim the account and flip the flags
    // under this lock; the deletes themselves happen outside it, so a back press never waits on
    // SQLite.
    private final Object loginFinalizeLock = new Object();
    // Set under loginFinalizeLock the moment the finalizer wins the race above. From then on the
    // login is the user's account and the session behind it is live, so abandoning the screen has
    // nothing left to undo -- and must not sign that session out.
    private boolean loginFinalizeCommitted = false;

    private boolean mFetchingAvatar = false;
    private String mSavedInstanceAccount;
    // The account row this login attempt created (null when an existing row was reused).
    // Only this one may be removed if the user abandons the login: every other row on the
    // device predates this screen and may hold restored history.
    private volatile Account accountCreatedByThisLogin = null;
    private XmppUri pendingUri = null;
    private ActivityLoginAccountBinding binding;
    private volatile boolean loginInProgress = false;
    private final TextWatcher mTextWatcher =
            new TextWatcher() {

                @Override
                public void onTextChanged(
                        final CharSequence s, final int start, final int before, final int count) {
                    updateLoginButton();
                }

                @Override
                public void beforeTextChanged(
                        final CharSequence s, final int start, final int count, final int after) {
                }

                @Override
                public void afterTextChanged(final Editable s) {
                }
            };
    private String cognitoUsername;
    private String cognitoPassword;
    private String cognitoOrg;
    private final OnClickListener mLoginButtonClickListener =
            v -> signInUserState();

    private void OnFailedLogin() {
        OnFailedLogin(R.string.incorrect_user_pass_combo);
    }

    private void OnFailedLogin(final int errorResId) {
        setLoginInProgress(false);
        runOnUiThread(
                () -> {
                    this.binding.accountUsernameLayout.setError(getString(errorResId));
                    removeErrorsOnAllBut(binding.accountUsernameLayout);
                    binding.progressBar.setVisibility(View.GONE);
                });

        safeSignOut();

        deleteAccountAndReturnIfNecessary();
    }

    @Override
    public void refreshUiReal() {
        invalidateOptionsMenu();
        if (mAccount != null && mAccount.getStatus() != Account.State.ONLINE && mFetchingAvatar) {
            Intent intent = new Intent(this, StartConversationActivity.class);
            StartConversationActivity.addInviteUri(intent, getIntent());
            startActivity(intent);
            finish();
        } else if (mAccount != null && mAccount.getStatus() == Account.State.ONLINE) {
        }
        if (mAccount != null) {
            updateAccountInformation();
        }
        updateLoginButton();
    }

    @Override
    public boolean onNavigateUp() {
        deleteAccountAndReturnIfNecessary();
        return super.onNavigateUp();
    }

    /**
     * Abandoning a login (back, up, or a rejected credential) must undo only what this login
     * attempt created. It used to delete every account on the device, which cascaded away the
     * conversations and messages of an account that was already there -- a restored backup, or the
     * signed-in user's own history after a single mistyped password.
     */
    private void deleteAccountAndReturnIfNecessary() {
        final Account created;
        synchronized (loginFinalizeLock) {
            if (loginFinalizeCommitted) {
                // The finalizer already committed: it kept this row, pruned the others, and is on
                // its way to finishInitialSetup(). Cancelling now would undo none of that but
                // would still safeSignOut() the Cognito session the app is about to make
                // authenticated calls with, forcing a re-auth on a login that succeeded.
                return;
            }
            loginFinalizeCancelled.set(true);
            if (xmppConnectionService != null) {
                created = this.accountCreatedByThisLogin;
                this.accountCreatedByThisLogin = null;
            } else {
                // Nothing can be deleted without the service; leave the field for whoever has it.
                created = null;
            }
        }
        // Null once the finalizer has committed the login, which is what keeps a back press
        // arriving mid-pruning from deleting the account the pruning just kept.
        if (created != null) {
            xmppConnectionService.deleteAccount(created);
        }
        safeSignOut();
        setLoginInProgress(false);
        runOnUiThread(
                () -> {
                    this.binding.accountPassword.getEditableText().clear();
                    this.binding.progressBar.setVisibility(View.GONE);
                });

    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    protected void finishInitialSetup() {
        // Login succeeded, so the row is the user's account now rather than a half-finished
        // artefact of this screen; a later back press must not take it away.
        this.accountCreatedByThisLogin = null;
        runOnUiThread(
                () -> {
                    SoftKeyboardUtils.hideSoftKeyboard(LoginAccountActivity.this);
                    final Intent intent;
                    intent = new Intent(this, StartConversationActivity.class);
                    intent.putExtra("init", true);
                    intent.putExtra(
                            EXTRA_ACCOUNT, mAccount.getJid().asBareJid().toEscapedString());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BATTERY_OP || requestCode == REQUEST_DATA_SAVER) {
            updateAccountInformation();
        }
        if (requestCode == REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            XmppConnectionService.toggleForegroundService(xmppConnectionService);
        }
        if (requestCode == REQUEST_CHANGE_STATUS) {
            PresenceTemplate template = mPendingPresenceTemplate.pop();
            if (template != null && resultCode == Activity.RESULT_OK) {
                generateSignature(data, template);
            }
        }
        if (requestCode == REQUEST_RESET_PASSWORD) {
            final String newPassword = resultCode == Activity.RESULT_OK && data != null
                    ? data.getStringExtra(ResetPasswordActivity.EXTRA_NEW_PASSWORD)
                    : null;
            if (newPassword != null) {
                cognitoPassword = newPassword;
                continueSignIn();
            } else {
                setLoginInProgress(false);
                binding.progressBar.setVisibility(View.GONE);
            }
        }
        if (requestCode == REQUEST_MFA_VERIFY) {
            if (resultCode == Activity.RESULT_OK) {
                continueSignIn();
            } else {
                setLoginInProgress(false);
                binding.progressBar.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void processFingerprintVerification(XmppUri uri) {
        processFingerprintVerification(uri, true);
    }

    protected void processFingerprintVerification(XmppUri uri, boolean showWarningToast) {
        if (mAccount != null
                && mAccount.getJid().asBareJid().equals(uri.getJid())
                && uri.hasFingerprints()) {
            if (xmppConnectionService.verifyFingerprints(mAccount, uri.getFingerprints())) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
                updateAccountInformation();
            }
        } else if (showWarningToast) {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show();
        }
    }

    private void setLoginInProgress(boolean inProgress) {
        loginInProgress = inProgress;
        runOnUiThread(
                () -> {
                    binding.accountUsername.setEnabled(!inProgress);
                    binding.accountPassword.setEnabled(!inProgress);
                });
        updateLoginButton();
    }

    protected void updateLoginButton() {
        runOnUiThread(
                () -> {
                    final boolean connecting =
                            mAccount != null && mAccount.getStatus() == Account.State.CONNECTING;
                    if (loginInProgress || connecting) {
                        // Progress lives inside the button: hide its label/icon and spin.
                        showLoginButtonProgress(
                                connecting ? R.string.account_status_connecting : R.string.login_loading);
                        this.binding.accountLogin.setEnabled(false);
                    } else if (binding.accountUsername.getText() == null || binding.accountUsername.getText().toString().trim().isEmpty() || binding.accountPassword.getText() == null || binding.accountPassword.getText().toString().trim().isEmpty()) {
                        showLoginButtonProgress(0);
                        this.binding.accountLogin.setEnabled(false);
                    } else {
                        showLoginButtonProgress(0);
                        this.binding.accountLogin.setEnabled(true);
                    }
                });
    }


    private void resetPasswordVisibility() {
        runOnUiThread(
                () -> {
                    if (binding == null || binding.accountPassword == null) {
                        return;
                    }
                    final boolean passwordToggleEnabled =
                            binding.accountPasswordLayout.isPasswordVisibilityToggleEnabled();
                    binding.accountPasswordLayout.setPasswordVisibilityToggleEnabled(false);
                    binding.accountPassword.setTransformationMethod(PasswordTransformationMethod.getInstance());
                    binding.accountPasswordLayout.setPasswordVisibilityToggleEnabled(passwordToggleEnabled);
                    final Editable password = binding.accountPassword.getText();
                    if (password != null) {
                        binding.accountPassword.setSelection(password.length());
                    }
                });
    }

    private void showLoginButtonProgress(final int progressLabelRes) {
        if (progressLabelRes != 0) {
            this.binding.accountLogin.setText("");
            this.binding.accountLogin.setContentDescription(getString(progressLabelRes));
            this.binding.accountLogin.setIcon(null);
            this.binding.progressBar.setVisibility(View.VISIBLE);
        } else {
            this.binding.progressBar.setVisibility(View.GONE);
            this.binding.accountLogin.setText(R.string.login);
            this.binding.accountLogin.setContentDescription(null);
            this.binding.accountLogin.setIconResource(R.drawable.ic_lock_24dp);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SmsUnavailableDialogHelper.resetNoticeShown(this);
        if (savedInstanceState != null) {
            this.mSavedInstanceAccount = savedInstanceState.getString("account");
        }
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_login_account);

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        this.binding.loginVersion.setText(
                getString(R.string.end_to_end_version, BuildConfig.VERSION_NAME));
        this.binding.accountUsername.addTextChangedListener(this.mTextWatcher);
        this.binding.accountPassword.addTextChangedListener(this.mTextWatcher);
        this.binding.accountLogin.setOnClickListener(mLoginButtonClickListener);
        resetPasswordVisibility();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                deleteAccountAndReturnIfNecessary();
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

    }

    @Override
    public void onPause() {
        resetPasswordVisibility();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        resetPasswordVisibility();
    }

    @Override
    public void onStart() {
        super.onStart();
        final Intent intent = getIntent();
        if (intent != null) {
            configureActionBar(getSupportActionBar(), true);
        }
    }

    public void importAccount(View view){
        if (hasStoragePermission()) {
            startActivity(new Intent(this, ImportBackupActivity.class));
        }
    }

    public void forgotPassword(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("https://console.glacier.chat/forget-password"));
        try {
            startActivity(intent);
        } catch (java.lang.Exception ex) {
            displayToast("Your device cannot access the web console. Contact your device administrator to request web access and to reset your password.",Toast.LENGTH_LONG);
        }
    }

    private void displayToast(final String msg, int toastLen) {
        runOnUiThread(() -> Toast.makeText(LoginAccountActivity.this, msg, toastLen).show());
    }


    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) {
            final XmppUri uri = new XmppUri(intent.getData());
            if (xmppConnectionServiceBound) {
                processFingerprintVerification(uri, false);
            } else {
                this.pendingUri = uri;
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle savedInstanceState) {
        if (mAccount != null) {
            savedInstanceState.putString(
                    "account", mAccount.getJid().asBareJid().toEscapedString());
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onBackendConnected() {
        if (mSavedInstanceAccount != null) {
            try {
                this.mAccount =
                        xmppConnectionService.findAccountByJid(
                                Jid.ofEscaped(mSavedInstanceAccount));
            } catch (IllegalArgumentException e) {
                this.mAccount = null;
            }

        }

        if (pendingUri != null) {
            processFingerprintVerification(pendingUri, false);
            pendingUri = null;
        }
        updateLoginButton();
        invalidateOptionsMenu();
    }

    /**
     * Only one account/session is supported at a time, so any row belonging to a different user
     * is dropped once we know which JID is actually signing in.
     *
     * This deliberately does not run on arrival at the login screen. Deleting rows up front took
     * the restored account with it: "Restore account" is reached from this very screen, and
     * accounts -> conversations -> messages cascade on delete, so a restored backup was erased
     * before the user ever got to type a password. Waiting until the JID is known also leaves the
     * matching row alone, which is what lets continueLogin() reuse it and keep the restored
     * history attached to its uuid.
     */
    private void removeAccountsExcept(final Account keep) {
        if (xmppConnectionService == null) {
            return;
        }
        final List<Account> accounts = xmppConnectionService.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        final String keepUuid = keep == null ? null : keep.getUuid();
        for (final Account account : new ArrayList<>(accounts)) {
            // Keyed on the uuid of the row actually being kept, not on the JID. Keying on the JID
            // spared every duplicate row for the same bare JID -- the very condition this cleanup
            // exists to repair -- because findAccountByJid() reuses only one of them and the
            // others survived to be picked again after a reload, restoring the login loop.
            if (keepUuid == null || !keepUuid.equals(account.getUuid())) {
                xmppConnectionService.deleteAccount(account);
            }
        }
    }

    private boolean databaseHasAccountFor(final Jid jid) {
        if (jid == null) {
            return false;
        }
        for (final Account account : DatabaseBackend.getInstance(this).getAccounts()) {
            if (account.getJid().asBareJid().equals(jid.asBareJid())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                deleteAccountAndReturnIfNecessary();
                break;

        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteAccount() {
        this.deleteAccount(mAccount, () -> finish());
    }

    private void generateSignature(Intent intent, PresenceTemplate template) {
        xmppConnectionService
                .getPgpEngine()
                .generateSignature(
                        intent,
                        mAccount,
                        template.getStatusMessage(),
                        new UiCallback<String>() {
                            @Override
                            public void success(String signature) {
                                xmppConnectionService.changeStatus(mAccount, template, signature);
                            }

                            @Override
                            public void error(int errorCode) {
                            }

                            @Override
                            public void userInputRequired(PendingIntent pi) {
                                mPendingPresenceTemplate.push(template);
                                try {
                                    startIntentSenderForResult(
                                            pi.getIntentSender(),
                                            REQUEST_CHANGE_STATUS,
                                            null,
                                            0,
                                            0,
                                            0,
                                            Compatibility.pgpStartIntentSenderOptions());
                                } catch (final IntentSender.SendIntentException ignored) {
                                }
                            }
                        });
    }

    @Override
    public void alias(String alias) {
        if (alias != null) {
            xmppConnectionService.updateKeyInAccount(mAccount, alias);
        }
    }

    private void updateAccountInformation() {
        this.binding.accountUsername.getEditableText().clear();
        this.binding.accountUsername.getEditableText().append(this.mAccount.getJid().getLocal());


        final boolean togglePassword =
                mAccount.isOptionSet(Account.OPTION_MAGIC_CREATE)
                        || !mAccount.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY);
        final boolean neverLoggedIn =
                !mAccount.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY)
                        && QuickConversationsService.isConversations();
        final boolean editPassword = mAccount.unauthorized() || neverLoggedIn;

        this.binding.accountPasswordLayout.setPasswordVisibilityToggleEnabled(togglePassword);

        this.binding.accountPassword.setFocusable(editPassword);
        this.binding.accountPassword.setFocusableInTouchMode(editPassword);
        this.binding.accountPassword.setCursorVisible(editPassword);
        this.binding.accountPassword.setEnabled(editPassword);


        final TextInputLayout errorLayout;
        final var status = this.mAccount.getStatus();
        if (status.isError()
                || Arrays.asList(
                        Account.State.NO_INTERNET,
                        Account.State.AIRPLANE_MODE,
                        Account.State.MISSING_INTERNET_PERMISSION)
                .contains(status)) {
            if (status == Account.State.UNAUTHORIZED) {
                errorLayout = this.binding.accountPasswordLayout;
            } else {
                errorLayout = this.binding.accountUsernameLayout;
            }
            errorLayout.setError(getString(this.mAccount.getStatus().getReadableId()));
            removeErrorsOnAllBut(errorLayout);
        }

    }

    private void removeErrorsOnAllBut(TextInputLayout exception) {

        if (this.binding.accountPasswordLayout != exception) {
            this.binding.accountPasswordLayout.setErrorEnabled(false);
            this.binding.accountPasswordLayout.setError(null);
        }
        if (this.binding.accountUsernameLayout != exception) {
            this.binding.accountUsernameLayout.setErrorEnabled(false);
            this.binding.accountUsernameLayout.setError(null);
        }

    }

    private void removeAllErrors() {

        this.binding.accountPasswordLayout.setErrorEnabled(false);
        this.binding.accountPasswordLayout.setError(null);

        this.binding.accountUsernameLayout.setErrorEnabled(false);
        this.binding.accountUsernameLayout.setError(null);
    }

    @Override
    public void onKeyStatusUpdated(AxolotlService.FetchStatus report) {
        refreshUi();
    }

    @Override
    public void onShowErrorToast(final int resId) {
        runOnUiThread(
                () -> Toast.makeText(LoginAccountActivity.this, resId, Toast.LENGTH_SHORT).show());
    }

    /**
     * Best-effort Amplify sign-out that never propagates an exception. Used on the login screen's
     * cleanup paths (failed login, back navigation) where Amplify may not be configured; throwing
     * here would crash the app with an uncaught IllegalStateException.
     */
    private void safeSignOut() {
        if (!GlacierGuardian.isAmplifyConfigured()) {
            return;
        }
        try {
            Amplify.Auth.signOut(signOutResult -> {});
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "Auth signOut failed", e);
        }
    }

    private void signInUserState() {
        removeAllErrors();
        synchronized (loginFinalizeLock) {
            loginFinalizeCancelled.set(false);
            loginFinalizeCommitted = false;
        }
        cognitoUsername = binding.accountUsername.getText().toString();
        cognitoPassword = binding.accountPassword.getText().toString();

        if (cognitoUsername.isEmpty()) {
            binding.accountUsernameLayout.setError(getString(R.string.invalid_username));
            binding.accountUsername.requestFocus();
            return;
        }
        if (cognitoPassword.isEmpty()) {
            binding.accountPasswordLayout.setError(getString(R.string.invalid_password));
            binding.accountPassword.requestFocus();
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        setLoginInProgress(true);

        // Amplify may have failed to configure during app startup; make sure it is ready before
        // touching Amplify.Auth, otherwise the framework throws an uncaught IllegalStateException.
        if (!GlacierGuardian.ensureAmplifyConfigured(getApplicationContext())) {
            Log.e(Config.LOGTAG, "Amplify is not configured; cannot sign in");
            OnFailedLogin(R.string.auth_failed_try_again);
            return;
        }

        try {
            Amplify.Auth.signOut(signOutResult -> {
                logIn(); // ← now always called after signOut finishes
            });
        } catch (final Exception e) {
            // signOut throws synchronously if Amplify is somehow still unconfigured; don't crash —
            // proceed to signIn, which is wrapped in its own try/catch.
            Log.w(Config.LOGTAG, "Auth signOut before sign-in failed; continuing", e);
            logIn();
        }
    }
    private void logIn(){
        try {

            Amplify.Auth.signIn(
                    cognitoUsername,
                    cognitoPassword,
                    result -> {
                        if (result.isSignedIn()) {
                            continueSignIn();
                            Log.i(Config.LOGTAG, "Sign in succeeded");
                        } else if (result.getNextStep().getSignInStep().equals(AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE)) {
                            Log.i(Config.LOGTAG, "Confirm sign-in with MFA code");
                            runOnUiThread(() -> startActivityForResult(
                                    new Intent(this, MfaVerifyActivity.class), REQUEST_MFA_VERIFY));
                        } else if (result.getNextStep().getSignInStep().equals(AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD)) {
                            Log.i(Config.LOGTAG, "Password reset required");
                            // Trigger the reset password UI
                            runOnUiThread(() -> startActivityForResult(
                                    new Intent(this, ResetPasswordActivity.class), REQUEST_RESET_PASSWORD));
                        } else {
                            Log.w(Config.LOGTAG, "Unsupported sign-in step required: " + result.getNextStep().getSignInStep());
                            OnFailedLogin(R.string.auth_additional_step_required);
                        }
                    },
                    error -> {
                        Log.e(Config.LOGTAG, "Sign in failed", error);
                        OnFailedLogin();
                    }
            );
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Sign in failed", e);
            OnFailedLogin();
        }
    }

    private void continueSignIn(){
        fetchCurrentUserDetails();
    }

    public void fetchCurrentUserDetails() {

        Amplify.Auth.fetchUserAttributes(
                attributes -> {
                    int attributeCount = 0;
                    for (AuthUserAttribute attribute : attributes) {
                        attributeCount++;
                        if (attribute.getKey().getKeyString().equals("custom:organization")) {
                            cognitoOrg = attribute.getValue();
                            break;
                        }

                    }
                    Log.i(Config.LOGTAG, "Fetched user attributes (" + attributeCount + ")");
                    queryGlacierUsersTable(cognitoUsername, cognitoOrg);
                },
                error -> {
                    Log.e(Config.LOGTAG, "Failed to fetch user attributes", error);
                    OnFailedLogin();
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
                    // Without this the screen is left mid-login forever: setLoginInProgress(true)
                    // has already disabled both fields and the button, and nothing else re-enables
                    // them. Every other failure path in this chain reports through OnFailedLogin().
                    OnFailedLogin();
                    return;
                }
                try {
                    final GlacierUserEntity userData = MobileApiClient.toGlacierUsers(response.body().user);
                    if (userData != null) {
                        getGlacierOrganizationModelQuery(organization, userData);
                    } else {
                        Log.e(Config.LOGTAG, "Glacier user lookup returned no user");
                        OnFailedLogin();
                    }
                } catch (Exception e){
                    Log.e(Config.LOGTAG, "Glacier user lookup failed", e);
                    OnFailedLogin();

                }
            }

            @Override
            public void onFailure(retrofit2.Call<MobileApiResponses.GlacierUserResponse> call, Throwable t) {
                Log.e(Config.LOGTAG, "Glacier user lookup failed", t);
                OnFailedLogin();

            }
        });
    }
    private void getGlacierOrganizationModelQuery(String organization, GlacierUserEntity glacierUsers) {
        MobileApiClient.service().getGlacierOrganization(organization, glacierUsers.getUsername()).enqueue(new retrofit2.Callback<MobileApiResponses.GlacierOrganizationResponse>() {
            @Override
            public void onResponse(retrofit2.Call<MobileApiResponses.GlacierOrganizationResponse> call,
                                   retrofit2.Response<MobileApiResponses.GlacierOrganizationResponse> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().organization == null) {
                    Log.e(Config.LOGTAG, "Glacier organization lookup failed with code " + response.code());
                    OnFailedLogin();
                    return;
                }
                try {
                    final GlacierOrgEntity goe = MobileApiClient.toGlacierOrgEntity(response.body().organization);
                    // Guard on the converted entity, not on the organization name that was passed
                    // in -- that one was used to build this very request and is always non-null,
                    // so the check never fired and a null goe went straight into createGlacierOrg.
                    // toGlacierOrgEntity() returns null when the response carries an organization
                    // object with no name, in which case every feature flag on it (forceTracking,
                    // smsEnabled, securityHubDataEnabled) would silently default
                    // to false. Those drive org policy, so fail the login rather than sign the user
                    // in under defaults their organization did not choose.
                    if (goe == null) {
                        Log.e(Config.LOGTAG, "Glacier organization lookup returned an unusable organization");
                        OnFailedLogin();
                        return;
                    }
                    xmppConnectionService.createGlacierOrg(goe);
                    continueLogin(glacierUsers);
                } catch (Exception e) {
                    Log.e(Config.LOGTAG, "Glacier organization lookup failed", e);
                    OnFailedLogin();
                }

            }

            @Override
            public void onFailure(retrofit2.Call<MobileApiResponses.GlacierOrganizationResponse> call, Throwable t) {
                Log.e(Config.LOGTAG, "Glacier organization lookup failed", t);
                OnFailedLogin();

            }
        });
    }


    private void continueLogin(GlacierUserEntity userData) {
        final String xmppToken = userData.getXmppToken();
        final String cognitoOrg = userData.getOrganization();
        final String accountJid = userData.getMessengerId();

        // The backend is JWT-only: the JID plus xmpp_token is the whole XMPP credential. There is
        // no password to fall back to -- not glacierpwd (retired) and not the Cognito password.
        switch (XmppTokenCredentials.checkLogin(userData)) {
            case MISSING_JID:
                binding.accountUsernameLayout.setError(getString(R.string.invalid_jid));
                binding.accountUsername.requestFocus();
                OnFailedLogin();
                return;
            case MISSING_TOKEN:
                Log.e(Config.LOGTAG, "GlacierUsers.xmpp_token unavailable; cannot log in to XMPP");
                OnFailedLogin(R.string.auth_failed_try_again);
                return;
            case OK:
                break;
        }
        List<String> coreRegion = userData.getCoreRegion();
        String coreRegionString = null;
        if (coreRegion != null) {
            coreRegionString = coreRegion.toString();
        }

        Jid jid = Jid.of(accountJid);
        // findAccountByJid() reads the service's cached list. A restore inserts its row straight
        // into the database, so a service that outlived the restore can still report no accounts
        // -- and creating a second row here would mint a fresh uuid, leaving every restored
        // conversation attached to the original row and invisible to the account just logged in.
        // Reconcile first and re-enter, so the normal reuse path sees the restored row.
        if (xmppConnectionService.findAccountByJid(jid) == null
                && databaseHasAccountFor(jid)
                && reconciledForLogin.compareAndSet(false, true)) {
            xmppConnectionService.reconcileWithDatabase(
                    () -> runOnUiThread(() -> continueLogin(userData)));
            return;
        }
        final Account existingAccount = xmppConnectionService.findAccountByJid(jid);
        final boolean reuseExistingAccount = existingAccount != null;
        if (reuseExistingAccount) {
            // Reuse the existing account (same uuid) instead of creating a duplicate row.
            // Creating a new row here on every re-login left the stale row behind; since
            // getAccounts() has no ordering guarantee, the app could keep surfacing the old,
            // now-invalid row, failing auth and routing the user right back to this screen.
            mAccount = existingAccount;
            mAccount.setOrganization(cognitoOrg);
            mAccount.setGlacierUser(userData);
            // A restored account is written to the backup with OPTION_DISABLED set, so it never
            // connects until someone re-authenticates it -- which is exactly what is happening
            // here. Without this the reused row stays inert and the user sees an empty, offline
            // app after a successful restore.
            mAccount.setOption(Account.OPTION_DISABLED, false);
            mAccount.setOption(Account.OPTION_SOFT_DISABLED, false);
        } else {
            mAccount = new Account(jid, "", cognitoOrg, userData, coreRegionString);
        }
        mAccount.setCoreRegion(coreRegion != null ? coreRegion : Collections.emptyList());
        // createAccount()/createCognitoAccount() do SQLite writes and AndroidKeyStore/Tink
        // crypto, and importCoreProfiles() touches disk. Do this off the main thread so login
        // doesn't jank the UI. setLoginInProgress()/finishInitialSetup() already hop back to
        // the main thread internally.
        final Account accountToCreate = mAccount;
        LOGIN_FINALIZE_EXECUTOR.execute(() -> {
            if (loginFinalizeCancelled.get()) {
                return;
            }
            // Keystore-backed store write; keep it off the main thread alongside the other
            // AndroidKeyStore/Tink crypto work this executor already does.
            XmppTokenCredentials.applyToken(accountToCreate, xmppToken);
            if (reuseExistingAccount) {
                xmppConnectionService.updateAccount(accountToCreate);
            } else {
                xmppConnectionService.createAccount(accountToCreate);
                synchronized (loginFinalizeLock) {
                    this.accountCreatedByThisLogin = accountToCreate;
                }
            }
            xmppConnectionService.queryLatestVersion();
            xmppConnectionService.importCoreProfiles(accountToCreate, null);
            CognitoAccount cacct = new CognitoAccount(cognitoUsername, cognitoPassword, cognitoOrg, accountToCreate.getUuid(), getApplicationContext());
            xmppConnectionService.createCognitoAccount(cacct);
            xmppConnectionService.queryDb(true);
            CognitoClientMigrationManager.markMigrationReauthV1Completed(getApplicationContext());
            // Reading the cancellation flag and committing to this account have to be one
            // atomic step. Checking and then pruning left a window: a back press landing in it
            // set the flag and deleted the row this login created, while this worker went on to
            // delete every row that did not match it -- the pre-existing account among them, its
            // conversations and messages cascaded away -- leaving the user with no account at
            // all. Claiming the account under the same lock deleteAccountAndReturnIfNecessary()
            // takes means exactly one of the two wins: either the cancellation is visible here
            // and nothing is pruned, or the login is committed and the back press finds nothing
            // left to delete.
            final boolean cancelled;
            final Account cancelledRowToDelete;
            synchronized (loginFinalizeLock) {
                cancelled = loginFinalizeCancelled.get();
                // Claim the row either way: on the commit path because past this point it is the
                // user's account rather than a half-finished artefact of this screen -- which is
                // what makes dropping the others safe -- and on the cancellation path so this and
                // deleteAccountAndReturnIfNecessary() cannot both delete it.
                cancelledRowToDelete = cancelled ? this.accountCreatedByThisLogin : null;
                this.accountCreatedByThisLogin = null;
                loginFinalizeCommitted = !cancelled;
            }
            if (cancelled) {
                if (cancelledRowToDelete != null) {
                    xmppConnectionService.deleteAccount(cancelledRowToDelete);
                }
                return;
            }
            // Only one account/session is supported, so stale rows go now -- after the login is
            // committed, never before it. Pruning earlier meant a back press could permanently
            // delete the pre-existing account while the cancellation path only removed the newly
            // created one, leaving the user with neither.
            removeAccountsExcept(accountToCreate);
            setLoginInProgress(false);
            finishInitialSetup();
        });
    }

    @Override
    public void onPreferencesFetched(Element prefs) {

    }

    @Override
    public void onPreferencesFetchFailed() {

    }
}
