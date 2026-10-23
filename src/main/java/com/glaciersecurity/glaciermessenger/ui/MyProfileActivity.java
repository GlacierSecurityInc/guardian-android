package com.glaciersecurity.glaciermessenger.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.KeyChainAliasCallback;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import com.glaciersecurity.glaciermessenger.utils.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Lifecycle;

import com.amplifyframework.auth.AuthException;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.XmppAxolotlSession;
import com.glaciersecurity.glaciermessenger.databinding.ActivityMyProfileBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.ListItem;
import com.glaciersecurity.glaciermessenger.entities.PresenceTemplate;
import com.glaciersecurity.glaciermessenger.services.BarcodeProvider;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService.OnAccountUpdate;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService.OnCaptchaRequested;
import com.glaciersecurity.glaciermessenger.ui.util.AvatarWorkerTask;
import com.glaciersecurity.glaciermessenger.ui.util.MenuDoubleTabUtil;
import com.glaciersecurity.glaciermessenger.ui.util.PendingItem;
import com.glaciersecurity.glaciermessenger.ui.util.SaslDowngrade;
import com.glaciersecurity.glaciermessenger.ui.util.SoftKeyboardUtils;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.utils.UIHelper;
import com.glaciersecurity.glaciermessenger.utils.XmppUri;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.OnKeyStatusUpdated;
import com.glaciersecurity.glaciermessenger.xmpp.OnUpdateBlocklist;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection.Features;
import com.glaciersecurity.glaciermessenger.xmpp.forms.Data;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;
import com.glaciersecurity.glaciermessenger.xmpp.pep.Avatar;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import org.openintents.openpgp.util.OpenPgpUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


public class MyProfileActivity extends OmemoActivity
        implements OnAccountUpdate,
        OnUpdateBlocklist,
        OnKeyStatusUpdated,
        OnCaptchaRequested,
        KeyChainAliasCallback,
        XmppConnectionService.OnShowErrorToast,
        XmppConnectionService.OnMamPreferencesFetched,
        XmppConnectionService.OnJingleRtpConnectionUpdate,
        ConnectivityReceiver.ConnectivityReceiverListener {

    public static final String EXTRA_OPENED_FROM_NOTIFICATION = "opened_from_notification";
    public static final String EXTRA_FORCE_REGISTER = "force_register";

    private static final int REQUEST_CHANGE_STATUS = 0xee11;
    private final PendingItem<PresenceTemplate> mPendingPresenceTemplate = new PendingItem<>();
    private AlertDialog mCaptchaDialog = null;
    private Jid jidToEdit;
    private Boolean mForceRegister = null;
    private Account mAccount;

    private final UiCallback<Avatar> mAvatarFetchCallback =
            new UiCallback<Avatar>() {

                @Override
                public void userInputRequired(final PendingIntent pi) {
                    finishInitialSetup();
                }

                @Override
                public void success(final Avatar avatar) {
                    finishInitialSetup();
                }

                @Override
                public void error(final int errorCode) {
                    finishInitialSetup();
                }
            };
    private final OnClickListener mAvatarClickListener =
            new OnClickListener() {
                @Override
                public void onClick(final View view) {
                    if (mAccount != null) {
                        final Intent intent =
                                new Intent(
                                        getApplicationContext(),
                                        PublishProfilePictureActivity.class);
                        intent.putExtra(
                                EXTRA_ACCOUNT, mAccount.getJid().asBareJid().toEscapedString());
                        startActivity(intent);
                    }
                }
            };
    private String messageFingerprint;
    private boolean mFetchingAvatar = false;
    private Toast mFetchingMamPrefsToast;
    private String mSavedInstanceAccount;
    private XmppUri pendingUri = null;
    private ActivityMyProfileBinding binding;
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;

    @Override
    public void refreshUiReal() {
        invalidateOptionsMenu();
        if (mAccount != null && mAccount.getStatus() != Account.State.ONLINE && mFetchingAvatar) {
            Intent intent = new Intent(this, StartConversationActivity.class);
            StartConversationActivity.addInviteUri(intent, getIntent());
            startActivity(intent);
            finish();
        } else if ( mAccount != null && mAccount.getStatus() == Account.State.ONLINE) {
            if (!mFetchingAvatar) {
                mFetchingAvatar = true;
                xmppConnectionService.checkForAvatar(mAccount, mAvatarFetchCallback);
            }
        }
        if (mAccount != null) {
            updateAccountInformation();
        }
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    protected void finishInitialSetup() {
        runOnUiThread(
                () -> {
                    SoftKeyboardUtils.hideSoftKeyboard(MyProfileActivity.this);

                });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHANGE_STATUS) {
            PresenceTemplate template = mPendingPresenceTemplate.pop();
            if (template != null && resultCode == Activity.RESULT_OK) {
                generateSignature(data, template);
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


    @Override
    protected String getShareableUri(boolean http) {
        if (mAccount != null) {
            return http ? mAccount.getShareableLink() : mAccount.getShareableUri();
        } else {
            return null;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            this.mSavedInstanceAccount = savedInstanceState.getString("account");
        }
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_my_profile);
        binding.returnToGlacierCall.setOnClickListener(v -> launchGlacierCall());

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

        this.binding.editDisplaynameNameButton.setOnClickListener(this::onEditYourNameClicked);
        this.binding.scanButton.setOnClickListener((v) -> ScanActivity.scan(this));
        this.binding.changeStatus.setOnClickListener(view -> changePresence());
        this.binding.changePassword.setOnClickListener(view -> showChangePasswordDialog());
        this.binding.clearDevices.setOnClickListener(view -> showWipePepDialog());
        this.binding.resetSecureConnections.setOnClickListener(view -> deleteOmemoIdentities());
        this.binding.acceptSaslDowngrade.setOnClickListener(
                view -> SaslDowngrade.promptToClearPin(this, xmppConnectionService, mAccount));
        this.binding.changeAvatar.setOnClickListener(mAvatarClickListener);
        this.binding.actionEditYourName.setOnClickListener(this::onEditYourNameClicked);

    }

    private void onEditYourNameClicked(View view) {
        quickEdit(
                mAccount.getDisplayName(),
                R.string.your_name,
                value -> {
                    final String displayName = value.trim();
                    mAccount.setDisplayName(displayName);
                    xmppConnectionService.publishDisplayName(mAccount);
                    refreshAvatar();
                    return null;
                },
                true);
    }

    private void refreshAvatar() {
        AvatarWorkerTask.loadAvatar(
                mAccount, binding.avater, R.dimen.avatar_on_details_screen_size);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.my_profile_actions, menu);



        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        final Intent intent = getIntent();
        if (intent != null) {
            try {
                this.jidToEdit = Jid.ofEscaped(intent.getStringExtra("jid"));
            } catch (final IllegalArgumentException | NullPointerException ignored) {
                this.jidToEdit = null;
            }
            final Uri data = intent.getData();
            final XmppUri xmppUri = data == null ? null : new XmppUri(data);
            final boolean scanned = intent.getBooleanExtra("scanned", false);
            if (jidToEdit != null && xmppUri != null && xmppUri.hasFingerprints()) {
                if (scanned) {
                    if (xmppConnectionServiceBound) {
                        processFingerprintVerification(xmppUri, false);
                    } else {
                        this.pendingUri = xmppUri;
                    }
                } else {
                    displayVerificationWarningDialog(xmppUri);
                }
            }
            boolean openedFromNotification =
                    intent.getBooleanExtra(EXTRA_OPENED_FROM_NOTIFICATION, false);
            this.mForceRegister =
                    intent.hasExtra(EXTRA_FORCE_REGISTER)
                            ? intent.getBooleanExtra(EXTRA_FORCE_REGISTER, false)
                            : null;
            this.messageFingerprint = intent.getStringExtra("fingerprint");
            setTitle(getString(R.string.account_details));
            configureActionBar(getSupportActionBar(), !openedFromNotification);

        }
    }

    private void displayVerificationWarningDialog(final XmppUri xmppUri) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.verify_omemo_keys);
        View view = getLayoutInflater().inflate(R.layout.dialog_verify_fingerprints, null);
        final CheckBox isTrustedSource = view.findViewById(R.id.trusted_source);
        TextView warning = view.findViewById(R.id.warning);
        warning.setText(R.string.verifying_omemo_keys_trusted_source_account);
        builder.setView(view);
        builder.setPositiveButton(
                R.string.continue_btn,
                (dialog, which) -> {
                    if (isTrustedSource.isChecked()) {
                        processFingerprintVerification(xmppUri, false);
                    } else {
                        finish();
                    }
                });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> finish());
        final var dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> finish());
        dialog.show();
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
            savedInstanceState.putBoolean(
                    "showMoreTable", binding.serverInfoMore.getVisibility() == View.VISIBLE);
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

        } else if (this.jidToEdit != null) {
            this.mAccount = xmppConnectionService.findAccountByJid(jidToEdit);
        }

        if (mAccount != null) {
            if (mPendingFingerprintVerificationUri != null) {
                processFingerprintVerification(mPendingFingerprintVerificationUri, false);
                mPendingFingerprintVerificationUri = null;
            }
            updateAccountInformation();
        }

        if (pendingUri != null) {
            processFingerprintVerification(pendingUri, false);
            pendingUri = null;
        }
        invalidateOptionsMenu();
        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(jingleManager != null && jingleManager.getOngoingRtpConnection() != null);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        final int id = item.getItemId();
        if (id == android.R.id.home) {
        } else if (id == R.id.action_share_barcode) {
            shareBarcode();
        } else if (id == R.id.action_share_http) {
            shareLink(true);
        } else if (id == R.id.action_share_uri) {
            shareLink(false);
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteAccount() {
        this.deleteAccount(mAccount, () -> finish());
    }

    private void shareBarcode() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, BarcodeProvider.getUriForAccount(this, mAccount));
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("image/png");
        startActivity(Intent.createChooser(intent, getText(R.string.share_with)));
    }

    private void showChangePasswordDialog() {
        final View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null);

        final View currentPasswordField = view.findViewById(R.id.cpw_current_password_field);
        final EditText currentPasswordInput = view.findViewById(R.id.cpw_current_password_input);
        final ImageView currentPasswordToggle = view.findViewById(R.id.cpw_current_password_toggle);

        final View newPasswordField = view.findViewById(R.id.cpw_new_password_field);
        final EditText newPasswordInput = view.findViewById(R.id.cpw_new_password_input);
        final ImageView newPasswordToggle = view.findViewById(R.id.cpw_new_password_toggle);

        final View repeatPasswordField = view.findViewById(R.id.cpw_repeat_password_field);
        final EditText repeatPasswordInput = view.findViewById(R.id.cpw_repeat_password_input);
        final ImageView repeatPasswordToggle = view.findViewById(R.id.cpw_repeat_password_toggle);

        final View[] strengthSegments = {
                view.findViewById(R.id.cpw_strength_segment_1),
                view.findViewById(R.id.cpw_strength_segment_2),
                view.findViewById(R.id.cpw_strength_segment_3),
                view.findViewById(R.id.cpw_strength_segment_4)
        };
        final ImageView reqLengthIcon = view.findViewById(R.id.cpw_req_length_icon);
        final ImageView reqCaseIcon = view.findViewById(R.id.cpw_req_case_icon);
        final ImageView reqNumberIcon = view.findViewById(R.id.cpw_req_number_icon);
        final ImageView reqSymbolIcon = view.findViewById(R.id.cpw_req_symbol_icon);

        final TextView mismatchHint = view.findViewById(R.id.cpw_mismatch_hint);
        final View errorContainer = view.findViewById(R.id.cpw_error_container);
        final TextView errorText = view.findViewById(R.id.cpw_error_text);
        final MaterialButton submitButton = view.findViewById(R.id.cpw_submit_button);
        final ProgressBar progressBar = view.findViewById(R.id.cpw_progress_bar);
        final MaterialButton cancelButton = view.findViewById(R.id.cpw_cancel_button);
        final ImageView closeButton = view.findViewById(R.id.cpw_close_button);
        final View formPage = view.findViewById(R.id.cpw_form_page);
        final View successPage = view.findViewById(R.id.cpw_success_page);

        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(view);
        final BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setDraggable(false);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);

        currentPasswordToggle.setOnClickListener(v -> togglePasswordVisibility(currentPasswordInput, currentPasswordToggle));
        newPasswordToggle.setOnClickListener(v -> togglePasswordVisibility(newPasswordInput, newPasswordToggle));
        repeatPasswordToggle.setOnClickListener(v -> togglePasswordVisibility(repeatPasswordInput, repeatPasswordToggle));
        setFieldFocusStyle(currentPasswordField, currentPasswordInput);
        setFieldFocusStyle(newPasswordField, newPasswordInput);
        setFieldFocusStyle(repeatPasswordField, repeatPasswordInput);

        final Runnable validate = () -> {
            final String currentPassword = currentPasswordInput.getText().toString();
            final String newPassword = newPasswordInput.getText().toString();
            final String repeatPassword = repeatPasswordInput.getText().toString();

            final boolean hasLength = newPassword.length() >= 12;
            final boolean hasCase = containsUpperAndLowerCase(newPassword);
            final boolean hasNumber = containsDigit(newPassword);
            final boolean hasSymbol = containsSymbol(newPassword);

            setRequirementMet(reqLengthIcon, hasLength);
            setRequirementMet(reqCaseIcon, hasCase);
            setRequirementMet(reqNumberIcon, hasNumber);
            setRequirementMet(reqSymbolIcon, hasSymbol);
            updateStrengthMeter(
                    strengthSegments,
                    (hasLength ? 1 : 0) + (hasCase ? 1 : 0) + (hasNumber ? 1 : 0) + (hasSymbol ? 1 : 0));

            final boolean passwordsMatch = !repeatPassword.isEmpty() && newPassword.equals(repeatPassword);
            mismatchHint.setVisibility(!repeatPassword.isEmpty() && !passwordsMatch ? View.VISIBLE : View.GONE);

            submitButton.setEnabled(
                    !currentPassword.isEmpty() && hasLength && hasCase && hasNumber && hasSymbol && passwordsMatch);
        };

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
        currentPasswordInput.addTextChangedListener(watcher);
        newPasswordInput.addTextChangedListener(watcher);
        repeatPasswordInput.addTextChangedListener(watcher);

        final Consumer<Boolean> setBusy = busy -> {
            cancelButton.setEnabled(!busy);
            closeButton.setEnabled(!busy);
            currentPasswordInput.setEnabled(!busy);
            newPasswordInput.setEnabled(!busy);
            repeatPasswordInput.setEnabled(!busy);
            currentPasswordToggle.setEnabled(!busy);
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

        submitButton.setOnClickListener(ignored -> {
            final String currentPassword = currentPasswordInput.getText().toString();
            final String newPassword = newPasswordInput.getText().toString();
            setBusy.accept(true);
            changeCognitoPassword(
                    currentPassword,
                    newPassword,
                    () -> runOnUiThread(() -> {
                        currentPasswordInput.getEditableText().clear();
                        newPasswordInput.getEditableText().clear();
                        repeatPasswordInput.getEditableText().clear();
                        formPage.setVisibility(View.GONE);
                        successPage.setVisibility(View.VISIBLE);
                        successPage.postDelayed(dialog::dismiss, 1500L);
                    }),
                    error -> runOnUiThread(() -> {
                        setBusy.accept(false);
                        errorText.setText(isIncorrectCurrentPasswordError(error)
                                ? getString(R.string.incorrect_current_password)
                                : getString(R.string.could_not_change_password));
                        errorContainer.setVisibility(View.VISIBLE);
                    }));
        });

        cancelButton.setOnClickListener(ignored -> dialog.dismiss());
        closeButton.setOnClickListener(ignored -> dialog.dismiss());

        dialog.show();
    }

    private void changeCognitoPassword(
            final String currentPassword,
            final String newPassword,
            final Runnable onSuccess,
            final Consumer<AuthException> onError) {
        if (!GlacierGuardian.ensureAmplifyConfigured(this)) {
            onError.accept(null);
            return;
        }
        Amplify.Auth.updatePassword(
                currentPassword,
                newPassword,
                () -> {
                    Log.i(Config.LOGTAG, "Cognito password changed");
                    if (mAccount != null) {
                        xmppConnectionService.updateCognitoPassword(mAccount, newPassword);
                    }
                    onSuccess.run();
                },
                error -> {
                    Log.e(Config.LOGTAG, "Failed to change Cognito password", error);
                    onError.accept(error);
                });
    }

    private boolean isIncorrectCurrentPasswordError(final AuthException error) {
        if (error == null) {
            return false;
        }
        final String className = error.getClass().getSimpleName();
        if ("NotAuthorizedException".equals(className) || "UserNotFoundException".equals(className)) {
            return true;
        }
        final StringBuilder details = new StringBuilder();
        if (error.getMessage() != null) {
            details.append(error.getMessage()).append(' ');
        }
        if (error.getRecoverySuggestion() != null) {
            details.append(error.getRecoverySuggestion()).append(' ');
        }
        if (error.getCause() != null && error.getCause().getMessage() != null) {
            details.append(error.getCause().getMessage());
        }
        final String lowerDetails = details.toString().toLowerCase(java.util.Locale.US);
        return lowerDetails.contains("incorrect username")
                || lowerDetails.contains("incorrect password")
                || lowerDetails.contains("not authorized");
    }

    private void togglePasswordVisibility(final EditText input, final ImageView toggle) {
        final boolean isVisible =
                input.getInputType() == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (isVisible) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            toggle.setImageResource(R.drawable.ic_visibility_off);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            toggle.setImageResource(R.drawable.ic_visibility_24dp);
        }
        input.setSelection(input.getText().length());
    }

    private void setFieldFocusStyle(final View container, final EditText input) {
        input.setOnFocusChangeListener((v, hasFocus) ->
                container.setBackgroundResource(
                        hasFocus ? R.drawable.bg_password_field_focused : R.drawable.bg_password_field));
    }

    private void setRequirementMet(final ImageView icon, final boolean met) {
        if (met) {
            icon.setImageResource(R.drawable.ic_check_circle_filled_16dp);
            icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.green600)));
        } else {
            icon.setImageResource(R.drawable.ic_circle_outline_16dp);
            icon.setImageTintList(null);
        }
    }

    private void updateStrengthMeter(final View[] segments, final int metCount) {
        final int accentColor = getColor(R.color.glacier_blue);
        final int mutedColor =
                MaterialColors.getColor(segments[0], com.google.android.material.R.attr.colorOutlineVariant);
        for (int i = 0; i < segments.length; i++) {
            final Drawable background = segments[i].getBackground();
            if (background instanceof GradientDrawable) {
                ((GradientDrawable) background.mutate()).setColor(i < metCount ? accentColor : mutedColor);
            }
        }
    }

    private static boolean containsUpperAndLowerCase(final String s) {
        boolean hasUpper = false;
        boolean hasLower = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            hasUpper |= Character.isUpperCase(c);
            hasLower |= Character.isLowerCase(c);
        }
        return hasUpper && hasLower;
    }

    private static boolean containsDigit(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSymbol(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void changePresence(){
       startActivity(new Intent(this, ChangeStatusActivity.class));
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
                            public void error(int errorCode) {}

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

        this.binding.accountJid.setText(this.mAccount.getJid().getEscapedLocal());
        this.binding.accountUsername.setText(this.mAccount.getDisplayName());
        AvatarWorkerTask.loadAvatar(
                mAccount, binding.avater, R.dimen.avatar_on_details_screen_size);
        updateTeams();
        updateStatus();
        this.binding.acceptSaslDowngrade.setVisibility(
                SaslDowngrade.isBlocked(this.mAccount) ? View.VISIBLE : View.GONE);

            final XmppConnection connection = this.mAccount.getXmppConnection();
            final Features features = connection == null ? null : connection.getFeatures();
            if (features == null) {
                this.binding.stats.setVisibility(View.GONE);
                this.binding.serverInfoRosterVersion.setText(R.string.server_info_unavailable);
                this.binding.serverInfoCarbons.setText(R.string.server_info_unavailable);
                this.binding.serverInfoMam.setText(R.string.server_info_unavailable);
                this.binding.serverInfoCsi.setText(R.string.server_info_unavailable);
                this.binding.serverInfoBlocking.setText(R.string.server_info_unavailable);
                this.binding.serverInfoSm.setText(R.string.server_info_unavailable);
                this.binding.serverInfoExternalService.setText(R.string.server_info_unavailable);
                this.binding.serverInfoBind2.setText(R.string.server_info_unavailable);
                this.binding.serverInfoSasl2.setText(R.string.server_info_unavailable);
                this.binding.serverInfoPep.setText(R.string.server_info_unavailable);
                this.binding.serverInfoHttpUpload.setText(R.string.server_info_unavailable);
                this.binding.loginMechanism.setText("");
            } else {
            this.binding.stats.setVisibility(View.VISIBLE);
            this.binding.sessionEst.setText(
                    UIHelper.readableTimeDifferenceFull(
                            this, connection.getLastSessionEstablished()));
            if (features.rosterVersioning()) {
                this.binding.serverInfoRosterVersion.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoRosterVersion.setText(R.string.server_info_unavailable);
            }
            if (features.carbons()) {
                this.binding.serverInfoCarbons.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoCarbons.setText(R.string.server_info_unavailable);
            }
            if (features.mam()) {
                this.binding.serverInfoMam.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoMam.setText(R.string.server_info_unavailable);
            }
            if (features.csi()) {
                this.binding.serverInfoCsi.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoCsi.setText(R.string.server_info_unavailable);
            }
            if (features.blocking()) {
                this.binding.serverInfoBlocking.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoBlocking.setText(R.string.server_info_unavailable);
            }
            if (features.sm()) {
                this.binding.serverInfoSm.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoSm.setText(R.string.server_info_unavailable);
            }
            if (features.externalServiceDiscovery()) {
                this.binding.serverInfoExternalService.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoExternalService.setText(R.string.server_info_unavailable);
            }
            if (features.bind2()) {
                this.binding.serverInfoBind2.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoBind2.setText(R.string.server_info_unavailable);
            }
            if (features.sasl2()) {
                this.binding.serverInfoSasl2.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoSasl2.setText(R.string.server_info_unavailable);
            }
            this.binding.loginMechanism.setText(Strings.nullToEmpty(features.loginMechanism()));
            if (features.pep()) {
                AxolotlService axolotlService = this.mAccount.getAxolotlService();
                if (axolotlService != null && axolotlService.isPepBroken()) {
                    this.binding.serverInfoPep.setText(R.string.server_info_broken);
                } else if (features.pepPublishOptions() || features.pepOmemoWhitelisted()) {
                    this.binding.serverInfoPep.setText(R.string.server_info_available);
                } else {
                    this.binding.serverInfoPep.setText(R.string.server_info_partial);
                }
            } else {
                this.binding.serverInfoPep.setText(R.string.server_info_unavailable);
            }
            if (features.httpUpload(0)) {
                final long maxFileSize = features.getMaxHttpUploadSize();
                if (maxFileSize > 0) {
                    this.binding.serverInfoHttpUpload.setText(
                            UIHelper.filesizeToString(maxFileSize));
                } else {
                    this.binding.serverInfoHttpUpload.setText(R.string.server_info_available);
                }
            } else {
                this.binding.serverInfoHttpUpload.setText(R.string.server_info_unavailable);
            }
            }

            this.binding.pushRow.setVisibility(
                    xmppConnectionService.getPushManagementService().isStub()
                            ? View.GONE
                            : View.VISIBLE);

            if (xmppConnectionService.getPushManagementService().available(mAccount)) {
                this.binding.serverInfoPush.setText(R.string.server_info_available);
            } else {
                this.binding.serverInfoPush.setText(R.string.server_info_unavailable);
            }
            final long pgpKeyId = this.mAccount.getPgpId();
            if (pgpKeyId != 0 && Config.supportOpenPgp()) {
                OnClickListener openPgp = view -> launchOpenKeyChain(pgpKeyId);
                OnClickListener delete = view -> showDeletePgpDialog();
                this.binding.pgpFingerprintBox.setVisibility(View.VISIBLE);
                this.binding.pgpFingerprint.setText(OpenPgpUtils.convertKeyIdToHex(pgpKeyId));
                this.binding.pgpFingerprint.setOnClickListener(openPgp);
                if ("pgp".equals(messageFingerprint)) {
                    this.binding.pgpFingerprintDesc.setTextColor(
                            MaterialColors.getColor(
                                    binding.pgpFingerprintDesc,
                                    com.google.android.material.R.attr.colorPrimaryVariant));
                }
                this.binding.pgpFingerprintDesc.setOnClickListener(openPgp);
                this.binding.actionDeletePgp.setOnClickListener(delete);
            } else {
                this.binding.pgpFingerprintBox.setVisibility(View.GONE);
            }
            final String ownAxolotlFingerprint =
                    this.mAccount.getAxolotlService().getOwnFingerprint();
            if (ownAxolotlFingerprint != null && Config.supportOmemo()) {
                this.binding.axolotlFingerprintBox.setVisibility(View.VISIBLE);
                final String ownDeviceLabel = Build.MANUFACTURER + " " + Build.MODEL;
                if (ownAxolotlFingerprint.equals(messageFingerprint)) {
                    this.binding.ownFingerprintDesc.setTextColor(
                            MaterialColors.getColor(
                                    binding.ownFingerprintDesc,
                                    com.google.android.material.R.attr.colorPrimaryVariant));
                    this.binding.ownFingerprintDesc.setText(
                            getString(R.string.omemo_fingerprint_device_selected_message, ownDeviceLabel));
                } else {
                    this.binding.ownFingerprintDesc.setTextColor(
                            MaterialColors.getColor(
                                    binding.ownFingerprintDesc,
                                    com.google.android.material.R.attr.colorOnSurface));
                    this.binding.ownFingerprintDesc.setText(ownDeviceLabel);
                }
                this.binding.axolotlFingerprint.setText(
                        CryptoHelper.prettifyFingerprint(ownAxolotlFingerprint.substring(2)));
            } else {
                this.binding.axolotlFingerprintBox.setVisibility(View.GONE);
            }
            boolean hasKeys = false;
            boolean showUnverifiedWarning = false;
            binding.otherDeviceKeys.removeAllViews();
            for (final XmppAxolotlSession session :
                    mAccount.getAxolotlService().findOwnSessions()) {
                final FingerprintStatus trust = session.getTrust();
                if (!trust.isCompromised()) {
                    boolean highlight = session.getFingerprint().equals(messageFingerprint);
                    addFingerprintRow(binding.otherDeviceKeys, session, highlight);
                    hasKeys = true;
                }
                if (trust.isUnverified()) {
                    showUnverifiedWarning = true;
                }
            }
            if (hasKeys
                    && Config.supportOmemo()) {
                // print an active device or the device list should
                // be fed with reactivated devices
                this.binding.otherDeviceKeysCard.setVisibility(View.VISIBLE);
                Set<Integer> otherDevices = mAccount.getAxolotlService().getOwnDeviceIds();
                if (otherDevices == null || otherDevices.isEmpty()) {
                    binding.clearDevices.setVisibility(View.GONE);
                    binding.otherDevicesTitle.setVisibility(View.GONE);
                } else {
                    binding.clearDevices.setVisibility(View.VISIBLE);
                    binding.otherDevicesTitle.setVisibility(View.VISIBLE);
                }
                binding.unverifiedWarning.setVisibility(
                        showUnverifiedWarning ? View.VISIBLE : View.GONE);
            } else {
                this.binding.otherDeviceKeysCard.setVisibility(View.GONE);
            }

    }

    private void updateStatus() {
       binding.detailsContactpresenceIcon.setImageResource(mAccount.getPresenceStatus().getStatusIcon());
        binding.profileStatusMessage.setText(mAccount.getPresenceStatusMessage());
    }

    private void updateTeams() {

        List<Contact> contactsAll = mAccount.getRoster().getContacts();
        AtomicReference<Set<String>> tagList = new AtomicReference<>(new HashSet<>());
        final LayoutInflater inflater = getLayoutInflater();


        for (Contact c : contactsAll){
            List<ListItem.Tag> groups = c.getTags();
            for (int i = 0; i < groups.size(); ++i) {
                tagList.get().add(groups.get(i).getName());
            }
        }
        if (tagList.get().isEmpty()){
            binding.tags.setVisibility(View.GONE);
            binding.teams.setVisibility(View.GONE);

        } else {
            binding.tags.setVisibility(View.VISIBLE);
            binding.teams.setVisibility(View.VISIBLE);

            binding.tags.removeViews(1, binding.tags.getChildCount() - 1);

            final ImmutableList.Builder<Integer> viewIdBuilder = new ImmutableList.Builder<>();
            for (final String name : tagList.get()) {
                final TextView tv = (TextView) inflater.inflate(R.layout.item_tag, binding.tags, false);
                tv.setText(name);
                final int id = ViewCompat.generateViewId();
                tv.setId(id);
                viewIdBuilder.add(id);
                binding.tags.addView(tv);
            }

            binding.teamsFlowWidget.setReferencedIds(Ints.toArray(viewIdBuilder.build()));
        }
    }


    private void showDeletePgpDialog() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.unpublish_pgp);
        builder.setMessage(R.string.unpublish_pgp_message);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.confirm,
                (dialogInterface, i) -> {
                    mAccount.setPgpSignId(0);
                    mAccount.unsetPgpSignature();
                    xmppConnectionService.databaseBackend.updateAccount(mAccount);
                    xmppConnectionService.sendPresence(mAccount);
                    refreshUiReal();
                });
        builder.create().show();
    }

    private void deleteOmemoIdentities() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.pref_delete_glacier_identities);
        builder.setMessage(R.string.pref_reset_omemo_message);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.reset, (dialog, which) -> {
            for (Account account : xmppConnectionService.getAccounts()) {
                if (account != null) {
                    account.getAxolotlService().regenerateKeys(true);
                }
            }
        });
        builder.create().show();
    }

    public void showWipePepDialog() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(getString(R.string.clear_other_devices));
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setMessage(getString(R.string.clear_other_devices_desc));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(
                getString(R.string.accept),
                (dialog, which) -> mAccount.getAxolotlService().wipeOtherPepDevices());
        builder.create().show();
    }

    @Override
    public void onKeyStatusUpdated(AxolotlService.FetchStatus report) {
        refreshUi();
    }

    @Override
    public void onCaptchaRequested(
            final Account account, final String id, final Data data, final Bitmap captcha) {
        runOnUiThread(
                () -> {
                    if (mCaptchaDialog != null && mCaptchaDialog.isShowing()) {
                        mCaptchaDialog.dismiss();
                    }
                    if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                        return;
                    }
                    final MaterialAlertDialogBuilder builder =
                            new MaterialAlertDialogBuilder(MyProfileActivity.this);
                    final View view = getLayoutInflater().inflate(R.layout.captcha, null);
                    final ImageView imageView = view.findViewById(R.id.captcha);
                    final EditText input = view.findViewById(R.id.input);
                    imageView.setImageBitmap(captcha);

                    builder.setTitle(getString(R.string.captcha_required));
                    builder.setView(view);

                    builder.setPositiveButton(
                            getString(R.string.ok),
                            (dialog, which) -> {
                                String rc = input.getText().toString();
                                data.put("username", account.getUsername());
                                data.put("password", account.getPassword());
                                data.put("ocr", rc);
                                data.submit();

                                if (xmppConnectionServiceBound) {
                                    xmppConnectionService.sendCreateAccountWithCaptchaPacket(
                                            account, id, data);
                                }
                            });
                    builder.setNegativeButton(
                            getString(R.string.cancel),
                            (dialog, which) -> {
                                if (xmppConnectionService != null) {
                                    xmppConnectionService.sendCreateAccountWithCaptchaPacket(
                                            account, null, null);
                                }
                            });

                    builder.setOnCancelListener(
                            dialog -> {
                                if (xmppConnectionService != null) {
                                    xmppConnectionService.sendCreateAccountWithCaptchaPacket(
                                            account, null, null);
                                }
                            });
                    mCaptchaDialog = builder.create();
                    mCaptchaDialog.show();
                    input.requestFocus();
                });
    }

    @Override
    public void onShowErrorToast(final int resId) {
        runOnUiThread(
                () -> Toast.makeText(MyProfileActivity.this, resId, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onPreferencesFetched(final Element prefs) {
        runOnUiThread(
                () -> {
                    if (mFetchingMamPrefsToast != null) {
                        mFetchingMamPrefsToast.cancel();
                    }
                    final MaterialAlertDialogBuilder builder =
                            new MaterialAlertDialogBuilder(MyProfileActivity.this);
                    builder.setTitle(R.string.server_side_mam_prefs);
                    String defaultAttr = prefs.getAttribute("default");
                    final List<String> defaults = Arrays.asList("never", "roster", "always");
                    final AtomicInteger choice =
                            new AtomicInteger(Math.max(0, defaults.indexOf(defaultAttr)));
                    builder.setSingleChoiceItems(
                            R.array.mam_prefs, choice.get(), (dialog, which) -> choice.set(which));
                    builder.setNegativeButton(R.string.cancel, null);
                    builder.setPositiveButton(
                            R.string.ok,
                            (dialog, which) -> {
                                prefs.setAttribute("default", defaults.get(choice.get()));
                                xmppConnectionService.pushMamPreferences(mAccount, prefs);
                            });
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                        builder.create().show();
                    }
                });
    }

    @Override
    public void onPreferencesFetchFailed() {
        runOnUiThread(
                () -> {
                    if (mFetchingMamPrefsToast != null) {
                        mFetchingMamPrefsToast.cancel();
                    }
                    Toast.makeText(
                                    MyProfileActivity.this,
                                    R.string.unable_to_fetch_mam_prefs,
                                    Toast.LENGTH_LONG)
                            .show();
                });
    }

    @Override
    public void OnUpdateBlocklist() {
        refreshUi();
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
