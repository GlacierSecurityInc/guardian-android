package com.glaciersecurity.glaciermessenger.utils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract.Profile;
import android.provider.Settings;

import com.google.common.base.Strings;

import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

public class PhoneHelper {

    /**
     * Absolute path to the property-reader binary.
     *
     * <p>Invoking it as a bare {@code "getprop"} makes the lookup depend on {@code PATH}
     * as inherited by this process, which is not a trustworthy input for a check whose
     * whole job is to decide whether the device has been tampered with. The AOSP location
     * has been {@code /system/bin/getprop} on every API level this app supports.
     */
    private static final String GETPROP = "/system/bin/getprop";

    @SuppressLint("HardwareIds")
    public static String getAndroidId(final Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    public static Uri getProfilePictureUri(final Context context) {
        if (!QuickConversationsService.isContactListIntegration(context)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED)) {
            return null;
        }
        final String[] projection = new String[]{Profile._ID, Profile.PHOTO_URI};
        try (final Cursor cursor =
                     context.getContentResolver()
                             .query(Profile.CONTENT_URI, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final var photoUri = cursor.getString(1);
                if (Strings.isNullOrEmpty(photoUri)) {
                    return null;
                }
                return Uri.parse(photoUri);
            }
        }
        return null;
    }

    public static boolean isEmulator() {
        // Layer 1: Build.* string checks (original — preserved verbatim)
        if (buildPropertiesIndicateEmulator()) {
            return true;
        }
        // Layer 2: /proc/cpuinfo hardware identifier (kernel-sourced)
        if (cpuInfoIndicatesEmulator()) {
            return true;
        }
        // Layer 3: ro.kernel.qemu system property (kernel command-line)
        if (qemuPropertySet()) {
            return true;
        }
        return false;
    }

    /**
     * Extended variant that also checks {@code ANDROID_ID} (null on most stock emulators).
     * Prefer this overload when a {@link Context} is available.
     */
    @SuppressLint("HardwareIds")
    public static boolean isEmulator(final Context context) {
        if (isEmulator()) {
            return true;
        }
        // Layer 4: ANDROID_ID is null on the stock Android emulator
        if (context != null) {
            final String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) {
                return true;
            }
        }
        return false;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Layer 1 — the original Build.* checks.
     * These are fast (no I/O) but spoofable by modifying {@code /system/build.prop}
     * or using a Magisk props module on a rooted device.
     */
    private static boolean buildPropertiesIndicateEmulator() {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("sdk_gphone64_arm64")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    static boolean cpuInfoIndicatesEmulator() {
        try (final BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("goldfish") || lower.contains("ranchu") || lower.contains("vbox")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // Cannot read /proc/cpuinfo — do not flag as emulator.
        }
        return false;
    }

    /**
     * Layer 3 — queries {@code ro.kernel.qemu} via {@code getprop}.
     *
     * <p>The Android emulator always sets {@code ro.kernel.qemu=1} via the kernel
     * command line. This property is not settable through {@code /system/build.prop}
     * without also modifying kernel boot parameters.
     *
     * @return {@code true} if {@code ro.kernel.qemu=1}; {@code false} on any I/O
     *         error or if the property is absent or set to a value other than {@code "1"}.
     */
    static boolean qemuPropertySet() {
        try {
            final Process process = Runtime.getRuntime().exec(new String[]{GETPROP, "ro.kernel.qemu"});
            try (final BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                final String value = reader.readLine();
                return "1".equals(value != null ? value.trim() : null);
            } finally {
                process.destroy();
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    public static String getVersionName(Context context) {
        final String packageName = context == null ? null : context.getPackageName();
        if (packageName != null) {
            try {
                return context.getPackageManager().getPackageInfo(packageName, 0).versionName;
            } catch (final PackageManager.NameNotFoundException | RuntimeException e) {
                return "unknown";
            }
        } else {
            return "unknown";
        }
    }
}