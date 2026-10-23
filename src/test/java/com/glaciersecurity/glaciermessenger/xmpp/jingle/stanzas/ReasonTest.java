package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.crypto.axolotl.CryptoFailedException;

import org.junit.Test;

/**
 * Reason is why a call ended, and it travels in both directions: the app maps its own failures
 * onto one of these to tell the peer, and maps the peer's value back to decide what to show the
 * user -- a missed call, a declined one, or an error.
 *
 * <p>The wire format is lower-hyphen ({@code connectivity-error}) and the enum is upper-underscore,
 * so every value is translated on the way in and out. That translation is checked across the whole
 * enum rather than on a sample, because a value that fails to round trip does not throw: it
 * quietly becomes UNKNOWN and the call ends with the wrong explanation.
 *
 * <p>An unparseable value must also land on UNKNOWN rather than propagating, since it arrives from
 * the peer at the point where a session is already being torn down.
 *
 * <p>Two branches of {@code of(RuntimeException)} are not reachable from here: the
 * AbstractContentMap unsupported-transport and unsupported-application exceptions have
 * package-private constructors, so only a test in their own package can raise them.
 */
public class ReasonTest {

    @Test
    public void everyReasonSurvivesTheRoundTripThroughItsWireValue() {
        // The property that matters: whatever this app sends, it can read back.
        for (final Reason reason : Reason.values()) {
            assertThat(Reason.of(reason.toString())).isEqualTo(reason);
        }
    }

    @Test
    public void theWireValueIsLowerHyphen() {
        assertThat(Reason.CONNECTIVITY_ERROR.toString()).isEqualTo("connectivity-error");
        assertThat(Reason.SUCCESS.toString()).isEqualTo("success");
        assertThat(Reason.ALTERNATIVE_SESSION.toString()).isEqualTo("alternative-session");
    }

    @Test
    public void aWireValueIsParsedBackToItsConstant() {
        assertThat(Reason.of("connectivity-error")).isEqualTo(Reason.CONNECTIVITY_ERROR);
        assertThat(Reason.of("decline")).isEqualTo(Reason.DECLINE);
    }

    @Test
    public void anUnrecognisedReasonBecomesUnknownRatherThanThrowing() {
        // This value came from the peer, during a teardown; throwing here would strand the
        // session rather than ending it.
        assertThat(Reason.of("cat-sat-on-keyboard")).isEqualTo(Reason.UNKNOWN);
        assertThat(Reason.of("")).isEqualTo(Reason.UNKNOWN);
        assertThat(Reason.of((String) null)).isEqualTo(Reason.UNKNOWN);
    }

    @Test
    public void theUnderscoreSpellingIsAlsoAccepted() {
        // Not by design so much as by consequence: CaseFormat.LOWER_HYPHEN sees no hyphen, treats
        // the whole string as one word and upper-cases it, which lands back on the enum name. So
        // a peer sending the constant name rather than the wire value is understood anyway.
        // Pinned here so that tightening it later is a deliberate change rather than a surprise.
        assertThat(Reason.of("CONNECTIVITY_ERROR")).isEqualTo(Reason.CONNECTIVITY_ERROR);
        assertThat(Reason.of("connectivity_error")).isEqualTo(Reason.CONNECTIVITY_ERROR);
    }

    @Test
    public void aSecurityExceptionIsReportedAsASecurityError() {
        assertThat(Reason.of(new SecurityException("no"))).isEqualTo(Reason.SECURITY_ERROR);
    }

    @Test
    public void anUnclassifiedRuntimeExceptionIsAnApplicationFailure() {
        assertThat(Reason.of(new IllegalStateException("no")))
                .isEqualTo(Reason.FAILED_APPLICATION);
    }

    @Test
    public void aWrappedExceptionIsClassifiedByItsRootCause() {
        // Failures arrive wrapped in ExecutionException from the futures the call setup runs on,
        // so classifying the wrapper instead of the cause would report everything as a generic
        // application failure.
        final Throwable wrapped = new RuntimeException("outer", new SecurityException("inner"));

        assertThat(Reason.ofThrowable(wrapped)).isEqualTo(Reason.SECURITY_ERROR);
    }

    @Test
    public void aCryptoFailureIsReportedAsASecurityError() {
        assertThat(Reason.ofThrowable(new CryptoFailedException("no key")))
                .isEqualTo(Reason.SECURITY_ERROR);
    }

    @Test
    public void aWrappedCryptoFailureIsAlsoASecurityError() {
        assertThat(
                        Reason.ofThrowable(
                                new RuntimeException("outer", new CryptoFailedException("no key"))))
                .isEqualTo(Reason.SECURITY_ERROR);
    }

    @Test
    public void anUnclassifiedCheckedExceptionIsAnApplicationFailure() {
        assertThat(Reason.ofThrowable(new Exception("something")))
                .isEqualTo(Reason.FAILED_APPLICATION);
    }
}
