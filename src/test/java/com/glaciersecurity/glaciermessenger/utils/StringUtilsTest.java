package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Null-tolerant string comparison used to decide whether a profile field actually changed
 * before it is published back to the server.
 */
public class StringUtilsTest {

    @Test
    public void nullAndEmptyAreTheSameValue() {
        assertThat(StringUtils.equals(null, "")).isTrue();
        assertThat(StringUtils.equals("", null)).isTrue();
        assertThat(StringUtils.equals(null, null)).isTrue();
    }

    @Test
    public void differentValuesAreNotEqual() {
        assertThat(StringUtils.equals("a", "b")).isFalse();
        assertThat(StringUtils.equals(null, "b")).isFalse();
    }

    @Test
    public void changedIsTheNegationOfEquals() {
        assertThat(StringUtils.changed("a", "a")).isFalse();
        assertThat(StringUtils.changed("a", "b")).isTrue();
        assertThat(StringUtils.changed(null, "")).isFalse();
    }

    @Test
    public void whitespaceOnlyBecomesNull() {
        assertThat(StringUtils.nullOnEmpty("   ")).isNull();
        assertThat(StringUtils.nullOnEmpty("")).isNull();
        assertThat(StringUtils.nullOnEmpty(null)).isNull();
    }

    @Test
    public void realContentIsReturnedUntrimmed() {
        assertThat(StringUtils.nullOnEmpty(" hello ")).isEqualTo(" hello ");
    }
}
