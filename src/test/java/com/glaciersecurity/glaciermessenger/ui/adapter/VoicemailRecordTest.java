package com.glaciersecurity.glaciermessenger.ui.adapter;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.ui.adapter.VoicemailRecord.VM_Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * {@link VoicemailRecord} is the whole model behind the voicemail list: it takes the API's raw
 * strings and turns them into what the row shows. Everything it does is lossy in a way that only
 * shows up as a wrong-looking list -- a duration that reads 01:05 for a 61-minute message, or a
 * list in the wrong order -- so the parsing is worth pinning.
 *
 * <p>Both parsers format through the default time zone and locale, so these tests fix both; that is
 * a statement about the class, not just test hygiene.
 *
 * <p>Plain JUnit: the class is java.text and java.time only.
 */
public class VoicemailRecordTest {

    private static final String SID = "RE00000000000000000000000000000001";
    private static final String CALLER = "+15555550199";
    private static final String CALLEE = "+15555550100";

    private TimeZone originalZone;
    private Locale originalLocale;

    @Before
    public void pinTheEnvironment() {
        originalZone = TimeZone.getDefault();
        originalLocale = Locale.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.US);
    }

    @After
    public void restoreTheEnvironment() {
        TimeZone.setDefault(originalZone);
        Locale.setDefault(originalLocale);
    }

    private static VoicemailRecord record(
            final String duration, final String time, final String status) {
        return record(duration, time, status, SID);
    }

    private static VoicemailRecord record(
            final String duration, final String time, final String status, final String sid) {
        return new VoicemailRecord(duration, "CA01", time, CALLER, sid, CALLEE, status);
    }

    // ---- status -----------------------------------------------------------------------------

    @Test
    public void theThreeKnownStatusesMap() {
        assertThat(record("5", "", "new").getStatus()).isEqualTo(VM_Status.NEW);
        assertThat(record("5", "", "read").getStatus()).isEqualTo(VM_Status.READ);
        assertThat(record("5", "", "deleted").getStatus()).isEqualTo(VM_Status.DELETED);
    }

    /**
     * Anything unrecognised falls back to NEW. That direction is the safe one -- an unknown status
     * shows as unheard rather than silently hiding a message the user has not played.
     */
    @Test
    public void anUnknownOrMissingStatusIsTreatedAsNew() {
        assertThat(record("5", "", "").getStatus()).isEqualTo(VM_Status.NEW);
        assertThat(record("5", "", "archived").getStatus()).isEqualTo(VM_Status.NEW);
        assertThat(record("5", "", null).getStatus()).isEqualTo(VM_Status.NEW);
        assertThat(record("5", "", "READ").getStatus()).isEqualTo(VM_Status.NEW);
    }

    @Test
    public void theStatusLabelsAreTheApiStrings() {
        assertThat(VM_Status.NEW.label).isEqualTo("new");
        assertThat(VM_Status.READ.label).isEqualTo("read");
        assertThat(VM_Status.DELETED.label).isEqualTo("deleted");
    }

    // ---- duration ---------------------------------------------------------------------------

    @Test
    public void aDurationInSecondsBecomesMinutesAndSeconds() {
        assertThat(record("65", "", "new").getDuration()).isEqualTo("01:05");
        assertThat(record("5", "", "new").getDuration()).isEqualTo("00:05");
        assertThat(record("0", "", "new").getDuration()).isEqualTo("00:00");
        assertThat(record("600", "", "new").getDuration()).isEqualTo("10:00");
    }

    /**
     * Pinned as observed behaviour, not endorsed: the output format is "mm:ss", so anything an hour
     * or longer loses the hours entirely and a 61-minute voicemail reads as one minute.
     */
    @Test
    public void aDurationOverAnHourSilentlyLosesTheHours() {
        assertThat(record("3665", "", "new").getDuration()).isEqualTo("01:05");
    }

    @Test
    public void anUnparseableDurationIsLeftAsGiven() {
        assertThat(record("abc", "", "new").getDuration()).isEqualTo("abc");
        assertThat(record("", "", "new").getDuration()).isEmpty();
        assertThat(record(null, "", "new").getDuration()).isNull();
    }

    // ---- timestamp --------------------------------------------------------------------------

    @Test
    public void anIsoTimestampBecomesADisplayDate() {
        assertThat(record("5", "2026-03-14T15:09:26.535Z", "new").getTime())
                .isEqualTo("03/14/2026 03:09 PM");
    }

    @Test
    public void aMidnightTimestampIsTwelveAm() {
        assertThat(record("5", "2026-03-14T00:30:00.000Z", "new").getTime())
                .isEqualTo("03/14/2026 12:30 AM");
    }

    @Test
    public void anUnparseableTimestampIsLeftAsGiven() {
        assertThat(record("5", "not a date", "new").getTime()).isEqualTo("not a date");
        assertThat(record("5", "2026-03-14T15:09:26Z", "new").getTime())
                .isEqualTo("2026-03-14T15:09:26Z");
    }

    /** The parsed Date is never stored, so the sort below has only the display string to work on. */
    @Test
    public void theParsedDateIsNotRetained() {
        assertThat(record("5", "2026-03-14T15:09:26.535Z", "new").getTimeStamp_date()).isNull();
    }

    // ---- identity and ordering --------------------------------------------------------------

    @Test
    public void recordsAreIdentifiedByRecordingSidAlone() {
        final VoicemailRecord one = record("5", "2026-03-14T15:09:26.535Z", "new");
        final VoicemailRecord two = record("600", "2026-01-01T01:01:01.000Z", "read");
        assertThat(one).isEqualTo(two);
        assertThat(one.hashCode()).isEqualTo(two.hashCode());
    }

    @Test
    public void differentRecordingSidsAreDifferentRecords() {
        final VoicemailRecord one = record("5", "2026-03-14T15:09:26.535Z", "new", SID);
        final VoicemailRecord two =
                record("5", "2026-03-14T15:09:26.535Z", "new", "RE0000000000000000000000000000002");
        assertThat(one).isNotEqualTo(two);
    }

    @Test
    public void aRecordEqualsNothingElse() {
        final VoicemailRecord one = record("5", "2026-03-14T15:09:26.535Z", "new");
        assertThat(one.equals(null)).isFalse();
        assertThat(one.equals("RE...")).isFalse();
    }

    @Test
    public void orderingIsCorrectWithinTheSameHalfOfTheDay() {
        final VoicemailRecord earlier = record("5", "2026-03-14T09:00:00.000Z", "new", "RE-a");
        final VoicemailRecord later = record("5", "2026-03-14T11:00:00.000Z", "new", "RE-b");
        assertThat(earlier.compareTo(later)).isEqualTo(-1);
        assertThat(later.compareTo(earlier)).isEqualTo(1);
        assertThat(earlier.compareTo(earlier)).isEqualTo(0);
    }

    /**
     * Pinned as observed behaviour, not endorsed: the compared string is a 12-hour clock, so 09:00
     * AM sorts after 05:00 PM on the same day. Voicemails left in the morning and the afternoon
     * come back interleaved wrongly, which is what a user actually notices.
     */
    @Test
    public void orderingIsWrongAcrossMiddayBecauseTheDisplayStringIsATwelveHourClock() {
        final VoicemailRecord morning = record("5", "2026-03-14T09:00:00.000Z", "new", "RE-am");
        final VoicemailRecord afternoon = record("5", "2026-03-14T17:00:00.000Z", "new", "RE-pm");
        assertThat(morning.compareTo(afternoon)).isEqualTo(1);
    }

    /**
     * Pinned as observed behaviour, not endorsed: compareTo compares the *formatted* "MM/dd/yyyy"
     * string lexicographically, so December 2025 sorts before January 2026 only by accident of the
     * leading digits -- here it sorts after. The list is wrong across any month or year boundary.
     * Any fix should make this test change.
     */
    @Test
    public void orderingIsWrongAcrossAYearBoundaryBecauseItComparesTheDisplayString() {
        final VoicemailRecord december = record("5", "2025-12-31T22:00:00.000Z", "new", "RE-dec");
        final VoicemailRecord january = record("5", "2026-01-01T09:00:00.000Z", "new", "RE-jan");

        assertThat(december.compareTo(january)).isEqualTo(1);

        final List<VoicemailRecord> records = new ArrayList<>(Arrays.asList(december, january));
        Collections.sort(records);
        assertThat(records.get(0)).isSameInstanceAs(january);
    }

    // ---- selection flags --------------------------------------------------------------------

    @Test
    public void selectionFlagsStartClear() {
        final VoicemailRecord one = record("5", "2026-03-14T15:09:26.535Z", "new");
        assertThat(one.isSelected()).isFalse();
        assertThat(one.isDeletePendingView()).isFalse();
        one.setSelected(true);
        one.setDeletePendingView(true);
        assertThat(one.isSelected()).isTrue();
        assertThat(one.isDeletePendingView()).isTrue();
    }
}
