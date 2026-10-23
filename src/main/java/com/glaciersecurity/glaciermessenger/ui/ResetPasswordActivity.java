package com.glaciersecurity.glaciermessenger.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.databinding.DataBindingUtil;

import com.amplifyframework.auth.result.step.AuthSignInStep;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityResetPasswordBinding;
import com.glaciersecurity.glaciermessenger.ui.widget.DisabledActionModeCallback;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ResetPasswordActivity extends BaseActivity {

    public static final String EXTRA_NEW_PASSWORD = "new_password";
    private static final int REQUEST_MFA_VERIFY = 0x7a53;

    // How long the success page lingers before handing control back to the caller, so the
    // "Signing you in…" message is actually readable instead of flashing by.
    private static final long SUCCESS_PAGE_DELAY_MS = 900L;

    private ActivityResetPasswordBinding binding;
    private final AtomicBoolean confirmFocused = new AtomicBoolean(false);
    private String pendingNewPassword;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        setResult(RESULT_CANCELED);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_reset_password);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onCancel();
            }
        });
        binding.resetNewPasswordInput.setCustomSelectionActionModeCallback(new DisabledActionModeCallback());
        binding.resetRepeatPasswordInput.setCustomSelectionActionModeCallback(new DisabledActionModeCallback());
        setupResetPasswordForm();
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MFA_VERIFY) {
            if (resultCode == RESULT_OK && pendingNewPassword != null) {
                finishSuccessfully(pendingNewPassword);
            } else {
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onCancel();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private void setupResetPasswordForm() {
        final EditText newPasswordInput = binding.resetNewPasswordInput;
        final ImageView newPasswordToggle = binding.resetNewPasswordToggle;
        final EditText repeatPasswordInput = binding.resetRepeatPasswordInput;
        final ImageView repeatPasswordToggle = binding.resetRepeatPasswordToggle;

        final View[] strengthSegments = {
                binding.resetStrengthSegment1,
                binding.resetStrengthSegment2,
                binding.resetStrengthSegment3,
                binding.resetStrengthSegment4
        };
        final TextView strengthLabel = binding.resetStrengthLabel;

        final FrameLayout reqLengthDot = binding.resetReqLengthDot;
        final ImageView reqLengthCheck = binding.resetReqLengthCheck;
        final TextView reqLengthText = binding.resetReqLengthText;
        final FrameLayout reqCaseDot = binding.resetReqCaseDot;
        final ImageView reqCaseCheck = binding.resetReqCaseCheck;
        final TextView reqCaseText = binding.resetReqCaseText;
        final FrameLayout reqNumberDot = binding.resetReqNumberDot;
        final ImageView reqNumberCheck = binding.resetReqNumberCheck;
        final TextView reqNumberText = binding.resetReqNumberText;
        final FrameLayout reqSymbolDot = binding.resetReqSymbolDot;
        final ImageView reqSymbolCheck = binding.resetReqSymbolCheck;
        final TextView reqSymbolText = binding.resetReqSymbolText;

        final View confirmStatusContainer = binding.resetConfirmStatusContainer;
        final ImageView confirmStatusIcon = binding.resetConfirmStatusIcon;
        final TextView confirmStatusText = binding.resetConfirmStatusText;
        final View errorContainer = binding.resetErrorContainer;
        final TextView errorText = binding.resetErrorText;
        final MaterialButton submitButton = binding.resetSubmitButton;
        final ProgressBar progressBar = binding.resetProgressBar;
        final MaterialButton cancelButton = binding.resetCancelButton;
        final View.OnClickListener onToggleVisibility = v -> {
            final boolean makeVisible = newPasswordInput.getInputType()
                    != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            setPasswordFieldVisible(newPasswordInput, newPasswordToggle, makeVisible);
            setPasswordFieldVisible(repeatPasswordInput, repeatPasswordToggle, makeVisible);
        };
        newPasswordToggle.setOnClickListener(onToggleVisibility);
        repeatPasswordToggle.setOnClickListener(onToggleVisibility);
        setFieldFocusStyle(binding.resetNewPasswordField, newPasswordInput);

        final Runnable validate = () -> {
            final String newPassword = newPasswordInput.getText().toString();
            final String repeatPassword = repeatPasswordInput.getText().toString();

            final boolean hasLength = newPassword.length() >= 12;
            final boolean hasCase = containsUpperAndLowerCase(newPassword);
            final boolean hasNumber = containsDigit(newPassword);
            final boolean hasSymbol = containsSymbol(newPassword);
            final int metCount =
                    (hasLength ? 1 : 0) + (hasCase ? 1 : 0) + (hasNumber ? 1 : 0) + (hasSymbol ? 1 : 0);

            setRequirementMet(reqLengthDot, reqLengthCheck, reqLengthText, hasLength);
            setRequirementMet(reqCaseDot, reqCaseCheck, reqCaseText, hasCase);
            setRequirementMet(reqNumberDot, reqNumberCheck, reqNumberText, hasNumber);
            setRequirementMet(reqSymbolDot, reqSymbolCheck, reqSymbolText, hasSymbol);
            updateStrengthMeter(strengthSegments, strengthLabel, metCount, newPassword.length());

            final boolean touched = !repeatPassword.isEmpty();
            final boolean match = touched && newPassword.equals(repeatPassword);
            final boolean mismatch = touched && !match;
            if (mismatch) {
                confirmStatusContainer.setVisibility(View.VISIBLE);
                confirmStatusIcon.setImageResource(R.drawable.ic_baseline_close_24);
                confirmStatusIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.reset_strength_weak)));
                confirmStatusText.setText(R.string.auth_passwords_do_not_match);
                confirmStatusText.setTextColor(getColor(R.color.reset_strength_weak));
            } else if (match) {
                confirmStatusContainer.setVisibility(View.VISIBLE);
                confirmStatusIcon.setImageResource(R.drawable.ic_check_24dp);
                confirmStatusIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.reset_strength_strong)));
                confirmStatusText.setText(R.string.auth_passwords_match);
                confirmStatusText.setTextColor(getColor(R.color.reset_strength_strong));
            } else {
                confirmStatusContainer.setVisibility(View.GONE);
            }
            binding.resetRepeatPasswordField.setBackgroundResource(mismatch
                    ? R.drawable.bg_reset_field_error
                    : match
                            ? R.drawable.bg_reset_field_success
                            : confirmFocused.get() ? R.drawable.bg_reset_field_focused : R.drawable.bg_reset_field);

            submitButton.setEnabled(hasLength && hasCase && hasNumber && hasSymbol && match);
        };
        repeatPasswordInput.setOnFocusChangeListener((v, hasFocus) -> {
            confirmFocused.set(hasFocus);
            validate.run();
        });

        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                errorContainer.setVisibility(View.GONE);
                validate.run();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        };
        newPasswordInput.addTextChangedListener(watcher);
        repeatPasswordInput.addTextChangedListener(watcher);
        validate.run();

        final Consumer<Boolean> setBusy = busy -> {
            cancelButton.setEnabled(!busy);
            newPasswordInput.setEnabled(!busy);
            repeatPasswordInput.setEnabled(!busy);
            newPasswordToggle.setEnabled(!busy);
            repeatPasswordToggle.setEnabled(!busy);
            submitButton.setText(busy ? "" : getString(R.string.update_password_button));
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) {
                submitButton.setEnabled(false);
            } else {
                validate.run();
            }
        };

        cancelButton.setOnClickListener(ignored -> onCancel());

        submitButton.setOnClickListener(ignored -> {
            final String newPassword = newPasswordInput.getText().toString();
            setBusy.accept(true);
            Amplify.Auth.confirmSignIn(
                    newPassword,
                    confirmResult -> runOnUiThread(() -> {
                        if (confirmResult.isSignedIn()) {
                            Log.i(Config.LOGTAG, "Password reset complete");
                            finishSuccessfully(newPassword);
                        } else if (requiresMfa(confirmResult.getNextStep().getSignInStep())) {
                            Log.i(Config.LOGTAG, "Password reset complete; MFA required");
                            pendingNewPassword = newPassword;
                            startActivityForResult(
                                    new Intent(this, MfaVerifyActivity.class), REQUEST_MFA_VERIFY);
                        } else {
                            Log.i(Config.LOGTAG, "Password reset step incomplete: "
                                    + confirmResult.getNextStep().getSignInStep());
                            setBusy.accept(false);
                            errorText.setText(getString(R.string.auth_password_reset_failed));
                            errorContainer.setVisibility(View.VISIBLE);
                        }
                    }),
                    error -> runOnUiThread(() -> {
                        Log.e(Config.LOGTAG, "Failed to complete password reset", error);
                        setBusy.accept(false);
                        errorText.setText(getString(R.string.auth_password_reset_failed));
                        errorContainer.setVisibility(View.VISIBLE);
                    })
            );
        });
    }


    private void finishSuccessfully(final String newPassword) {
        binding.resetFormPage.setVisibility(View.GONE);
        binding.resetSuccessPage.setVisibility(View.VISIBLE);
        binding.resetSuccessPage.postDelayed(() -> {
            final Intent result = new Intent();
            result.putExtra(EXTRA_NEW_PASSWORD, newPassword);
            setResult(RESULT_OK, result);
            finish();
        }, SUCCESS_PAGE_DELAY_MS);
    }

    private static boolean requiresMfa(final AuthSignInStep signInStep) {
        return signInStep == AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE
                || signInStep == AuthSignInStep.CONFIRM_SIGN_IN_WITH_SMS_MFA_CODE;
    }

    private void setPasswordFieldVisible(EditText input, ImageView toggle, boolean visible) {
        if (visible) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            toggle.setImageResource(R.drawable.ic_visibility_24dp);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            toggle.setImageResource(R.drawable.ic_visibility_off);
        }
        input.setSelection(input.getText().length());
    }

    private void setFieldFocusStyle(View container, EditText input) {
        input.setOnFocusChangeListener((v, hasFocus) ->
                container.setBackgroundResource(
                        hasFocus ? R.drawable.bg_reset_field_focused : R.drawable.bg_reset_field));
    }

    private void setRequirementMet(FrameLayout dot, ImageView check, TextView label, boolean met) {
        dot.setBackgroundResource(
                met ? R.drawable.bg_reset_requirement_dot_met : R.drawable.bg_reset_requirement_dot_unmet);
        check.setVisibility(met ? View.VISIBLE : View.GONE);
        label.setTextColor(getColor(met ? R.color.restore_text : R.color.restore_muted));
    }

    private void updateStrengthMeter(View[] segments, TextView label, int metCount, int pwLen) {
        if (pwLen == 0) {
            label.setVisibility(View.GONE);
            final int mutedColor = getColor(R.color.restore_border_strong);
            for (final View segment : segments) {
                final Drawable background = segment.getBackground();
                if (background instanceof GradientDrawable) {
                    ((GradientDrawable) background.mutate()).setColor(mutedColor);
                }
            }
            return;
        }

        final int bandColor;
        final int bandLabelRes;
        if (metCount <= 1) {
            bandColor = getColor(R.color.reset_strength_weak);
            bandLabelRes = metCount == 0 ? R.string.password_strength_too_weak : R.string.password_strength_weak;
        } else if (metCount == 2) {
            bandColor = getColor(R.color.reset_strength_fair);
            bandLabelRes = R.string.password_strength_fair;
        } else if (metCount == 3) {
            bandColor = getColor(R.color.reset_strength_good);
            bandLabelRes = R.string.password_strength_good;
        } else {
            bandColor = getColor(R.color.reset_strength_strong);
            bandLabelRes = R.string.password_strength_strong;
        }
        final int mutedColor = getColor(R.color.restore_border_strong);
        for (int i = 0; i < segments.length; i++) {
            final Drawable background = segments[i].getBackground();
            if (background instanceof GradientDrawable) {
                ((GradientDrawable) background.mutate()).setColor(i < metCount ? bandColor : mutedColor);
            }
        }
        label.setText(bandLabelRes);
        label.setTextColor(bandColor);
        label.setVisibility(View.VISIBLE);
    }

    private static boolean containsUpperAndLowerCase(String s) {
        boolean hasUpper = false;
        boolean hasLower = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            hasUpper |= Character.isUpperCase(c);
            hasLower |= Character.isLowerCase(c);
        }
        return hasUpper && hasLower;
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSymbol(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
