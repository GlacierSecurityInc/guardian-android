package de.gultsch.minidns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Covers: "DNS (configured/not configured)", "Mobile Threat Defense (DNS)".
 *
 * <p>DoT is the one connection the app makes before it has a network identity to lose, which
 * makes a silent downgrade there hard to notice: a resolver that answers is indistinguishable
 * from a resolver that answers honestly. These cover the socket configuration that the TLS
 * stack itself enforces during the handshake -- endpoint identification, and the SNI name a
 * named server is verified against. No socket is connected, so they run offline.
 */
public class DNSSocketTest {

    /** The parameters a real socket would start from, as the connect path obtains them. */
    private static SSLParameters socketDefaults() throws IOException {
        try (final SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            return socket.getSSLParameters();
        }
    }

    @Test
    public void aNamedServerIsVerifiedAgainstItsNameNotTheAddressItResolvedTo() throws Exception {
        final SSLParameters parameters =
                DNSSocket.withEndpointIdentification(
                        socketDefaults(), DNSSocket.sniServerNames("dns.example.com"));

        assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm());

        // Without SNI the stack has no name to check the certificate against: the socket is
        // created unconnected, so it carries no peer name of its own and endpoint
        // identification would fall back to the address it dialled.
        final List<SNIServerName> serverNames = parameters.getServerNames();
        assertNotNull("no SNI name was set", serverNames);
        assertEquals(1, serverNames.size());
        assertEquals("dns.example.com", ((SNIHostName) serverNames.get(0)).getAsciiName());
    }

    @Test
    public void anIpAddressedServerGetsEndpointIdentificationAndNoSni() throws Exception {
        final SSLParameters parameters =
                DNSSocket.withEndpointIdentification(
                        socketDefaults(), DNSSocket.sniServerNames(null));

        assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm());
        // RFC 6066 has no room for an address literal in SNI; the stack verifies the
        // iPAddress SAN against the address instead.
        final List<SNIServerName> serverNames = parameters.getServerNames();
        assertTrue(
                "an IP-addressed server must not send SNI",
                serverNames == null || serverNames.isEmpty());
    }

    @Test
    public void endpointIdentificationSurvivesParametersThatAlreadyCarrySettings() throws Exception {
        // getSSLParameters() hands back the socket's configured protocols and cipher suites;
        // turning on endpoint identification must not drop them on the way back in.
        final SSLParameters defaults = socketDefaults();
        final String[] protocols = defaults.getProtocols();

        final SSLParameters parameters =
                DNSSocket.withEndpointIdentification(defaults, Collections.emptyList());

        assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm());
        assertArrayEquals(protocols, parameters.getProtocols());
    }

    @Test
    public void anEmptyHostnameIsTreatedAsAnIpAddressedServer() throws Exception {
        assertTrue(DNSSocket.sniServerNames("").isEmpty());
    }

    @Test
    public void aHostnameThatCannotBecomeAnSniNameFailsClosed() {
        // A DoT server we cannot name is a DoT server we cannot authenticate, so configuration
        // has to fail rather than fall back to an unverified connection.
        for (final String unusable : new String[] {"not a hostname", "dns.example.com."}) {
            try {
                DNSSocket.sniServerNames(unusable);
                fail("expected " + unusable + " to be rejected");
            } catch (final IOException e) {
                assertTrue(
                        "message should name the offending host, was: " + e.getMessage(),
                        e.getMessage().contains(unusable));
            }
        }
    }

    /**
     * A DoT server that fails every time must not cost a descriptor per attempt.
     *
     * <p>DNS queries retry and {@code NetworkDataSource} does not remember that a server
     * failed, so an unreachable or mis-certified Private DNS server is dialled again on every
     * lookup.
     *
     * <p>Scope, measured rather than assumed: for a handshake the TLS stack itself rejects,
     * this holds with or without {@code connectTlsSocket}'s cleanup, because the stack closes
     * the underlying socket on a fatal handshake error -- the delta is 0 either way. What the
     * cleanup is actually for is the paths that come after a *successful* handshake (a leaf
     * that fails hostname verification or {@code validateDoTCertificate}), where the socket is
     * healthy and stays open unless something closes it. Those need a peer whose chain the
     * system trust store accepts, which a unit test cannot stage, so they are not covered here.
     */
    @Test
    public void aRepeatedlyFailingDoTServerDoesNotAccumulateFileDescriptors() throws Exception {
        assumeTrue("descriptor counting needs a Unix OS bean", openFileDescriptorCount() >= 0);

        try (final ServerSocket server = plainServerThatClosesEveryConnection()) {
            final DNSServer dnsServer =
                    new DNSServer(
                            InetAddress.getByName("127.0.0.1"),
                            server.getLocalPort(),
                            Transport.TLS);

            // Warm up: the first attempt loads TLS providers, which opens descriptors of its own.
            attemptAndExpectFailure(dnsServer);
            final long before = openFileDescriptorCount();
            for (int i = 0; i < ATTEMPTS; i++) {
                attemptAndExpectFailure(dnsServer);
            }
            final long leaked = openFileDescriptorCount() - before;

            assertTrue(
                    "descriptors grew by " + leaked + " over " + ATTEMPTS + " failed connects",
                    leaked < ATTEMPTS / 2);
        }
    }

    private static final int ATTEMPTS = 24;

    private static void attemptAndExpectFailure(final DNSServer dnsServer) {
        try {
            DNSSocket.connect(dnsServer).close();
            fail("a plain-TCP peer should not complete a TLS handshake");
        } catch (final IOException expected) {
            // The point of the test is what happens to the socket, not which IOException.
        }
    }

    /** Accepts connections and drops them, so every TLS handshake against it fails. */
    private static ServerSocket plainServerThatClosesEveryConnection() throws IOException {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final Thread thread =
                new Thread(
                        () -> {
                            while (!server.isClosed()) {
                                try (final Socket accepted = server.accept()) {
                                    assertNotNull(accepted);
                                } catch (final IOException e) {
                                    return;
                                }
                            }
                        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    /**
     * Open descriptors for this process, or -1 where that cannot be read.
     *
     * <p>Reflective because unit tests compile against {@code android.jar}, which has no
     * {@code java.lang.management}; the classes are there on the JVM that runs them.
     */
    private static long openFileDescriptorCount() {
        try {
            final Object bean =
                    Class.forName("java.lang.management.ManagementFactory")
                            .getMethod("getOperatingSystemMXBean")
                            .invoke(null);
            final Class<?> unixBean = Class.forName("com.sun.management.UnixOperatingSystemMXBean");
            if (!unixBean.isInstance(bean)) {
                return -1;
            }
            final Method count = unixBean.getMethod("getOpenFileDescriptorCount");
            return (long) count.invoke(bean);
        } catch (final ReflectiveOperationException | ClassCastException e) {
            return -1;
        }
    }
}
