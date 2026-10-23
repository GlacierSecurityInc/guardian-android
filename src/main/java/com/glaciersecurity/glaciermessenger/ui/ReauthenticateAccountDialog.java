package com.glaciersecurity.glaciermessenger.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.amplifyframework.auth.AuthException;
import com.amplifyframework.auth.result.step.AuthSignInStep;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.AuthFailures;
import com.glaciersecurity.glaciermessenger.utils.CognitoClientMigrationManager;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.TimeUnit;

public class ReauthenticateAccountDialog extends DialogFragment {

    public static final String TAG = "reauthenticate_account_dialog";
    public static final String MIGRATION_NOTICE_TAG = "reauthenticate_account_dialog_migration_notice";
    private static final String ARG_MIGRATION_NOTICE_ONLY = "arg_migration_notice_only";
    public static final String REQUEST_KEY_MIGRATION_NOTICE = "request_key_migration_notice";
    public static final String RESULT_KEY_CONFIRMED = "result_key_confirmed";
    private static final long REAUTH_SNOOZE_MINUTES = 5L;

    private TextInputLayout usernameLayout;
    private TextInputLayout passwordLayout;
    private String reauthUsername;
    private String pendingReauthenticatedPassword;
    private boolean migrationNoticeConfirmed = false;

    public static ReauthenticateAccountDialog newInstance() {
        return new ReauthenticateAccountDialog();
    }

    public static ReauthenticateAccountDialog newMigrationNoticeInstance() {
        final ReauthenticateAccountDialog dialog = new ReauthenticateAccountDialog();
        final Bundle args = new Bundle();
        args.putBoolean(ARG_MIGRATION_NOTICE_ONLY, true);
        dialog.setArguments(args);
        return dialog;
    }

    private boolean isMigrationNoticeOnly() {
        final Bundle args = getArguments();
        return args != null && args.getBoolean(ARG_MIGRATION_NOTICE_ONLY, false);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setCancelable(false);
        final View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_reauthenticate_account, null, false);

        usernameLayout = view.findViewById(R.id.account_username_layout);
        passwordLayout = view.findViewById(R.id.account_password_layout);
        final EditText usernameInput = view.findViewById(R.id.account_username);
        final EditText passwordInput = view.findViewById(R.id.account_password);
        final ProgressBar progressBar = view.findViewById(R.id.progress_bar);
        final View forgotPassword = view.findViewById(R.id.forgot_password);
        final View supportButton = view.findViewById(R.id.account_support);

        forgotPassword.setOnClickListener(v -> openForgotPasswordPage());
        supportButton.setOnClickListener(v -> openSupportPage());

        reauthUsername = resolveCurrentAccountUsername();
        usernameInput.setText(reauthUsername);
        usernameInput.setEnabled(false);
        usernameInput.setFocusable(false);
        usernameInput.setFocusableInTouchMode(false);
        usernameInput.setClickable(false);
        usernameInput.setLongClickable(false);
        usernameInput.setCursorVisible(false);
        usernameInput.setKeyListener(null);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.reauthentication_required_title)
                .setMessage(R.string.reauthentication_required_message)
                .setView(view)
                .setPositiveButton(R.string.continue_btn, null)
                .setNegativeButton(R.string.reauthentication_snooze_5_minutes, (d, which) -> {
                    d.cancel();
                    OkHttpClientProvider.notifyReauthenticationSnoozed(
                            REAUTH_SNOOZE_MINUTES, TimeUnit.MINUTES);
                    dismissAllowingStateLoss();
                })
                .create();

        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        dialog.setOnShowListener(d -> {
            final Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                usernameLayout.setError(null);
                passwordLayout.setError(null);

                final String password = passwordInput.getText() == null
                        ? ""
                        : passwordInput.getText().toString();
                reauthUsername = resolveCurrentAccountUsername();
                usernameInput.setText(reauthUsername);

                if (TextUtils.isEmpty(reauthUsername)) {
                    usernameLayout.setError(getString(R.string.invalid_username));
                    return;
                }

                if (TextUtils.isEmpty(password)) {
                    passwordLayout.setError(getString(R.string.invalid_password));
                    passwordInput.requestFocus();
                    return;
                }

                setLoadingState(progressBar, positive, true);

                if (CognitoClientMigrationManager.isMigrationReauthV1Needed(requireContext().getApplicationContext())) {
                    CognitoClientMigrationManager.forceSignedOutState(
                            signOutSucceeded -> {
                                if (!isAdded()) {
                                    return;
                                }
                                requireActivity().runOnUiThread(() -> {
                                    if (!signOutSucceeded) {
                                        Log.w(Config.LOGTAG, "Migration pre-signout failed; continuing sign-in");
                                    }
                                    startReauthenticationSignIn(password, progressBar, positive);
                                });
                            });
                    return;
                }

                startReauthenticationSignIn(password, progressBar, positive);
            });
        });

        return dialog;
    }

    @Nullable
    private String resolveCurrentAccountUsername() {
        final XmppActivity activity = (XmppActivity) requireActivity();
        final Account account = activity.xmppConnectionService == null
                ? null
                : activity.xmppConnectionService.getFirstAccount();
        return account == null ? null : account.getUsername();
    }

    private void openForgotPasswordPage() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.glacier.chat/forget-password"));
        openExternalLink(intent, R.string.reauthentication_web_console_unavailable);
    }

    private void openSupportPage() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://glacier.chat/support"));
        openExternalLink(intent, R.string.reauthentication_web_unavailable);
    }

    private void openExternalLink(final Intent intent, final int fallbackMessage) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            if (!isAdded()) {
                return;
            }
            Toast.makeText(requireContext(), fallbackMessage, Toast.LENGTH_LONG).show();
        }
    }

    private void setLoadingState(final ProgressBar progressBar, final Button loginButton, final boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!loading);
    }

    private void startReauthenticationSignIn(final String password,
                                             final ProgressBar progressBar,
                                             final Button positiveButton) {
        pendingReauthenticatedPassword = password;
        Amplify.Auth.signIn(
                reauthUsername,
                password,
                result -> {
                    if (result.isSignedIn()) {
                        if (!isAdded()) {
                            return;
                        }
                        requireActivity().runOnUiThread(() -> {
                            persistReauthenticatedPassword(password);
                            CognitoClientMigrationManager.markMigrationReauthV1Completed(requireContext().getApplicationContext());
                            if (isMigrationNoticeOnly()) {
                                migrationNoticeConfirmed = true;
                            }
                            OkHttpClientProvider.notifyReauthenticationSucceeded();
                            Toast.makeText(requireContext(), R.string.reauthentication_success, Toast.LENGTH_SHORT).show();
                            dismissAllowingStateLoss();
                        });
                        return;
                    }

                    final AuthSignInStep step = result.getNextStep().getSignInStep();
                    Log.w(Config.LOGTAG, "Reauthentication requires extra step: " + step);
                    if (!isAdded()) {
                        return;
                    }
                    requireActivity().runOnUiThread(() -> {
                        setLoadingState(progressBar, positiveButton, false);
                        if (step == AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE) {
                            showMfaInputDialog();
                        } else if (step == AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD) {
                            showPasswordResetDialog();
                        } else {
                            Toast.makeText(requireContext(), R.string.reauthentication_additional_step_required, Toast.LENGTH_LONG).show();
                        }
                    });
                },
                error -> {
                    Log.e(Config.LOGTAG, "Reauthentication failed", error);
                    if (!isAdded()) {
                        return;
                    }
                    requireActivity().runOnUiThread(() -> {
                        setLoadingState(progressBar, positiveButton, false);
                        if (isCredentialFailure(error)) {
                            applyLoginStyleCredentialError();
                        } else {
                            Toast.makeText(requireContext(), R.string.auth_connection_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                });
    }


    private void applyLoginStyleCredentialError() {
        if (usernameLayout == null || passwordLayout == null) {
            return;
        }
        usernameLayout.setError(null);
        passwordLayout.setErrorEnabled(true);
        passwordLayout.setError(getString(R.string.incorrect_user_pass_combo));
    }


    private boolean isCredentialFailure(final AuthException error) {
        return AuthFailures.isCredentialFailure(error);
    }

    private void showMfaInputDialog() {
        if (!isAdded()) {
            return;
        }
        final AlertDialog.Builder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.mfa_verification_title);
        final LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        builder.setView(layout);

        final EditText input = new EditText(requireContext());
        input.setHint(getString(R.string.mfa_enter_code_hint));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        layout.addView(input);

        builder.setPositiveButton(R.string.mfa_confirm_button, null);
        builder.setNegativeButton(R.string.cancel, null);

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String code = input.getText().toString().trim();
            if (TextUtils.isEmpty(code)) {
                Toast.makeText(requireContext(), R.string.auth_code_cannot_be_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            continueMfa(code);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void continueMfa(final String mfaCode) {
        Amplify.Auth.confirmSignIn(
                mfaCode,
                confirmResult -> {
                    final AuthSignInStep step = confirmResult.getNextStep() == null
                            ? null
                            : confirmResult.getNextStep().getSignInStep();
                    handlePostConfirmSignInResult(confirmResult.isSignedIn(), step, pendingReauthenticatedPassword);
                },
                error -> {
                    Log.e(Config.LOGTAG, "Failed to complete MFA reauthentication", error);
                    if (!isAdded()) {
                        return;
                    }
                    requireActivity().runOnUiThread(this::applyLoginStyleCredentialError);
                }
        );
    }

    private void showPasswordResetDialog() {
        if (!isAdded()) {
            return;
        }
        final AlertDialog.Builder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.reset_password_title);

        final LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        builder.setView(layout);

        final TextView newPass = new TextView(requireContext());
        newPass.setText(R.string.new_password_label);
        layout.addView(newPass);

        final TextInputLayout newPasswordLayout = new TextInputLayout(requireContext());
        newPasswordLayout.setHint(getString(R.string.new_password_label));
        newPasswordLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        final TextInputEditText newPasswordInput = new TextInputEditText(requireContext());
        newPasswordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        newPasswordLayout.addView(newPasswordInput);
        layout.addView(newPasswordLayout);

        final TextView newPass2 = new TextView(requireContext());
        newPass2.setText(R.string.repeat_new_password_label);
        layout.addView(newPass2);

        final TextInputLayout repeatPasswordLayout = new TextInputLayout(requireContext());
        repeatPasswordLayout.setHint(getString(R.string.repeat_new_password_label));
        repeatPasswordLayout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        final TextInputEditText newPasswordInput2 = new TextInputEditText(requireContext());
        newPasswordInput2.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        repeatPasswordLayout.addView(newPasswordInput2);
        layout.addView(repeatPasswordLayout);

        builder.setPositiveButton(R.string.submit_button, null);
        builder.setNegativeButton(R.string.cancel, null);

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String newPassword = newPasswordInput.getText().toString().trim();
            final String repeated = newPasswordInput2.getText().toString().trim();
            if (TextUtils.isEmpty(newPassword) || TextUtils.isEmpty(repeated)) {
                Toast.makeText(requireContext(), R.string.auth_all_fields_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newPassword.equals(repeated)) {
                Toast.makeText(requireContext(), R.string.auth_passwords_do_not_match, Toast.LENGTH_SHORT).show();
                return;
            }
            continueNewPassword(newPassword);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void continueNewPassword(final String newPassword) {
        pendingReauthenticatedPassword = newPassword;
        Amplify.Auth.confirmSignIn(
                newPassword,
                confirmResult -> {
                    final AuthSignInStep step = confirmResult.getNextStep() == null
                            ? null
                            : confirmResult.getNextStep().getSignInStep();
                    handlePostConfirmSignInResult(confirmResult.isSignedIn(), step, newPassword);
                },
                error -> {
                    Log.e(Config.LOGTAG, "Failed to complete password reset during reauthentication", error);
                    if (!isAdded()) {
                        return;
                    }
                    requireActivity().runOnUiThread(this::applyLoginStyleCredentialError);
                }
        );
    }

    private void handlePostConfirmSignInResult(final boolean signedIn,
                                               @Nullable final AuthSignInStep nextStep,
                                               @Nullable final String refreshedPassword) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (signedIn) {
                persistReauthenticatedPassword(refreshedPassword);
                CognitoClientMigrationManager.markMigrationReauthV1Completed(requireContext().getApplicationContext());
                OkHttpClientProvider.notifyReauthenticationSucceeded();
                Toast.makeText(requireContext(), R.string.reauthentication_success, Toast.LENGTH_SHORT).show();
                dismissAllowingStateLoss();
                return;
            }
            if (nextStep == AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE) {
                showMfaInputDialog();
            } else if (nextStep == AuthSignInStep.CONFIRM_SIGN_IN_WITH_NEW_PASSWORD) {
                showPasswordResetDialog();
            } else {
                Toast.makeText(requireContext(), R.string.reauthentication_additional_step_required, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void persistReauthenticatedPassword(@Nullable final String refreshedPassword) {
        if (TextUtils.isEmpty(refreshedPassword) || !isAdded()) {
            return;
        }
        final XmppActivity activity = (XmppActivity) requireActivity();
        final XmppConnectionService service = activity.xmppConnectionService;
        if (service == null) {
            return;
        }
        final Account account = service.getFirstAccount();
        if (account == null) {
            return;
        }
        service.updateCognitoPassword(account, refreshedPassword);
    }
}
