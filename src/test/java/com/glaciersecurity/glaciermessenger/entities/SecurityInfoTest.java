package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Covers: "Org Security Hub Data", "Updates on Console", "Sec Hub checks/updates".
 *
 * <p>This payload is the device's whole side of the Security Hub contract. A key that goes
 * missing does not fail anywhere on the device -- it shows up as a blank column on the console,
 * which is exactly the kind of thing a manual pass does not catch.
 */
public class SecurityInfoTest {

    private static final List<String> CONTRACT_KEYS =
            Arrays.asList(
                    "device",
                    "deviceid",
                    "glacier_version",
                    "glacier_version_outdated",
                    "os_version",
                    "os_version_outdated",
                    "os_patch",
                    "compromised",
                    "compromised_detail",
                    "screen_lock",
                    "biometric_lock",
                    "app_lock",
                    "core_enabled",
                    "last_updated");

    private static SecurityInfo populated() {
        final SecurityInfo info = new SecurityInfo("device-id-1");
        info.setDevice("Galaxy S24");
        info.setGlacierVersion("7.3");
        info.setGlacierOutdated(false);
        info.setOsVersion("15");
        info.setOsOutdated(true);
        info.setOsPatch("2026-08-01");
        info.setCompromised(false);
        info.setCompromisedDetail("");
        info.setScreenLock(true);
        info.setApplicationLock(true);
        info.setBiometricLock(false);
        info.setCoreEnabled(true);
        return info;
    }

    @Test
    public void everyContractKeyIsPresentEvenWhenTheDeviceCouldNotDetermineIt() throws Exception {
        // Nothing set beyond the device id: JSONObject.put() drops null values, so an
        // undetermined field has to be reported as empty rather than omitted.
        final JSONObject json = new JSONObject(new SecurityInfo("device-id-1").toJsonString());

        for (final String key : CONTRACT_KEYS) {
            assertTrue("missing key: " + key, json.has(key));
        }
        assertEquals(CONTRACT_KEYS.size(), json.length());
        assertEquals("", json.getString("glacier_version"));
        assertEquals("", json.getString("os_patch"));
        assertEquals("", json.getString("compromised_detail"));
    }

    /**
     * The two device fields are crossed relative to their constant names: the id passed to the
     * constructor is reported under "deviceid" and the hardware name under "device". Pinned here
     * because the console reads these by name.
     */
    @Test
    public void reportsTheDeviceIdAndHardwareNameUnderTheKeysTheConsoleReads() throws Exception {
        final JSONObject json = new JSONObject(populated().toJsonString());

        assertEquals("device-id-1", json.getString("deviceid"));
        assertEquals("Galaxy S24", json.getString("device"));
    }

    @Test
    public void reportsFlagsAsJsonBooleansNotStrings() throws Exception {
        final JSONObject json = new JSONObject(populated().toJsonString());

        assertEquals(Boolean.FALSE, json.get("glacier_version_outdated"));
        assertEquals(Boolean.TRUE, json.get("os_version_outdated"));
        assertEquals(Boolean.TRUE, json.get("screen_lock"));
        assertEquals(Boolean.FALSE, json.get("biometric_lock"));
        assertEquals(Boolean.TRUE, json.get("app_lock"));
        assertEquals(Boolean.TRUE, json.get("core_enabled"));
    }

    /**
     * The report time used to be {@code Date.toString()}, whose timezone rendering varies by
     * device and which almost no server-side parser accepts.
     */
    @Test
    public void stampsTheReportTimeAsIso8601InUtc() throws Exception {
        final JSONObject json = new JSONObject(populated().toJsonString());

        final String lastUpdated = json.getString("last_updated");
        assertTrue(
                "not ISO-8601 UTC: " + lastUpdated,
                lastUpdated.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
    }

    @Test
    public void aCopyCarriesEveryFieldForward() {
        final SecurityInfo original = populated();

        final SecurityInfo copy = original.getCopy();

        assertTrue(original.equals(copy));
        assertTrue(copy.equals(original));
        assertTrue(copy.deviceIdEquals(original));
    }

    @Test
    public void aDifferentDeviceIsNotTheSameDevice() {
        final SecurityInfo other = populated();
        other.setDevice("Pixel 9");

        assertTrue(other.deviceIdEquals(populated()));
        assertEquals(false, other.equals(populated()));
    }

    /**
     * equals() is an Object override, so it has to survive the state the constructor leaves
     * behind -- every String field but the device id is still null there. It used to throw from
     * whichever side happened to be the argument, which made it asymmetric as well as unsafe.
     */
    @Test
    public void comparingAgainstAnUndeterminedDeviceIsFalseFromEitherSide() {
        final SecurityInfo fresh = new SecurityInfo("device-id-1");

        assertEquals(false, populated().equals(fresh));
        assertEquals(false, fresh.equals(populated()));
    }

    /**
     * The os patch level is the field most often missing, and it was the one field equals()
     * null-guarded -- by rejecting the comparison outright, so a copy of an instance without one
     * came back unequal to its source while hashCode() still agreed.
     */
    @Test
    public void aCopyWithNoPatchLevelIsStillEqualToItsSource() {
        final SecurityInfo original = populated();
        original.setOsPatch(null);

        final SecurityInfo copy = original.getCopy();

        assertTrue(original.equals(copy));
        assertEquals(original.hashCode(), copy.hashCode());
    }

    @Test
    public void twoUndeterminedDevicesWithTheSameIdAreEqual() {
        final SecurityInfo a = new SecurityInfo("device-id-1");
        final SecurityInfo b = new SecurityInfo("device-id-1");

        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
    }
}
