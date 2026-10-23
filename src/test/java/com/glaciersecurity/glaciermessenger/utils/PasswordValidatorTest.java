package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * The password rule shown to users during sign-up, password change and reset. All three
 * screens read the same validator, so a change here is a change to every one of them.
 */
public class PasswordValidatorTest {

    private static final String VALID = "Guardian2026!x";

    @Test
    public void acceptsAPasswordMeetingEveryRule() {
        assertThat(PasswordValidator.isValid(VALID)).isTrue();
    }

    @Test
    public void rejectsNull() {
        assertThat(PasswordValidator.isValid(null)).isFalse();
    }

    @Test
    public void rejectsEmpty() {
        assertThat(PasswordValidator.isValid("")).isFalse();
    }

    @Test
    public void rejectsOneCharacterShortOfTheMinimum() {
        final String tooShort = "Guardian2!x";
        assertThat(tooShort.length()).isEqualTo(PasswordValidator.MIN_LENGTH - 1);
        assertThat(PasswordValidator.isValid(tooShort)).isFalse();
    }

    @Test
    public void acceptsExactlyTheMinimumLength() {
        final String exact = "Guardian2!xy";
        assertThat(exact.length()).isEqualTo(PasswordValidator.MIN_LENGTH);
        assertThat(PasswordValidator.isValid(exact)).isTrue();
    }

    @Test
    public void rejectsWithoutAnUppercaseLetter() {
        assertThat(PasswordValidator.isValid("guardian2026!x")).isFalse();
    }

    @Test
    public void rejectsWithoutALowercaseLetter() {
        assertThat(PasswordValidator.isValid("GUARDIAN2026!X")).isFalse();
    }

    @Test
    public void rejectsWithoutADigit() {
        assertThat(PasswordValidator.isValid("GuardianGuard!x")).isFalse();
    }

    @Test
    public void rejectsWithoutASpecialCharacter() {
        assertThat(PasswordValidator.isValid("Guardian2026xy")).isFalse();
    }

    @Test
    public void acceptsEverySpecialCharacterTheRequirementsAdvertise() {
        // The requirements string shown to the user promises this exact set works. If a
        // character is dropped from SPECIAL_CHARS the user is told something untrue.
        for (int i = 0; i < PasswordValidator.SPECIAL_CHARS.length(); i++) {
            final char special = PasswordValidator.SPECIAL_CHARS.charAt(i);
            final String password = "Guardian2026" + special;
            assertThat(PasswordValidator.isValid(password)).isTrue();
        }
    }

    @Test
    public void doesNotCountASpaceAsASpecialCharacter() {
        assertThat(PasswordValidator.isValid("Guardian 2026x")).isFalse();
    }
}
