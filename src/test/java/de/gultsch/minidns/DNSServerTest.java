package de.gultsch.minidns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;

/**
 * Covers: "DNS (configured/not configured)", "Configure DNS", "Mobile Threat Defense (DNS)".
 *
 * <p>A misconfigured DNS server object is one of the quieter failure modes: the wrong default
 * port or a hostname paired with a plaintext transport does not fail loudly, it just fails to
 * resolve, and on a device that looks like "no connection".
 */
public class DNSServerTest {

    private static InetAddress ip() throws Exception {
        return InetAddress.getByName("192.0.2.53");
    }

    @Test
    public void eachTransportHasItsIanaDefaultPort() {
        assertEquals(Integer.valueOf(53), Transport.DEFAULT_PORTS.get(Transport.UDP));
        assertEquals(Integer.valueOf(53), Transport.DEFAULT_PORTS.get(Transport.TCP));
        assertEquals(Integer.valueOf(853), Transport.DEFAULT_PORTS.get(Transport.TLS));
        assertEquals(Integer.valueOf(443), Transport.DEFAULT_PORTS.get(Transport.HTTPS));
    }

    @Test
    public void anAddressWithoutAPortTakesTheTransportDefault() throws Exception {
        assertEquals(853, new DNSServer(ip(), Transport.TLS).port);
        assertEquals(443, new DNSServer(ip(), Transport.HTTPS).port);
        assertEquals(53, new DNSServer(ip(), Transport.UDP).port);
    }

    @Test
    public void aPlainAddressIsReachableOverUdpOrTcpOnPort53() throws Exception {
        final DNSServer server = new DNSServer(ip());

        assertEquals(53, server.port);
        assertEquals(Arrays.asList(Transport.UDP, Transport.TCP), server.transports);
        assertNull(server.hostname);
    }

    @Test
    public void anExplicitNullPortFallsBackToZeroRatherThanThrowing() throws Exception {
        assertEquals(0, new DNSServer(ip(), null, Transport.UDP).port);
    }

    /**
     * A hostname is only meaningful where the transport can validate it. Pairing one with UDP or
     * TCP would silently drop the validation it was given for.
     */
    @Test
    public void aHostnameIsRejectedForTransportsThatCannotValidateIt() {
        for (final Transport transport : new Transport[] {Transport.UDP, Transport.TCP}) {
            try {
                new DNSServer("dns.example.com", transport);
                fail("expected " + transport + " to be rejected with a hostname");
            } catch (final IllegalArgumentException expected) {
                // exactly what should happen
            }
        }
    }

    @Test
    public void aHostnameIsAcceptedForTlsBasedTransports() {
        assertEquals(853, new DNSServer("dns.example.com", Transport.TLS).port);
        assertEquals(443, new DNSServer("dns.example.com", Transport.HTTPS).port);
        assertNull(new DNSServer("dns.example.com", Transport.TLS).inetAddress);
    }

    @Test
    public void aSingleTransportServerReportsIt() throws Exception {
        assertEquals(Transport.TLS, new DNSServer(ip(), Transport.TLS).uniqueTransport());
    }

    @Test
    public void aMultiTransportServerHasNoUniqueTransport() throws Exception {
        try {
            new DNSServer(ip()).uniqueTransport();
            fail("expected a UDP+TCP server to have no unique transport");
        } catch (final IllegalArgumentException expected) {
            // Iterables.getOnlyElement on two elements
        }
    }

    @Test
    public void narrowingToOneTransportKeepsTheAddressAndPort() throws Exception {
        final DNSServer both = new DNSServer(ip());

        final DNSServer tcpOnly = both.asUniqueTransport(Transport.TCP);

        assertEquals(Collections.singletonList(Transport.TCP), tcpOnly.transports);
        assertEquals(both.inetAddress, tcpOnly.inetAddress);
        assertEquals(both.port, tcpOnly.port);
        assertEquals(Transport.TCP, tcpOnly.uniqueTransport());
    }

    @Test
    public void narrowingToATransportTheServerDoesNotHaveIsRejected() throws Exception {
        try {
            new DNSServer(ip()).asUniqueTransport(Transport.TLS);
            fail("expected a UDP+TCP server to reject being narrowed to TLS");
        } catch (final IllegalArgumentException expected) {
            // exactly what should happen
        }
    }

    @Test
    public void serversAreComparedByAddressPortAndTransport() throws Exception {
        assertEquals(new DNSServer(ip(), Transport.TLS), new DNSServer(ip(), Transport.TLS));
        assertEquals(
                new DNSServer(ip(), Transport.TLS).hashCode(),
                new DNSServer(ip(), Transport.TLS).hashCode());

        assertNotEquals(new DNSServer(ip(), Transport.TLS), new DNSServer(ip(), Transport.HTTPS));
        assertNotEquals(
                new DNSServer(ip(), Transport.TLS),
                new DNSServer(InetAddress.getByName("192.0.2.54"), Transport.TLS));
    }
}
