package com.glaciersecurity.glaciermessenger.ui.fragment.phone;



import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;
import com.glaciersecurity.glaciermessenger.ui.ExternalCallActivity;
import com.glaciersecurity.glaciermessenger.ui.ExternalContactDetailsActivity;
import com.glaciersecurity.glaciermessenger.ui.PhoneModuleActivity;
import com.glaciersecurity.glaciermessenger.ui.adapter.VoicemailAdapter;
import com.glaciersecurity.glaciermessenger.ui.util.PlaybackSpeedOptions;
import com.glaciersecurity.glaciermessenger.ui.interfaces.OnSMSProfileClickListener;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.utils.OnVmCompleteCallbackListener;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.glaciersecurity.glaciermessenger.utils.VoicemailStorageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VoicemailPhoneFragment extends PhoneFragment implements OnSMSProfileClickListener, OnVmCompleteCallbackListener {
    private PhoneModuleActivity activity;

    private MediaPlayer mediaPlayer;
    private VoicemailAdapter adapter;
    private View emptyLayout;
    private ProgressBar progressBar;
    private volatile String pendingRecordingId;
    private int playbackSpeedIndex = PlaybackSpeedOptions.defaultIndex();
    private final android.os.Handler progressHandler = new android.os.Handler();
    private boolean mediaPlayerPrepared;
    private boolean playRequested;
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null) {
                adapter.updateCurrentProgress(mediaPlayer.getCurrentPosition());
                if (mediaPlayer.isPlaying()) {
                    progressHandler.postDelayed(this, 500);
                }
            }
        }
    };
    private boolean deleteMode = false;

    private Account mAccount;
    private SmsProfile selectedProfile;


    @Override
    public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.activity_voicemail, container, false);
        emptyLayout = v.findViewById(R.id.empty_voicemail_list);
        RecyclerView recyclerView = v.findViewById(R.id.voicemail_list);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        progressBar = v.findViewById(R.id.progressBar);
        recyclerView.setLayoutManager(layoutManager);
        adapter = new VoicemailAdapter(activity);
        adapter.setPlaybackSpeedIndex(playbackSpeedIndex);
        adapter.setVoicemailClickListener(new VoicemailAdapter.OnVoicemailClickListener() {

            @Override
            public void onVoicemailClick_Pause() {
                playRequested = false;
                if (mediaPlayer != null) {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                    }
                    progressHandler.removeCallbacksAndMessages(null);
                }
            }

            @Override
            public void onVoicemailClick_Info(VoicemailRecord record) {
                Intent contactDetails = new Intent(getContext(), ExternalContactDetailsActivity.class);
                contactDetails.putExtra("toNumber", record.getFrom());
                startActivity(contactDetails);
            }

            @Override
            public void onVoicemailClick_CallBack(VoicemailRecord record) {

                String number = PhoneUtils.getStripPhoneNumber(record.getFrom());
                Intent callActivity = new Intent(getContext(), ExternalCallActivity.class);
                callActivity.setAction(Config.ACTION_OUTGOING_CALL);
                callActivity.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                callActivity.putExtra("toNumber", PhoneUtils.getStripPhoneNumber(number));
                if (activity.selectedProfile != null) {
                    callActivity.putExtra(Config.TO_PROXY, activity.selectedProfile.getUnformattedNumber());
                }
                startActivity(callActivity);

            }


            @Override
            public void onVoicemailClick_Delete(VoicemailRecord record) {
                AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
                builder.setMessage(getText(R.string.delete_voicemail));
                builder.setTitle(R.string.delete_voicemail_title);
                builder.setCancelable(true);
                builder.setPositiveButton(R.string.dialog_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        releaseMediaPlayer();
                        try {
                            updateVoicemailStatus(record, VoicemailRecord.VM_Status.DELETED.label);
                        } catch (final Exception e) {
                        }
                        dialog.dismiss();
                    }
                });
                builder.setNegativeButton(R.string.cancel, null);
                builder.create().show();
            }

            @Override
            public void onVoicemailClick(View view, VoicemailRecord record) {
                releaseMediaPlayer();
                adapter.setCurrentProgressBar(view.findViewById(R.id.voicemail_progress));
                adapter.resetCurrentProgress();

                if (activity == null) {
                    return;
                }

                final String requestId = getVoicemailIdentifier(record);
                pendingRecordingId = requestId;
                final PhoneModuleActivity currentActivity = activity;
                playRequested = false;
                mediaPlayerPrepared = false;

                new Thread(() -> {
                    boolean cached = VoicemailStorageUtils.ensureFileForVoicemail(currentActivity, record, mAccount);
                    if (!requestId.equals(pendingRecordingId)) {
                        return;
                    }
                    if (!cached) {
                        runOnUiThread(() -> {
                            if (!requestId.equals(pendingRecordingId)) {
                                return;
                            }
                            adapter.resetCurrentProgress();
                            if (getContext() != null) {
                                Toast.makeText(getContext(), R.string.voicemail_download_failed, Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }

                    File audioFile = VoicemailStorageUtils.getVoicemailFile(currentActivity, record);
                    if (audioFile == null) {
                        return;
                    }
                    if (!requestId.equals(pendingRecordingId)) {
                        return;
                    }
                    runOnUiThread(() -> {
                        if (!requestId.equals(pendingRecordingId)) {
                            return;
                        }
                        prepareMediaPlayerWithFile(view, audioFile);
                    });
                }).start();

                if (record.getStatus().equals(VoicemailRecord.VM_Status.NEW)) {
                    try {
                        updateVoicemailStatus(record, VoicemailRecord.VM_Status.READ.label);
                    } catch (final Exception e) {
                    }
                }
            }

            @Override
            public void onVoicemailClick_Play() {
                if (getContext() != null && !ConnectivityReceiver.isConnected(getContext())){
                    runOnUiThread(()->{
                        Toast.makeText(getContext(), R.string.voicemail_cannot_play_offline, Toast.LENGTH_SHORT).show();
                    });
                }
                playRequested = true;
                startPlaybackIfPrepared();
            }

            @Override
            public void onVoicemailClick_Speed(View view) {
                playbackSpeedIndex = PlaybackSpeedOptions.cycleIndex(playbackSpeedIndex);
                adapter.setPlaybackSpeedIndex(playbackSpeedIndex);
                if (view instanceof TextView) {
                    TextView speedView = (TextView) view;
                    speedView.setText(
                            PlaybackSpeedOptions.labelForIndex(
                                    speedView.getContext(), playbackSpeedIndex));
                    speedView.setContentDescription(
                            PlaybackSpeedOptions.contentDescriptionForIndex(
                                    speedView.getContext(), playbackSpeedIndex));
                }
                applyVoicemailPlaybackSpeed();
            }
        });

        recyclerView.setAdapter(adapter);
        return v;
    }

    private void prepareMediaPlayerWithFile(View view, File audioFile) {
        mediaPlayerPrepared = false;
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        adapter.setCurrentProgressBar(view.findViewById(R.id.voicemail_progress));
        adapter.resetCurrentProgress();
        try {
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                adapter.setCurrentMax(mp.getDuration());
                applyVoicemailPlaybackSpeed();
                mediaPlayerPrepared = true;
                if (!playRequested) {
                    try {
                        if (mp.isPlaying()) {
                            mp.pause();
                            mp.seekTo(0);
                        }
                    } catch (IllegalStateException ignore) {
                        // Player not yet ready to pause/seek; will be handled on play.
                    }
                }
                startPlaybackIfPrepared();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                progressHandler.removeCallbacksAndMessages(null);
                adapter.resetCurrentProgress();
                playRequested = false;
                try {
                    mp.seekTo(0);
                } catch (IllegalStateException ignore) {
                    // Player not ready to seek; ignore.
                }
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            if (getContext() != null) {
                Toast.makeText(getContext(), R.string.voicemail_playback_failed, Toast.LENGTH_SHORT).show();
            }
            releaseMediaPlayer();
        }
    }

    private void startPlaybackIfPrepared() {
        if (!mediaPlayerPrepared || mediaPlayer == null) {
            return;
        }
        try {
            if (!mediaPlayer.isPlaying() && playRequested) {
                mediaPlayer.start();
                progressHandler.post(progressRunnable);
            }
        } catch (IllegalStateException e) {
            Log.w(Config.LOGTAG, "Unable to start voicemail playback", e);
        }
    }

    private void applyVoicemailPlaybackSpeed() {
        if (mediaPlayer == null) {
            return;
        }
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(PlaybackSpeedOptions.speedForIndex(playbackSpeedIndex));
            params.setPitch(1.0f);
            mediaPlayer.setPlaybackParams(params);
        } catch (IllegalStateException ignore) {
            // Player not ready yet; will apply once prepared.
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Unable to set voicemail playback speed", e);
        }
    }

    private void releaseMediaPlayer() {
        progressHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        pendingRecordingId = null;
        mediaPlayerPrepared = false;
        playRequested = false;
    }

    private String getVoicemailIdentifier(VoicemailRecord record) {
        if (record.getRecordingSid() != null && !record.getRecordingSid().isEmpty()) {
            return record.getRecordingSid();
        }
        if (record.getCallSid() != null && !record.getCallSid().isEmpty()) {
            return record.getCallSid();
        }
        if (record.getTimeStamp_string() != null && !record.getTimeStamp_string().isEmpty()) {
            return record.getTimeStamp_string();
        }
        if (record.getTime() != null && !record.getTime().isEmpty()) {
            return record.getTime();
        }
        return String.valueOf(record.hashCode());
    }

    public void updateVoicemailStatus(VoicemailRecord recording, String status) {
        final Account account = mAccount;
        if (account == null) {
            return;
        }
        new Thread(() -> {
            String listVoicemailsUrl =
                    Endpoints.base() + EndPointConfig.UPDATE_VOICEMAIL_STATUS_URL;
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("organization", account.getOrganization());
            formFields.put("username", account.getUsername());
            formFields.put("recordingSid", recording.getRecordingSid());
            formFields.put("status", status);
            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(listVoicemailsUrl, formFields)
                        .build();
            } catch (IOException e) {
                Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
                activity.xmppConnectionService.checkConsoleStatus();
                return;
            }
            try (Response response = client.newCall(request).execute()) {
                String responseBody = "{\"message\":\"failure\"}";
                if (response != null && response.body() != null) {
                    responseBody = response.body().string();
                }
                Gson gson = new Gson();
                SMSdbInfo.UpdateVoicemailResponse updateVoicemailResponse;
                try {
                    updateVoicemailResponse = gson.fromJson(responseBody, SMSdbInfo.UpdateVoicemailResponse.class);
                } catch (JsonSyntaxException jsonSyntaxException) {
                    handleWebhookJsonSyntaxException("update voicemail", responseBody, jsonSyntaxException);
                    runOnUiThread(() -> activity.binding.consoleError.setVisibility(View.VISIBLE));
                    return;
                }
                if (updateVoicemailResponse.message != null && updateVoicemailResponse.message.equals("Voicemail status updated successfully")) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            recording.setStatus(status);
                            activity.xmppConnectionService.addVoicemailRecord(recording);
                            adapter.updateVmStatus(recording);
                            checkVmCount();
                        }
                    });

                }
            } catch (Exception ex) {
                Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
                activity.xmppConnectionService.checkConsoleStatus();
            }
        }).start();
    }

    public void updateAllVoicemailStatuses(String status) {
        final Account account = mAccount;
        if (account == null) {
            return;
        }
        new Thread(() -> {
            String listVoicemailsUrl =
                    Endpoints.base() + EndPointConfig.UPDATE_ALL_VOICEMAIL_STATUS_URL;
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            Map<String, String> formFields = new HashMap<>();
            formFields.put("organization", account.getOrganization());
            formFields.put("username", account.getUsername());
            formFields.put("status", status);
            formFields.put("number", selectedProfile.getUnformattedNumber());
            Request request = null;
            try {
                request = OkHttpClientProvider.buildJwtFormRequestBuilder(listVoicemailsUrl, formFields)
                        .build();
            } catch (IOException e) {
                Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
                activity.xmppConnectionService.checkConsoleStatus();
                return;
            }
            try (Response response = client.newCall(request).execute()) {
                String responseBody = "{\"message\":\"failure\"}";
                if (response != null && response.body() != null) {
                    responseBody = response.body().string();
                }
                Gson gson = new Gson();
                SMSdbInfo.UpdateAllVoicemailsResponse updateAllVoicemailsResponse;
                try {
                    updateAllVoicemailsResponse = gson.fromJson(responseBody, SMSdbInfo.UpdateAllVoicemailsResponse.class);
                } catch (JsonSyntaxException jsonSyntaxException) {
                    handleWebhookJsonSyntaxException("update all voicemails", responseBody, jsonSyntaxException);
                    runOnUiThread(() -> activity.binding.consoleError.setVisibility(View.VISIBLE));
                    return;
                }
                if (updateAllVoicemailsResponse.message != null && updateAllVoicemailsResponse.message.equals("All voicemail statuses updated successfully")) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.xmppConnectionService.updateVoicemailStatusForProxy(status, selectedProfile);
                            adapter.updateAllVmStatus(status);
                            checkVmCount();
                        }
                    });

                }
            } catch (Exception ex) {
                Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
                activity.xmppConnectionService.checkConsoleStatus();
            }
        }).start();
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (activity.xmppConnectionServiceBound) {
            initValues();
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        MenuItem selectAll = menu.findItem(R.id.select_all);
        if (selectAll != null) selectAll.setVisible(deleteMode);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.action_voicemail_menu, menu);
        MenuItem selectAll = menu.findItem(R.id.select_all);
        if (selectAll != null) {
            selectAll.setVisible(deleteMode);
        }
        super.onCreateOptionsMenu(menu, menuInflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.delete_selected) {
            if (!deleteMode) {
                deleteMode = true;
                adapter.setDeleteMode(true);
                activity.invalidateOptionsMenu();
            } else {
                deleteSelectedVoicemails();
            }
        } else if (id == R.id.select_all) {
            if (!adapter.isSelectAll()) {
                adapter.selectAll();
                item.setIcon(R.drawable.ic_baseline_check_box_outline_blank_24);
            } else {
                adapter.unselectAll();
                item.setIcon(R.drawable.baseline_check_box_24);

            }

        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteSelectedVoicemails() {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(getContext());
        builder.setMessage(R.string.delete_selected_voicemails);
        builder.setTitle(R.string.dialog_confirmation_title);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.dialog_delete, (dialogInterface, k) -> {
            for (VoicemailRecord record : adapter.getSelectedVoicemails()) {
                updateVoicemailStatus(record, VoicemailRecord.VM_Status.DELETED.label);
            }
            adapter.clearSelections();
            deleteMode = false;
            adapter.setDeleteMode(false);
            activity.invalidateOptionsMenu();

        });
        builder.setNegativeButton(R.string.cancel, null);
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }


    private void handleWebhookJsonSyntaxException(String context, String responseBody, JsonSyntaxException exception) {
        activity.xmppConnectionService.checkConsoleStatus();
    }


    private void initValues() {
        selectedProfile = activity.xmppConnectionService.getSmsInfo().getSelectedProfile();
        mAccount = activity.xmppConnectionService.getFirstAccount();
        activity.xmppConnectionService.getSmsInfo().setOnVMCompleteCallbackListener(this);
        if (selectedProfile != null) {
            activity.clearVoicemailNotificationForProfile(selectedProfile);
            ArrayList<VoicemailRecord> vms = activity.xmppConnectionService.getVoicemailsForProxy(selectedProfile);
            adapter.setVoicemailLogList(vms);
            checkVmCount();
        }
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            if (mAccount != null && selectedProfile != null) {
                activity.xmppConnectionService.getSmsInfo().listVoicemailRequest(mAccount, selectedProfile);
            }
        }).start();
    }


    @Override
    public void onBackendConnected() {
        initValues();
    }

    private void checkVmCount() {
        setVoicemailNumbers();
        if (isEmpty()) {
            emptyLayout.setVisibility(View.VISIBLE);
        } else {
            emptyLayout.setVisibility(View.GONE);
        }
    }

    private boolean isEmpty() {
        for (VoicemailRecord vm : adapter.voicemailLogList) {
            if (vm.getStatus().equals(VoicemailRecord.VM_Status.NEW) || vm.getStatus().equals(VoicemailRecord.VM_Status.READ)) {
                return false;
            }
        }
        return true;
    }


    private void setVoicemailNumbers() {
        int newVM = 0;
        for (VoicemailRecord vm : adapter.getVoicemailLogList()) {
            if (vm.getStatus().equals(VoicemailRecord.VM_Status.NEW)) {
                newVM++;
            }
        }
        activity.setUnreadVoicemailCount(newVM);
        activity.invalidateOptionsMenu();
    }

    @Override
    protected void refresh() {

    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof PhoneModuleActivity) {
            this.activity = (PhoneModuleActivity) context;
        } else {
            throw new IllegalStateException("Trying to attach fragment to activity that is not the PHoneModuleActivity");
        }
    }

    @Override
    public void OnSMSProfileClick(SmsProfile smsProfile) {

        if (smsProfile != null) {
            selectedProfile = smsProfile;
            activity.clearVoicemailNotificationForProfile(selectedProfile);
            ArrayList<VoicemailRecord> vms = activity.xmppConnectionService.getVoicemailsForProxy(selectedProfile);
            adapter.setVoicemailLogList(vms);
            checkVmCount();
            progressBar.setVisibility(View.VISIBLE);
            new Thread(() -> {
                if (mAccount != null && selectedProfile != null) {
                    activity.xmppConnectionService.getSmsInfo().listVoicemailRequest(mAccount, selectedProfile);
                }
            }).start();
        } else {
            adapter.setVoicemailLogList(new ArrayList<>());
            checkVmCount();
            progressBar.setVisibility(View.GONE);
        }
    }


    @Override
    public void onPause() {
        super.onPause();
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(0);
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            progressHandler.removeCallbacksAndMessages(null);
            adapter.resetCurrentProgress();
        }
    }


    @Override
    public void onSelectedVoiceMailLoaded() {
        runOnUiThread(() -> {
            ArrayList<VoicemailRecord> vms = activity.xmppConnectionService.getVoicemailsForProxy(selectedProfile);
            adapter.setVoicemailLogList(vms);
            progressBar.setVisibility(View.GONE);
            checkVmCount();
        });
    }

}


