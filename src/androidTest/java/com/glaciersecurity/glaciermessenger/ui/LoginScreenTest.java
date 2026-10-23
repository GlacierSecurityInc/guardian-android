package com.glaciersecurity.glaciermessenger.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.clearText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.google.common.truth.Truth.assertThat;
import static org.hamcrest.CoreMatchers.not;
import static com.google.common.truth.Truth.assertWithMessage;

import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.glaciersecurity.glaciermessenger.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The sign-in screen, driven through Espresso.
 *
 * <p>This is the first test in the repository that enters an Activity, and the sign-in screen is
 * the right one to start with for two reasons: it is the only screen reachable on a device with no
 * account, which is the state the orchestrator leaves before every test here; and it is the one
 * screen where nobody can get past a mistake. Every other screen has a user who is already in.
 *
 * <p>What is asserted is the screen's contract rather than its appearance -- that the two fields
 * and the button exist and are usable, that the password is masked, and that what was typed
 * survives a configuration change. The last one is the classic silent failure: nothing errors, the
 * fields simply empty themselves when the device rotates or the system recreates the activity, and
 * the user retypes a password they now doubt.
 *
 * <p>Nothing here signs in. No credentials are typed, no network call is made and no account is
 * created; the values below are from reserved ranges. Driving a real sign-in needs a real account,
 * and that belongs in a manual pass, outside the repository.
 */
@RunWith(AndroidJUnit4.class)
public class LoginScreenTest {

    private static final String A_USERNAME = "testuser@example.com";
    private static final String NOT_A_PASSWORD = "not-a-real-password";

    @Test
    public void theSignInFormIsShown() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            onView(withId(R.id.account_username)).check(matches(isDisplayed()));
            onView(withId(R.id.account_password)).check(matches(isDisplayed()));
            onView(withId(R.id.account_login)).check(matches(isDisplayed()));
        }
    }

    /**
     * The button is the validation gate: it stays disabled until both fields have something in
     * them. Getting that backwards is a support call rather than a crash -- either the user taps a
     * dead button with no explanation, or an empty sign-in is sent and comes back as "incorrect
     * username or password", which is a lie about what went wrong.
     */
    @Test
    public void theSignInButtonIsDisabledUntilBothFieldsAreFilled() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            onView(withId(R.id.account_login)).check(matches(not(isEnabled())));

            onView(withId(R.id.account_username))
                    .perform(replaceText(A_USERNAME), closeSoftKeyboard());
            onView(withId(R.id.account_login)).check(matches(not(isEnabled())));

            onView(withId(R.id.account_password))
                    .perform(replaceText(NOT_A_PASSWORD), closeSoftKeyboard());
            onView(withId(R.id.account_login)).check(matches(isEnabled()));
        }
    }

    @Test
    public void emptyingAFieldDisablesTheButtonAgain() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            onView(withId(R.id.account_username))
                    .perform(replaceText(A_USERNAME), closeSoftKeyboard());
            onView(withId(R.id.account_password))
                    .perform(replaceText(NOT_A_PASSWORD), closeSoftKeyboard());
            onView(withId(R.id.account_login)).check(matches(isEnabled()));

            onView(withId(R.id.account_password)).perform(clearText(), closeSoftKeyboard());
            onView(withId(R.id.account_login)).check(matches(not(isEnabled())));
        }
    }

    /** Whitespace is trimmed before the check, so a field of spaces is still an empty field. */
    @Test
    public void whitespaceAloneDoesNotCountAsCredentials() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            onView(withId(R.id.account_username)).perform(replaceText("   "), closeSoftKeyboard());
            onView(withId(R.id.account_password)).perform(replaceText("   "), closeSoftKeyboard());

            onView(withId(R.id.account_login)).check(matches(not(isEnabled())));
        }
    }

    /**
     * Visual masking, specifically. It is not the same thing as the field being masked in the
     * accessibility tree -- it is not -- but losing the transformation would put the password in
     * plain text on the screen, which is the part a shoulder-surfer gets for free.
     */
    @Test
    public void thePasswordFieldIsMasked() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(
                    activity -> {
                        final EditText password = activity.findViewById(R.id.account_password);
                        assertWithMessage("transformation method on the password field")
                                .that(password.getTransformationMethod())
                                .isInstanceOf(PasswordTransformationMethod.class);
                    });
        }
    }

    @Test
    public void bothFieldsAcceptText() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);

            onView(withId(R.id.account_username))
                    .perform(replaceText(A_USERNAME), closeSoftKeyboard());
            onView(withId(R.id.account_password))
                    .perform(replaceText(NOT_A_PASSWORD), closeSoftKeyboard());

            onView(withId(R.id.account_username)).check(matches(withText(A_USERNAME)));
            onView(withId(R.id.account_password)).check(matches(withText(NOT_A_PASSWORD)));
        }
    }

    /**
     * A recreate is what a rotation, a theme change or the system reclaiming the activity all come
     * down to. Losing the typed username here costs the user nothing but their patience -- which is
     * exactly why it goes unreported and stays broken.
     */
    @Test
    public void whatWasTypedSurvivesTheActivityBeingRecreated() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withId(R.id.account_username))
                    .perform(replaceText(A_USERNAME), closeSoftKeyboard());

            scenario.recreate();

            onView(withId(R.id.account_username)).check(matches(withText(A_USERNAME)));
        }
    }

    @Test
    public void clearingAFieldEmptiesIt() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withId(R.id.account_username))
                    .perform(replaceText(A_USERNAME), closeSoftKeyboard());
            onView(withId(R.id.account_username)).perform(clearText(), closeSoftKeyboard());
            onView(withId(R.id.account_username)).check(matches(withText("")));
        }
    }

    /** The build number on the sign-in screen is the only version a signed-out user can report. */
    @Test
    public void theBuildVersionIsShownToASignedOutUser() {
        try (ActivityScenario<LoginAccountActivity> scenario =
                ActivityScenario.launch(LoginAccountActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(
                    activity -> {
                        final android.widget.TextView version =
                                activity.findViewById(R.id.login_version);
                        assertThat(version).isNotNull();
                        assertWithMessage("version label on the sign-in screen")
                                .that(version.getText().toString())
                                .isNotEmpty();
                    });
        }
    }
}
