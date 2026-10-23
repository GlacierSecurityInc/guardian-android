package com.glaciersecurity.glaciermessenger.xmpp;

import com.google.common.net.InetAddresses;

import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Domainpart;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;

import java.io.Serializable;
import java.net.IDN;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface Jid extends Comparable<Jid>, Serializable, CharSequence {

    Pattern JID = Pattern.compile("^((.*?)@)?([^/@]+)(/(.*))?$");

    /**
     * The LDH rule, applied to the <em>ASCII</em> form of a hostname: at most 253 characters,
     * dot-separated labels of at most 63, each starting and ending with a letter or digit.
     *
     * <p>Deliberately ASCII-only. {@link #ofUserInput} runs {@link IDN#toASCII} first, so an
     * internationalised domain reaches this pattern as punycode. Matching Unicode letters here
     * instead would silently exclude every script that writes with combining marks -- Devanagari,
     * Thai, Arabic, Hebrew and others -- because a vowel sign is {@code \p{M}}, not {@code \p{L}}.
     * The length limits also only mean anything against the ASCII form, since that is what
     * actually travels.
     */
    Pattern HOSTNAME =
            Pattern.compile(
                    "^(?=.{1,253}$)(?!-)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                            + "(?:\\.(?!-)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$");

    static Jid of(CharSequence local, CharSequence domain, CharSequence resource) {
        if (local == null) {
            if (resource == null) {
                return ofDomain(domain);
            } else {
                return ofDomainAndResource(domain, resource);
            }
        }
        if (resource == null) {
            return ofLocalAndDomain(local, domain);
        }
        try {
            return new WrappedJid(JidCreate.entityFullFrom(
                    Localpart.fromUnescaped(local.toString()),
                    Domainpart.from(domain.toString()),
                    Resourcepart.from(resource.toString())
            ));
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Jid ofEscaped(CharSequence local, CharSequence domain, CharSequence resource) {
        try {
            if (resource == null) {
                return new WrappedJid(
                        JidCreate.bareFrom(
                                Localpart.from(local.toString()),
                                Domainpart.from(domain.toString())
                        )
                );
            }
            return new WrappedJid(JidCreate.entityFullFrom(
                    Localpart.from(local.toString()),
                    Domainpart.from(domain.toString()),
                    Resourcepart.from(resource.toString())
            ));
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }


    static Jid ofDomain(CharSequence domain) {
        try {
            return new WrappedJid(JidCreate.domainBareFrom(domain));
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Jid ofLocalAndDomain(CharSequence local, CharSequence domain) {
        try {
            return new WrappedJid(
                    JidCreate.bareFrom(
                            Localpart.fromUnescaped(local.toString()),
                            Domainpart.from(domain.toString())
                    )
            );
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Jid ofDomainAndResource(CharSequence domain, CharSequence resource) {
        try {
            return new WrappedJid(
                    JidCreate.domainFullFrom(
                            Domainpart.from(domain.toString()),
                            Resourcepart.from(resource.toString())
                    ));
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Jid ofLocalAndDomainEscaped(CharSequence local, CharSequence domain) {
        try {
            return new WrappedJid(
                    JidCreate.bareFrom(
                            Localpart.from(local.toString()),
                            Domainpart.from(domain.toString())
                    )
            );
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Jid of(CharSequence jid) {
        if (jid instanceof Jid) {
            return (Jid) jid;
        }
        Matcher matcher = JID.matcher(jid);
        if (matcher.matches()) {
            return of(matcher.group(2), matcher.group(3), matcher.group(5));
        } else {
            throw new IllegalArgumentException("Could not parse JID: " + jid);
        }
    }

    static Jid ofEscaped(CharSequence jid) {
        try {
            return new WrappedJid(JidCreate.from(jid));
        } catch (final XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Parses an address a person typed, scanned or tapped, as opposed to one that arrived on the
     * wire.
     *
     * <p>{@link #of} and {@link #ofEscaped} apply nameprep and nothing else, and nameprep (RFC
     * 3491) prohibits neither ASCII space nor ASCII control characters -- excluding those is
     * IDNA's job, via the LDH rule. So on its own {@code ofEscaped} accepts
     * {@code test@something invalid.tld}, and it leaves a punycode domain spelled as punycode,
     * which makes it unequal to the same domain written in Unicode. A contact saved under one
     * spelling then does not match a stanza that arrives under the other.
     *
     * <p>This applies the missing half: the domain must be an IP literal or a hostname that
     * survives {@link IDN#toASCII} under STD3 rules, and a hostname is folded to its Unicode form
     * so the punycode and Unicode spellings converge.
     * Ported from upstream Conversations' {@code Jid.ofUserInput}, minus its Quicksy-only rule
     * about gmail addresses on the Play Store flavour, which has no counterpart here.
     *
     * <p>Deliberately <em>not</em> applied to stanzas. An address the server sent is already
     * normalised, and re-deriving it here would risk changing what an incoming stanza routes to.
     *
     * @throws IllegalArgumentException if the domain is empty, or is neither a valid hostname nor
     *     an IP literal.
     */
    static Jid ofUserInput(final CharSequence input) {
        final Jid jid = ofEscaped(input);
        final String domain = jid.getDomain().toEscapedString();
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("Domain can not be empty");
        }
        // An IP literal -- dotted v4, or v6 in brackets -- is addressable but is not a hostname,
        // and IDN.toASCII rejects the bracketed form. Settle it before converting.
        if (InetAddresses.isUriInetAddress(domain)) {
            return jid;
        }
        // Convert first, validate second. STD3 rules are what reject a space, a control
        // character, an underscore, an empty label and a leading or trailing hyphen; the pattern
        // then applies the length limits, which toASCII does not enforce for the name as a whole.
        final String ascii;
        try {
            ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid hostname: " + obfuscatePart(domain), e);
        }
        if (!HOSTNAME.matcher(ascii).matches()) {
            throw new IllegalArgumentException("Invalid hostname: " + obfuscatePart(domain));
        }
        final String unicode = IDN.toUnicode(ascii);
        final Jid bare =
                jid.isDomainJid()
                        ? ofDomain(unicode)
                        : ofLocalAndDomain(jid.getLocal(), unicode);
        return jid.isBareJid() ? bare : bare.withResource(jid.getResource());
    }

    // One overload, not two: which of a Jid/CharSequence pair was picked used to depend on the
    // static type of the argument, even though both produced the same string.
    static String obfuscateForLog(CharSequence jid) {
        if (jid == null) {
            return "null";
        }
        if (jid instanceof Jid) {
            final Jid parsed = (Jid) jid;
            return obfuscateParts(parsed.getEscapedLocal(), parsed.getDomain() == null ? null : parsed.getDomain().toEscapedString(), parsed.getResource());
        }
        final Matcher matcher = JID.matcher(jid);
        if (!matcher.matches()) {
            return obfuscatePart(jid.toString());
        }
        return obfuscateParts(matcher.group(2), matcher.group(3), matcher.group(5));
    }

    static String obfuscateParts(String local, String domain, String resource) {
        StringBuilder builder = new StringBuilder();
        if (local != null) {
            builder.append(obfuscatePart(local)).append('@');
        }
        if (domain != null) {
            builder.append(obfuscatePart(domain));
        }
        if (resource != null) {
            builder.append('/').append(obfuscatePart(resource));
        }
        return builder.toString();
    }

    static String obfuscatePart(String part) {
        if (part == null) {
            return null;
        }
        if (part.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder(part.length());
        masked.append(part.charAt(0));
        for (int i = 1; i < part.length(); i++) {
            masked.append('*');
        }
        return masked.toString();
    }
    boolean isFullJid();

    boolean isBareJid();

    boolean isDomainJid();

    Jid asBareJid();

    Jid withResource(CharSequence resource);

    String getLocal();

    String getEscapedLocal();

    Jid getDomain();

    String getResource();

    String toEscapedString();
}
