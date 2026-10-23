package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

/**
 * Group carries the SDP {@code a=group:BUNDLE audio video} line across to Jingle, which is what
 * lets a call put every stream on one ICE transport instead of negotiating one per stream.
 *
 * <p>It is translated in both directions -- parsed from an SDP string when reading a local
 * offer, and read back out of XML when the peer sends one -- so the two representations are
 * checked against each other here. A group naming a content that does not exist, or a group
 * silently dropped because the string was malformed, degrades a bundled call into a much slower
 * negotiation rather than failing outright, so the null cases are asserted explicitly.
 */
public class GroupTest {

    @Test
    public void anSdpGroupLineIsSplitIntoSemanticsAndTags() {
        final Group group = Group.ofSdpString("BUNDLE audio video");

        assertThat(group.getSemantics()).isEqualTo("BUNDLE");
        assertThat(group.getIdentificationTags()).containsExactly("audio", "video").inOrder();
    }

    @Test
    public void aGroupWithASingleTagIsStillAGroup() {
        assertThat(Group.ofSdpString("BUNDLE audio").getIdentificationTags())
                .containsExactly("audio");
    }

    @Test
    public void aGroupWithNoTagsIsNotAGroup() {
        // Semantics alone bundles nothing; the caller has to treat this as absent.
        assertThat(Group.ofSdpString("BUNDLE")).isNull();
        assertThat(Group.ofSdpString("")).isNull();
    }

    @Test
    public void theTagsBecomeContentChildren() {
        final Group group = new Group("BUNDLE", ImmutableList.of("audio", "video"));

        assertThat(group.getName()).isEqualTo("group");
        assertThat(group.getNamespace()).isEqualTo(Namespace.JINGLE_APPS_GROUPING);
        assertThat(group.getChildren()).hasSize(2);
        assertThat(group.getChildren().get(0).getAttribute("name")).isEqualTo("audio");
    }

    @Test
    public void aGroupSurvivesTheRoundTripThroughXml() {
        final Group original = Group.ofSdpString("BUNDLE audio video");
        final Group parsed = Group.upgrade(Group.upgrade(original));

        assertThat(parsed.getSemantics()).isEqualTo("BUNDLE");
        assertThat(parsed.getIdentificationTags()).containsExactly("audio", "video").inOrder();
    }

    @Test
    public void onlyNamedContentChildrenCountAsTags() {
        final Element element = new Element("group", Namespace.JINGLE_APPS_GROUPING);
        element.setAttribute("semantics", "BUNDLE");
        element.addChild(new Element("content").setAttribute("name", "audio"));
        // A content child with no name identifies nothing and must not become a null tag.
        element.addChild(new Element("content"));
        // Not a content element at all.
        element.addChild(new Element("description").setAttribute("name", "video"));

        assertThat(Group.upgrade(element).getIdentificationTags()).containsExactly("audio");
    }

    @Test
    public void upgradeRejectsAnElementThatIsNotAGroup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Group.upgrade(new Element("content", Namespace.JINGLE_APPS_GROUPING)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Group.upgrade(new Element("group", Namespace.JINGLE)));
    }
}
