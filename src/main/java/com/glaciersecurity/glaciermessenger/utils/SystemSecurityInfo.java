package com.glaciersecurity.glaciermessenger.utils;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;

import androidx.annotation.BoolRes;
import androidx.biometric.BiometricManager;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.SecurityInfo;
import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


public class SystemSecurityInfo {

    /**
     * Absolute path to the property-reader binary.
     *
     * <p>Invoking it as a bare {@code "getprop"} makes the lookup depend on {@code PATH}
     * as inherited by this process, which is not a trustworthy input for a check whose
     * whole job is to decide whether the device has been tampered with. The AOSP location
     * has been {@code /system/bin/getprop} on every API level this app supports.
     */
    private static final String GETPROP = "/system/bin/getprop";

    private static final String GOLDFISH = "goldfish";
    private static final String RANCHU = "ranchu";
    private static final String SDK = "sdk";
    private static final Set<String> ALLOWED_SYSTEM_PROPERTIES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "ro.debuggable",
                    "ro.ss.version"
            )));

    // Expanded hooking-framework package list
    // Covers the original packages plus all major modern forks and managers.
    // Package-name detection is still bypassable by renaming, but raises the
    // bar and catches the majority of non-targeted attacks.
    private static final String[] KNOWN_HOOKING_PACKAGES = {
            // Xposed — original installer (obsolete but still seen in the wild)
            "de.robv.android.xposed.installer",
            // LSPosed — dominant modern Xposed successor
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            // LSPatch — Xposed without root, patches APKs directly
            "com.android.shell.lspatch",
            // TaiChi / Wuji — rootless Xposed via process injection
            "me.weishu.exp",
            // VirtualXposed — runs Xposed inside a virtual space
            "io.va.exposed",
            // Oasis Island (uses virtualisation for app isolation + hooking)
            "com.oasisfeng.island",
            // Substrate (Cydia) — historic but still deployed
            "com.saurik.substrate",
            // Frida (official server wrapper app, rarely installed but check anyway)
            "re.frida.server",
            "com.frida",
            // Magisk — root + module manager; many hooking modules run inside it
            "com.topjohnwu.magisk",
            // Kitsune Mask — Magisk fork with systemless xposed support
            "io.github.huskydg.magisk",
            // Classic su managers
            "com.noshufou.android.su",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
    };

    // Stack-trace signatures for hooking frameworks
    // These class-name prefixes appear in every method call when a framework is
    // active, regardless of whether its APK has been renamed or hidden.
    private static final String[] HOOKING_STACK_SIGNATURES = {
            "de.robv.android.xposed",   // original XposedBridge
            "org.lsposed",              // LSPosed bridge classes
            "me.weishu.epic",           // Epic dex hook library (TaiChi / LSPatch)
            "com.android.shell.lspatch",
            "com.swift.sandhook",       // SandHook — another dex-level hook lib
            "lab.galaxy.yhook",         // YHook
    };

    private String latestOs;
    private String latestGlacier;
    private String latestSamsungOS;

    private String latestAlt;
    private boolean isUsingVpn;
    private String anomalyString;

    private XmppConnectionService xmppConnectionService;
    private volatile SecurityInfo securityInfo;
    private volatile List<String> anomalyFindings = Collections.emptyList();
    private final AtomicBoolean refreshRequested = new AtomicBoolean();

    public SystemSecurityInfo(XmppConnectionService xmppConnectionService) {
        this.xmppConnectionService = xmppConnectionService;
    }

    /**
     * Whether the device looked secure as of the last {@link #checkCurrentSecurityInfo()}.
     *
     * <p>Reads the cached snapshot rather than re-probing. Every screen with a Security Hub
     * badge calls this from the main thread, and the live probes
     * fork a process per system property, walk the installed packages and call the biometric
     * service over binder -- far too much to run while painting a badge.
     *
     * <p>Before the first sweep has landed there is nothing to report, so this answers
     * "secure" and schedules a refresh; the badge settles a moment later instead of blocking
     * the caller.
     */
    public boolean isSecure() {
        final SecurityInfo snapshot = securityInfo;
        if (snapshot == null) {
            requestRefresh();
            return true;
        }
        return !snapshot.getOsOutdated()
                && !snapshot.getGlacierOutdated()
                && !snapshot.getCompromised()
                && snapshot.getScreenLock();
    }

    // ── Last-known values from the cached snapshot ────────────────────────────
    // checkCurrentSecurityInfo() is the one place that probes the device; the UI renders what
    // it left behind. Re-running the probes to paint a row is what kept this work on the main
    // thread even after the sweep itself moved off it.

    /** Whether the last sweep found anomalies. False if no sweep has run yet. */
    public boolean lastKnownAnomalies() {
        final SecurityInfo snapshot = securityInfo;
        return snapshot != null && snapshot.getCompromised();
    }

    /** The findings behind {@link #lastKnownAnomalies()}; empty if no sweep has run yet. */
    public List<String> lastKnownFindings() {
        return anomalyFindings;
    }

    /** Whether a device screen lock was set as of the last sweep. */
    public boolean lastKnownScreenLock() {
        final SecurityInfo snapshot = securityInfo;
        return snapshot != null && snapshot.getScreenLock();
    }

    /** Whether device biometrics were enrolled as of the last sweep. */
    public boolean lastKnownBioLock() {
        final SecurityInfo snapshot = securityInfo;
        return snapshot != null && snapshot.getBiometricLock();
    }

    /** Whether the OS was up to date as of the last sweep. */
    public boolean lastKnownLatestOs() {
        final SecurityInfo snapshot = securityInfo;
        return snapshot == null || !snapshot.getOsOutdated();
    }

    /** Whether Glacier was up to date as of the last sweep. */
    public boolean lastKnownLatestGlacier() {
        final SecurityInfo snapshot = securityInfo;
        return snapshot == null || !snapshot.getGlacierOutdated();
    }

    // One refresh in flight at a time: every visible screen can ask for the badge at once,
    // and there is no point queueing the same sweep eight times behind itself.
    private void requestRefresh() {
        if (xmppConnectionService == null) {
            return;
        }
        if (refreshRequested.compareAndSet(false, true)) {
            xmppConnectionService.refreshSecurityInfo(() -> refreshRequested.set(false));
        }
    }

    public boolean hasScreenLock() {
        KeyguardManager myKM = (KeyguardManager) xmppConnectionService.getSystemService(Context.KEYGUARD_SERVICE);
        return myKM.isDeviceSecure();
    }

    public boolean hasBioLock() {
        final BiometricManager myBM = BiometricManager.from(xmppConnectionService);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final int strongOrWeakAuthenticators =
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK;
            return myBM.canAuthenticate(strongOrWeakAuthenticators)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }
        return false;
    }

    public boolean isUSBdebug() {
        return isUSBDebuggingEnabled(xmppConnectionService);
    }

    public boolean isCoreEnabled() {
        return isUsingVpn;
    }

    public void setUsingVpn(boolean usingVpn) {
        isUsingVpn = usingVpn;
    }

    public void checkLatestVersions() {
        latestGlacier = getPreferences(xmppConnectionService).getString("latest_version_glacier_android", "");
        latestSamsungOS = getPreferences(xmppConnectionService).getString("latest_version_samsung_os", "");
        latestOs = getPreferences(xmppConnectionService).getString("latest_version_android_os", "");
        latestAlt = getPreferences(xmppConnectionService).getString("latest_version_altos", "");

        boolean osOutdated = !isLatestOS();
        boolean glacierOutdated = !isLatestGlacier();

        securityInfo.setOsOutdated(osOutdated);
        securityInfo.setGlacierOutdated(glacierOutdated);
    }

    public boolean isLatestOS() {
        if (Build.MODEL.contains("Pixelbook")) {
            return true;
        }

        if (Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
            if (latestSamsungOS == null || latestSamsungOS.isEmpty()) {
                return true;
            }
            ComparableVersion latestCompSamsungOs = new ComparableVersion(latestSamsungOS);
            ComparableVersion myOs = new ComparableVersion(Build.VERSION.RELEASE);
            return myOs.compareTo(latestCompSamsungOs) >= 0;
        }

        if (latestAlt != null) {
            String myOsBuild = getDeviceBuildVersion();
            ComparableVersion latestCompAltOs = new ComparableVersion(latestAlt);
            ComparableVersion myOsB = new ComparableVersion(myOsBuild);
            if (myOsB.compareTo(latestCompAltOs) >= 0) {
                return true;
            }
        }

        if (latestOs != null) {
            ComparableVersion latestCompOs = new ComparableVersion(latestOs);
            String myOsVer = Build.VERSION.RELEASE;
            ComparableVersion myOs = new ComparableVersion(myOsVer);
            return myOs.compareTo(latestCompOs) >= 0;
        }
        return true;
    }

    private String getDeviceBuildVersion() {
        return readSystemProperty("ro.ss.version");
    }

    public boolean isLatestGlacier() {
        if (latestGlacier != null) {
            ComparableVersion latestCompGlacier = new ComparableVersion(latestGlacier);
            String versionName = BuildConfig.VERSION_NAME;
            if (versionName.contains("-")) {
                versionName = versionName.split("-")[0];
            }
            ComparableVersion myVersion = new ComparableVersion(versionName);
            return myVersion.compareTo(latestCompGlacier) >= 0;
        }
        return true;
    }

    public boolean hasAnomalies() {
        final List<String> findings = getSecurityFindings(xmppConnectionService);
        anomalyFindings = findings.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(findings));
        anomalyString = findings.isEmpty()
                ? "Your system is safe"
                : TextUtils.join("; ", findings);
        return !findings.isEmpty();
    }

    public String getAnomaliesString() {
        return anomalyString;
    }

    /**
     * Recompute the cached {@link SecurityInfo} from the current device state.
     *
     * <p><strong>Blocking.</strong> This queries the keyguard and biometric system services
     * over binder, walks the installed packages looking for anomalies and forks a process per
     * system property it reads. Call it from a background thread --
     * {@code XmppConnectionService.refreshSecurityInfo(Runnable)} serializes it for you. On the
     * main thread a slow biometric service is enough to blow the 5s input-dispatch deadline.
     */
    public void checkCurrentSecurityInfo() {
        if (securityInfo == null) {
            securityInfo = new SecurityInfo(PhoneHelper.getAndroidId(xmppConnectionService));

            String deviceStr = Build.MANUFACTURER + " " + Build.MODEL;
            String patch = getCurrentSecurityPatch();
            securityInfo.setDevice(deviceStr);

            String osversion = Build.VERSION.RELEASE;
            securityInfo.setOsVersion(osversion);
            securityInfo.setOsPatch(patch);

            String gversionName = BuildConfig.VERSION_NAME;
            gversionName = gversionName.split("-")[0];
            securityInfo.setGlacierVersion(gversionName);
        }

        boolean hasAnomalies = hasAnomalies();
        securityInfo.setCompromised(hasAnomalies);
        // Set unconditionally: nested under a changed-check, the detail stayed null on a healthy
        // device, and a null field is dropped from the uploaded JSON entirely.
        securityInfo.setCompromisedDetail(anomalyString);

        boolean hasScreenLock = hasScreenLock();
        if (hasScreenLock != securityInfo.getScreenLock()) {
            securityInfo.setScreenLock(hasScreenLock);
        }

        boolean hasBioLock = isBiometricPINOn(xmppConnectionService.getApplicationContext());
        if (hasBioLock != securityInfo.getApplicationLock()) {
            securityInfo.setApplicationLock(hasBioLock);
        }

        boolean hasDeviceLock = hasBioLock();
        if (hasDeviceLock != securityInfo.getBiometricLock()) {
            securityInfo.setBiometricLock(hasDeviceLock);
        }

        boolean hasCoreEnabled = isCoreEnabled();
        if (hasCoreEnabled != securityInfo.getCoreEnabled()) {
            securityInfo.setCoreEnabled(hasCoreEnabled);
        }

        checkLatestVersions();
    }

    // ── Static security findings ──────────────────────────────────────────────

    /**
     * Returns a list of human-readable security findings for this device.
     *
     * <p>Callers on the main thread will skip the Frida port-probe check
     * (network I/O is not permitted on the main thread). All other checks
     * are safe to call from any thread.
     */
    public static List<String> getSecurityFindings(Context context) {
        final List<String> findings = new ArrayList<>();

        // ── Debugging flags ───────────────────────────────────────────────────
        final boolean usbDebugEnabled = isUSBDebuggingEnabled(context);
        final boolean wifiDebugEnabled = isWifiDebuggingEnabled(context);
        if (usbDebugEnabled && wifiDebugEnabled) {
            findings.add("USB and Wi-Fi debugging are enabled");
        } else if (usbDebugEnabled) {
            findings.add("USB debugging is enabled");
        } else if (wifiDebugEnabled) {
            findings.add("Wi-Fi debugging is enabled");
        }

        if (isRoDebuggableEnabled()) {
            findings.add("ro.debuggable is enabled");
        }

        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            findings.add("Build tags contain test-keys");
        }

        // Root detection (path-agnostic, Magisk-aware)
        if (isRooted(context)) {
            findings.add("Shows signs of being rooted");
        }

        // ── Live debugger ─────────────────────────────────────────────────────
        if (Debug.isDebuggerConnected()) {
            findings.add("Active debugger detected");
        }

        // Hooking framework — package enumeration
        if (isHookingPackageInstalled(context)) {
            findings.add("Hooking framework package detected");
        }

        // Hooking framework — call-stack inspection
        if (isHookingFrameworkInStack()) {
            findings.add("Hooking framework detected in call stack");
        }

        // Hooking framework — /proc/self/maps
        if (isFridaAgentInMemory()) {
            findings.add("Frida agent library detected in process memory");
        }

        // Hooking framework — Frida TCP port probe
        // Network I/O must not run on the main thread; skip the check there.
        if (Looper.myLooper() != Looper.getMainLooper() && isFridaPortOpen()) {
            findings.add("Frida instrumentation server detected on localhost");
        }

        // ── Emulator ──────────────────────────────────────────────────────────
        if (isEmulator(context)) {
            findings.add("Emulator detected");
        }

        return findings;
    }

    // ── Emulator detection ────────────────────────────────────────────────────

    /**
     * Checks common indicators that the app is running inside an Android emulator.
     */
    public static boolean isEmulator(Context context) {
        @SuppressLint("HardwareIds")
        final String androidId = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
        return Build.PRODUCT.contains(SDK)
                || Build.HARDWARE.contains(GOLDFISH)
                || Build.HARDWARE.contains(RANCHU)
                || androidId == null;
    }

    // Root detection

    /**
     * Returns {@code true} if the device shows signs of being rooted.
     *
     * <p>Coverage:
     * <ul>
     *   <li>Searches every directory on {@code $PATH} plus common non-PATH
     *       locations, so a moved {@code su} binary is still found.</li>
     *   <li>Checks Magisk, KernelSU, and APatch characteristic data paths.</li>
     *   <li>Attempts to execute {@code su -c id} as a last resort; if it
     *       succeeds without a permission prompt the device is rooted.</li>
     * </ul>
     *
     * <p>Note: a sufficiently motivated attacker on a rooted device can always
     * subvert these checks. This method raises the detection bar but cannot
     * provide absolute guarantees.
     */
    public static boolean isRooted(Context context) {
        // Skip most checks inside the emulator — su is expected there.
        final boolean onEmulator = isEmulator(context);

        // 1. Build tag check (unchanged from original)
        final String buildTags = Build.TAGS;
        if (!onEmulator && buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }

        // 2. Legacy Superuser.apk path
        if (new File("/system/app/Superuser.apk").exists()) {
            return true;
        }

        // 3. Search su across $PATH directories plus common non-PATH locations
        final List<String> searchDirs = new ArrayList<>();
        final String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            searchDirs.addAll(Arrays.asList(pathEnv.split(":")));
        }
        searchDirs.addAll(Arrays.asList(
                "/system/bin",
                "/system/xbin",
                "/system/sbin",
                "/vendor/bin",
                "/sbin",
                "/data/local",
                "/data/local/bin",
                "/data/local/xbin"
        ));
        if (!onEmulator) {
            for (final String dir : searchDirs) {
                if (new File(dir, "su").exists()) {
                    return true;
                }
            }
        }

        // 4. Magisk / KernelSU / APatch characteristic data paths
        if (new File("/sbin/.magisk").exists()
                || new File("/data/adb/magisk").exists()
                || new File("/data/adb/ksu").exists()       // KernelSU
                || new File("/data/adb/apatch").exists()) { // APatch
            return true;
        }

        // 5. Try executing su — if it exits cleanly the device is rooted.
        //    On a stock device this either throws (binary not found) or exits
        //    with a non-zero code (permission denied / user cancelled).
        if (!onEmulator) {
            try {
                final Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                final boolean finished = process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (finished && process.exitValue() == 0) {
                    return true;
                }
                process.destroy();
            } catch (Exception ignored) {
                // Binary not found or permission denied — not rooted
            }
        }

        return false;
    }

    // Expanded package-name detection

    /**
     * Returns {@code true} if any known hooking-framework package is installed.
     *
     * <p>This is the same mechanism as the original {@code isHookingFrameworkDetected}
     * but with a substantially expanded package list covering LSPosed, LSPatch,
     * TaiChi, Magisk managers, and modern Frida wrappers.
     *
     * <p>Package-name checks can be defeated by renaming the framework APK; use
     * this in combination with {@link #isHookingFrameworkInStack()},
     * {@link #isFridaAgentInMemory()}, and {@link #isFridaPortOpen()}.
     */
    public static boolean isHookingPackageInstalled(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        for (final String pkg : KNOWN_HOOKING_PACKAGES) {
            try {
                packageManager.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        // Also check for the XposedBridge class directly — present even when the
        // installer APK has been renamed.
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        return false;
    }

    // Frida TCP port probe

    /**
     * Probes TCP port 27042 on localhost, which is Frida's default server port.
     *
     * <p>This detects Frida even when the server binary has been renamed and
     * no filesystem traces remain, because the listening socket is always
     * present while Frida is active.
     *
     * <p><strong>Must be called from a background thread.</strong> Calling from
     * the main thread will throw {@link android.os.NetworkOnMainThreadException}.
     * {@link #getSecurityFindings(Context)} guards this automatically.
     *
     * @return {@code true} if port 27042 accepted a connection.
     */
    public static boolean isFridaPortOpen() {
        try (Socket socket = new Socket()) {
            // 300 ms is enough on a local loopback interface
            socket.connect(new InetSocketAddress("127.0.0.1", 27042), 300);
            return true; // connection succeeded → Frida is listening
        } catch (IOException ignored) {
            return false; // connection refused → port is closed
        }
    }

    // Call-stack hooking detection

    /**
     * Inspects the current thread's stack trace for class-name prefixes that
     * belong to known hooking frameworks.
     *
     * <p>Unlike package-name detection, this check operates on already-loaded
     * classes inside the running JVM. An attacker cannot rename a class that is
     * already mapped into memory without recompiling the entire framework.
     *
     * <p>Safe to call from any thread.
     *
     * @return {@code true} if a hooking framework class is present in the stack.
     */
    public static boolean isHookingFrameworkInStack() {
        // Scan every live Java thread, not just the calling thread.
        final Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
        for (final StackTraceElement[] stack : allStacks.values()) {
            for (final StackTraceElement frame : stack) {
                final String className = frame.getClassName();
                if (className == null) continue;
                for (final String signature : HOOKING_STACK_SIGNATURES) {
                    if (className.startsWith(signature)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // /proc/self/maps memory inspection

    /**
     * Reads {@code /proc/self/maps} and looks for Frida's injected shared
     * libraries.
     *
     * <p>When Frida injects its agent into a target process, the agent shared
     * object ({@code frida-agent.so} or {@code frida-gadget.so}) appears in the
     * process's memory map regardless of where the Frida server binary lives on
     * disk. This check survives binary renaming, custom server paths, and Frida
     * gadget mode, because the in-memory library name is fixed at compile time.
     *
     * <p>Safe to call from any thread; {@code /proc/self/maps} is always
     * readable within our own process without special permissions.
     *
     * @return {@code true} if a Frida library mapping is found.
     */
    public static boolean isFridaAgentInMemory() {
        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream("/proc/self/maps")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("frida-agent")
                        || line.contains("frida-gadget")
                        || line.contains("linjector")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // /proc/self/maps is always readable in our own process;
            // an IOException here is unexpected but not a security signal.
        }
        return false;
    }

    // ── Unchanged helpers ─────────────────────────────────────────────────────

    public static int getCurrentOSVersion() {
        return Build.VERSION.SDK_INT;
    }

    public static String getCurrentSecurityPatch() {
        return Build.VERSION.SECURITY_PATCH;
    }

    public static boolean isDeveloperToolsEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
    }

    public static boolean isUSBDebuggingEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    public static boolean isWifiDebuggingEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    public static boolean isRoDebuggableEnabled() {
        return "1".equals(readSystemProperty("ro.debuggable"));
    }

    private static String readSystemProperty(String key) {
        if (!ALLOWED_SYSTEM_PROPERTIES.contains(key)) {
            return "";
        }
        String value = "";
        try {
            Process process = Runtime.getRuntime().exec(new String[]{GETPROP, key});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                value = reader.readLine();
            }
            process.destroy();
        } catch (IOException e) {
        }
        return value == null ? "" : value.trim();
    }

    /**
     * @deprecated Use {@link #isHookingPackageInstalled(Context)} instead.
     *             This method is preserved for call-site compatibility but now
     *             delegates to the improved implementation.
     */
    @Deprecated
    public static boolean isHookingFrameworkDetected(Context context) {
        return isHookingPackageInstalled(context);
    }

    public static boolean isBiometricPINReady(Context context) {
        return BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean isBiometricPINOn(Context context) {
        return getBooleanPreference(context, AppSettings.ENABLE_BIOMETRICS, R.bool.enable_biometrics);
    }

    private static boolean getBooleanPreference(Context context, String name, @BoolRes int res) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(name, context.getResources().getBoolean(res));
    }

    // ── Backend helpers (unchanged) ───────────────────────────────────────────

    public void trySecurityInfoUpload() {
        if (xmppConnectionService != null) {
            new Thread(() -> uploadSecurityInfo(xmppConnectionService.getFirstAccount())).start();
        }
    }

    private void uploadSecurityInfo(Account account) {
        if (account == null || securityInfo == null) {
            Log.w(Config.LOGTAG, "Cannot upload Security Hub info: missing account or device info");
            return;
        }
        // The backend keys each entry by the deviceid nested inside securityInfo and merges the
        // fields itself, so this device only ever sends its own single security-info object.
        xmppConnectionService.updateSecHubDB(account, securityInfo.toJsonString());
    }
}
