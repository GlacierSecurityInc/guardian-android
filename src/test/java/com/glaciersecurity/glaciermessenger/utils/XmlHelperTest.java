package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Everything the app puts on the wire passes through here. An unescaped angle bracket in a
 * message body is a malformed stanza at best and stanza injection at worst.
 */
public class XmlHelperTest {

    @Test
    public void escapesTheFivePredefinedEntities() {
        assertThat(XmlHelper.encodeEntities("<>&\"'"))
                .isEqualTo("&lt;&gt;&amp;&quot;&apos;");
    }

    @Test
    public void escapesTheAmpersandFirstSoEntitiesAreNotDoubleEncoded() {
        assertThat(XmlHelper.encodeEntities("a & b < c")).isEqualTo("a &amp; b &lt; c");
    }

    @Test
    public void closingATagInsideABodyCannotEscapeIt() {
        assertThat(XmlHelper.encodeEntities("</body><iq type='set'>")).doesNotContain("<");
    }

    @Test
    public void stripsControlCharacters() {
        // XML 1.0 cannot represent these at all, so they have to be dropped rather than
        // escaped. U+0001 and U+001F bracket the range that gets removed.
        final String withControls = "a" + (char) 0x01 + "b" + (char) 0x1F + "c";
        assertThat(XmlHelper.encodeEntities(withControls)).isEqualTo("abc");
    }

    @Test
    public void keepsTheWhitespaceControlCharactersXmlAllows() {
        assertThat(XmlHelper.encodeEntities("a\nb\tc\rd")).isEqualTo("a\nb\tc\rd");
    }

    @Test
    public void leavesOrdinaryTextUntouched() {
        assertThat(XmlHelper.encodeEntities("hello world")).isEqualTo("hello world");
    }
}
