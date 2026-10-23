package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.Config;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The parts of CryptoHelper that carry a security consequence and run on the JVM:
 *
 * <ul>
 *   <li>fingerprint formatting -- what the user compares by eye when verifying an OMEMO key,
 *   <li>SASL escaping -- attacker-controlled text going into an authentication exchange,
 *   <li>cipher suite ordering -- what the app is willing to negotiate over TLS.
 * </ul>
 *
 * <p>The Base64-backed members ({@code random}, {@code getFingerprintCert}) are left out: they
 * go through {@code android.util.Base64}, which is a throwing stub on the unit-test classpath.
 */
public class CryptoHelperTest {

    @Test
    public void bytesToHexIsLowercaseAndZeroPadded() {
        assertThat(CryptoHelper.bytesToHex(new byte[] {0x00, 0x0f, (byte) 0xff, 0x7b}))
                .isEqualTo("000fff7b");
    }

    @Test
    public void hexRoundTrips() {
        final byte[] original = new byte[] {0x00, 0x01, (byte) 0x80, (byte) 0xff, 0x42};
        assertThat(CryptoHelper.hexToBytes(CryptoHelper.bytesToHex(original)))
                .isEqualTo(original);
    }

    @Test
    public void hexToStringDecodesAscii() {
        assertThat(CryptoHelper.hexToString("6775617264696e")).isEqualTo("guardin");
    }

    @Test
    public void concatenateByteArraysJoinsInOrder() {
        assertThat(CryptoHelper.concatenateByteArrays(new byte[] {1, 2}, new byte[] {3}))
                .isEqualTo(new byte[] {1, 2, 3});
    }

    // ---- OMEMO fingerprint display -------------------------------------------------------

    @Test
    public void prettifyFingerprintGroupsAFullFingerprintIntoEights() {
        // A 64-hex-char OMEMO fingerprint, grouped so a human can compare it out loud.
        final String fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertThat(CryptoHelper.prettifyFingerprint(fingerprint))
                .isEqualTo("01234567 89abcdef 01234567 89abcdef 01234567 89abcdef 01234567 89abcdef");
    }

    @Test
    public void prettifyFingerprintLeavesShortInputAlone() {
        // Below 40 characters it is not a fingerprint, so it is passed through untouched rather
        // than being chopped into groups that would look like one.
        assertThat(CryptoHelper.prettifyFingerprint("short")).isEqualTo("short");
    }

    @Test
    public void prettifyFingerprintTurnsNullIntoAnEmptyString() {
        assertThat(CryptoHelper.prettifyFingerprint(null)).isEmpty();
    }

    @Test
    public void prettifyFingerprintCertUsesColonSeparatedOctets() {
        assertThat(CryptoHelper.prettifyFingerprintCert("00aabb")).isEqualTo("00:aa:bb");
    }

    // ---- SASL --------------------------------------------------------------------------

    @Test
    public void saslEscapeReplacesTheTwoDelimiterCharacters() {
        // A comma or an equals sign in a username would otherwise be read as SCRAM syntax.
        assertThat(CryptoHelper.saslEscape("a,b=c")).isEqualTo("a=2Cb=3Dc");
    }

    @Test
    public void saslEscapeDoesNotDoubleEscapeItsOwnOutput() {
        // "=2C" must come out as "=3D2C", not "=2C" again: the '=' is escaped, the digits are not.
        assertThat(CryptoHelper.saslEscape("=2C")).isEqualTo("=3D2C");
    }

    @Test
    public void saslEscapeLeavesOrdinaryTextAlone() {
        assertThat(CryptoHelper.saslEscape("testuser")).isEqualTo("testuser");
    }

    @Test
    public void saslPrepNormalisesCompatibilityCharacters() {
        // NFKC folds the fullwidth "ａ" onto plain "a" so two spellings cannot both authenticate.
        assertThat(CryptoHelper.saslPrep("ａ")).isEqualTo("a");
    }

    // ---- TLS ---------------------------------------------------------------------------

    @Test
    public void weakCipherSuitesAreRemovedEvenWhenThePlatformOffersThem() {
        final String[] platform = {
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "SSL_RSA_WITH_RC4_128_MD5",
            "TLS_RSA_WITH_NULL_SHA256",
            "TLS_ECDH_anon_WITH_AES_128_CBC_SHA",
            "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
            "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
        };
        final String[] ordered = CryptoHelper.getOrderedCipherSuites(platform);
        for (final String suite : ordered) {
            for (final String weak : Config.WEAK_CIPHER_PATTERNS) {
                assertThat(suite).doesNotContain(weak);
            }
        }
        assertThat(Arrays.asList(ordered)).contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @Test
    public void thePreferredCipherSuitesComeFirst() {
        // Order is the negotiated preference. A platform-only suite must not outrank a
        // hand-picked one from Config.ENABLED_CIPHERS.
        final String[] platform = {
            "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        };
        assertThat(CryptoHelper.getOrderedCipherSuites(platform)[0])
                .isEqualTo("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @Test
    public void theFallbackSignallingSuiteIsNeverOffered() {
        // TLS_FALLBACK_SCSV is a signal, not a cipher; sending it as one breaks the handshake.
        final String[] platform = {"TLS_FALLBACK_SCSV", "TLS_RSA_WITH_AES_128_CBC_SHA"};
        assertThat(Arrays.asList(CryptoHelper.getOrderedCipherSuites(platform)))
                .doesNotContain("TLS_FALLBACK_SCSV");
    }

    @Test
    public void aPlatformOfferingNothingUsableYieldsNoSuites() {
        assertThat(CryptoHelper.getOrderedCipherSuites(new String[] {"SSL_RSA_WITH_RC4_128_MD5"}))
                .isEmpty();
    }

    // ---- generated secrets ---------------------------------------------------------------

    @Test
    public void createPasswordIsTwelveCharacters() {
        assertThat(CryptoHelper.createPassword(new SecureRandom())).hasLength(12);
    }

    @Test
    public void createPasswordDrawsOnlyFromItsAlphabet() {
        final String alphabet =
                "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz123456789+-/#$!?";
        final String password = CryptoHelper.createPassword(new SecureRandom());
        for (final char c : password.toCharArray()) {
            assertThat(alphabet.indexOf(c)).isAtLeast(0);
        }
    }

    @Test
    public void uuidPatternAcceptsAVersionFourUuidAndRejectsOthers() {
        assertThat(CryptoHelper.UUID_PATTERN.matcher("7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .matches()).isTrue();
        // Version nibble 1 rather than 4.
        assertThat(CryptoHelper.UUID_PATTERN.matcher("7c9e6679-7425-10de-944b-e07fc1f90ae7")
                .matches()).isFalse();
    }
}
