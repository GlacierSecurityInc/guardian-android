package com.glaciersecurity.glaciermessenger;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.preference.PreferenceManager;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.core.Amplify;
import com.glaciersecurity.glaciermessenger.utils.AppLifecycleHandler;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OrientationLock;

import com.glaciersecurity.glaciermessenger.services.EmojiInitializationService;
import com.glaciersecurity.glaciermessenger.utils.ExceptionHelper;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.services.BootCompletedReceiver;
import com.glaciersecurity.glaciermessenger.wireguard.WireGuardStateMonitor;
import com.google.firebase.FirebaseApp;

public class GlacierGuardian extends Application {

    @SuppressLint("StaticFieldLeak")
    private static Context CONTEXT;

    private static volatile boolean amplifyConfigured = false;
    private static boolean amplifyPluginAdded = false;
    private static boolean amplifyConfigurationFailed = false;

    public static Context getContext() {
        return GlacierGuardian.CONTEXT;
    }

    /**
     * Returns whether {@link Amplify#configure(Context)} has completed successfully. Callers must
     * not invoke {@code Amplify.Auth.*} when this is {@code false}, otherwise the framework throws
     * an uncaught {@link IllegalStateException} ("Tried to get a plugin before it was configured").
     */
    public static boolean isAmplifyConfigured() {
        return amplifyConfigured;
    }

    /**
     * Idempotently adds the Cognito auth plugin and configures Amplify. Once configuration succeeds
     * this becomes a no-op.
     *
     * <p>The Cognito credential store is Android Keystore-backed, so {@link Amplify#configure(Context)}
     * fails when it runs before the user has unlocked the device (e.g. a background/boot start before
     * the first unlock). Amplify Android does not recover from such a failure: once {@code configure()}
     * throws, the Auth category is permanently marked configuration-failed for the life of the process,
     * and every later {@code configure()} or plugin access keeps failing until the process restarts.
     * Retrying {@code configure()} in-process therefore cannot help.
     *
     * <p>To keep a boot-before-unlock start recoverable, we never call {@code configure()} while
     * storage is locked — we defer it instead — so the first real attempt happens after unlock and
     * succeeds. A genuine failure with storage already unlocked is recorded as permanent so callers
     * stop retrying a call that can no longer recover.
     *
     * @return {@code true} if Amplify is configured and its auth plugin is ready to use; {@code false}
     *     if configuration was deferred because storage is locked (retry after unlock) or has
     *     permanently failed (a process restart is required).
     */
    public static synchronized boolean ensureAmplifyConfigured(final Context context) {
        if (amplifyConfigured) {
            return true;
        }
        if (amplifyConfigurationFailed) {
            // configure() already failed once with storage unlocked. Amplify cannot recover the Auth
            // category in this process, so retrying would only throw again (or, worse, report
            // AlreadyConfigured and then crash on the first Auth call). Require a restart instead.
            return false;
        }

        // Adding the plugin touches no Keystore state, so it is safe to do up front and only once;
        // doing it here (rather than alongside configure) also means a deferred-while-locked start
        // does not have to repeat it after unlock.
        try {
            if (!amplifyPluginAdded) {
                Amplify.addPlugin(new AWSCognitoAuthPlugin());
                amplifyPluginAdded = true;
            }
        } catch (final AmplifyException e) {
            Log.e("MyApp", "Error adding Amplify auth plugin", e);
            return false;
        }

        // Gate configure() on unlocked storage. Calling it while locked would fail and permanently
        // poison the Auth category, so a later unlock could never recover without restarting.
        final UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        final boolean userUnlocked = userManager == null || userManager.isUserUnlocked();
        if (!userUnlocked) {
            // Transient: leave Amplify unconfigured so this can be retried successfully after unlock.
            Log.w("MyApp", "Deferring Amplify.configure(): user storage is locked");
            return false;
        }

        try {
            Amplify.configure(context.getApplicationContext());
            amplifyConfigured = true;
            Log.i("MyApp", "Amplify configured successfully");
        } catch (final Amplify.AlreadyConfiguredException e) {
            // Another path already configured Amplify; treat as success.
            amplifyConfigured = true;
        } catch (final AmplifyException e) {
            // configure() ran with storage unlocked and still failed: Amplify has now marked the
            // Auth category configuration-failed for the life of the process. Record it as permanent
            // so callers stop retrying a call that can never recover.
            amplifyConfigurationFailed = true;
            Log.e("MyApp", "Error initializing Amplify (" + e.getClass().getSimpleName() + ")", e);
        }
        return amplifyConfigured;
    }

    public static int getDesiredNightMode(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String theme = prefs.getString(AppSettings.THEME, "dark");
        return getDesiredNightMode(theme);
    }

    public static boolean isDynamicColorsDesired() {
        return false;
    }

    public static int getDesiredNightMode(final String theme) {
        if ("light".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("automatic".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        return AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static boolean isAppInForeground() {
        return AppLifecycleHandler.isAppInForeground();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CONTEXT = this.getApplicationContext();
        OkHttpClientProvider.init(this);
        EmojiInitializationService.execute(getApplicationContext());
        ExceptionHelper.init(getApplicationContext());
        applyThemeSettings();
        OrientationLock.install(this);

        ensureAmplifyConfigured(getApplicationContext());

        AppLifecycleHandler handler = new AppLifecycleHandler(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(handler);

        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this);
        }

        WireGuardStateMonitor.initialize(this);

        // An older build installed over a newer one finds a database it cannot read -- either a
        // schema newer than this build understands, or a file that is encrypted while
        // this build links no SQLCipher. Touching the database here would throw out of
        // Application.onCreate and crash-loop the app before any UI exists to say why, so skip
        // database-dependent startup and let ConversationActivity explain it instead.
        final DatabaseBackend.DatabaseAccess access = DatabaseBackend.accessState(this);
        if (access == DatabaseBackend.DatabaseAccess.TOO_NEW
                || access == DatabaseBackend.DatabaseAccess.ENCRYPTED) {
            Log.e(
                    Config.LOGTAG,
                    "skipping database-dependent startup: database is "
                            + (access == DatabaseBackend.DatabaseAccess.TOO_NEW
                                    ? "too new"
                                    : "encrypted and unreadable by this build"));
        } else {
            CoreProfile coreProfile = DatabaseBackend.getInstance(this).getRunningCoreProfile();
            if (coreProfile != null) {
                BootCompletedReceiver.startLastUsedTunnel(this, coreProfile);
            }
        }
    }

    public void applyThemeSettings() {
        AppCompatDelegate.setDefaultNightMode(getDesiredNightMode(this));
    }


}
