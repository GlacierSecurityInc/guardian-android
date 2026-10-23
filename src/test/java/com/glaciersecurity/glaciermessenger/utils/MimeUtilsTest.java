package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * MimeUtils decides what a file <em>is</em>, from a name or a URL the sender chose. That answer
 * drives whether a transfer is shown as an inline image, handed to a viewer, or treated as an
 * opaque download, so the parsing half matters more than the lookup table.
 *
 * <p>{@code extractRelevantExtension} is that parsing half. It has to find the real extension in
 * something that may be a bare filename, a full URL with a query string and a fragment, or a name
 * wrapped in a crypto extension by the sending client -- and a file whose extension is read from
 * the query string rather than the path is a file typed by whatever the URL's author appended.
 *
 * <p>The lookup half is a large static table, so the tests there sample it rather than enumerate
 * it, and concentrate on the argument handling around it -- which is where the two classes of
 * caller disagree. See the pair of tests at the end.
 */
public class MimeUtilsTest {

    // ---- finding the extension ----------------------------------------------------------

    @Test
    public void aPlainFilenameGivesItsExtension() {
        assertThat(MimeUtils.extractRelevantExtension("holiday.jpg")).isEqualTo("jpg");
    }

    @Test
    public void onlyTheLastExtensionCounts() {
        assertThat(MimeUtils.extractRelevantExtension("archive.tar.gz")).isEqualTo("gz");
    }

    @Test
    public void aPathIsReducedToItsFilename() {
        assertThat(MimeUtils.extractRelevantExtension("/tmp/photos/holiday.jpg")).isEqualTo("jpg");
    }

    @Test
    public void aDirectoryNameWithADotDoesNotSupplyTheExtension() {
        // The dot is in the path, not the filename. Reading it would type every file in that
        // directory by the directory's name.
        assertThat(MimeUtils.extractRelevantExtension("/tmp/my.photos/holiday")).isNull();
    }

    @Test
    public void aFilenameWithNoDotHasNoExtension() {
        assertThat(MimeUtils.extractRelevantExtension("README")).isNull();
    }

    @Test
    public void anEmptyOrNullPathHasNoExtension() {
        assertThat(MimeUtils.extractRelevantExtension(null)).isNull();
        assertThat(MimeUtils.extractRelevantExtension("")).isNull();
    }

    @Test
    public void aQueryStringIsNotPartOfTheFilename() {
        // The lever this closes: without cutting at '?', a URL ending "?x=.exe" would type the
        // download by whatever the URL's author appended rather than by the file itself.
        assertThat(MimeUtils.extractRelevantExtension("https://example.com/holiday.jpg?token=abc"))
                .isEqualTo("jpg");
    }

    @Test
    public void aFragmentIsNotPartOfTheFilename() {
        assertThat(MimeUtils.extractRelevantExtension("https://example.com/holiday.jpg#preview"))
                .isEqualTo("jpg");
    }

    @Test
    public void aQueryAndAFragmentAreBothRemoved() {
        assertThat(
                        MimeUtils.extractRelevantExtension(
                                "https://example.com/files/holiday.jpg?token=abc#preview"))
                .isEqualTo("jpg");
    }

    @Test
    public void anExtensionOnlyInTheQueryStringIsNotUsed() {
        // The file itself has no extension; the query string must not supply one.
        assertThat(MimeUtils.extractRelevantExtension("https://example.com/download?name=x.exe"))
                .isNull();
    }

    // ---- crypto extensions ------------------------------------------------------------------

    @Test
    public void theCryptoExtensionIsKeptUnlessAskedToLookPastIt() {
        // The default is the literal extension, because that is what is on disk right now.
        assertThat(MimeUtils.extractRelevantExtension("holiday.jpg.pgp")).isEqualTo("pgp");
    }

    @Test
    public void lookingPastTheCryptoExtensionFindsTheRealOne() {
        // What the file will be once decrypted, which is what the transfer should be typed as.
        assertThat(MimeUtils.extractRelevantExtension("holiday.jpg.pgp", true)).isEqualTo("jpg");
        assertThat(MimeUtils.extractRelevantExtension("holiday.jpg.gpg", true)).isEqualTo("jpg");
    }

    @Test
    public void onlyOneLayerOfCryptoExtensionIsStripped() {
        // Characterisation, not a requirement. The recursive call is to the one-argument
        // overload, which defaults ignoreCryptoExtension back to false -- so a doubly-wrapped
        // name gives up after the first layer and reports the second crypto extension rather
        // than the real one. Passing the flag through would unwrap all the way, which reads like
        // the intent.
        //
        // Doubly-wrapped files are not something this app produces, so this is pinned rather
        // than filed; it is the test that would change if the recursion is ever threaded.
        assertThat(MimeUtils.extractRelevantExtension("holiday.jpg.pgp.gpg", true))
                .isEqualTo("pgp");
    }

    @Test
    public void aCryptoExtensionWithNothingBehindItResolvesToNothing() {
        assertThat(MimeUtils.extractRelevantExtension("holiday.pgp", true)).isNull();
    }

    @Test
    public void anExtensionThatIsNotACryptoOneIsLeftAlone() {
        assertThat(MimeUtils.extractRelevantExtension("holiday.jpg", true)).isEqualTo("jpg");
    }

    @Test
    public void theCryptoStripAlsoAppliesAfterTheQueryStringIsRemoved() {
        assertThat(
                        MimeUtils.extractRelevantExtension(
                                "https://example.com/holiday.jpg.pgp?token=abc", true))
                .isEqualTo("jpg");
    }

    // ---- the lookup table --------------------------------------------------------------------

    @Test
    public void aKnownExtensionMapsToItsMimeType() {
        assertThat(MimeUtils.guessMimeTypeFromExtension("png")).isEqualTo("image/png");
        assertThat(MimeUtils.guessMimeTypeFromExtension("pdf")).isEqualTo("application/pdf");
    }

    @Test
    public void anUnknownOrEmptyExtensionHasNoMimeType() {
        assertThat(MimeUtils.guessMimeTypeFromExtension("notarealextension")).isNull();
        assertThat(MimeUtils.guessMimeTypeFromExtension("")).isNull();
        assertThat(MimeUtils.guessMimeTypeFromExtension(null)).isNull();
    }

    @Test
    public void aKnownMimeTypeMapsBackToAnExtension() {
        assertThat(MimeUtils.guessExtensionFromMimeType("image/png")).isEqualTo("png");
        assertThat(MimeUtils.guessExtensionFromMimeType("text/plain")).isEqualTo("txt");
    }

    @Test
    public void mimeTypeParametersAreIgnoredWhenLookingUpAnExtension() {
        // A Content-Type header legitimately carries a charset; the lookup has to see past it.
        assertThat(MimeUtils.guessExtensionFromMimeType("text/plain; charset=utf-8"))
                .isEqualTo("txt");
    }

    @Test
    public void anUnknownOrEmptyMimeTypeHasNoExtension() {
        assertThat(MimeUtils.guessExtensionFromMimeType("application/x-not-real")).isNull();
        assertThat(MimeUtils.guessExtensionFromMimeType("")).isNull();
        assertThat(MimeUtils.guessExtensionFromMimeType(null)).isNull();
    }

    // ---- where the two kinds of accessor disagree ----------------------------------------------

    @Test
    public void theExtensionLookupIsCaseInsensitiveButTheMembershipCheckIsNot() {
        // Characterisation of an inconsistency, not a requirement.
        // guessMimeTypeFromExtension lower-cases its argument; hasExtension does not. So a caller
        // that guards with hasExtension before looking up will reject an upper-case extension
        // that the lookup itself would have resolved -- ".JPG" off a Windows client, for one.
        assertThat(MimeUtils.guessMimeTypeFromExtension("PNG")).isEqualTo("image/png");
        assertThat(MimeUtils.hasExtension("PNG")).isFalse();
        assertThat(MimeUtils.hasExtension("png")).isTrue();
    }

    @Test
    public void theMimeTypeMembershipCheckDoesNotSeePastParameters() {
        // The same shape of mismatch on the other side: guessExtensionFromMimeType splits on ';'
        // and hasMimeType does not, so a Content-Type with a charset fails the check while the
        // lookup would have succeeded.
        assertThat(MimeUtils.guessExtensionFromMimeType("text/plain; charset=utf-8"))
                .isEqualTo("txt");
        assertThat(MimeUtils.hasMimeType("text/plain; charset=utf-8")).isFalse();
        assertThat(MimeUtils.hasMimeType("text/plain")).isTrue();
    }

    @Test
    public void membershipChecksRefuseEmptyInputRatherThanThrowing() {
        assertThat(MimeUtils.hasExtension(null)).isFalse();
        assertThat(MimeUtils.hasExtension("")).isFalse();
        assertThat(MimeUtils.hasMimeType(null)).isFalse();
        assertThat(MimeUtils.hasMimeType("")).isFalse();
    }
}
