package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.services.AbstractConnectionManager.Extension;

import org.junit.Test;

/**
 * {@link Extension} is how a received file's type is decided, from nothing but the path an
 * untrusted peer chose. It is worth pinning because the crypto case inverts the obvious answer:
 * for {@code photo.jpg.pgp} the extension that matters is {@code jpg}, the one *inside* the
 * envelope, and getting that backwards means the decrypted file lands on disk named as PGP
 * ciphertext (or worse, the reverse).
 *
 * <p>Plain JUnit: the class reaches no Android API on this path -- only String and the
 * {@code VALID_CRYPTO_EXTENSIONS} list on Transferable.
 */
public class AbstractConnectionManagerExtensionTest {

    @Test
    public void aPlainFilenameHasOneExtensionAndNoSecondary() {
        final Extension extension = Extension.of("photo.jpg");
        assertThat(extension.main).isEqualTo("jpg");
        assertThat(extension.secondary).isNull();
        assertThat(extension.getExtension()).isEqualTo("jpg");
    }

    @Test
    public void onlyTheLastPathSegmentIsConsidered() {
        assertThat(Extension.of("/some/dir.with.dots/photo.jpg").getExtension()).isEqualTo("jpg");
    }

    @Test
    public void extensionsAreLowercased() {
        assertThat(Extension.of("/tmp/PHOTO.JPG").getExtension()).isEqualTo("jpg");
    }

    @Test
    public void aPgpEnvelopeReportsTheExtensionInsideIt() {
        final Extension extension = Extension.of("photo.jpg.pgp");
        assertThat(extension.main).isEqualTo("pgp");
        assertThat(extension.secondary).isEqualTo("jpg");
        assertThat(extension.getExtension()).isEqualTo("jpg");
    }

    @Test
    public void gpgIsTreatedAsAnEnvelopeToo() {
        assertThat(Extension.of("notes.pdf.gpg").getExtension()).isEqualTo("pdf");
    }

    @Test
    public void aNonCryptoDoubleExtensionReportsTheOuterOne() {
        final Extension extension = Extension.of("backup.tar.gz");
        assertThat(extension.secondary).isEqualTo("tar");
        assertThat(extension.getExtension()).isEqualTo("gz");
    }

    /**
     * A bare {@code .pgp} with nothing in front of it has no inner extension to report, so the
     * answer is null rather than "pgp". Callers have to cope with an unknown type here; the point
     * of the test is that they are never told the file is PGP ciphertext when it is the payload.
     */
    @Test
    public void aBareCryptoExtensionYieldsNothing() {
        final Extension extension = Extension.of("blob.pgp");
        assertThat(extension.main).isEqualTo("pgp");
        assertThat(extension.secondary).isNull();
        assertThat(extension.getExtension()).isNull();
    }

    @Test
    public void aFilenameWithNoDotHasNoExtension() {
        final Extension extension = Extension.of("/tmp/README");
        assertThat(extension.main).isNull();
        assertThat(extension.getExtension()).isNull();
    }

    @Test
    public void aTrailingDotIsNotAnExtension() {
        assertThat(Extension.of("weird.").getExtension()).isNull();
    }

    @Test
    public void anEmptyPathIsHandledRatherThanThrowing() {
        assertThat(Extension.of("").getExtension()).isNull();
        assertThat(Extension.of("/a/dir/").getExtension()).isNull();
    }

    /**
     * A dotfile's name is read as a bare extension. Pinned as observed behaviour rather than
     * endorsed: {@code .bashrc} is a filename, not a file of type "bashrc".
     */
    @Test
    public void aDotfileIsReadAsAnExtension() {
        assertThat(Extension.of(".bashrc").getExtension()).isEqualTo("bashrc");
    }

    /**
     * of() splits on '.' after taking the last path segment and never strips a query string, so a
     * URL keeps it. Pinned because the callers pass URLs: anything comparing the result against a
     * known extension will miss here, and it fails as "unknown type" rather than loudly.
     */
    @Test
    public void aQueryStringIsNotStrippedFromAUrl() {
        assertThat(Extension.of("https://host/files/photo.png?token=abc").getExtension())
                .isEqualTo("png?token=abc");
    }
}
