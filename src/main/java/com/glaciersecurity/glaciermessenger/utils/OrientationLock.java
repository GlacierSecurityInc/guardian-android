package com.glaciersecurity.glaciermessenger.utils;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity;

/**
 * Locks every activity to portrait on devices where {@code R.bool.portrait_only} is true, which is
 * everything narrower than sw600dp. Tablets keep rotating on purpose: the two-pane conversations
 * layout is qualified {@code w945dp} and is only ever reachable in landscape.
 *
 * <p>{@link MediaViewerActivity} is exempt: it shows content whose shape the sender picked, and a
 * landscape photo or video is worth turning the phone for.
 *
 * <p>{@code PdfViewerActivity} is deliberately <em>not</em> exempt. Its signature overlays are
 * positioned in {@code pdf_container} pixels and exported by scaling those pixels against the
 * container's current size, so a placement only means anything while the container keeps the
 * geometry it had when the signature was dropped. Rotating either destroys the activity and loses
 * every placement, or — with {@code configChanges} — resizes the container underneath them and
 * writes the signatures to the wrong part of the exported page. Letting it rotate needs the
 * placements stored in page-relative coordinates first.
 *
 * <p>This runs from an activity lifecycle callback rather than {@code android:screenOrientation} in
 * the manifest for two reasons: the manifest attribute cannot express the sw600dp exception, and a
 * callback also covers the activities we do not declare ourselves (the image cropper).
 */
public final class OrientationLock {

    private OrientationLock() {}

    public static void install(final Application application) {
        application.registerActivityLifecycleCallbacks(new Callbacks());
    }

    private static class Callbacks implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(@NonNull final Activity activity, @Nullable final Bundle savedInstanceState) {
            if (!activity.getResources().getBoolean(R.bool.portrait_only)) {
                return;
            }
            if (activity instanceof MediaViewerActivity) {
                return;
            }
            // Leave anything that asked for an orientation of its own alone.
            if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                return;
            }
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        @Override
        public void onActivityStarted(@NonNull final Activity activity) {}

        @Override
        public void onActivityResumed(@NonNull final Activity activity) {}

        @Override
        public void onActivityPaused(@NonNull final Activity activity) {}

        @Override
        public void onActivityStopped(@NonNull final Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(@NonNull final Activity activity, @NonNull final Bundle outState) {}

        @Override
        public void onActivityDestroyed(@NonNull final Activity activity) {}
    }
}
