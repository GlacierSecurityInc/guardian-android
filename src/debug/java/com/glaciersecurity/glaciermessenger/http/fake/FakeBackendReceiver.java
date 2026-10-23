package com.glaciersecurity.glaciermessenger.http.fake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.http.fake.FakeScenarios.Scenario;
import com.glaciersecurity.glaciermessenger.utils.Log;

/**
 * Drives {@link FakeGlacierBackend} from adb, so a failure scenario can be switched on in the
 * middle of a session without rebuilding or restarting the app.
 *
 * <p>Debug-only: this class lives in {@code src/debug} and is declared in
 * {@code src/debug/AndroidManifest.xml}, so it does not exist in a release build at all. It is
 * exported, which any other app on the device could use to reach it -- acceptable for a debug
 * APK whose entire backend is already imaginary, and impossible in a store build.
 *
 * <pre>{@code
 * PKG=com.glaciersecurity.glaciermessenger
 * A=$PKG.FAKE_BACKEND
 *
 * # -p $PKG is required: since Android 8 an implicit broadcast cannot reach a
 * # manifest-declared receiver, and the bare form fails silently with result=0.
 *
 * # make one endpoint fail; keys are the last path segment
 * adb shell am broadcast -p $PKG -a $A -e endpoint purchaseTwilioNumber -e scenario failure
 * adb shell am broadcast -p $PKG -a $A -e endpoint voicemail_list -e scenario malformed
 * adb shell am broadcast -p $PKG -a $A -e endpoint call-history -e scenario network_error
 *
 * # make everything fail, the way a console outage looks
 * adb shell am broadcast -p $PKG -a $A -e endpoint all -e scenario http_500
 *
 * # back to normal, and back to the starting tenant
 * adb shell am broadcast -p $PKG -a $A -e command reset
 * adb shell am broadcast -p $PKG -a $A -e command reseed
 *
 * # org-level switches
 * adb shell am broadcast -p $PKG -a $A -e command sms --ez value false
 * adb shell am broadcast -p $PKG -a $A -e command purchase --ez value false
 *
 * # what is currently set (read it back with: adb logcat -d -s glacier)
 * adb shell am broadcast -p $PKG -a $A -e command status
 * }</pre>
 *
 * <p>Scenario names are the {@link Scenario} constants, case-insensitively: {@code ok},
 * {@code failure}, {@code http_500}, {@code empty}, {@code malformed}, {@code network_error}.
 * An unrecognised name resolves to {@code ok} rather than failing the broadcast, so a typo
 * clears the override instead of leaving a stale one in place.
 */
public class FakeBackendReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.glaciersecurity.glaciermessenger.FAKE_BACKEND";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        if (!FakeGlacierBackend.isEnabled()) {
            Log.w(Config.LOGTAG,
                    "FakeBackendReceiver: ignoring broadcast -- this build was not assembled "
                            + "with -PglacierFakeBackend=true, so the fake backend is not "
                            + "installed and nothing would change.");
            return;
        }

        final String command = intent.getStringExtra("command");
        if (command != null) {
            handleCommand(command, intent);
            return;
        }

        final String endpoint = intent.getStringExtra("endpoint");
        if (endpoint == null) {
            Log.w(Config.LOGTAG,
                    "FakeBackendReceiver: broadcast carried neither 'command' nor 'endpoint'.");
            return;
        }
        final Scenario scenario = Scenario.parse(intent.getStringExtra("scenario"));
        FakeScenarios.set(endpoint, scenario);
        Log.w(Config.LOGTAG, "FakeBackendReceiver: " + endpoint + " -> " + scenario
                + " | " + FakeGlacierBackend.describe());
    }

    private void handleCommand(final String command, final Intent intent) {
        switch (command.toLowerCase(java.util.Locale.ROOT)) {
            case "reset":
                FakeScenarios.reset();
                break;
            case "reseed":
                FakeScenarios.reset();
                FakeTenant.seed();
                break;
            case "sms":
                FakeTenant.setSmsEnabled(intent.getBooleanExtra("value", true));
                break;
            case "purchase":
                FakeTenant.setPurchaseNumbersAllowed(intent.getBooleanExtra("value", true));
                break;
            case "status":
                break;
            default:
                Log.w(Config.LOGTAG, "FakeBackendReceiver: unknown command '" + command + "'");
                return;
        }
        Log.w(Config.LOGTAG, "FakeBackendReceiver: " + command + " | "
                + FakeGlacierBackend.describe());
    }
}
