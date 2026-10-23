package com.glaciersecurity.glaciermessenger.xml;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;

/**
 * Element is the stanza builder: everything the app writes to the socket is an Element tree
 * serialised by {@code toString}. The reading side ({@code findChild} and friends) runs over
 * stanzas the server sent, so it has to cope with children that are absent, repeated, or in the
 * wrong namespace rather than returning the first plausible match.
 *
 * <p>{@code XmlHelperTest} already covers the escaping function itself; what is checked here is
 * that Element and Tag actually route content and attribute values through it.
 */
public class ElementTest {

    @Test
    public void anElementWithNoContentSerialisesAsAnEmptyTag() {
        assertThat(new Element("presence").toString()).isEqualTo("<presence/>");
    }

    @Test
    public void contentIsWrappedInAStartAndEndTag() {
        assertThat(new Element("body").setContent("hello").toString())
                .isEqualTo("<body>hello</body>");
    }

    @Test
    public void contentIsEntityEncoded() {
        // A body containing markup must not be able to close its own tag.
        assertThat(new Element("body").setContent("</body><iq type='set'/>").toString())
                .isEqualTo("<body>&lt;/body&gt;&lt;iq type=&apos;set&apos;/&gt;</body>");
    }

    @Test
    public void attributeValuesAreEntityEncoded() {
        final Element element = new Element("message");
        element.setAttribute("id", "a\"b");
        assertThat(element.toString()).isEqualTo("<message id=\"a&quot;b\"/>");
    }

    @Test
    public void theNamespaceConstructorSetsXmlns() {
        final Element element = new Element("query", "jabber:iq:roster");
        assertThat(element.getNamespace()).isEqualTo("jabber:iq:roster");
        assertThat(element.toString()).isEqualTo("<query xmlns=\"jabber:iq:roster\"/>");
    }

    @Test
    public void childrenSerialiseInOrder() {
        final Element message = new Element("message");
        message.addChild("body").setContent("hi");
        message.addChild("thread").setContent("t1");
        assertThat(message.toString())
                .isEqualTo("<message><body>hi</body><thread>t1</thread></message>");
    }

    @Test
    public void findChildReturnsNullRatherThanThrowingForAMissingChild() {
        assertThat(new Element("message").findChild("body")).isNull();
        assertThat(new Element("message").findChildContent("body")).isNull();
    }

    @Test
    public void findChildRespectsTheNamespace() {
        final Element message = new Element("message");
        message.addChild("x", "urn:example:one").setContent("first");
        message.addChild("x", "urn:example:two").setContent("second");
        assertThat(message.findChildContent("x", "urn:example:two")).isEqualTo("second");
        assertThat(message.findChild("x", "urn:example:three")).isNull();
    }

    @Test
    public void findChildrenReturnsEveryMatchInTheNamespace() {
        final Element message = new Element("message");
        message.addChild("x", "urn:example:one");
        message.addChild("x", "urn:example:one");
        message.addChild("x", "urn:example:two");
        assertThat(message.findChildren("x", "urn:example:one")).hasSize(2);
    }

    @Test
    public void findChildEnsureSingleRefusesAnAmbiguousStanza() {
        // A stanza that carries two of a child that the XEP says is singular is malformed, and
        // silently taking the first one is how a spoofed element gets preferred over the real one.
        final Element message = new Element("message");
        message.addChild("x", "urn:example:one");
        message.addChild("x", "urn:example:one");
        assertThat(message.findChildEnsureSingle("x", "urn:example:one")).isNull();
    }

    @Test
    public void findChildEnsureSingleReturnsTheOnlyMatch() {
        final Element message = new Element("message");
        message.addChild("x", "urn:example:one").setContent("only");
        assertThat(message.findChildEnsureSingle("x", "urn:example:one").getContent())
                .isEqualTo("only");
    }

    @Test
    public void hasChildDistinguishesNamespaces() {
        final Element message = new Element("message");
        message.addChild("received", "urn:xmpp:receipts");
        assertThat(message.hasChild("received")).isTrue();
        assertThat(message.hasChild("received", "urn:xmpp:receipts")).isTrue();
        assertThat(message.hasChild("received", "urn:xmpp:chat-markers:0")).isFalse();
    }

    @Test
    public void aJidAttributeRoundTrips() {
        final Element message = new Element("message");
        message.setAttribute("to", Jid.of("alice@example.com/phone"));
        assertThat(message.getAttributeAsJid("to")).isEqualTo(Jid.of("alice@example.com/phone"));
    }

    @Test
    public void aMalformedJidAttributeReadsBackAsInvalidRatherThanThrowing() {
        final Element message = new Element("message");
        message.setAttribute("from", "not a jid@@@");
        // The parser has to survive a hostile from address; the value must not be a usable Jid.
        assertThat(com.glaciersecurity.glaciermessenger.xmpp.InvalidJid
                        .isValid(message.getAttributeAsJid("from")))
                .isFalse();
    }

    @Test
    public void getAttributeAsBooleanAcceptsTrueAndOneOnly() {
        final Element element = new Element("x");
        element.setAttribute("a", "true");
        element.setAttribute("b", "TRUE");
        element.setAttribute("c", "1");
        element.setAttribute("d", "yes");
        assertThat(element.getAttributeAsBoolean("a")).isTrue();
        assertThat(element.getAttributeAsBoolean("b")).isTrue();
        assertThat(element.getAttributeAsBoolean("c")).isTrue();
        assertThat(element.getAttributeAsBoolean("d")).isFalse();
        assertThat(element.getAttributeAsBoolean("missing")).isFalse();
    }

    @Test
    public void removeAttributeDropsItFromTheSerialisedForm() {
        final Element element = new Element("x");
        element.setAttribute("id", "1");
        element.removeAttribute("id");
        assertThat(element.toString()).isEqualTo("<x/>");
    }

    @Test
    public void numericAttributeOverloadsSerialiseAsText() {
        final Element element = new Element("x");
        element.setAttribute("count", 42);
        assertThat(element.getAttribute("count")).isEqualTo("42");
        assertThat(element.getLongAttribute("count")).isEqualTo(42L);
    }

    @Test
    public void getLongAttributeFallsBackToZeroForNonNumericText() {
        final Element element = new Element("x");
        element.setAttribute("count", "not-a-number");
        assertThat(element.getLongAttribute("count")).isEqualTo(0L);
        assertThat(element.getOptionalIntAttribute("count").isPresent()).isFalse();
    }
}
