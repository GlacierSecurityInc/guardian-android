package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Locale;

/**
 * The raw-key encoding, tested away from Keystore and away from SQLCipher.
 *
 * <p>Only the encoding is covered here, and that is the point: {@code x'<64 hex>'} is what tells
 * SQLCipher to use the bytes as the key directly instead of running them through PBKDF2. Get the
 * length or the casing wrong and SQLCipher does not reject it -- it silently treats the string as a
 * <em>passphrase</em> and derives a different key from it, so the database opens fine on the way in
 * and is unreadable by anything that encodes it correctly later. That failure surfaces as data loss,
 * not as an error, which is why the length and alphabet are asserted rather than assumed.
 *
 * <p>Key generation and storage are not exercised here; they need a real Keystore and live in
 * {@code UnifiedPushDatabaseEncryptionTest} on the instrumented tier.
 */
public class DatabasePassphraseEncodingTest {

    @Test
    public void thirtyTwoBytesBecomeSixtyFourHexDigits() {
        final byte[] material = new byte[32];
        assertThat(DatabasePassphrase.toHex(material)).hasLength(64);
    }

    @Test
    public void hexIsLowercaseAndZeroPadded() {
        final byte[] material = {0x00, 0x0f, (byte) 0xa0, (byte) 0xff};
        assertThat(DatabasePassphrase.toHex(material)).isEqualTo("000fa0ff");
    }

    /**
     * Guards against a default-locale {@code String.format}. In Turkish, formatting that uppercases
     * would produce a dotless capital I; the hex here must be stable regardless of device locale.
     */
    @Test
    public void hexDoesNotDependOnTheDefaultLocale() {
        final Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            final byte[] material = {(byte) 0xab, (byte) 0xcd, (byte) 0xef};
            assertThat(DatabasePassphrase.toHex(material)).isEqualTo("abcdef");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void rawKeySpecWrapsSixtyFourHexDigits() {
        final String hex = repeat("ab", 32);
        assertThat(DatabasePassphrase.asRawKeySpec(hex)).isEqualTo("x'" + hex + "'");
    }

    @Test
    public void rawKeySpecRejectsAShortKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabasePassphrase.asRawKeySpec(repeat("ab", 31)));
    }

    @Test
    public void rawKeySpecRejectsALongKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabasePassphrase.asRawKeySpec(repeat("ab", 33)));
    }

    /**
     * Uppercase is the plausible mistake -- plenty of hex helpers emit it -- and SQLCipher would
     * accept the string as a passphrase rather than a raw key, so it has to be rejected here.
     */
    @Test
    public void rawKeySpecRejectsUppercaseHex() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabasePassphrase.asRawKeySpec(repeat("AB", 32)));
    }

    @Test
    public void rawKeySpecRejectsNonHexCharacters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabasePassphrase.asRawKeySpec(repeat("ag", 32)));
    }

    @Test
    public void generatedMaterialRoundTripsIntoARawKeySpec() {
        final byte[] material = new byte[32];
        for (int i = 0; i < material.length; ++i) {
            material[i] = (byte) i;
        }
        final String spec = DatabasePassphrase.asRawKeySpec(DatabasePassphrase.toHex(material));
        assertThat(spec).startsWith("x'");
        assertThat(spec).endsWith("'");
        assertThat(spec).hasLength(64 + 3);
    }

    private static String repeat(final String unit, final int times) {
        final StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; ++i) {
            builder.append(unit);
        }
        return builder.toString();
    }
}
