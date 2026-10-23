package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;

/**
 * Timestamps reach the app in at least three shapes depending on which backend produced them.
 * Every one of them has to land on the same instant, because call history and voicemail lists
 * are sorted by the result.
 */
public class DateParserTest {

    /** 2025-07-22 19:36:21 UTC, the moment every case below should resolve to. */
    private static final long EXPECTED_MILLIS = 1753212981000L;

    @Test
    public void parsesIsoInstant() throws ParseException {
        assertThat(DateParser.parseMillis("2025-07-22T19:36:21Z")).isEqualTo(EXPECTED_MILLIS);
    }

    @Test
    public void parsesIsoWithAColonSeparatedOffset() throws ParseException {
        assertThat(DateParser.parseMillis("2025-07-22T15:36:21-04:00"))
                .isEqualTo(EXPECTED_MILLIS);
    }

    @Test
    public void parsesIsoWithACompactOffset() throws ParseException {
        // Some endpoints emit -0400 rather than -04:00; the parser normalises it before
        // handing the string to Instant.
        assertThat(DateParser.parseMillis("2025-07-22T15:36:21-0400")).isEqualTo(EXPECTED_MILLIS);
    }

    @Test
    public void parsesTheJavaToStringShape() throws ParseException {
        assertThat(DateParser.parseMillis("Tue Jul 22 15:36:21 EDT 2025"))
                .isEqualTo(EXPECTED_MILLIS);
    }

    @Test
    public void parsesTheLegacyTwelveHourShapeInLocalTime() throws ParseException {
        final long expected = LocalDateTime.of(2025, Month.JULY, 22, 15, 36)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        assertThat(DateParser.parseMillis("07/22/2025 03:36 PM")).isEqualTo(expected);
    }

    @Test
    public void midnightIsAmNotPm() throws ParseException {
        final long expected = LocalDateTime.of(2025, Month.JULY, 22, 0, 5)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        assertThat(DateParser.parseMillis("07/22/2025 12:05 AM")).isEqualTo(expected);
    }

    @Test
    public void unparseableInputRaisesParseExceptionRatherThanReturningZero() {
        // A timestamp that silently became the epoch would sort to the top of every history
        // list instead of surfacing as an error.
        assertThrows(ParseException.class, () -> DateParser.parse("not a date"));
    }

    @Test
    public void nullInputIsRejectedOutright() {
        assertThrows(IllegalArgumentException.class, () -> DateParser.parse(null));
    }
}
