package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;

import com.amplifyframework.auth.cognito.result.AWSCognitoAuthSignOutResult;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.Config;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CognitoClientMigrationManager {
    private static final String PREF_FILE = "auth_migration";
    private static final String KEY_MIGRATION_REAUTH_V1_COMPLETED = "auth_migration_reauth_v1_completed";

    private CognitoClientMigrationManager() {
    }

    public static boolean isMigrationReauthV1Completed(final Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_MIGRATION_REAUTH_V1_COMPLETED, false);
    }

    public static boolean isMigrationReauthV1Needed(final Context context) {
        return !isMigrationReauthV1Completed(context);
    }

    public static void markMigrationReauthV1Completed(final Context context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MIGRATION_REAUTH_V1_COMPLETED, true)
                .apply();
    }

    public static void forceSignedOutState(final MigrationSignOutCallback callback) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable completeSuccessOnce = () -> {
            if (done.compareAndSet(false, true)) {
                callback.onComplete(true);
            }
        };
        final Runnable completeFailureOnce = () -> {
            if (done.compareAndSet(false, true)) {
                callback.onComplete(false);
            }
        };

        try {
            Amplify.Auth.signOut(
                    signOutResult -> {
                        if (signOutResult instanceof AWSCognitoAuthSignOutResult.CompleteSignOut) {
                            clearDataStoreAndContinue(completeSuccessOnce);
                        } else if (signOutResult instanceof AWSCognitoAuthSignOutResult.PartialSignOut) {
                            Log.w(Config.LOGTAG, "Auth partial signOut during Cognito client migration");
                            clearDataStoreAndContinue(completeSuccessOnce);
                        } else if (signOutResult instanceof AWSCognitoAuthSignOutResult.FailedSignOut) {
                            Log.w(Config.LOGTAG, "Auth failed signOut during Cognito client migration");
                            completeFailureOnce.run();
                        } else {
                            Log.w(Config.LOGTAG, "Unknown signOut result during Cognito client migration");
                            completeFailureOnce.run();
                        }
                    });
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Auth signOut threw exception during Cognito client migration", e);
            completeFailureOnce.run();
        }
    }

    private static void clearDataStoreAndContinue(final Runnable onComplete) {
        onComplete.run();
    }

    public interface MigrationSignOutCallback {
        void onComplete(boolean signOutSucceeded);
    }
}
