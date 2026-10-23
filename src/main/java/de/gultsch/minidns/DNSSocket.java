package de.gultsch.minidns;

import com.glaciersecurity.glaciermessenger.utils.Log;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import com.glaciersecurity.glaciermessenger.Config;

import org.conscrypt.OkHostnameVerifier;
import org.minidns.dnsmessage.DnsMessage;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class DNSSocket implements Closeable {

    public static final int QUERY_TIMEOUT = 5_000;

    private final Semaphore semaphore = new Semaphore(1);
    private final Map<Integer, SettableFuture<DnsMessage>> inFlightQueries = new HashMap<>();
    private final Socket socket;
    private final DataInputStream dataInputStream;
    private final DataOutputStream dataOutputStream;

    private DNSSocket(
            final Socket socket,
            final DataInputStream dataInputStream,
            final DataOutputStream dataOutputStream) {
        this.socket = socket;
        this.dataInputStream = dataInputStream;
        this.dataOutputStream = dataOutputStream;
        new Thread(this::readDNSMessages).start();
    }

    private void readDNSMessages() {
        try {
            while (socket.isConnected()) {
                final DnsMessage response = readDNSMessage();
                final SettableFuture<DnsMessage> future;
                synchronized (inFlightQueries) {
                    future = inFlightQueries.remove(response.id);
                }
                if (future != null) {
                    future.set(response);
                } else {
                    Log.e(Config.LOGTAG, "no in flight query found for response id " + response.id);
                }
            }
            evictInFlightQueries(new EOFException());
        } catch (final IOException e) {
            evictInFlightQueries(e);
        }
    }

    private void evictInFlightQueries(final Exception e) {
        synchronized (inFlightQueries) {
            final Iterator<Map.Entry<Integer, SettableFuture<DnsMessage>>> iterator =
                    inFlightQueries.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Integer, SettableFuture<DnsMessage>> entry = iterator.next();
                entry.getValue().setException(e);
                iterator.remove();
            }
        }
    }

    private static DNSSocket of(final Socket socket) throws IOException {
        final DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
        final DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
        return new DNSSocket(socket, dataInputStream, dataOutputStream);
    }

    public static DNSSocket connect(final DNSServer dnsServer) throws IOException {
        return switch (dnsServer.uniqueTransport()) {
            case TCP -> connectTcpSocket(dnsServer);
            case TLS -> connectTlsSocket(dnsServer);
            default -> throw new IllegalStateException("This is not a socket based transport");
        };
    }

    private static DNSSocket connectTcpSocket(final DNSServer dnsServer) throws IOException {
        Preconditions.checkArgument(dnsServer.uniqueTransport() == Transport.TCP);
        final SocketAddress socketAddress =
                new InetSocketAddress(dnsServer.inetAddress, dnsServer.port);
        final Socket socket = new Socket();
        socket.connect(socketAddress, QUERY_TIMEOUT / 2);
        socket.setSoTimeout(QUERY_TIMEOUT);
        return DNSSocket.of(socket);
    }

    private static DNSSocket connectTlsSocket(final DNSServer dnsServer) throws IOException {
        Preconditions.checkArgument(dnsServer.uniqueTransport() == Transport.TLS);
        final SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        // Build the SNI name before the socket exists so an unusable hostname
        // cannot leak a file descriptor.
        final List<SNIServerName> serverNames = sniServerNames(dnsServer.hostname);
        final SSLSocket sslSocket = (SSLSocket) factory.createSocket();
        // Everything from here to a handed-off DNSSocket can throw -- a connect timeout, a
        // handshake the stack itself rejects now that endpoint identification is on, a leaf
        // that fails the second pass -- and DNS queries retry, so a server that fails every
        // time would otherwise accumulate one descriptor per attempt until the finalizer runs.
        boolean handedOff = false;
        try {
            sslSocket.setSSLParameters(
                    withEndpointIdentification(sslSocket.getSSLParameters(), serverNames));
            if (Strings.isNullOrEmpty(dnsServer.hostname)) {
                // IP-only DoT server: connect and handshake, then fully validate the cert chain.
                final SocketAddress socketAddress =
                        new InetSocketAddress(dnsServer.inetAddress, dnsServer.port);
                sslSocket.connect(socketAddress, QUERY_TIMEOUT / 2);
                sslSocket.setSoTimeout(QUERY_TIMEOUT);
                sslSocket.startHandshake();
                try {
                    validateDoTCertificate(sslSocket.getSession(), dnsServer.inetAddress);
                } catch (final CertificateException e) {
                    throw new IOException(
                            "DoT TLS certificate validation failed: " + e.getMessage(), e);
                }
            } else {
                // Hostname-based DoT server: the stack verifies the name during the handshake;
                // this is the second, explicit pass over the leaf.
                final SocketAddress socketAddress =
                        new InetSocketAddress(dnsServer.hostname, dnsServer.port);
                sslSocket.connect(socketAddress, QUERY_TIMEOUT / 2);
                sslSocket.setSoTimeout(QUERY_TIMEOUT);
                sslSocket.startHandshake();
                final SSLSession session = sslSocket.getSession();
                final Certificate[] peerCertificates = session.getPeerCertificates();
                if (peerCertificates.length == 0
                        || !(peerCertificates[0] instanceof X509Certificate certificate)) {
                    throw new IOException("Peer did not provide X509 certificates");
                }
                if (!OkHostnameVerifier.strictInstance().verify(dnsServer.hostname, certificate)) {
                    throw new SSLPeerUnverifiedException("Peer did not provide valid certificates");
                }
            }
            // Past this point the reader thread owns the socket and closes it.
            final DNSSocket dnsSocket = DNSSocket.of(sslSocket);
            handedOff = true;
            return dnsSocket;
        } finally {
            if (!handedOff) {
                closeQuietly(sslSocket);
            }
        }
    }

    /**
     * Returns {@code sslParameters} with JSSE endpoint identification (RFC 2818 hostname/IP
     * verification) turned on; the caller applies it to the socket before the handshake runs.
     *
     * <p>For a named server this is also what makes the hostname travel as an SNI extension.
     * The socket is created unconnected so the connect timeout can be applied, which leaves it
     * with no peer name of its own: nothing was offered to the server, and a DoT server that
     * selects a certificate by SNI answers with its default one instead. For an IP-addressed
     * server no SNI is sent -- RFC 6066 has no room for a literal -- and the stack verifies
     * against the address.
     *
     * <p>The verification itself is defence in depth rather than a fix for an open hole. The
     * checks that run after the handshake ({@link OkHostnameVerifier} for named servers,
     * {@link #validateDoTCertificate} for IP-addressed ones) already established chain and
     * identity before any query could be written, and they stay. What moves is where identity
     * is enforced: in the stack, where a later refactor or a new caller of {@link #of} cannot
     * skip past it, rather than in app code after {@code startHandshake()} has returned.
     *
     * <p>The result has to be handed to {@code SSLSocket#setSSLParameters} at the socket's own
     * call site: that is both where it takes effect and what CodeQL
     * {@code java/unsafe-cert-trust} looks for.
     */
    // Package-private for DNSSocketTest.
    static SSLParameters withEndpointIdentification(
            final SSLParameters sslParameters, final List<SNIServerName> serverNames) {
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!serverNames.isEmpty()) {
            sslParameters.setServerNames(serverNames);
        }
        return sslParameters;
    }

    /**
     * Returns the SNI server names to offer for {@code hostname}, or an empty list for an
     * IP-addressed server.
     *
     * @throws IOException if the hostname cannot be represented as an SNI name; a DoT server we
     *     cannot name is one we cannot authenticate, so this fails closed.
     */
    // Package-private for DNSSocketTest.
    static List<SNIServerName> sniServerNames(final String hostname) throws IOException {
        if (Strings.isNullOrEmpty(hostname)) {
            return Collections.emptyList();
        }
        try {
            return Collections.singletonList(new SNIHostName(hostname));
        } catch (final IllegalArgumentException e) {
            throw new IOException("Not a usable DoT server name: " + hostname, e);
        }
    }

    private static void closeQuietly(final Socket socket) {
        try {
            socket.close();
        } catch (final IOException ignored) {

        }
    }

    /**
     * Validates the TLS certificate chain presented by an IP-addressed DoT server.
     *
     * <p>Checks performed:
     * <ol>
     *   <li>Chain is non-empty and every certificate is X.509.</li>
     *   <li>No certificate in the chain is self-signed — rejects self-signed leaves
     *       <em>and</em> self-signed intermediates/roots.</li>
     *   <li>The leaf certificate contains an iPAddress SAN matching the server's
     *       {@link InetAddress}.</li>
     * </ol>
     *
     * @param session       the established {@link SSLSession} after handshake.
     * @param serverAddress the IP address the socket connected to.
     * @throws CertificateException if any check fails; the caller must close the socket.
     */
    private static void validateDoTCertificate(
            final SSLSession session, final InetAddress serverAddress) throws CertificateException {
        final Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (final SSLPeerUnverifiedException e) {
            throw new CertificateException("Could not retrieve peer certificates", e);
        }

        if (chain == null || chain.length == 0) {
            throw new CertificateException("Empty certificate chain from DoT server");
        }

        final X509Certificate[] x509Chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            if (!(chain[i] instanceof X509Certificate)) {
                throw new CertificateException("Non-X509 certificate at chain index " + i);
            }
            x509Chain[i] = (X509Certificate) chain[i];
        }

        // independently validate the chain against system trust anchors using a
        // TrustManager built from a fresh TrustManagerFactory, rather than relying solely on
        // the implicit validation SSLSocketFactory.getDefault() already performed during the
        // handshake. This keeps the check correct even if some unrelated code elsewhere in the
        // app ever repoints the JVM-wide default SSLContext/TrustManager.
        validateAgainstSystemTrustAnchors(x509Chain);

        // Reject self-signed certificates at every position in the chain.
        for (final X509Certificate cert : x509Chain) {
            if (isSelfSigned(cert)) {
                throw new CertificateException(
                        "Self-signed certificate rejected (subject: " + cert.getSubjectDN() + ")");
            }
        }

        // Verify the leaf has an IP SAN matching the server's address.
        final X509Certificate leaf = x509Chain[0];
        final String expectedIp = serverAddress.getHostAddress();
        if (!hasMatchingIpSan(leaf, expectedIp)) {
            throw new CertificateException(
                    "DoT certificate has no IP SAN for " + expectedIp
                            + " (subject: " + leaf.getSubjectDN() + ")");
        }
    }

    private static void validateAgainstSystemTrustAnchors(final X509Certificate[] chain)
            throws CertificateException {
        final TrustManagerFactory trustManagerFactory;
        try {
            trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
        } catch (final NoSuchAlgorithmException | KeyStoreException e) {
            throw new CertificateException(
                    "Unable to build trust manager for DoT certificate validation", e);
        }
        for (final TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager x509TrustManager) {
                x509TrustManager.checkServerTrusted(
                        chain, chain[0].getPublicKey().getAlgorithm());
                return;
            }
        }
        throw new CertificateException(
                "No X509TrustManager available to validate DoT certificate chain");
    }

    /**
     * Returns {@code true} if the certificate contains an iPAddress SAN (type 7)
     * that matches {@code ip} (case-insensitive).
     */
    private static boolean hasMatchingIpSan(
            final X509Certificate cert, final String ip) throws CertificateException {
        try {
            final Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return false;
            for (final List<?> san : sans) {
                // SAN GeneralName type 7 = iPAddress
                if (san.size() >= 2 && Integer.valueOf(7).equals(san.get(0))) {
                    if (ip.equalsIgnoreCase(String.valueOf(san.get(1)))) {
                        return true;
                    }
                }
            }
        } catch (final CertificateParsingException e) {
            throw new CertificateException("Failed to parse SANs from DoT certificate", e);
        }
        return false;
    }

    /**
     * Returns {@code true} if the certificate is self-signed, i.e. its signature
     * can be verified with its own public key (subject == issuer).
     */
    private static boolean isSelfSigned(final X509Certificate cert) {
        try {
            cert.verify(cert.getPublicKey());
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    public DnsMessage query(final DnsMessage query) throws IOException, InterruptedException {
        try {
            return queryAsync(query).get(QUERY_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException(e);
            }
        } catch (final TimeoutException e) {
            throw new IOException(e);
        }
    }

    public ListenableFuture<DnsMessage> queryAsync(final DnsMessage query)
            throws InterruptedException, IOException {
        final SettableFuture<DnsMessage> responseFuture = SettableFuture.create();
        synchronized (this.inFlightQueries) {
            this.inFlightQueries.put(query.id, responseFuture);
        }
        this.semaphore.acquire();
        try {
            query.writeTo(this.dataOutputStream);
            this.dataOutputStream.flush();
        } finally {
            this.semaphore.release();
        }
        return responseFuture;
    }

    private DnsMessage readDNSMessage() throws IOException {
        final int length = this.dataInputStream.readUnsignedShort();
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            // InputStream#read returns -1 on premature EOF; without this check
            // a peer that closes early drives `read` negative, and the next call throws an
            // uncaught IndexOutOfBoundsException instead of a normal, catchable IOException.
            final int count = this.dataInputStream.read(data, read, length - read);
            if (count < 0) {
                throw new EOFException(
                        "DNS/TCP peer closed connection after "
                                + read
                                + " of "
                                + length
                                + " expected bytes");
            }
            read += count;
        }
        return CachingNetworkDataSource.readDNSMessage(data);
    }

    @Override
    public void close() throws IOException {
        this.socket.close();
    }

    public void closeQuietly() {
        try {
            this.socket.close();
        } catch (final IOException ignored) {

        }
    }
}
