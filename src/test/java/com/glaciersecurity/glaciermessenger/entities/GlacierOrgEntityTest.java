package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers: "Configure DNS" / "Mobile Threat Defense (DNS)" (Security Hub) and the
 * org-level option flags.
 *
 * <p>The DNS id arrives from the console and is used to build a hostname, so anything outside
 * the allowed alphabet has to be rejected before it gets there.
 */
public class GlacierOrgEntityTest {

    @Test
    public void acceptsTheAlphabetTheConsoleIssues() {
        assertTrue(GlacierOrgEntity.isValidDnsId("abc123"));
        assertTrue(GlacierOrgEntity.isValidDnsId("ABC-123_xyz"));
        assertTrue(GlacierOrgEntity.isValidDnsId("a"));
    }

    @Test
    public void rejectsAnythingThatCouldNotBeAHostnameLabel() {
        assertFalse(GlacierOrgEntity.isValidDnsId(null));
        assertFalse(GlacierOrgEntity.isValidDnsId(""));
        assertFalse(GlacierOrgEntity.isValidDnsId("has space"));
        assertFalse(GlacierOrgEntity.isValidDnsId("dot.separated"));
        assertFalse(GlacierOrgEntity.isValidDnsId("semi;colon"));
        assertFalse(GlacierOrgEntity.isValidDnsId("new\nline"));
    }

    @Test
    public void enforcesTheLengthLimitExactly() {
        final String maximum = repeat('a', 128);

        assertTrue(GlacierOrgEntity.isValidDnsId(maximum));
        assertFalse(GlacierOrgEntity.isValidDnsId(maximum + "a"));
    }

    private static String repeat(final char c, final int times) {
        final StringBuilder builder = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
