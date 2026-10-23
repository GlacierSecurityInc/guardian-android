package com.glaciersecurity.glaciermessenger.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.minidns.dnsname.DnsName;

import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Covers: "DNS", "Configure DNS", "Core/VPN" -- the parts of connection resolution that do
 * not need a network.
 *
 * <p>Nothing here performs a lookup. What is covered is the logic around one: which port implies
 * direct TLS, what counts as a usable hostname, the order candidate endpoints are tried in, and
 * the see-other-host redirect parser.
 */
public class ResolverTest {

    // ── Port policy ───────────────────────────────────────────────────────────

    @Test
    public void onlyTheDirectTlsPortsImplyDirectTls() {
        assertTrue(Resolver.useDirectTls(443));
        assertTrue(Resolver.useDirectTls(5223));
        assertFalse(Resolver.useDirectTls(Resolver.XMPP_PORT_STARTTLS));
        assertFalse(Resolver.useDirectTls(80));
        assertFalse(Resolver.useDirectTls(0));
    }

    @Test
    public void aHardCodedHostIsTrustedAndTakesItsTlsModeFromThePort() {
        final List<Resolver.Result> direct = Resolver.fromHardCoded("example.com", 5223);

        assertEquals(1, direct.size());
        assertEquals(DnsName.from("example.com"), direct.get(0).getHostname());
        assertEquals(5223, direct.get(0).getPort());
        assertTrue(direct.get(0).isDirectTls());
        assertTrue("hard-coded hosts are not DNS answers to authenticate",
                direct.get(0).isAuthenticated());

        assertFalse(Resolver.fromHardCoded("example.com", 5222).get(0).isDirectTls());
    }

    // ── Hostname validation ───────────────────────────────────────────────────

    @Test
    public void acceptsHostnamesDnsCanActuallyCarry() {
        assertFalse(Resolver.invalidHostname("example.com"));
        assertFalse(Resolver.invalidHostname("a.b.c.example.com"));
        assertFalse(Resolver.invalidHostname("xn--bcher-kva.example"));
    }

    /**
     * Note: a null hostname is not covered, because it does not return true -- it throws NPE out
     * of minidns, which this method's catch clause does not cover. Nothing in the app calls
     * invalidHostname with anything, so that is latent rather than a live crash; left as-is
     * rather than encoding the current behaviour as intended.
     */
    @Test
    public void rejectsHostnamesDnsCannot() {
        assertTrue(Resolver.invalidHostname(repeat('a', 64) + ".example.com"));
        assertTrue(Resolver.invalidHostname("example..com"));
    }

    // ── Candidate ordering ────────────────────────────────────────────────────

    /**
     * The order these come back in is the order connection attempts are made, so it decides how
     * fast a connection is established on a network where one family or transport is broken:
     * lower SRV priority first, then direct TLS, then a resolved IP over an unresolved name, and
     * IPv4 before IPv6.
     */
    @Test
    public void candidatesAreTriedInTheIntendedOrder() throws Exception {
        final InetAddress v4 = InetAddress.getByName("192.0.2.1");
        final InetAddress v6 = InetAddress.getByName("2001:db8::1");
        final DnsName host = DnsName.from("example.com");

        final Resolver.Result starttlsV6 = Resolver.Result.createDefault(host, v6, 5222);
        final Resolver.Result starttlsV4 = Resolver.Result.createDefault(host, v4, 5222);
        final Resolver.Result directTlsV4 = Resolver.Result.createDefault(host, v4, 5223);
        final Resolver.Result unresolved = Resolver.Result.createDefault(host, null, 5222);

        final List<Resolver.Result> shuffled =
                new ArrayList<>(Arrays.asList(unresolved, starttlsV6, starttlsV4, directTlsV4));
        shuffled.sort(resultComparator());

        assertEquals(
                Arrays.asList(directTlsV4, starttlsV4, starttlsV6, unresolved), shuffled);
    }

    @Test
    public void theDefaultPortsAreBothOfferedForAResolvedAddress() throws Exception {
        final InetAddress ip = InetAddress.getByName("192.0.2.1");

        final List<Resolver.Result> results =
                Resolver.Result.createWithDefaultPorts(DnsName.from("example.com"), ip);

        assertEquals(2, results.size());
        assertEquals(5222, results.get(0).getPort());
        assertFalse(results.get(0).isDirectTls());
        assertEquals(5223, results.get(1).getPort());
        assertTrue(results.get(1).isDirectTls());
    }

    // ── see-other-host ────────────────────────────────────────────────────────

    @Test
    public void redirectsToABareHostnameKeepingTheOriginalPort() {
        final Resolver.Result redirected = hardCoded(5222).seeOtherHost("other.example.com");

        assertNotNull(redirected);
        assertEquals(DnsName.from("other.example.com"), redirected.getHostname());
        assertNull(redirected.getIp());
        assertEquals(5222, redirected.getPort());
    }

    @Test
    public void redirectsToAHostnameWithAnExplicitPort() {
        final Resolver.Result redirected = hardCoded(5222).seeOtherHost("other.example.com:5333");

        assertNotNull(redirected);
        assertEquals(DnsName.from("other.example.com"), redirected.getHostname());
        assertEquals(5333, redirected.getPort());
    }

    @Test
    public void redirectsToALiteralIpv4Address() {
        final Resolver.Result redirected = hardCoded(5222).seeOtherHost("192.0.2.9:5333");

        assertNotNull(redirected);
        assertTrue(redirected.getIp() instanceof Inet4Address);
        assertEquals(5333, redirected.getPort());
        assertNull(redirected.getHostname());
    }

    /** A bracketed IPv6 literal must not have its last colon read as a port separator. */
    @Test
    public void redirectsToABracketedIpv6AddressWithoutAPort() {
        final Resolver.Result redirected = hardCoded(5222).seeOtherHost("[2001:db8::1]");

        assertNotNull(redirected);
        assertTrue(redirected.getIp() instanceof Inet6Address);
        assertEquals(5222, redirected.getPort());
    }

    @Test
    public void redirectsToABracketedIpv6AddressWithAPort() {
        final Resolver.Result redirected = hardCoded(5222).seeOtherHost("[2001:db8::1]:5333");

        assertNotNull(redirected);
        assertTrue(redirected.getIp() instanceof Inet6Address);
        assertEquals(5333, redirected.getPort());
    }

    @Test
    public void carriesTheTlsModeAcrossARedirectButNotTheTrust() {
        final Resolver.Result redirected = hardCoded(5223).seeOtherHost("other.example.com");

        assertNotNull(redirected);
        assertTrue(redirected.isDirectTls());
        assertFalse("a redirect target is not authenticated", redirected.isAuthenticated());
    }

    @Test
    public void refusesARedirectItCannotMakeSenseOf() {
        final Resolver.Result result = hardCoded(5222);

        assertNull(result.seeOtherHost(""));
        assertNull(result.seeOtherHost("   "));
        assertNull(result.seeOtherHost("other.example.com:notaport"));
        assertNull(result.seeOtherHost(":5333"));
    }

    private static Resolver.Result hardCoded(final int port) {
        return Resolver.fromHardCoded("example.com", port).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Comparator<Resolver.Result> resultComparator() throws Exception {
        // The ordering policy has no public entry point that does not perform a lookup.
        final Field field = Resolver.class.getDeclaredField("RESULT_COMPARATOR");
        field.setAccessible(true);
        return (Comparator<Resolver.Result>) field.get(null);
    }

    private static String repeat(final char c, final int times) {
        final StringBuilder builder = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
