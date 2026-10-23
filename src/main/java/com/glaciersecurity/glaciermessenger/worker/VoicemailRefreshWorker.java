package com.glaciersecurity.glaciermessenger.worker;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.utils.Log;

public class VoicemailRefreshWorker extends Worker {

    public static final String UNIQUE_WORK_NAME = "voicemail_refresh_work";

    public VoicemailRefreshWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Intent intent = new Intent(getApplicationContext(), XmppConnectionService.class);
            intent.setAction(XmppConnectionService.ACTION_REFRESH_VOICEMAILS);
            getApplicationContext().startService(intent);
            return Result.success();
        } catch (Exception e) {
            Log.w("VoicemailRefreshWorker", "Failed to request voicemail refresh", e);
            return Result.retry();
        }
    }
}
