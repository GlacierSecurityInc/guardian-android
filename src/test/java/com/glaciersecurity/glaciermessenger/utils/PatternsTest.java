package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * These patterns run over every incoming message body to decide what becomes a tappable link.
 * A false positive turns ordinary text into something a user can tap; a pattern that can
 * backtrack badly turns a message body into a denial of service on the UI thread. The last test
 * is a rough guard against the latter.
 */
public class PatternsTest {

    private static String firstMatch(final Pattern pattern, final String text) {
        final Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    @Test
    public void anXmppUriIsRecognised() {
        assertThat(firstMatch(Patterns.XMPP_PATTERN, "write to xmpp:alice@example.com now"))
                .isEqualTo("xmpp:alice@example.com");
    }

    @Test
    public void anXmppUriKeepsItsQueryComponent() {
        assertThat(firstMatch(Patterns.XMPP_PATTERN, "xmpp:room@conference.example.com?join"))
                .isEqualTo("xmpp:room@conference.example.com?join");
    }

    @Test
    public void aBareAddressIsNotAnXmppUri() {
        // Without the scheme this is just text; linkifying it would make any address tappable.
        assertThat(firstMatch(Patterns.XMPP_PATTERN, "alice@example.com")).isNull();
    }

    @Test
    public void aWebUrlIsRecognised() {
        assertThat(firstMatch(Patterns.WEB_URL, "see https://example.com/page for more"))
                .isEqualTo("https://example.com/page");
    }

    @Test
    public void ordinaryProseIsNotAWebUrl() {
        assertThat(firstMatch(Patterns.WEB_URL, "just some words here")).isNull();
    }

    @Test
    public void anIpv4AddressIsRecognised() {
        assertThat(Patterns.IP_ADDRESS.matcher("192.0.2.1").matches()).isTrue();
    }

    @Test
    public void anOutOfRangeIpv4OctetIsRejected() {
        assertThat(Patterns.IP_ADDRESS.matcher("999.0.2.1").matches()).isFalse();
        assertThat(Patterns.IP_ADDRESS.matcher("192.0.2").matches()).isFalse();
    }

    @Test
    public void anIpv6AddressIsRecognisedInItsBracketedForm() {
        // The pattern requires the brackets, because that is how an IPv6 literal appears in a
        // URL authority; a bare "2001:db8::1" is deliberately not matched.
        assertThat(Patterns.IP6_ADDRESS.matcher("[2001:db8::1]").matches()).isTrue();
        assertThat(Patterns.IP6_ADDRESS.matcher("2001:db8::1").matches()).isFalse();
    }

    @Test
    public void anEmailAddressIsRecognised() {
        assertThat(firstMatch(Patterns.EMAIL_ADDRESS, "mail testuser@example.com please"))
                .isEqualTo("testuser@example.com");
    }

    @Test
    public void aPathologicalBodyDoesNotStallTheMatcher() throws Exception {
        // A body built to trigger catastrophic backtracking. This runs on the UI thread when a
        // message is rendered, so "returns at all" is the requirement.
        final StringBuilder hostile = new StringBuilder("http://");
        for (int i = 0; i < 400; ++i) {
            hostile.append("a.");
        }
        hostile.append('!');
        final String body = hostile.toString();
        assertFindCompletes(Patterns.WEB_URL, body);
        assertFindCompletes(Patterns.XMPP_PATTERN, body);
    }

    /**
     * Runs {@code find()} under a deadline that is actually enforced.
     *
     * <p>Timing the call and asserting afterwards would not work: the failure this guards against
     * is the matcher never returning, and then the assertion is never reached either -- the test
     * worker just hangs. So the match runs on its own thread, and the input is wrapped in a
     * {@link CharSequence} that checks the interrupt flag on every {@code charAt}. That is what
     * makes a runaway matcher cancellable; {@code Future.cancel} alone cannot stop one, because
     * {@code Matcher} never checks for interruption itself.
     */
    private static void assertFindCompletes(final Pattern pattern, final String body)
            throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<Boolean> result =
                    executor.submit(() -> pattern.matcher(new Interruptible(body)).find());
            try {
                result.get(2, TimeUnit.SECONDS);
            } catch (final TimeoutException e) {
                result.cancel(true);
                throw new AssertionError(
                        "matcher did not finish within 2s: " + pattern.pattern(), e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** A CharSequence that aborts the regex engine when its thread is interrupted. */
    private static final class Interruptible implements CharSequence {

        private final CharSequence delegate;

        private Interruptible(final CharSequence delegate) {
            this.delegate = delegate;
        }

        @Override
        public char charAt(final int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("matcher interrupted");
            }
            return delegate.charAt(index);
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public CharSequence subSequence(final int start, final int end) {
            return new Interruptible(delegate.subSequence(start, end));
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
