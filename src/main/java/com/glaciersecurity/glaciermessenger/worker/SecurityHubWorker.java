package com.glaciersecurity.glaciermessenger.worker;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.getPreferences;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.glaciersecurity.glaciermessenger.AppSettings;
import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.SecurityInfo;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.SystemSecurityInfo;
import com.glaciersecurity.glaciermessenger.utils.SecurityHubReportStatus;
import com.glaciersecurity.glaciermessenger.utils.SecurityInfoApiClient;
import com.glaciersecurity.glaciermessenger.utils.WakeLockHelper;
import com.glaciersecurity.glaciermessenger.wireguard.PersistentConnectionProperties;
import com.wireguard.android.backend.GoBackend;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class SecurityHubWorker extends Worker {

    /**
     * Absolute path to the property-reader binary.
     *
     * <p>Invoking it as a bare {@code "getprop"} makes the lookup depend on {@code PATH}
     * as inherited by this process, which is not a trustworthy input for a check whose
     * whole job is to decide whether the device has been tampered with. The AOSP location
     * has been {@code /system/bin/getprop} on every API level this app supports.
     */
    private static final String GETPROP = "/system/bin/getprop";

    public static final String KEY_USERNAME = "security_hub_username";
    public static final String KEY_ORGANIZATION = "security_hub_organization";
    public static final String KEY_DEVICE_ID = "security_hub_device_id";

    private static final Set<String> ALLOWED_SYSTEM_PROPERTIES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("ro.ss.version")));
    private SecurityInfo securityInfo;
    private String latestOs;
    private String latestGlacier;
    private String latestSamsungOS;
    private String latestAlt;
    private String anomalyString;

    private final String username;
    private final String organization;
    private final String deviceId;
    private final WakeLock wakeLock;

    public SecurityHubWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.username = workerParams.getInputData().getString(KEY_USERNAME);
        this.organization = workerParams.getInputData().getString(KEY_ORGANIZATION);
        this.deviceId = workerParams.getInputData().getString(KEY_DEVICE_ID);
        final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = pm != null
                ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SecurityHubWorker.class.getName())
                : null;
    }

    @NonNull
    @Override
    public Result doWork() {
        if (username == null || organization == null || deviceId == null) {
            Log.w(Config.LOGTAG, "Missing account info for SecurityHubWorker");
            return Result.failure();
        }
        WakeLockHelper.acquire(wakeLock);
        try {
            return updateSecurityHub();
        } finally {
            WakeLockHelper.release(wakeLock);
        }
    }

    private Result updateSecurityHub() {
        final Context context = getApplicationContext();
        try {
            securityInfo = new SecurityInfo(deviceId);

            final String deviceStr = Build.MANUFACTURER + " " + Build.MODEL;
            securityInfo.setDevice(deviceStr);

            final String patch = getCurrentSecurityPatch();
            securityInfo.setOsVersion(Build.VERSION.RELEASE);
            securityInfo.setOsPatch(patch);

            String glacierVersion = BuildConfig.VERSION_NAME;
            if (glacierVersion.contains("-")) {
                glacierVersion = glacierVersion.split("-")[0];
            }
            securityInfo.setGlacierVersion(glacierVersion);

            securityInfo.setCompromised(hasAnomalies(context));
            securityInfo.setCompromisedDetail(anomalyString);
            securityInfo.setScreenLock(hasScreenLock(context));
            securityInfo.setApplicationLock(isBiometricPINOn(context));
            securityInfo.setBiometricLock(hasBioLock(context));
            securityInfo.setCoreEnabled(isCoreEnabled(context));

            checkLatestVersions(context);

            return uploadSecurityInfo();
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Failed to update Security Hub", e);
            SecurityHubReportStatus.recordFailure(context, e.getMessage());
            return Result.retry();
        }
    }

    private Result uploadSecurityInfo() throws IOException {
        // The backend keys each entry by the deviceid nested inside securityInfo and merges the
        // fields itself, so this device only ever sends its own single security-info object.
        SecurityInfoApiClient.updateSecurityInfoSync(organization, username, securityInfo.toJsonString());
        SecurityHubReportStatus.recordSuccess(getApplicationContext());
        return Result.success();
    }

    private void checkLatestVersions(Context context) {
        latestGlacier = getPreferences(context).getString("latest_version_glacier_android", "");
        latestSamsungOS = getPreferences(context).getString("latest_version_samsung_os", "");
        latestOs = getPreferences(context).getString("latest_version_android_os", "");
        latestAlt = getPreferences(context).getString("latest_version_altos", "");

        final boolean osOutdated = !isLatestOS();
        final boolean glacierOutdated = !isLatestGlacier();

        securityInfo.setOsOutdated(osOutdated);
        securityInfo.setGlacierOutdated(glacierOutdated);
    }

    private boolean isLatestOS() {
        if (Build.MODEL.contains("Pixelbook")) {
            return true;
        }

        if (Build.MANUFACTURER.equalsIgnoreCase("samsung")) {
            if (latestSamsungOS == null || latestSamsungOS.isEmpty()) {
                return true;
            }
            final ComparableVersion latestCompSamsungOs = new ComparableVersion(latestSamsungOS);
            final ComparableVersion myOs = new ComparableVersion(Build.VERSION.RELEASE);
            return myOs.compareTo(latestCompSamsungOs) >= 0;
        }

        if (latestAlt != null && !latestAlt.isEmpty()) {
            final String myOsBuild = getDeviceBuildVersion();
            final ComparableVersion latestCompAltOs = new ComparableVersion(latestAlt);
            final ComparableVersion myOsB = new ComparableVersion(myOsBuild);
            if (myOsB.compareTo(latestCompAltOs) >= 0) {
                return true;
            }
        }

        if (latestOs != null && !latestOs.isEmpty()) {
            final ComparableVersion latestCompOs = new ComparableVersion(latestOs);
            final String myOsVer = Build.VERSION.RELEASE;
            final ComparableVersion myOs = new ComparableVersion(myOsVer);
            return myOs.compareTo(latestCompOs) >= 0;
        }
        return true;
    }

    private boolean isLatestGlacier() {
        if (latestGlacier != null && !latestGlacier.isEmpty()) {
            final ComparableVersion latestCompGlacier = new ComparableVersion(latestGlacier);
            String versionName = BuildConfig.VERSION_NAME;
            if (versionName.contains("-")) {
                versionName = versionName.split("-")[0];
            }
            final ComparableVersion myVersion = new ComparableVersion(versionName);
            return myVersion.compareTo(latestCompGlacier) >= 0;
        }
        return true;
    }

    private boolean hasAnomalies(Context context) {
        final List<String> findings = SystemSecurityInfo.getSecurityFindings(context);
        anomalyString = findings.isEmpty() ? "Your system is safe" : TextUtils.join("; ", findings);
        return !findings.isEmpty();
    }

    private boolean hasBioLock(Context context) {
        final BiometricManager biometricManager = BiometricManager.from(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final int strongOrWeakAuthenticators =
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK;
            return biometricManager.canAuthenticate(strongOrWeakAuthenticators)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }
        return false;
    }

    private boolean hasScreenLock(Context context) {
        final KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isDeviceSecure();
    }

    private boolean isCoreEnabled(Context context) {
        try {
            PersistentConnectionProperties.getInstance().setBackend(new GoBackend(context));
            return PersistentConnectionProperties.getInstance().isRunningTunnel();
        } catch (Exception e) {
            Log.w(Config.LOGTAG, "Failed to determine core status", e);
            return false;
        }
    }

    public static String getCurrentSecurityPatch() {
        return Build.VERSION.SECURITY_PATCH;
    }

    private static boolean isBiometricPINOn(Context context) {
        return getBooleanPreference(context, AppSettings.ENABLE_BIOMETRICS, R.bool.enable_biometrics);
    }

    private static boolean getBooleanPreference(Context context, String name, @BoolRes int res) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(name, context.getResources().getBoolean(res));
    }

    private String getDeviceBuildVersion() {
        final String key = "ro.ss.version";
        if (!ALLOWED_SYSTEM_PROPERTIES.contains(key)) {
            return "";
        }

        String line = "";
        try {
            final Process ifc = Runtime.getRuntime().exec(new String[]{GETPROP, key});
            try (final BufferedReader bis =
                         new BufferedReader(new InputStreamReader(ifc.getInputStream()))) {
                line = bis.readLine();
            }
            ifc.destroy();
        } catch (IOException e) {
        }
        return line == null ? "" : line.trim();
    }
}
