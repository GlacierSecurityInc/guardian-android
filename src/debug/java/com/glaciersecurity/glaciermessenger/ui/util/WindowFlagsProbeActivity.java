package com.glaciersecurity.glaciermessenger.ui.util;

import android.app.Activity;

/**
 * An empty activity that gives window-level instrumented tests a real
 * {@link android.view.Window} without the setup a real screen needs — no bound connection
 * service, no account, no network.
 *
 * <p>It lives in the debug source set rather than androidTest because an activity declared in the
 * test APK belongs to that APK's package, and the instrumentation's target process cannot launch
 * it. It is not exported and is absent from release builds entirely.
 */
public class WindowFlagsProbeActivity extends Activity {
}
