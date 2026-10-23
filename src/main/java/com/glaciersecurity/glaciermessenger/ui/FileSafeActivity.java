package com.glaciersecurity.glaciermessenger.ui;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;


import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.databinding.ActivityFilesafeBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.FileSafeAttachment;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileUploadClient;
import com.glaciersecurity.glaciermessenger.services.CallIntegration;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.adapter.ConnectionToolbarAdapter;
import com.glaciersecurity.glaciermessenger.ui.adapter.FileSafeAttachmentAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.ActivityResult;
import com.glaciersecurity.glaciermessenger.ui.util.Attachment;
import com.glaciersecurity.glaciermessenger.utils.FileUtils;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleConnectionManager;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.JingleRtpConnection;
import com.glaciersecurity.glaciermessenger.xmpp.jingle.RtpEndUserState;

import java.io.File;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

public class FileSafeActivity extends XmppActivity implements XmppConnectionService.OnJingleRtpConnectionUpdate, ConnectivityReceiver.ConnectivityReceiverListener {
    public static final String EXTRA_PREFILL_URI =
            "com.glaciersecurity.glaciermessenger.ui.FileSafeActivity.EXTRA_PREFILL_URI";
    private ActivityFilesafeBinding binding;
    private static final int PICK_FILE_REQUEST_CODE = 0x0304;
    private List<FileSafeAttachment> attachmentsSelected = new ArrayList<>();
    private final List<FileSafeAttachment> attachmentsUploaded = new ArrayList<>();

    private boolean uploading = false;
    private int inFlightUploads = 0;
    private FileSafeAttachmentAdapter mFileSafeAttachmentAdapterSelected;
    private FileSafeAttachmentAdapter mFileSafeAttachmentAdapterUploaded;
    private ConnectionToolbarAdapter connectionToolbarAdapter;
    private ConnectivityReceiver connectivityReceiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_filesafe);
        binding.returnToGlacierCall.setOnClickListener(v -> launchGlacierCall());

        mFileSafeAttachmentAdapterSelected = new FileSafeAttachmentAdapter(this, attachmentsSelected);
        mFileSafeAttachmentAdapterSelected.setOnAttachmentActionListener(
                new FileSafeAttachmentAdapter.OnAttachmentActionListener() {
                    @Override
                    public void onRemove(FileSafeAttachment attachment) {
                        mFileSafeAttachmentAdapterSelected.removeAttachmentsList(
                                attachment.getAttachment());
                        updateActionButtons();
                    }

                    @Override
                    public void onRetry(FileSafeAttachment attachment) {
                        retrySingleAttachmentUpload(attachment);
                    }
                });

        mFileSafeAttachmentAdapterUploaded = new FileSafeAttachmentAdapter(this, attachmentsUploaded);

        binding.recyclerViewSelected.setAdapter(mFileSafeAttachmentAdapterSelected);
        binding.recyclerViewUploaded.setAdapter(mFileSafeAttachmentAdapterUploaded);
        this.binding.recyclerViewSelected.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        this.binding.recyclerViewUploaded.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        connectivityReceiver = new ConnectivityReceiver(this);
        connectionToolbarAdapter = new ConnectionToolbarAdapter(
                this, binding.offlineLayout, xmppConnectionService);
        binding.offlineLayout.setOnClickListener(mRefreshNetworkClickListener);
        connectionToolbarAdapter.updateOfflineStatusBar();

        binding.selectFilesButton.setOnClickListener(v -> openFilePicker());
        binding.uploadButton.setOnClickListener(v -> uploadToS3());
        binding.cancelButton.setOnClickListener(v -> {
            mFileSafeAttachmentAdapterSelected.clearAttachmentsList();
            mFileSafeAttachmentAdapterUploaded.clearAttachmentsList();
            updateActionButtons();
        });

        addPrefillAttachment(getIntent());
        updateActionButtons();
    }

    private void retrySingleAttachmentUpload(@NonNull final FileSafeAttachment fileSafeAttachment) {
        if (uploading || inFlightUploads > 0) {
            Toast.makeText(this, R.string.uploading_filesafe_button_message, Toast.LENGTH_SHORT).show();
            return;
        }

        final Attachment attachment = fileSafeAttachment.getAttachment();
        if (attachment == null) {
            return;
        }

        mFileSafeAttachmentAdapterSelected.updateAttachment(
                attachment, FileSafeAttachment.UploadStatus.INIT);
        uploadToS3();
    }

    private void uploadToS3() {
        if (attachmentsSelected.isEmpty()) {
            showNothingToUploadToast();
            updateActionButtons();
            return;
        }

        boolean hasPendingUploads = false;
        for (FileSafeAttachment fileSafeAttachment : attachmentsSelected) {
            if (fileSafeAttachment != null
                    && fileSafeAttachment.getUploadStatus() != FileSafeAttachment.UploadStatus.SUCCESS) {
                hasPendingUploads = true;
                break;
            }
        }

        if (!hasPendingUploads) {
            showNothingToUploadToast();
            updateActionButtons();
            return;
        }

        inFlightUploads = 0;
        uploading = true;
        updateActionButtons();
        for (FileSafeAttachment fileSafeAttachment : attachmentsSelected) {
            Attachment attachment = fileSafeAttachment.getAttachment();
            if (fileSafeAttachment.getUploadStatus().equals(FileSafeAttachment.UploadStatus.SUCCESS)) {
                continue;
            }
            String filepath = FileUtils.getRealPathFromURI(xmppConnectionService, attachment.getUri());
            if (filepath == null) {
                filepath = FileUtils.getDisplayName(xmppConnectionService, attachment.getUri());
                if (filepath == null) {
                    mFileSafeAttachmentAdapterSelected.updateAttachment(
                            attachment, FileSafeAttachment.UploadStatus.FAILED);
                    continue;
                }
                String prefix;
                String postfix;
                try {
                    final int extensionSeparator = filepath.lastIndexOf('.');
                    if (extensionSeparator <= 0 || extensionSeparator >= filepath.length() - 1) {
                        mFileSafeAttachmentAdapterSelected.updateAttachment(
                                attachment, FileSafeAttachment.UploadStatus.FAILED);
                        continue;
                    }
                    prefix = filepath.substring(0, extensionSeparator);
                    if (prefix.length() < 3) {
                        prefix = (prefix + "___").substring(0, 3);
                    }
                    postfix = filepath.substring(extensionSeparator);
                } catch (Exception e) {
                    mFileSafeAttachmentAdapterSelected.updateAttachment(
                            attachment, FileSafeAttachment.UploadStatus.FAILED);
                    continue;
                }

                File outputFile;
                try {
                    outputFile = File.createTempFile(prefix, postfix, getCacheDir());
                } catch (IOException | IllegalArgumentException e) {
                    Log.e(Config.LOGTAG, "Error creating temp file", e);
                    mFileSafeAttachmentAdapterSelected.updateAttachment(
                            attachment, FileSafeAttachment.UploadStatus.FAILED);
                    continue;
                }
                outputFile.deleteOnExit();
                new File(getFilesDir(), filepath);

                try (InputStream inputStream = getContentResolver().openInputStream(attachment.getUri());
                     OutputStream outputStream = new FileOutputStream(outputFile)) {
                    if (inputStream == null) {
                        mFileSafeAttachmentAdapterSelected.updateAttachment(
                                attachment, FileSafeAttachment.UploadStatus.FAILED);
                        continue;
                    }

                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.flush();

                    if (uploadFile(outputFile, attachment)) {
                        mFileSafeAttachmentAdapterSelected.updateAttachment(
                                attachment, FileSafeAttachment.UploadStatus.IN_PROGRESS);
                        inFlightUploads++;
                    } else {
                        mFileSafeAttachmentAdapterSelected.updateAttachment(
                                attachment, FileSafeAttachment.UploadStatus.FAILED);
                    }

                } catch (IOException | RuntimeException e) {
                    Log.e(Config.LOGTAG, "Error copying file", e);
                    mFileSafeAttachmentAdapterSelected.updateAttachment(
                            attachment, FileSafeAttachment.UploadStatus.FAILED);
                }
            } else {
                try {
                    if (uploadFile(new File(filepath), attachment)) {
                        mFileSafeAttachmentAdapterSelected.updateAttachment(
                                attachment, FileSafeAttachment.UploadStatus.IN_PROGRESS);
                        inFlightUploads++;
                    } else {
                        mFileSafeAttachmentAdapterSelected.updateAttachment(
                                attachment, FileSafeAttachment.UploadStatus.FAILED);
                    }
                } catch (RuntimeException e) {
                    Log.e(Config.LOGTAG, "Error uploading file", e);
                    mFileSafeAttachmentAdapterSelected.updateAttachment(
                            attachment, FileSafeAttachment.UploadStatus.FAILED);
                }
            }
        }
        uploading = inFlightUploads > 0;
        updateActionButtons();
    }

    private void showNothingToUploadToast() {
        Toast.makeText(this, R.string.upload_filesafe_nothing_new_message, Toast.LENGTH_SHORT).show();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
    }

    @Override
    public void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, connectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {
        }
    }

    private void addPrefillAttachment(Intent intent) {
        if (intent == null) return;
        final Uri prefillUri = intent.getParcelableExtra(EXTRA_PREFILL_URI);
        if (prefillUri == null) return;
        final List<Attachment> attachments =
                Attachment.of(this, prefillUri, Attachment.Type.FILE);
        for (Attachment attachment : attachments) {
            final FileSafeAttachment fsa =
                    new FileSafeAttachment(attachment, FileSafeAttachment.UploadStatus.INIT);
            if (!attachmentsSelected.contains(fsa)) {
                mFileSafeAttachmentAdapterSelected.addAttachmentsList(
                        attachment, FileSafeAttachment.UploadStatus.INIT);
            }
        }
    }

    public boolean uploadFile(File file, Attachment attachment) {
        if (!xmppConnectionServiceBound) return false;
        Account account = xmppConnectionService.getFirstAccount();
        if (file == null || account == null) return false;

        MobileUploadClient.uploadFile(
                EndPointConfig.FILESAFE_UPLOAD_URL,
                file,
                file.getName(),
                new MobileUploadClient.UploadCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(Config.LOGTAG, "Upload successful");
                        runOnUiThread(() -> {
                            mFileSafeAttachmentAdapterSelected.removeAttachmentsList(attachment);
                            mFileSafeAttachmentAdapterUploaded.addAttachmentsList(
                                    attachment, FileSafeAttachment.UploadStatus.SUCCESS);
                            finalizeUploadState();
                        });
                    }

                    @Override
                    public void onFailure(@NonNull Throwable throwable) {
                        Log.e(Config.LOGTAG, "Upload failed", throwable);
                        runOnUiThread(() -> {
                            mFileSafeAttachmentAdapterSelected.updateAttachment(
                                    attachment, FileSafeAttachment.UploadStatus.FAILED);
                            showUploadFailureSnackbar(throwable);
                            finalizeUploadState();
                        });
                    }
                });
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (xmppConnectionService != null) {
            if (activityResult.resultCode == Activity.RESULT_OK
                    && requestCode == PICK_FILE_REQUEST_CODE
                    && data != null) {
                List<Attachment> fileAttachments =
                        Attachment.extractAttachments(this, data, Attachment.Type.FILE);
                for (Attachment a : fileAttachments) {
                    mFileSafeAttachmentAdapterSelected.addAttachmentsList(
                            a, FileSafeAttachment.UploadStatus.INIT);
                }
                updateActionButtons();
            }
        }
    }

    private void updateActionButtons() {
        runOnUiThread(() -> {
            final boolean hasAttachments = !attachmentsSelected.isEmpty();
            boolean hasFailures = false;
            boolean hasPending = false;
            boolean hasSuccess = false;

            for (FileSafeAttachment fsa : attachmentsSelected) {
                if (fsa == null) continue;
                final FileSafeAttachment.UploadStatus status = fsa.getUploadStatus();
                if (status == null) continue;
                switch (status) {
                    case FAILED:     hasFailures = true; break;
                    case INIT:
                    case IN_PROGRESS: hasPending = true; break;
                    case SUCCESS:    hasSuccess  = true; break;
                }
            }

            // Toolbar subtitle: "N files" while files are selected
            updateToolbarSubtitle(attachmentsSelected.size());

            @StringRes final int textRes;
            @StringRes final int contentDescriptionRes;

            if (uploading) {
                textRes = R.string.uploading_filesafe_button_message;
                contentDescriptionRes = R.string.upload_filesafe_button_cd_uploading;
            } else if (!hasAttachments) {
                textRes = R.string.upload_btn_text;
                contentDescriptionRes = R.string.upload_filesafe_button_cd_no_files;
            } else if (hasFailures) {
                textRes = R.string.upload_filesafe_retry_button_text;
                contentDescriptionRes = R.string.upload_filesafe_button_cd_retry;
            } else if (!hasPending && hasSuccess) {
                textRes = R.string.upload_filesafe_upload_more_button_text;
                contentDescriptionRes = R.string.upload_filesafe_button_cd_upload_more;
            } else {
                textRes = R.string.upload_btn_text;
                contentDescriptionRes = R.string.upload_filesafe_button_cd_upload;
            }

            // Show count on the upload button when files are selected and not yet uploading
            final String uploadButtonText;
            if (!uploading && hasAttachments && (hasPending || hasSuccess)) {
                final int count = attachmentsSelected.size();
                uploadButtonText = count == 1
                        ? getString(R.string.upload_btn_text_one_file)
                        : getString(R.string.upload_btn_text_with_count, count);
            } else {
                uploadButtonText = getString(textRes);
            }

            final boolean enabled = hasAttachments && !uploading;
            binding.uploadButton.setEnabled(enabled);
            binding.uploadButton.setText(uploadButtonText);
            binding.uploadButton.setContentDescription(getString(contentDescriptionRes));

            binding.cancelButton.setEnabled(enabled);
            final @StringRes int cancelCd = hasAttachments
                    ? R.string.upload_filesafe_cancel_button_cd
                    : R.string.upload_filesafe_cancel_button_cd_disabled;
            binding.cancelButton.setContentDescription(getString(cancelCd));

            binding.uploadButton.setVisibility(View.VISIBLE);
            binding.cancelButton.setVisibility(View.VISIBLE);

            binding.selectFilesButton.setText(
                    hasAttachments
                            ? R.string.upload_filesafe_add_more_button_text
                            : R.string.upload_filesafe_add_files_button_text);

            final boolean hasUploaded = !attachmentsUploaded.isEmpty();
            binding.selectedFilesLabel.setVisibility(hasAttachments ? View.VISIBLE : View.GONE);
            binding.uploadedFilesLabel.setVisibility(hasUploaded ? View.VISIBLE : View.GONE);
            binding.recyclerViewUploaded.setVisibility(hasUploaded ? View.VISIBLE : View.GONE);
        });
    }

    private void updateToolbarSubtitle(int fileCount) {
        if (getSupportActionBar() == null) return;
        if (fileCount == 0) {
            getSupportActionBar().setSubtitle(null);
        } else if (fileCount == 1) {
            getSupportActionBar().setSubtitle(getString(R.string.upload_filesafe_one_file_subtitle));
        } else {
            getSupportActionBar().setSubtitle(
                    getString(R.string.upload_filesafe_files_subtitle, fileCount));
        }
    }

    private void finalizeUploadState() {
        inFlightUploads = Math.max(0, inFlightUploads - 1);
        uploading = inFlightUploads > 0;
        updateActionButtons();
    }

    private void showUploadFailureSnackbar(@NonNull final Throwable throwable) {
        final String failureMessage = buildUploadFailureMessage(throwable);
        Snackbar.make(binding.getRoot(), failureMessage, Snackbar.LENGTH_LONG).show();
    }

    @NonNull
    private String buildUploadFailureMessage(@NonNull final Throwable throwable) {
        if (throwable instanceof MobileUploadClient.UploadHttpException) {
            final MobileUploadClient.UploadHttpException uploadHttpException =
                    (MobileUploadClient.UploadHttpException) throwable;
            final String webhookMessage = parseWebhookMessage(uploadHttpException.getResponseBody());
            if (webhookMessage != null && !webhookMessage.isBlank()) {
                return getString(R.string.upload_filesafe_error_webhook_message, webhookMessage);
            }
            return getString(R.string.upload_filesafe_error_http_message);
        }
        return getString(R.string.upload_filesafe_error_message);
    }

    private String parseWebhookMessage(@NonNull final String responseBody) {
        if (responseBody.isBlank()) return null;
        try {
            final JSONObject jsonObject = new JSONObject(responseBody);
            final String message = jsonObject.optString("message", "").trim();
            return message.isEmpty() ? null : message;
        } catch (JSONException ignored) {
            return responseBody.trim();
        }
    }

    @Override
    protected void refreshUiReal() {
    }

    @Override
    protected void onBackendConnected() {
        final var jingleManager = xmppConnectionService.getJingleConnectionManager();
        updateReturnToGlacierCall(
                jingleManager != null && jingleManager.getOngoingRtpConnection() != null);
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.updateOfflineStatusBar();
        }
    }

    @Override
    public void onJingleRtpConnectionUpdate(Account account, Jid with, String sessionId,
                                            RtpEndUserState state) {
        switch (state) {
            case CONNECTED, RECONNECTING, INCOMING_CONTENT_ADD ->
                    runOnUiThread(() -> updateReturnToGlacierCall(true));
            default -> runOnUiThread(() -> updateReturnToGlacierCall(false));
        }
    }

    @Override
    public void onAudioDeviceChanged(CallIntegration.AudioDevice selectedAudioDevice,
                                     Set<CallIntegration.AudioDevice> availableAudioDevices) {
    }

    @Override
    public void onNetworkConnectionChanged() {
        if (connectionToolbarAdapter != null) {
            connectionToolbarAdapter.checkNetworkConnection();
        }
    }

    private final View.OnClickListener mRefreshNetworkClickListener = v ->
            connectionToolbarAdapter.onClick();

    private void launchGlacierCall() {
        final JingleConnectionManager manager =
                xmppConnectionService.getJingleConnectionManager();
        if (manager == null) return;
        final JingleRtpConnection jrc = manager.getOngoingRtpConnection();
        if (jrc == null) return;
        final Intent intent = new Intent(this, RtpSessionActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(RtpSessionActivity.EXTRA_ACCOUNT,
                jrc.getId().account.getJid().asBareJid().toEscapedString());
        intent.putExtra(RtpSessionActivity.EXTRA_WITH,
                jrc.getId().with.toEscapedString());
        intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, jrc.getId().sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
