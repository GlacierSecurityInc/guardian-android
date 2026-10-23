package com.glaciersecurity.glaciermessenger.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.worker.SecurityHubWorker;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class SecurityHubService extends Service {

    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_ORGANIZATION = "organization";
    public static final String EXTRA_DEVICE_ID = "device_id";

    private static final String SECURITY_HUB_ONE_TIME_WORK = "security_hub_one_time";
    private static final String SECURITY_HUB_PERIODIC_WORK = "security_hub_periodic";
    private static final long DEFAULT_INTERVAL_HOURS = 4L;


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String username = intent.getStringExtra(EXTRA_USERNAME);
            final String organization = intent.getStringExtra(EXTRA_ORGANIZATION);
            final String deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
            scheduleSecurityHubUpdates(this, username, organization, deviceId);
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    public static synchronized void scheduleSecurityHubUpdates(
            Context context,
            String username,
            String organization,
            String deviceId) {
        if (context == null) {
            return;
        }
        if (username == null || organization == null || deviceId == null) {
            Log.w(Config.LOGTAG, "Missing account info for SecurityHub scheduling");
            return;
        }
        final Context appContext = context.getApplicationContext();
        final Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        final Data inputData = new Data.Builder()
                .putString(SecurityHubWorker.KEY_USERNAME, username)
                .putString(SecurityHubWorker.KEY_ORGANIZATION, organization)
                .putString(SecurityHubWorker.KEY_DEVICE_ID, deviceId)
                .build();
        final OneTimeWorkRequest immediateWork = new OneTimeWorkRequest.Builder(SecurityHubWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        final WorkManager workManager = WorkManager.getInstance(appContext);
        workManager.enqueueUniqueWork(
                SECURITY_HUB_ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                immediateWork);
        final PeriodicWorkRequest periodicWork = new PeriodicWorkRequest.Builder(
                SecurityHubWorker.class,
                DEFAULT_INTERVAL_HOURS,
                TimeUnit.HOURS)
                .setInputData(inputData)
                .setConstraints(constraints)
                .build();
        workManager.enqueueUniquePeriodicWork(
                SECURITY_HUB_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicWork);
    }

    public static synchronized void cancelSecurityHubUpdates(Context context) {
        if (context == null) {
            return;
        }
        final WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
        workManager.cancelUniqueWork(SECURITY_HUB_ONE_TIME_WORK);
        workManager.cancelUniqueWork(SECURITY_HUB_PERIODIC_WORK);
    }

    public static boolean isSecurityHubWorkActive(Context context) {
        if (context == null) {
            return false;
        }
        final Context appContext = context.getApplicationContext();
        final WorkManager workManager = WorkManager.getInstance(appContext);
        return isWorkEnqueuedOrRunning(workManager, SECURITY_HUB_ONE_TIME_WORK)
                || isWorkEnqueuedOrRunning(workManager, SECURITY_HUB_PERIODIC_WORK);
    }

    private static boolean isWorkEnqueuedOrRunning(WorkManager workManager, String uniqueWorkName) {
        try {
            final List<WorkInfo> workInfos = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get();
            for (WorkInfo info : workInfos) {
                final WorkInfo.State state = info.getState();
                if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING) {
                    return true;
                }
            }
        } catch (ExecutionException e) {
            Log.w(Config.LOGTAG, "Unable to check SecurityHub work status", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(Config.LOGTAG, "Interrupted while checking SecurityHub work status", e);
        }
        return false;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
