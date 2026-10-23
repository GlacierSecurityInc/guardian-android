package com.glaciersecurity.glaciermessenger.xmpp;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.utils.JidHelper;

import org.junit.BeforeClass;
import org.junit.Test;
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep;

/**
 * JID handling is the addressing layer under every stanza the app sends or accepts. Two things
 * matter here beyond "does it parse": the local and domain parts must case-fold, or
 * {@code Alice@example.com} and {@code alice@example.com} become two contacts that look
 * identical on screen; and {@link Jid#obfuscateForLog} must not leak an address into a log file.
 *
 * <p>Seeded from upstream Conversations' {@code eu.siacs.conversations.xmpp.JidTest} and adapted
 * to this fork's jxmpp-backed {@code Jid.of} / {@code Jid.ofEscaped} factories -- upstream's
 * {@code ofUserInput} does not exist on this line.
 *
 * <p>The {@code @BeforeClass} matters. jxmpp does no stringprep at all until an implementation
 * registers itself, and in the app that happens in {@code XmppConnectionService.onCreate} --
 * which a unit test never runs. Without this call {@code Jid.of("test@something invalid.tld")}
 * is accepted, and every validation case below passes vacuously.
 */
public class JidTest {

    @BeforeClass
    public static void setUpStringprep() {
        LibIdnXmppStringprep.setup();
    }

    @Test
    public void aFullJidSplitsIntoLocalDomainAndResource() {
        final Jid jid = Jid.of("alice@example.com/phone");
        assertThat(jid.getLocal()).isEqualTo("alice");
        assertThat(jid.getDomain().toEscapedString()).isEqualTo("example.com");
        assertThat(jid.getResource()).isEqualTo("phone");
        assertThat(jid.isFullJid()).isTrue();
        assertThat(jid.isBareJid()).isFalse();
    }

    @Test
    public void aBareJidHasNoResource() {
        final Jid jid = Jid.of("alice@example.com");
        assertThat(jid.isBareJid()).isTrue();
        assertThat(jid.isFullJid()).isFalse();
    }

    @Test
    public void aDomainJidHasNoLocalPart() {
        final Jid jid = Jid.of("example.com");
        assertThat(jid.isDomainJid()).isTrue();
        assertThat(jid.toString()).isEqualTo("example.com");
    }

    @Test
    public void asBareJidDropsTheResource() {
        assertThat(Jid.of("alice@example.com/phone").asBareJid().toString())
                .isEqualTo("alice@example.com");
    }

    @Test
    public void withResourceReplacesTheResource() {
        assertThat(Jid.of("alice@example.com/phone").withResource("tablet").toString())
                .isEqualTo("alice@example.com/tablet");
    }

    @Test
    public void theLocalPartAndDomainCaseFold() {
        // Without this, a contact could be impersonated by an address differing only in case.
        assertThat(Jid.of("Alice@Example.COM").toString()).isEqualTo("alice@example.com");
    }

    @Test
    public void theResourceIsCaseSensitive() {
        // Resources identify a device, not a person, and XMPP keeps their case.
        assertThat(Jid.of("alice@example.com/Phone").getResource()).isEqualTo("Phone");
    }

    @Test
    public void twoJidsWithTheSameAddressAreEqual() {
        assertThat(Jid.of("alice@example.com")).isEqualTo(Jid.of("Alice@example.com"));
    }

    @Test
    public void nameprepFoldsCompatibilityCharactersInTheLocalPart() {
        // A fullwidth "a" must not be able to stand in for a plain one -- that is a homograph
        // attack on a contact address. Nameprep's NFKC pass folds them together.
        assertThat(Jid.of("\uFF41lice@example.com").toString()).isEqualTo("alice@example.com");
    }

    @Test
    public void nameprepDropsIgnorableCharactersFromTheDomain() {
        // A soft hyphen maps to nothing, so it cannot be used to spell a lookalike domain.
        assertThat(Jid.of("test@\u00ADexample.com").getDomain().toEscapedString())
                .isEqualTo("example.com");
    }

    @Test
    public void aUnicodeInternationalisedDomainIsAccepted() {
        assertThat(Jid.of("test@\u0533\u056E\u0578\u0578\u0578\u0581.\u0570\u0561\u0575")
                        .getDomain()
                        .toEscapedString())
                .isEqualTo("\u0533\u056E\u0578\u0578\u0578\u0581.\u0570\u0561\u0575"
                        .toLowerCase(java.util.Locale.US));
    }

    @Test
    public void anEmptyDomainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Jid.of("alice@"));
    }

    @Test
    public void aDoubleDashInTheDomainIsAllowed() {
        // Punycode-adjacent domains legitimately contain "--"; rejecting them would break IDNs.
        assertThat(Jid.of("user@a--z.com").getDomain().toEscapedString()).isEqualTo("a--z.com");
    }

    @Test
    public void anIpAddressIsAValidDomain() {
        final Jid jid = Jid.of("test@192.0.2.1/home");
        assertThat(jid.getDomain().toEscapedString()).isEqualTo("192.0.2.1");
        assertThat(jid.getResource()).isEqualTo("home");
    }

    @Test
    public void aLocalPartWithASpaceIsEscapedOnTheWire() {
        // XEP-0106: characters illegal in a local part travel as \20-style escapes.
        final Jid jid = Jid.of("a b", "example.com", null);
        assertThat(jid.toEscapedString()).isEqualTo("a\\20b@example.com");
        assertThat(jid.getLocal()).isEqualTo("a b");
    }

    @Test
    public void ofEscapedReadsBackWhatToEscapedStringWrote() {
        final Jid original = Jid.of("a b", "example.com", null);
        assertThat(Jid.ofEscaped(original.toEscapedString()).getLocal()).isEqualTo("a b");
    }

    @Test
    public void obfuscateForLogKeepsOnlyTheFirstCharacterOfEachPart() {
        // Logs on a device can be read by anything holding READ_LOGS; an address must not survive.
        assertThat(Jid.obfuscateForLog(Jid.of("alice@example.com/phone")))
                .isEqualTo("a****@e**********/p****");
    }

    @Test
    public void obfuscateForLogHandlesAStringThatIsNotAJid() {
        assertThat(Jid.obfuscateForLog("not-a-jid")).isEqualTo("n********");
    }

    @Test
    public void obfuscateForLogHandlesNull() {
        assertThat(Jid.obfuscateForLog((Jid) null)).isEqualTo("null");
    }

    @Test
    public void obfuscatePartHandlesTheEdgeCases() {
        assertThat(Jid.obfuscatePart("")).isEmpty();
        assertThat(Jid.obfuscatePart(null)).isNull();
    }

    // ---- ofUserInput: an address a person typed, scanned or tapped ---------------------
    //
    // of/ofEscaped apply nameprep and nothing else, which is right for a stanza off the wire but
    // not for user input: nameprep prohibits neither ASCII space nor ASCII controls, and it does
    // not fold punycode. ofUserInput adds the IDNA half. These were characterisation tests
    // before the method existed; they are requirements now.

    @Test
    public void ofUserInputRejectsADomainContainingASpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Jid.ofUserInput("test@something invalid.tld"));
    }

    @Test
    public void ofUserInputRejectsAnAsciiControlCharacterInTheDomain() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Jid.ofUserInput("test@exam" + ((char) 0) + "ple.com"));
    }

    @Test
    public void ofUserInputFoldsPunycodeOntoItsUnicodeSpelling() {
        // The whole point: a contact saved from a typed punycode address and one saved from the
        // Unicode spelling have to be the same contact.
        final Jid punycode = Jid.ofUserInput("Test@xn--kxae4bafwg.xn--pxaix.gr");
        final Jid unicode =
                Jid.ofUserInput("test@\u03BF\u03C5\u03C4\u03BF\u03C0\u03AF\u03B1"
                        + ".\u03B4\u03C0\u03B8.gr");
        assertThat(punycode).isEqualTo(unicode);
    }

    @Test
    public void ofUserInputKeepsTheResourceWhileFoldingTheDomain() {
        final Jid jid = Jid.ofUserInput("Test@xn--kxae4bafwg.xn--pxaix.gr/phone");
        assertThat(jid.getResource()).isEqualTo("phone");
        assertThat(jid.asBareJid())
                .isEqualTo(Jid.ofUserInput("test@xn--kxae4bafwg.xn--pxaix.gr"));
    }

    @Test
    public void ofUserInputAcceptsAnOrdinaryAddress() {
        assertThat(Jid.ofUserInput("alice@example.com").toString()).isEqualTo("alice@example.com");
        assertThat(Jid.ofUserInput("example.com").isDomainJid()).isTrue();
    }

    @Test
    public void ofUserInputAcceptsAUnicodeInternationalisedDomain() {
        assertThat(Jid.ofUserInput("test@\u0533\u056E\u0578\u0578\u0578\u0581"
                        + ".\u0570\u0561\u0575").isBareJid())
                .isTrue();
    }

    @Test
    public void ofUserInputAcceptsScriptsWrittenWithCombiningMarks() {
        // A vowel sign is \p{M}, not \p{L}. Validating the Unicode spelling with a letters-and-
        // digits rule silently excluded whole scripts while leaving Greek and Armenian working,
        // so the breakage was invisible unless you tested the right language. Validation now runs
        // on the punycode form, where every script is ASCII.
        final String[] domains = {
            "\u0909\u0926\u093E\u0939\u0930\u0923.\u092D\u093E\u0930\u0924", // Devanagari
            "\u0E44\u0E17\u0E22.\u0E44\u0E17\u0E22", // Thai
            "\u0645\u062B\u0627\u0644.\u0625\u062E\u062A\u0628\u0627\u0631", // Arabic
        };
        for (final String domain : domains) {
            assertThat(Jid.ofUserInput("test@" + domain).isBareJid()).isTrue();
        }
    }

    @Test
    public void ofUserInputFoldsACombiningMarkDomainOntoItsPunycodeSpelling() {
        // The Devanagari example, whose punycode form is xn--p1b6ci4b4b3a.xn--h2brj9c.
        assertThat(Jid.ofUserInput(
                        "test@\u0909\u0926\u093E\u0939\u0930\u0923"
                                + ".\u092D\u093E\u0930\u0924"))
                .isEqualTo(Jid.ofUserInput("test@xn--p1b6ci4b4b3a.xn--h2brj9c"));
    }

    @Test
    public void ofUserInputAcceptsIpLiterals() {
        assertThat(Jid.ofUserInput("test@192.0.2.1").getDomain().toEscapedString())
                .isEqualTo("192.0.2.1");
        assertThat(Jid.ofUserInput("test@[2001:db8::1]").getDomain().toEscapedString())
                .isEqualTo("[2001:db8::1]");
    }

    @Test
    public void ofUserInputRejectsMalformedHostnames() {
        // Leading hyphen, trailing hyphen, underscore, empty label, and a bare colon-separated
        // v6 address that was not bracketed -- none of these are addressable.
        for (final String bad :
                new String[] {
                    "test@-example.com",
                    "test@example-.com",
                    "test@exa_mple.com",
                    "test@example..com",
                    "test@::1",
                }) {
            assertThrows(
                    "expected " + bad + " to be rejected",
                    IllegalArgumentException.class,
                    () -> Jid.ofUserInput(bad));
        }
    }

    @Test
    public void ofUserInputRejectsAnOverlongDomain() {
        final StringBuilder domain = new StringBuilder();
        while (domain.length() < 260) {
            domain.append("abcdefgh.");
        }
        domain.append("com");
        assertThrows(
                IllegalArgumentException.class,
                () -> Jid.ofUserInput("test@" + domain));
    }

    @Test
    public void ofStaysLenientBecauseItParsesStanzasNotUserInput() {
        // Deliberate: an address the server sent is already normalised, and re-deriving it on the
        // stanza path would risk changing what an incoming message routes to. The strict rules
        // live in ofUserInput and are applied at the points where a person supplies an address.
        assertThat(Jid.of("test@something invalid.tld").getDomain().toEscapedString())
                .isEqualTo("something invalid.tld");
    }

    @Test
    public void parseOrFallbackToInvalidNeverThrows() {
        // MessageParser routes on the from address of an untrusted stanza; a malformed one has
        // to degrade to an InvalidJid rather than blow up the parser.
        final Jid jid = JidHelper.parseOrFallbackToInvalid("not a jid@@@");
        assertThat(InvalidJid.isValid(jid)).isFalse();
        assertThat(InvalidJid.getNullForInvalid(jid)).isNull();
    }

    @Test
    public void aValidJidSurvivesParseOrFallbackToInvalid() {
        final Jid jid = JidHelper.parseOrFallbackToInvalid("alice@example.com");
        assertThat(InvalidJid.isValid(jid)).isTrue();
        assertThat(InvalidJid.getNullForInvalid(jid)).isEqualTo(jid);
    }

    @Test
    public void localPartOrFallbackUsesTheDomainForGenericLocalParts() {
        // "me@example.com" as a display name is useless; JidHelper falls back to the domain.
        assertThat(JidHelper.localPartOrFallback(Jid.of("me@example.com"))).isEqualTo("example");
        assertThat(JidHelper.localPartOrFallback(Jid.of("xmpp@example.com"))).isEqualTo("example");
        assertThat(JidHelper.localPartOrFallback(Jid.of("alice@example.com"))).isEqualTo("alice");
    }
}
