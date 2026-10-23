package com.glaciersecurity.glaciermessenger.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers: "Phone numbers", "Input number", "Add Number" (Phone Module).
 *
 * <p>The cases that matter are the international ones: a number that already carries its own
 * country code must never pick up the North American default.
 */
public class PhoneUtilsTest {

    @Test
    public void normalisesAFormattedNorthAmericanNumberToE164() {
        assertEquals("+12025550142", PhoneUtils.getPhoneNumber("(202) 555-0142"));
        assertEquals("+12025550142", PhoneUtils.getPhoneNumber("202-555-0142"));
        assertEquals("+12025550142", PhoneUtils.getPhoneNumber("12025550142"));
        assertEquals("+12025550142", PhoneUtils.getPhoneNumber("+1 (202) 555-0142"));
    }

    @Test
    public void leavesANumberThatAlreadyCarriesACountryCodeAlone() {
        assertEquals("+442079460142", PhoneUtils.getPhoneNumber("+44 20 7946 0142"));
        assertEquals("+442079460142", PhoneUtils.getPhoneNumber("0044 20 7946 0142"));
    }

    @Test
    public void toleratesNull() {
        assertNull(PhoneUtils.getPhoneNumber(null));
        assertNull(PhoneUtils.getStripPhoneNumber(null));
        assertNull(PhoneUtils.getStripPhoneNumberWithCountryCode(null));
    }

    @Test
    public void stripsOnlyALeadingDefaultCountryCode() {
        assertEquals("2025550142", PhoneUtils.getStripPhoneNumber("+1 (202) 555-0142"));
        assertEquals("2025550142", PhoneUtils.getStripPhoneNumber("(202) 555-0142"));
    }

    @Test
    public void doesNotStripTheCountryCodeOfAnInternationalNumber() {
        assertEquals("+442079460142", PhoneUtils.getStripPhoneNumber("+44 20 7946 0142"));
    }

    @Test
    public void stripWithCountryCodeKeepsInternationalNumbersInE164() {
        assertEquals(
                "+442079460142", PhoneUtils.getStripPhoneNumberWithCountryCode("+44 20 7946 0142"));
        assertEquals(
                "+442079460142",
                PhoneUtils.getStripPhoneNumberWithCountryCode("0044 20 7946 0142"));
        assertEquals("+12025550142", PhoneUtils.getStripPhoneNumberWithCountryCode("2025550142"));
    }

    @Test
    public void reformatsNorthAmericanNumbersForDisplayAndLeavesOthersAsTyped() {
        assertEquals("+1 (202) 555-0142", PhoneUtils.reformatNumber("2025550142"));
        assertEquals("+1 (202) 555-0142", PhoneUtils.reformatNumber("+12025550142"));
        assertEquals("+44 20 7946 0142", PhoneUtils.reformatNumber("+44 20 7946 0142"));
        assertEquals("0044 20 7946 0142", PhoneUtils.reformatNumber("0044 20 7946 0142"));
    }

    @Test
    public void lastFourDigitsFallsBackToTheWholeNumberWhenThereAreNotFour() {
        assertEquals("0142", PhoneUtils.lastFourDigits("+12025550142"));
        assertEquals("12", PhoneUtils.lastFourDigits("12"));
        assertNull(PhoneUtils.lastFourDigits(null));
    }

    @Test
    public void comparesNumbersWrittenDifferently() {
        assertTrue(PhoneUtils.areSameNumber("+1 (202) 555-0142", "2025550142"));
        assertTrue(PhoneUtils.areSameNumber("202-555-0142", "(202) 555-0142"));
        assertFalse(PhoneUtils.areSameNumber("2025550142", "2025550143"));
        assertFalse(PhoneUtils.areSameNumber(null, "2025550142"));
        assertFalse(PhoneUtils.areSameNumber("2025550142", null));
    }
}
