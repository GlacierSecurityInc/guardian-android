package com.glaciersecurity.glaciermessenger.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers: "Core/VPN", "Import Profiles", "VPN routing (near/far)".
 *
 * <p>Two defaults are applied to a downloaded profile before WireGuard parses it: a CIDR suffix
 * on bare addresses, and a PersistentKeepalive on any peer that does not set one. The keepalive
 * is what keeps a tunnel reachable across a wifi-to-cellular handoff -- without it the server
 * keeps talking to the old endpoint and everything inbound is dropped, which on a device looks
 * like "messages stopped arriving" minutes later rather than like a config problem.
 */
public class ConfigParserTest {

    private static String applyDefaults(final String source) throws Exception {
        return ConfigParser.applyDefaults(source);
    }

    // ── Address normalisation ─────────────────────────────────────────────────

    @Test
    public void givesABareIpv4AddressAHostSuffix() throws Exception {
        final String result = applyDefaults("[Interface]\nAddress = 10.0.0.2\n");

        assertTrue(result, result.contains("Address = 10.0.0.2/32"));
    }

    @Test
    public void givesABareIpv6AddressAHostSuffix() throws Exception {
        final String result = applyDefaults("[Interface]\nAddress = fd00::2\n");

        assertTrue(result, result.contains("Address = fd00::2/128"));
    }

    @Test
    public void leavesAnAddressThatAlreadyCarriesAPrefixAlone() throws Exception {
        final String result = applyDefaults("[Interface]\nAddress = 10.0.0.2/24\n");

        assertTrue(result, result.contains("Address = 10.0.0.2/24"));
        assertFalse(result, result.contains("/24/32"));
    }

    @Test
    public void normalisesEveryAddressInAList() throws Exception {
        final String result = applyDefaults("[Interface]\nAddress = 10.0.0.2, fd00::2, 10.0.0.3/24\n");

        assertTrue(result, result.contains("Address = 10.0.0.2/32,fd00::2/128,10.0.0.3/24"));
    }

    @Test
    public void recognisesTheAddressKeyWhateverItsCase() throws Exception {
        final String result = applyDefaults("[Interface]\naddress=10.0.0.2\n");

        assertTrue(result, result.contains("10.0.0.2/32"));
    }

    // ── Keepalive defaulting ──────────────────────────────────────────────────

    @Test
    public void givesAPeerWithoutAKeepaliveTheDefault() throws Exception {
        final String result = applyDefaults("[Peer]\nPublicKey = abc\nEndpoint = 1.2.3.4:51820\n");

        assertEquals(1, countOccurrences(result, "PersistentKeepalive"));
        assertTrue(result, result.contains("PersistentKeepalive = 25"));
    }

    @Test
    public void leavesAPeerThatSetsItsOwnKeepaliveAlone() throws Exception {
        final String result =
                applyDefaults("[Peer]\nPublicKey = abc\nPersistentKeepalive = 10\n");

        assertEquals(1, countOccurrences(result, "PersistentKeepalive"));
        assertTrue(result, result.contains("PersistentKeepalive = 10"));
        assertFalse(result, result.contains("= 25"));
    }

    @Test
    public void recognisesAnExistingKeepaliveWhateverItsCaseOrSpacing() throws Exception {
        final String result = applyDefaults("[Peer]\nPublicKey = abc\n  persistentkeepalive=10\n");

        assertEquals(1, countOccurrences(result, "eepalive"));
    }

    @Test
    public void givesEveryPeerItsOwnDefault() throws Exception {
        final String result =
                applyDefaults(
                        "[Interface]\nAddress = 10.0.0.2/32\n\n"
                                + "[Peer]\nPublicKey = abc\n\n"
                                + "[Peer]\nPublicKey = def\n");

        assertEquals(2, countOccurrences(result, "PersistentKeepalive = 25"));
    }

    @Test
    public void doesNotGiveTheInterfaceSectionAKeepalive() throws Exception {
        final String result = applyDefaults("[Interface]\nAddress = 10.0.0.2/32\nPrivateKey = k\n");

        assertEquals(0, countOccurrences(result, "PersistentKeepalive"));
    }

    /**
     * The default has to land with the rest of the peer's settings, not after the blank line that
     * separates sections -- otherwise it would read as belonging to whatever follows.
     */
    @Test
    public void placesTheDefaultInsideThePeerSectionNotAfterIt() throws Exception {
        final String result =
                applyDefaults("[Peer]\nPublicKey = abc\n\n[Interface]\nPrivateKey = k\n");

        final int keepalive = result.indexOf("PersistentKeepalive");
        final int nextSection = result.indexOf("[Interface]");
        assertTrue(result, keepalive > 0 && keepalive < nextSection);
    }

    @Test
    public void addsNothingToAProfileWithNoPeer() throws Exception {
        final String result = applyDefaults("[Interface]\nAddress = 10.0.0.2/32\n");

        assertEquals("[Interface]\nAddress = 10.0.0.2/32\n", result);
    }

    @Test
    public void handlesAnEmptyProfile() throws Exception {
        assertEquals("\n", applyDefaults(""));
    }

    @Test
    public void handlesAPeerSectionWithNoSettings() throws Exception {
        final String result = applyDefaults("[Peer]\n");

        assertEquals(0, countOccurrences(result, "PersistentKeepalive"));
    }

    /**
     * The default must sit directly under the first peer's last real line, so it belongs to that
     * peer rather than drifting into the next section.
     */
    @Test
    public void theDefaultLandsOnTheLineAfterThePeersLastSetting() throws Exception {
        final String result =
                applyDefaults("[Peer]\nPublicKey = abc\n\n[Peer]\nPublicKey = def\n");

        final String[] lines = result.split("\n", -1);
        assertEquals(result, "PersistentKeepalive = 25", lines[2]);
    }

    /** AllowedIPs is not an Address line and must not be rewritten as one. */
    @Test
    public void leavesAllowedIpsAlone() throws Exception {
        final String result =
                applyDefaults("[Peer]\nPublicKey = abc\nAllowedIPs = 0.0.0.0/0, ::/0\n");

        assertTrue(result, result.contains("AllowedIPs = 0.0.0.0/0, ::/0"));
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
