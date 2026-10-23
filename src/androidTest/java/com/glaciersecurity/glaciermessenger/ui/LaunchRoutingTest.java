package com.glaciersecurity.glaciermessenger.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.testutil.DeviceTestFixtures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link ConversationActivity} is the launcher entry point and does nothing but route: it reads the
 * accounts table, picks a destination and finishes itself. A device with no accounts gets the
 * onboarding wizard; a device with any account row stays in the app.
 *
 * <p>Worth a test because the failure is an empty app. The activity finishes in {@code onCreate}
 * either way, so a routing decision that goes wrong does not crash -- the launcher icon opens
 * nothing at all, which reads as the app failing to start rather than as a bug in one line. The
 * two directions are also the whole first-run experience: sending a brand new device to a
 * username/password screen skips setup, and sending a returning one to the wizard asks it to set
 * up again.
 *
 * <p>It is driven with an {@link Instrumentation.ActivityMonitor} rather than
 * {@code ActivityScenario}, because a trampoline never reaches RESUMED and
 * {@code ActivityScenario.launch} waits for exactly that. What is asserted is the activity that
 * comes up *next*, which is the actual contract.
 *
 * <p>The accounts table is emptied here rather than left to the orchestrator's {@code pm clear}.
 * That clear is off under {@code -PcoverageReport}, and without this the "no account" case
 * inherits rows another test seeded and routes the other way -- which is how this test first
 * failed.
 */
@RunWith(AndroidJUnit4.class)
public class LaunchRoutingTest {

    private static final long TIMEOUT_MS = 20_000L;

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private DatabaseBackend database;
    private Activity launched;

    @Before
    public void startFromNoAccounts() {
        DeviceTestFixtures.setUpStringprep();
        database = DatabaseBackend.getInstance(context);
        deleteEveryAccount();
    }

    @After
    public void cleanUp() {
        if (launched != null) {
            instrumentation.runOnMainSync(launched::finish);
            launched = null;
        }
        deleteEveryAccount();
    }

    private void deleteEveryAccount() {
        for (final Account account : database.getAccounts()) {
            database.deleteAccount(account);
        }
    }

    @Test
    public void aDeviceWithNoAccountLandsOnTheOnboardingWizard() {
        assertThat(database.getAccounts()).isEmpty();
        launched = routeTo(StepperWizard.class);
        assertWithMessage(
                        "launching %s with no account rows should bring up %s",
                        ConversationActivity.class.getSimpleName(),
                        StepperWizard.class.getSimpleName())
                .that(launched)
                .isNotNull();
    }

    @Test
    public void aDeviceThatAlreadyHasAnAccountStaysInTheApp() {
        seedDisabledAccount();
        assertThat(database.getAccounts()).isNotEmpty();

        launched = routeTo(ConversationsActivity.class);
        assertWithMessage(
                        "launching %s with an account row should bring up %s",
                        ConversationActivity.class.getSimpleName(),
                        ConversationsActivity.class.getSimpleName())
                .that(launched)
                .isNotNull();
    }

    /**
     * The trampoline must not stay on the back stack, or backing out of where it sent you returns
     * to a blank activity that immediately routes forward again -- a loop the user cannot leave
     * except by killing the app.
     *
     * <p>An account is seeded first, and that is the whole point of the test rather than setup
     * noise. With no accounts the destination is {@code StepperWizard}, whose intent carries
     * {@code FLAG_ACTIVITY_CLEAR_TASK} -- which destroys the trampoline whether or not it ever
     * called {@code finish()}, so that route asserts nothing. The returning-account route is the
     * one where the explicit {@code finish()} is load-bearing, and it is also the only one where
     * the loop this guards against could actually happen.
     */
    @Test
    public void theTrampolineFinishesItselfOnTheRouteThatDoesNotClearTheTask() {
        seedDisabledAccount();

        final Instrumentation.ActivityMonitor trampoline =
                instrumentation.addMonitor(ConversationActivity.class.getName(), null, false);
        try {
            startLauncherActivity();
            final Activity activity =
                    instrumentation.waitForMonitorWithTimeout(trampoline, TIMEOUT_MS);
            assertThat(activity).isNotNull();
            instrumentation.waitForIdleSync();
            assertWithMessage("%s should have finished itself", activity.getClass().getSimpleName())
                    .that(activity.isFinishing() || activity.isDestroyed())
                    .isTrue();
        } finally {
            instrumentation.removeMonitor(trampoline);
        }
    }

    /**
     * Disabled, deliberately. Any account row at all routes to {@code ConversationsActivity} --
     * the loop's early return and the fall-through below it have the same destination -- but an
     * *enabled* one makes {@code XmppActivity.onStart} bind {@code XmppConnectionService}, which
     * reconnects eligible accounts and puts a real DNS lookup and socket attempt for the fixture
     * domain on the wire. {@code OPTION_DISABLED} keeps the routing condition and leaves the
     * network alone.
     */
    private void seedDisabledAccount() {
        final Account account = DeviceTestFixtures.newAccount();
        account.setOption(Account.OPTION_DISABLED, true);
        database.createAccount(account);
    }

    /** Launch the trampoline and wait for {@code destination} to come up, or time out. */
    private Activity routeTo(final Class<? extends Activity> destination) {
        final Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(destination.getName(), null, false);
        try {
            startLauncherActivity();
            return instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void startLauncherActivity() {
        final Intent intent =
                new Intent(context, ConversationActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
