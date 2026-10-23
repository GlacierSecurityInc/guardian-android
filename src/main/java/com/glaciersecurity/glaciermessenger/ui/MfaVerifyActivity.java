package com.glaciersecurity.glaciermessenger.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.res.ResourcesCompat;
import androidx.databinding.DataBindingUtil;

import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityMfaVerifyBinding;
import com.glaciersecurity.glaciermessenger.ui.widget.DisabledActionModeCallback;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

public class MfaVerifyActivity extends BaseActivity {

    // How long the success page lingers before handing control back to the caller, so the
    // "Signing you in…" message is actually readable instead of flashing by.
    private static final long SUCCESS_PAGE_DELAY_MS = 900L;

    private static final int CODE_LENGTH = 6;

    private ActivityMfaVerifyBinding binding;

    // Segmented code boxes, built at runtime and kept in sync with the hidden capture field.
    private final View[] otpBoxes = new View[CODE_LENGTH];
    private final TextView[] otpDigits = new TextView[CODE_LENGTH];
    private final View[] otpCarets = new View[CODE_LENGTH];

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        setResult(RESULT_CANCELED);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_mfa_verify);
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
        binding.mfaCodeInput.setCustomSelectionActionModeCallback(new DisabledActionModeCallback());
        buildOtpBoxes();
        setupMfaForm();
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

    private int dp(final float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    // Builds the six segmented digit boxes into mfa_otp_container. Each box carries a
    // centered digit TextView and a blinking caret shown only on the active slot.
    private void buildOtpBoxes() {
        final LinearLayout container = binding.mfaOtpContainer;
        container.removeAllViews();
        final int gap = dp(9);
        final int caretW = dp(2);
        final int caretH = dp(26);
        for (int i = 0; i < CODE_LENGTH; i++) {
            final FrameLayout box = new FrameLayout(this);
            final LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) {
                lp.setMarginStart(gap);
            }
            box.setLayoutParams(lp);
            box.setBackgroundResource(R.drawable.bg_mfa_otp_box);

            final TextView digit = new TextView(this);
            final FrameLayout.LayoutParams digitLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            digit.setLayoutParams(digitLp);
            digit.setTextColor(getResources().getColor(R.color.restore_text, getTheme()));
            digit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
            final android.graphics.Typeface brand =
                    ResourcesCompat.getFont(this, R.font.neue_haas_grotesk);
            digit.setTypeface(
                    brand != null ? brand : digit.getTypeface(), android.graphics.Typeface.BOLD);
            box.addView(digit);

            final View caret = new View(this);
            final FrameLayout.LayoutParams caretLp =
                    new FrameLayout.LayoutParams(caretW, caretH, Gravity.CENTER);
            caret.setLayoutParams(caretLp);
            caret.setBackgroundResource(R.drawable.bg_mfa_caret);
            caret.setVisibility(View.GONE);
            box.addView(caret);

            container.addView(box);
            otpBoxes[i] = box;
            otpDigits[i] = digit;
            otpCarets[i] = caret;
        }
        renderOtp("");
    }

    // Reflects the captured code onto the boxes: fill typed digits, mark the next empty
    // slot active with a caret, and reset the rest.
    private void renderOtp(final CharSequence code) {
        final int len = code.length();
        for (int i = 0; i < CODE_LENGTH; i++) {
            final boolean filled = i < len;
            final boolean active = i == len;
            otpDigits[i].setText(filled ? String.valueOf(code.charAt(i)) : "");
            if (active) {
                otpBoxes[i].setBackgroundResource(R.drawable.bg_mfa_otp_box_active);
                startCaretBlink(otpCarets[i]);
            } else {
                otpCarets[i].clearAnimation();
                otpCarets[i].setVisibility(View.GONE);
                otpBoxes[i].setBackgroundResource(
                        filled ? R.drawable.bg_mfa_otp_box_filled : R.drawable.bg_mfa_otp_box);
            }
        }
    }

    private void startCaretBlink(final View caret) {
        caret.setVisibility(View.VISIBLE);
        if (caret.getAnimation() != null) {
            return;
        }
        final AlphaAnimation blink = new AlphaAnimation(1f, 0f);
        blink.setDuration(500);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        caret.startAnimation(blink);
    }

    private void setupMfaForm() {
        final EditText codeInput = binding.mfaCodeInput;
        final View errorContainer = binding.mfaErrorContainer;
        final TextView errorText = binding.mfaErrorText;
        final MaterialButton confirmButton = binding.mfaConfirmButton;
        final ProgressBar progressBar = binding.mfaProgressBar;
        final MaterialButton cancelButton = binding.mfaCancelButton;
        final MaterialButton pasteButton = binding.mfaPasteButton;
        final View lostAccess = binding.mfaLostAccess;
        final View formPage = binding.mfaFormPage;
        final View successPage = binding.mfaSuccessPage;

        codeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                errorContainer.setVisibility(View.GONE);
                confirmButton.setEnabled(s.length() == CODE_LENGTH);
                renderOtp(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Keep the (invisible) caret pinned to the end so entry always appends.
                if (codeInput.getSelectionStart() != s.length()) {
                    codeInput.setSelection(s.length());
                }
            }
        });

        // The transparent field overlaps the boxes; make the whole strip a focus target and
        // pop the keyboard on entry.
        codeInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                codeInput.setSelection(codeInput.getText().length());
            }
        });
        codeInput.requestFocus();
        codeInput.post(() -> showKeyboard(codeInput));

        final Consumer<Boolean> setBusy = busy -> {
            cancelButton.setEnabled(!busy);
            codeInput.setEnabled(!busy);
            pasteButton.setEnabled(!busy);
            confirmButton.setText(busy ? "" : getString(R.string.mfa_confirm_button));
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) {
                confirmButton.setEnabled(false);
            } else {
                confirmButton.setEnabled(codeInput.getText().length() == CODE_LENGTH);
            }
        };

        pasteButton.setOnClickListener(v -> pasteCodeFromClipboard(codeInput));

        lostAccess.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mfa_lost_access_title)
                .setMessage(R.string.mfa_lost_access_message)
                .setPositiveButton(R.string.ok, null)
                .show());

        cancelButton.setOnClickListener(v -> onCancel());

        confirmButton.setOnClickListener(v -> {
            final String code = codeInput.getText().toString().trim();
            setBusy.accept(true);
            hideKeyboard(codeInput);
            Amplify.Auth.confirmSignIn(
                    code,
                    confirmResult -> runOnUiThread(() -> {
                        if (confirmResult.isSignedIn()) {
                            Log.i(Config.LOGTAG, "MFA Sign in complete");
                            formPage.setVisibility(View.GONE);
                            successPage.setVisibility(View.VISIBLE);
                            successPage.postDelayed(() -> {
                                setResult(RESULT_OK);
                                finish();
                            }, SUCCESS_PAGE_DELAY_MS);
                        } else {
                            Log.i(Config.LOGTAG, "MFA step incomplete");
                            setBusy.accept(false);
                            errorText.setText(getString(R.string.auth_mfa_failed));
                            errorContainer.setVisibility(View.VISIBLE);
                        }
                    }),
                    error -> runOnUiThread(() -> {
                        Log.e(Config.LOGTAG, "Failed to complete MFA sign-in", error);
                        setBusy.accept(false);
                        errorText.setText(getString(R.string.auth_mfa_failed));
                        errorContainer.setVisibility(View.VISIBLE);
                    })
            );
        });
    }

    // Pulls the first six digits out of whatever is on the clipboard so a code copied from an
    // authenticator or SMS can be dropped in with one tap.
    private void pasteCodeFromClipboard(final EditText codeInput) {
        final ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return;
        }
        final ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        final CharSequence pasted = clip.getItemAt(0).coerceToText(this);
        if (pasted == null) {
            return;
        }
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < pasted.length() && digits.length() < CODE_LENGTH; i++) {
            final char c = pasted.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return;
        }
        codeInput.setText(digits);
        codeInput.setSelection(codeInput.getText().length());
    }

    private void showKeyboard(final View view) {
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard(final View view) {
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
