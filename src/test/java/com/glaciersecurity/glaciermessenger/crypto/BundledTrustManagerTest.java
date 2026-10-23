package com.glaciersecurity.glaciermessenger.crypto;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.testutil.TestCertificates;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * BundledTrustManager is the narrowing half of the TLS setup: it trusts a keystore shipped inside
 * the app and nothing else. {@link CombiningTrustManager} then puts it in front of the platform
 * default, so between them the app trusts the system store plus one bundled CA.
 *
 * <p>Narrowing is the property worth asserting. A trust manager built from a bundled keystore must
 * accept what that keystore holds and refuse everything else -- if it quietly fell back to the
 * platform store it would still look like it worked, because the certificates the app actually
 * talks to are publicly signed anyway. The failure would only show up as the bundled CA being
 * pointlessly shipped.
 *
 * <p>The keystore here is built in memory and serialised rather than read from a fixture file, so
 * there is nothing to regenerate and the contents are visible in the test.
 *
 * <p>The last test pins a fail-open that is worth knowing about. See its comment.
 */
public class BundledTrustManagerTest {

    private static final String PASSWORD = "test-password";

    /** A serialised keystore holding exactly the given certificates as trust anchors. */
    private static InputStream keyStoreHolding(final X509Certificate... certificates)
            throws Exception {
        final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        int index = 0;
        for (final X509Certificate certificate : certificates) {
            keyStore.setCertificateEntry("anchor-" + index++, certificate);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, PASSWORD.toCharArray());
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static X509TrustManager bundling(final X509Certificate... certificates)
            throws Exception {
        return BundledTrustManager.builder()
                .loadKeyStore(keyStoreHolding(certificates), PASSWORD)
                .build();
    }

    @Test
    public void theBundledCertificateIsTheOnlyAcceptedIssuer() throws Exception {
        // The whole point: one bundled anchor, not the platform's several hundred.
        final X509Certificate bundled = TestCertificates.parse(TestCertificates.NO_SAN_PEM);

        final X509Certificate[] issuers = bundling(bundled).getAcceptedIssuers();

        assertThat(issuers).hasLength(1);
        assertThat(issuers[0]).isEqualTo(bundled);
    }

    @Test
    public void itTrustsFarLessThanThePlatformDoes() throws Exception {
        // Stated as a relationship rather than a number, because the platform's count varies by
        // JDK and would make this test a maintenance chore for no benefit.
        final int platform = TrustManagers.createDefaultTrustManager().getAcceptedIssuers().length;
        final int bundled =
                bundling(TestCertificates.parse(TestCertificates.NO_SAN_PEM))
                        .getAcceptedIssuers()
                        .length;

        assertThat(platform).isGreaterThan(bundled);
    }

    @Test
    public void aCertificateOutsideTheBundleIsRefused() throws Exception {
        // The narrowing, from the other side. This certificate is a perfectly well-formed one --
        // it is simply not the one that was bundled.
        final X509TrustManager trustManager =
                bundling(TestCertificates.parse(TestCertificates.NO_SAN_PEM));
        final X509Certificate[] chain = {TestCertificates.parse(TestCertificates.WITH_SANS_PEM)};

        assertThrows(
                CertificateException.class, () -> trustManager.checkServerTrusted(chain, "RSA"));
    }

    @Test
    public void severalBundledCertificatesAreAllAnchors() throws Exception {
        final X509TrustManager trustManager =
                bundling(
                        TestCertificates.parse(TestCertificates.NO_SAN_PEM),
                        TestCertificates.parse(TestCertificates.WITH_SANS_PEM));

        assertThat(trustManager.getAcceptedIssuers()).hasLength(2);
    }

    @Test
    public void aKeyStoreCannotBeLoadedTwice() throws Exception {
        // Loading twice would silently discard the first keystore, so the builder refuses rather
        // than letting a caller believe both were bundled.
        final BundledTrustManager.Builder builder =
                BundledTrustManager.builder()
                        .loadKeyStore(
                                keyStoreHolding(TestCertificates.parse(TestCertificates.NO_SAN_PEM)),
                                PASSWORD);

        assertThrows(
                IllegalStateException.class,
                () ->
                        builder.loadKeyStore(
                                keyStoreHolding(
                                        TestCertificates.parse(TestCertificates.WITH_SANS_PEM)),
                                PASSWORD));
    }

    @Test
    public void theWrongPasswordIsRefused() throws Exception {
        assertThrows(
                java.io.IOException.class,
                () ->
                        BundledTrustManager.builder()
                                .loadKeyStore(
                                        keyStoreHolding(
                                                TestCertificates.parse(TestCertificates.NO_SAN_PEM)),
                                        "not-the-password"));
    }

    @Test
    public void buildingWithoutAKeyStoreFallsBackToThePlatformStore() throws Exception {
        // Characterisation, and a fail-open worth knowing about. `build()` with no loadKeyStore
        // passes a null KeyStore to TrustManagerFactory.init, and JSSE reads that as "use the
        // default trust store" -- so a BundledTrustManager that bundles nothing trusts everything
        // the platform does, rather than nothing.
        //
        // Not reachable today: the only caller, TrustManagers.defaultWithBundledLetsEncrypt,
        // always loads a keystore first. Pinned so that adding a second caller, or making the
        // builder refuse an unloaded build, is a visible change rather than a silent one.
        final X509TrustManager emptyBundle = BundledTrustManager.builder().build();

        assertThat(emptyBundle.getAcceptedIssuers().length)
                .isEqualTo(TrustManagers.createDefaultTrustManager().getAcceptedIssuers().length);
    }
}
