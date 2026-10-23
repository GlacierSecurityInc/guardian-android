package com.glaciersecurity.glaciermessenger.ui.fragment.settings;

import androidx.annotation.Nullable;
import androidx.work.WorkInfo;

import java.io.File;
import java.util.List;

final class LogoutBackupDecision {

    private static final long BACKUP_TIMESTAMP_TOLERANCE_MS = 2_000L;

    private LogoutBackupDecision() {
    }

    /**
     * Whether any of the unique work's records is still pending (enqueued, running or blocked).
     * While this is true the logout flow must keep waiting for the backup to finish. Observing the
     * unique-work stream (rather than a single request id) means we keep tracking the job that will
     * actually run even when {@link androidx.work.ExistingWorkPolicy#KEEP} discards a freshly
     * enqueued request because a backup was already in progress.
     */
    static boolean hasActiveWork(@Nullable final List<WorkInfo> workInfos) {
        if (workInfos == null) {
            return false;
        }
        for (final WorkInfo workInfo : workInfos) {
            if (workInfo != null && !workInfo.getState().isFinished()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the unique work produced a fresh, non-empty backup file after the logout backup was
     * started. The freshness check guards against acting on stale, previously-finished work records
     * that WorkManager still reports for the same unique name.
     */
    static boolean hasFreshSuccessfulBackup(
            @Nullable final List<WorkInfo> workInfos,
            final File backupDirectory,
            final long backupStartedAtMillis) {
        if (workInfos == null) {
            return false;
        }
        boolean succeeded = false;
        for (final WorkInfo workInfo : workInfos) {
            if (workInfo != null && workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                succeeded = true;
                break;
            }
        }
        return succeeded && containsFreshBackupFile(backupDirectory, backupStartedAtMillis);
    }

    private static boolean containsFreshBackupFile(
            final File file,
            final long backupStartedAtMillis) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isFile()) {
            return isFreshBackupFile(file, backupStartedAtMillis);
        }
        final File[] children = file.listFiles();
        if (children == null) {
            return false;
        }
        for (final File child : children) {
            if (containsFreshBackupFile(child, backupStartedAtMillis)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFreshBackupFile(final File file, final long backupStartedAtMillis) {
        return file.getName().endsWith(".ceb")
                && file.length() > 0L
                && file.lastModified() >= backupStartedAtMillis - BACKUP_TIMESTAMP_TOLERANCE_MS;
    }
}
