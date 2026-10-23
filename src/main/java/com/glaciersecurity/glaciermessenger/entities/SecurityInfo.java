package com.glaciersecurity.glaciermessenger.entities;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Locale;
import java.util.TimeZone;

public class SecurityInfo {
    private static final String DEVICE_ID = "device";
    private static final String DEVICE = "deviceid";
    private static final String GLACIER_VERSION = "glacier_version";
    private static final String GLACIER_VERSION_OUTDATED = "glacier_version_outdated";
    private static final String OS_VERSION = "os_version";
    private static final String OS_VERSION_OUTDATED = "os_version_outdated";
    private static final String OS_PATCH = "os_patch";
    private static final String COMPROMISED = "compromised";
    private static final String COMPROMISED_DETAIL = "compromised_detail";
    private static final String SCREEN_LOCK = "screen_lock";
    private static final String BIOMETRIC_LOCK = "biometric_lock";
    private static final String CORE_ENABLED = "core_enabled";
    private static final String LAST_UPDATED = "last_updated";
    private static final String LAST_UPDATED_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    protected String sDeviceId;
    protected String sDevice;
    protected String sGlacierVersion;
    protected boolean sGlacierOutdated;
    protected String sOsVersion;
    protected boolean sOsOutdated;
    protected String sOsPatch;
    protected boolean sCompromised;
    protected String sCompromisedDetail;
    protected boolean sScreenLock;
    protected boolean sApplicationLock;
    protected boolean sBiometricLock;
    protected boolean sCoreEnabled;
    protected String sLastUpdated;


    public SecurityInfo(String deviceid) {
        sDeviceId = deviceid;
    }

    public String toJsonString() {
        try {
            final JSONObject json = new JSONObject();
            json.put(DEVICE, orEmpty(getDeviceId()));
            json.put(DEVICE_ID, orEmpty(getDevice()));
            json.put(GLACIER_VERSION, orEmpty(getGlacierVersion()));
            json.put(GLACIER_VERSION_OUTDATED, getGlacierOutdated());
            json.put(OS_VERSION, orEmpty(getOsVersion()));
            json.put(OS_VERSION_OUTDATED, getOsOutdated());
            json.put(OS_PATCH, orEmpty(getOsPatch()));
            json.put(COMPROMISED, getCompromised());
            json.put(COMPROMISED_DETAIL, orEmpty(getCompromisedDetail()));
            json.put(SCREEN_LOCK, getScreenLock());
            json.put(BIOMETRIC_LOCK, getBiometricLock());
            json.put("app_lock", getApplicationLock());
            json.put(CORE_ENABLED, getCoreEnabled());
            json.put(LAST_UPDATED, setLastUpdated());
            return json.toString();
        } catch (JSONException e) {
            Log.e(Config.LOGTAG, "Failed to build Security Hub JSON payload", e);
            return "{}";
        }
    }

    /**
     * JSONObject.put() removes the key when handed a null value, so any field the device could
     * not determine vanished from the payload rather than being reported as empty. Every key
     * here is part of the security-info contract, so keep the shape fixed.
     */
    private static String orEmpty(final String value) {
        return value == null ? "" : value;
    }

    public String getDeviceId() {
        return sDeviceId;
    }

    public String getDevice() {
        return sDevice;
    }

    public void setDevice(String device) {
        sDevice = device;
    }

    public String getGlacierVersion() {
        return sGlacierVersion;
    }

    public void setGlacierVersion(String gversion) {
        sGlacierVersion = gversion;
    }

    public boolean getGlacierOutdated() {
        return sGlacierOutdated;
    }

    public void setGlacierOutdated(boolean outdated) {
        sGlacierOutdated = outdated;
    }

    public String getOsVersion() {
        return sOsVersion;
    }

    public void setOsVersion(String version) {
        sOsVersion = version;
    }

    public boolean getOsOutdated() {
        return sOsOutdated;
    }

    public void setOsOutdated(boolean outdated) {
        sOsOutdated = outdated;
    }

    public String getOsPatch() {
        return sOsPatch;
    }

    public void setOsPatch(String patch) {
        sOsPatch = patch;
    }

    public boolean getCompromised() {
        return sCompromised;
    }

    public void setCompromised(boolean compromised) {
        sCompromised = compromised;
    }

    public String getCompromisedDetail() {
        return sCompromisedDetail;
    }

    public void setCompromisedDetail(String detail) {
        sCompromisedDetail = detail;
    }

    public boolean getScreenLock() {
        return sScreenLock;
    }

    public void setScreenLock(boolean screenlock) {
        sScreenLock = screenlock;
    }

    public boolean getApplicationLock() {
        return sApplicationLock;
    }

    public void setApplicationLock(boolean applock) {
        sApplicationLock = applock;
    }

    public boolean getBiometricLock() {
        return sBiometricLock;
    }

    public void setBiometricLock(boolean biolock) {
        sBiometricLock = biolock;
    }

    public boolean getCoreEnabled() {
        return sCoreEnabled;
    }

    /**
     * Stamps and returns the report time as ISO-8601 in UTC, e.g. {@code 2026-09-01T13:45:02Z}.
     *
     * <p>This was {@code Date.toString()}, which renders as
     * {@code "Tue Sep 01 09:45:02 GMT-04:00 2026"} — a shape whose timezone rendering varies by
     * device and which almost no server-side date parser accepts, so the report time the console
     * displayed did not reliably match when the device actually reported.
     */
    public String setLastUpdated() {
        final SimpleDateFormat iso8601 = new SimpleDateFormat(LAST_UPDATED_FORMAT, Locale.US);
        iso8601.setTimeZone(TimeZone.getTimeZone("UTC"));
        sLastUpdated = iso8601.format(new Date());
        return sLastUpdated;
    }

    public void setCoreEnabled(boolean coreenabled) {
        sCoreEnabled = coreenabled;
    }

    public SecurityInfo getCopy() {
        SecurityInfo newSecinfo = new SecurityInfo(getDeviceId());
        newSecinfo.setDevice(getDevice());
        newSecinfo.setGlacierVersion(getGlacierVersion());
        newSecinfo.setGlacierOutdated(getGlacierOutdated());
        newSecinfo.setOsVersion(getOsVersion());
        newSecinfo.setOsOutdated(getOsOutdated());
        newSecinfo.setOsPatch(getOsPatch());
        newSecinfo.setCompromised(getCompromised());
        newSecinfo.setCompromisedDetail(getCompromisedDetail());
        newSecinfo.setScreenLock(getScreenLock());
        newSecinfo.setApplicationLock(getApplicationLock());
        newSecinfo.setBiometricLock(getBiometricLock());
        newSecinfo.setCoreEnabled(getCoreEnabled());
        newSecinfo.setLastUpdated();
        return newSecinfo;
    }

    public boolean deviceIdEquals (SecurityInfo sinfo) {
        return sinfo.getDeviceId().equals(this.sDeviceId);
    }

    /**
     * Every String field here is nullable -- the constructor sets only the device id, and the
     * rest stay null until the device manages to determine them, which is why the JSON payload
     * needs {@link #orEmpty}. Comparing them with bare {@code getX().equals(..)} threw from
     * inside {@code equals} whenever the argument was the incompletely-populated side, and the
     * lone {@code getOsPatch() != null} guard made a null patch level unequal to itself.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SecurityInfo sinfo)) {
            return false;
        }
        return Objects.equals(sinfo.getDeviceId(), this.sDeviceId) &&
                Objects.equals(sinfo.getDevice(), this.sDevice) &&
                Objects.equals(sinfo.getGlacierVersion(), this.sGlacierVersion) &&
                sinfo.getGlacierOutdated() == this.sGlacierOutdated &&
                Objects.equals(sinfo.getOsVersion(), this.sOsVersion) &&
                sinfo.getOsOutdated() == this.sOsOutdated &&
                Objects.equals(sinfo.getOsPatch(), this.sOsPatch) &&
                sinfo.getCompromised() == this.sCompromised &&
                Objects.equals(sinfo.getCompromisedDetail(), this.sCompromisedDetail) &&
                sinfo.getScreenLock() == this.sScreenLock &&
                sinfo.getApplicationLock() == this.sApplicationLock &&
                sinfo.getBiometricLock() == this.sBiometricLock &&
                sinfo.getCoreEnabled() == this.sCoreEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sDeviceId, sDevice, sGlacierVersion, sGlacierOutdated, sOsVersion,
                sOsOutdated, sOsPatch, sCompromised, sCompromisedDetail, sScreenLock,
                sApplicationLock, sBiometricLock, sCoreEnabled);
    }
}
