package com.glaciersecurity.glaciermessenger.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.github.barteksc.pdfviewer.PDFView;
import com.glaciersecurity.glaciermessenger.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * That {@link PdfViewerActivity} asks for the password of a protected PDF instead of showing a
 * blank page, and that a file it cannot open at all closes the viewer rather than leaving it empty.
 *
 * <p>Device-only: the decoding is pdfium's native code, which no JVM tier can load. The fixtures in
 * {@code assets/pdf} are a one-line PDF and the same page encrypted with the user password
 * {@value #PASSWORD}.
 */
@RunWith(AndroidJUnit4.class)
public class PdfViewerPasswordTest {

    private static final String PASSWORD = "test1234";
    private static final long LOAD_TIMEOUT_MS = 10_000;

    private final List<File> copied = new ArrayList<>();

    @After
    public void tearDown() {
        for (File file : copied) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    public void protectedPdfPromptsAndOpensWithTheRightPassword() throws IOException {
        try (ActivityScenario<PdfViewerActivity> scenario = launch(fromAsset("locked.pdf"))) {
            onView(withId(R.id.pdf_password)).inRoot(isDialog()).check(matches(isDisplayed()));

            onView(withId(R.id.pdf_password)).inRoot(isDialog()).perform(replaceText("wrong"));
            onView(withText(R.string.pdf_password_open)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.pdf_password_incorrect)).inRoot(isDialog())
                    .check(matches(isDisplayed()));

            onView(withId(R.id.pdf_password)).inRoot(isDialog()).perform(replaceText(PASSWORD));
            onView(withText(R.string.pdf_password_open)).inRoot(isDialog()).perform(click());

            assertWithMessage("pages loaded after the right password")
                    .that(awaitPageCount(scenario))
                    .isEqualTo(1);
        }
    }

    @Test
    public void cancellingThePromptClosesTheViewer() throws IOException {
        try (ActivityScenario<PdfViewerActivity> scenario = launch(fromAsset("locked.pdf"))) {
            onView(withId(R.id.pdf_password)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click());

            assertWithMessage("viewer destroyed after cancel")
                    .that(await(() -> scenario.getState() == Lifecycle.State.DESTROYED))
                    .isTrue();
        }
    }

    @Test
    public void unprotectedPdfOpensWithoutAPrompt() throws IOException {
        try (ActivityScenario<PdfViewerActivity> scenario = launch(fromAsset("open.pdf"))) {
            assertWithMessage("pages loaded with no password")
                    .that(awaitPageCount(scenario))
                    .isEqualTo(1);
        }
    }

    @Test
    public void unreadablePdfClosesTheViewer() throws IOException {
        final File file = newFixtureFile("broken.pdf");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write("%PDF-1.4\nnot really a pdf\n".getBytes(StandardCharsets.US_ASCII));
        }
        try (ActivityScenario<PdfViewerActivity> scenario = launch(Uri.fromFile(file))) {
            assertWithMessage("viewer destroyed after a load error")
                    .that(await(() -> scenario.getState() == Lifecycle.State.DESTROYED))
                    .isTrue();
        }
    }

    private static ActivityScenario<PdfViewerActivity> launch(Uri uri) {
        final Context context = ApplicationProvider.getApplicationContext();
        final Intent intent = new Intent(context, PdfViewerActivity.class).setData(uri);
        return ActivityScenario.launch(intent);
    }

    private Uri fromAsset(String name) throws IOException {
        final File file = newFixtureFile(name);
        final Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = testContext.getAssets().open("pdf/" + name);
                OutputStream out = new FileOutputStream(file)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return Uri.fromFile(file);
    }

    private File newFixtureFile(String name) {
        final Context context = ApplicationProvider.getApplicationContext();
        final File file = new File(context.getCacheDir(), "pdf-viewer-test-" + name);
        copied.add(file);
        return file;
    }

    /** The page count is set when the decode finishes, which Espresso's idling does not cover. */
    private static int awaitPageCount(ActivityScenario<PdfViewerActivity> scenario) {
        final AtomicInteger pages = new AtomicInteger();
        await(() -> {
            scenario.onActivity(activity ->
                    pages.set(activity.<PDFView>findViewById(R.id.pdfView).getPageCount()));
            return pages.get() > 0;
        });
        return pages.get();
    }

    private static boolean await(BooleanSupplier condition) {
        final long deadline = SystemClock.uptimeMillis() + LOAD_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            SystemClock.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
