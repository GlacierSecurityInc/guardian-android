package com.glaciersecurity.glaciermessenger.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Predicate;

/**
 * The screens behind sign-in.
 *
 * <p>Every other test in this tier starts from a cleared app, because the orchestrator clears
 * package data before each one -- so the whole app past the sign-in form has been unreachable.
 * This class is the exception, and it pays for that with two requirements it cannot arrange
 * itself: a device that is <em>already signed in</em>, and {@code clearPackageData=false} so the
 * session survives to the next test.
 *
 * <p>It follows {@code GlacierApiContractTest}'s pattern exactly: it skips unless you opt in, so
 * an ordinary run, a managed-device run and the overnight soak are all unaffected.
 *
 * <pre>
 * # sign in on the device first
 *
 * ANDROID_SERIAL=emulator-5562 ./gradlew connectedPlaystoreDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.glacierSignedInUi=true \
 *   -Pandroid.testInstrumentationRunnerArguments.clearPackageData=false \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.glaciersecurity.glaciermessenger.ui.SignedInScreensTest
 * </pre>
 *
 * <p><b>No credentials appear here, and none may.</b> The account is signed in out of band and
 * this class only borrows the session. Nothing below asserts on user content either -- not a
 * conversation name, not the account address -- because an assertion message ends up in the run
 * log and the JID is exactly what must not. Every assertion is structural: the view exists, the
 * list is populated, the activity is locked to portrait.
 *
 * <p>Nothing here writes, sends or changes account state. The screens are opened and read.
 */
@RunWith(AndroidJUnit4.class)
public class SignedInScreensTest {

    private static final long TIMEOUT_MS = 15_000L;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    /**
     * Gated in {@code @Before}, never {@code @BeforeClass}: an assumption thrown from a class-level
     * hook is reported against the class description, so no test ever starts and AGP fails the
     * whole run with a shortfall and zero failures.
     */
    @Before
    public void requireAnOptInAndASignedInDevice() {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue(
                "opt in with"
                    + " -Pandroid.testInstrumentationRunnerArguments.glacierSignedInUi=true",
                "true".equals(arguments.getString("glacierSignedInUi")));

        // Opted in but signed out is a setup mistake, not a reason to skip quietly.
        assertWithMessage(
                        "no account on this device -- sign in first, and pass"
                            + " clearPackageData=false so the session survives")
                .that(DatabaseBackend.getInstance(context).getAccounts())
                .isNotEmpty();
    }

    private boolean isPhone() {
        return context.getResources().getBoolean(R.bool.portrait_only);
    }

    /**
     * Poll until {@code condition} holds on the activity, for screens that populate after the
     * first frame. {@code onActivity} already runs on the main thread.
     */
    private static <A extends Activity> boolean await(
            final ActivityScenario<A> scenario, final Predicate<A> condition) {
        final long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] satisfied = {false};
            scenario.onActivity(activity -> satisfied[0] = condition.test(activity));
            if (satisfied[0]) {
                return true;
            }
            SystemClock.sleep(250);
        }
        return false;
    }

    // ---- the main screen ---------------------------------------------------------------------

    @Test
    public void theConversationListIsShownWhenSignedIn() {
        try (ActivityScenario<ConversationsActivity> scenario =
                ActivityScenario.launch(ConversationsActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            onView(withId(R.id.main_fragment)).check(matches(isDisplayed()));
            onView(withId(R.id.navigation_conversations)).check(matches(isDisplayed()));
        }
    }

    /**
     * The bottom bar is the only way to reach four of the five sections. A destination silently
     * missing from the menu removes a whole area of the app with no error anywhere.
     */
    @Test
    public void theBottomBarOffersEverySection() {
        try (ActivityScenario<ConversationsActivity> scenario =
                ActivityScenario.launch(ConversationsActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(
                    activity -> {
                        final BottomNavigationView nav =
                                activity.findViewById(R.id.navigation_conversations);
                        assertThat(nav).isNotNull();
                        for (final int id :
                                new int[] {
                                    R.id.chat_nav,
                                    R.id.contacts_nav,
                                    R.id.phone_nav,
                                    R.id.security_hub_nav,
                                    R.id.settings_nav
                                }) {
                            assertWithMessage("bottom navigation item %s", nameOf(activity, id))
                                    .that(nav.getMenu().findItem(id))
                                    .isNotNull();
                        }
                    });
        }
    }

    @Test
    public void theConversationListHasAnAdapterAttached() {
        try (ActivityScenario<ConversationsActivity> scenario =
                ActivityScenario.launch(ConversationsActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            // The list lives in a fragment attached after the activity resumes, so this waits
            // rather than asserting on the first frame. The row *count* is deliberately not
            // asserted: it depends on the account's history, which a test must not depend on.
            assertWithMessage("conversation list never attached an adapter")
                    .that(
                            await(
                                    scenario,
                                    activity -> {
                                        final RecyclerView list = activity.findViewById(R.id.list);
                                        return list != null && list.getAdapter() != null;
                                    }))
                    .isTrue();
        }
    }

    // ---- the Security Hub ---------------------------------------------------------------------

    /**
     * The Hub is the screen that tells someone whether their device is safe, and its rows are
     * computed on the device rather than fetched. An empty Hub is the dangerous failure: it looks
     * exactly like a clean bill of health.
     */
    @Test
    public void theSecurityHubBindsItsRows() {
        try (ActivityScenario<SecurityHubActivity> scenario =
                ActivityScenario.launch(SecurityHubActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            assertWithMessage("Security Hub never populated a row")
                    .that(
                            await(
                                    scenario,
                                    activity -> {
                                        final RecyclerView list =
                                                activity.findViewById(R.id.recyclerView);
                                        return list != null
                                                && list.getAdapter() != null
                                                && list.getAdapter().getItemCount() > 0;
                                    }))
                    .isTrue();
        }
    }

    // ---- orientation, on real screens ----------------------------------------------------------

    /**
     * {@code OrientationLockTest} proves the lock on a debug probe activity. This proves it on the
     * two screens a user actually spends time in -- the callback is installed application-wide, so
     * a screen that opted out would be invisible to the probe.
     */
    @Test
    public void theSignedInScreensKeepThePortraitLockOnAPhone() {
        assumeTrue("portrait lock only applies to phones", isPhone());

        try (ActivityScenario<ConversationsActivity> scenario =
                ActivityScenario.launch(ConversationsActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(
                    activity ->
                            assertThat(activity.getRequestedOrientation())
                                    .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        }

        try (ActivityScenario<SecurityHubActivity> scenario =
                ActivityScenario.launch(SecurityHubActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(
                    activity ->
                            assertThat(activity.getRequestedOrientation())
                                    .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        }
    }

    private static String nameOf(final Activity activity, final int id) {
        try {
            return activity.getResources().getResourceEntryName(id);
        } catch (final RuntimeException e) {
            return String.valueOf(id);
        }
    }
}
