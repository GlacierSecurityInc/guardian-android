package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.utils.AccountUtils;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileUploadClient;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.glaciersecurity.glaciermessenger.ui.XmppActivity;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ExceptionHelper {

    // No fixed filename constant. The crash report is written to a
    // randomly-named temp file; its path is stored in private SharedPreferences so
    // checkForCrash() can locate it without relying on a predictable filename that
    // could be pre-targeted with a symlink attack.
    private static final String PREFS_NAME = "crash_report_prefs";
    private static final String PREFS_KEY  = "crash_report_path";

    public static void init(final Context context) {
        if (Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(context));
    }

    public static boolean checkForCrash(final XmppActivity activity) {
        final XmppConnectionService service =
                activity == null ? null : activity.xmppConnectionService;
        if (service == null) {
            return false;
        }
        final AppSettings appSettings = new AppSettings(activity);
        if (!appSettings.isSendCrashReports() || Config.BUG_REPORTS == null) {
            return false;
        }
        final Account account = AccountUtils.getFirstEnabled(service);
        if (account == null) {
            return false;
        }

        // Retrieve the crash report path from SharedPreferences rather
        // than constructing a fixed, predictable path.
        final SharedPreferences prefs =
                activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String storedPath = prefs.getString(PREFS_KEY, null);
        if (storedPath == null) {
            return false;
        }

        // Clear the stored path immediately — single-use regardless of
        // outcome. Even if the read or upload fails, the pointer is gone so we cannot
        // be made to re-process the same (potentially swapped) file.
        prefs.edit().remove(PREFS_KEY).apply();

        // Validate that the canonical path of the stored file still
        // resolves inside the application cache directory. A symlink swapped in between
        // createTempFile() and this read would be caught here.
        final File file = new File(storedPath);
        try {
            final String canonicalFile  = file.getCanonicalPath();
            final String canonicalCache = activity.getCacheDir().getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalCache + File.separator)) {
                Log.w(Config.LOGTAG,
                        "crash report path escapes cache dir — ignoring: " + canonicalFile);
                return false;
            }
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "could not canonicalize crash report path", e);
            return false;
        }

        if (!file.exists()) {
            return false;
        }

        // Read the file content, then delete it immediately.
        // The file is deleted before any network I/O so it cannot be read again
        // by another code path regardless of what happens during upload.
        final String report;
        try {
            report = Files.asCharSource(file, Charsets.UTF_8).read();
        } catch (final IOException e) {
            file.delete();
            return false;
        }
        file.delete();

         if (appSettings.isSendCrashReportsToS3()) {
                final String filename =
                        String.format(
                                "%s_%s_%s.txt",
                                account.getOrganization(),
                                account.getUsername(),
                                new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US)
                                        .format(new Date()));

                // Write report content to a fresh temp file for the S3 upload.
                // The original file was already deleted above; we create a new
                // short-lived file solely for the Amplify upload call.
                final File uploadFile;
                try {
                    uploadFile = File.createTempFile("crash_upload_", ".tmp",
                            activity.getCacheDir());
                    Files.asCharSink(uploadFile, Charsets.UTF_8).write(report);
                } catch (final IOException e) {
                    Log.e(Config.LOGTAG, "could not write crash report for S3 upload", e);
                    return false;
                }

                MobileUploadClient.uploadFile(
                        EndPointConfig.BUG_REPORT_UPLOAD_URL,
                        uploadFile,
                        filename,
                        new MobileUploadClient.UploadCallback() {
                            @Override
                            public void onSuccess() {
                                Log.i(Config.LOGTAG, "Bug report upload successful");
                                if (uploadFile.delete()) {
                                }
                            }

                            @Override
                            public void onFailure(@NonNull Throwable throwable) {
                                Log.e(Config.LOGTAG, "Bug report upload failed", throwable);
                                if (uploadFile.delete()) {
                                }
                            }
                        });

                // Stack traces can carry sensitive content, so S3 over authenticated
                // HTTPS is the sole delivery channel.
            }

        return true;
    }

    /**
     * Writes the crash report to a randomly-named temp file inside the application's
     * private cache directory, then stores the file path in private SharedPreferences.
     *
     * <p>Using a random name (via {@link File#createTempFile}) prevents an attacker from
     * pre-placing a symlink at a known path. The SharedPreferences key is the only way
     * {@link #checkForCrash} can locate the file; there is no guessable fixed filename.
     *
     * <p>Called from {@link ExceptionHandler#uncaughtException} — keep this method
     * side-effect-free beyond writing the file and storing the path.
     */
    static void writeToStacktraceFile(final Context context, final String msg) {
        try {
            // Random filename — not predictable by other processes.
            final File tempFile = File.createTempFile("crash_", ".tmp", context.getCacheDir());
            Files.asCharSink(tempFile, Charsets.UTF_8).write(msg);

            // Store path via MODE_PRIVATE SharedPreferences — inaccessible to other apps.
            // apply() is async but acceptable here; the process is about to terminate.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREFS_KEY, tempFile.getAbsolutePath())
                    .apply();

        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "could not write stack trace to file", e);
        }
    }
}