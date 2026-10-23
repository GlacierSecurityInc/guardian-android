package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.newParent;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.onMain;
import static com.glaciersecurity.glaciermessenger.testutil.ViewHarness.themedContext;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.glaciersecurity.glaciermessenger.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Inflates every layout in the app under the app's own theme.
 *
 * <p>A layout is only checked when its screen opens. A custom view renamed or moved to another
 * package, an attribute deleted from {@code attrs.xml}, a {@code ?attr} that only exists in one
 * theme -- none of these are build errors, and none of them are caught by anything that runs
 * today. They surface as an {@code InflateException} the first time someone navigates to that
 * screen, which for the rarer screens can be a release later.
 *
 * <p>That is the whole claim this class makes, and it is deliberately a narrow one: it says every
 * layout can be *constructed*, not that it looks right or that its screen works. It is cheap
 * enough to be worth it anyway -- inflation runs every custom view's two-argument constructor and
 * resolves every attribute and every theme reference in the file.
 *
 * <p>This tier rather than Robolectric: Robolectric's resource loader is not the platform's, so an
 * inflation it accepts is not evidence the device would. The point of the test is what the device
 * does.
 *
 * <p>Layouts are inflated into a real parent with {@code attachToRoot} true, so a {@code <merge>}
 * root -- which throws without one -- is covered rather than skipped.
 *
 * <p>Nothing is excluded, and nothing should be without a stated reason: an exclusion list is how
 * a sweep like this quietly stops covering the thing it was added for. If a layout ever genuinely
 * cannot be inflated standalone, skip that one by name here and say why.
 */
@RunWith(AndroidJUnit4.class)
public class LayoutInflationTest {

    private static Map<String, Integer> allLayouts() {
        final Map<String, Integer> layouts = new TreeMap<>();
        for (final Field field : R.layout.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            try {
                layouts.put(field.getName(), field.getInt(null));
            } catch (final IllegalAccessException e) {
                throw new AssertionError("could not read R.layout." + field.getName(), e);
            }
        }
        return layouts;
    }

    @Test
    public void thereAreLayoutsToInflate() {
        // Guards the reflection above: if R.layout stopped being readable this class would
        // otherwise pass by testing nothing.
        assertWithMessage("layouts found in R.layout")
                .that(allLayouts().size())
                .isGreaterThan(50);
    }

    @Test
    public void everyLayoutInflatesUnderTheAppTheme() {
        final List<String> failures = new ArrayList<>();
        onMain(
                () -> {
                    final Context context = themedContext();
                    final LayoutInflater inflater = LayoutInflater.from(context);
                    for (final Map.Entry<String, Integer> layout : allLayouts().entrySet()) {
                        final ViewGroup parent = newParent(context);
                        try {
                            inflater.inflate(layout.getValue(), parent, true);
                        } catch (final Throwable t) {
                            failures.add(layout.getKey() + ": " + describe(t));
                        }
                    }
                });

        assertWithMessage(
                        "layouts that could not be inflated under R.style.AppTheme (%s of %s)",
                        failures.size(), allLayouts().size())
                .that(failures)
                .isEmpty();
    }

    /** Root cause plus message -- an InflateException's own message names the tag, not the fault. */
    private static String describe(final Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return thrown.getClass().getSimpleName()
                + " <- "
                + cause.getClass().getName()
                + ": "
                + cause.getMessage();
    }
}
