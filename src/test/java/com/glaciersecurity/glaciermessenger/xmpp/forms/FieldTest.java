package com.glaciersecurity.glaciermessenger.xmpp.forms;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xml.Element;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

/**
 * A Field is one row of an XEP-0004 data form -- the mechanism behind room configuration,
 * registration and ad-hoc commands, so the values here are both read from the server and sent
 * back to it.
 *
 * <p>The behaviour worth pinning down is that setting a value <em>replaces</em> rather than
 * appends. A form is normally filled in by round-tripping the server's own form back to it, so a
 * setter that appended would submit the old value alongside the new one and let the server pick.
 *
 * <p>{@code parse} is a view, not a copy: it hands back a Field sharing the underlying element's
 * attribute and child collections. Data relies on exactly that to edit fields in place, so it is
 * asserted here rather than left as an implementation accident.
 */
public class FieldTest {

    @Test
    public void aFieldIsNamedByItsVarAttribute() {
        final Field field = new Field("muc#roomconfig_roomname");

        assertThat(field.getFieldName()).isEqualTo("muc#roomconfig_roomname");
        assertThat(field.getName()).isEqualTo("field");
    }

    @Test
    public void settingAValueReplacesTheOneAlreadyThere() {
        final Field field = new Field("muc#roomconfig_roomname");
        field.setValue("first");
        field.setValue("second");

        assertThat(field.getValue()).isEqualTo("second");
        assertThat(field.getValues()).containsExactly("second");
    }

    @Test
    public void settingSeveralValuesReplacesTheWholeSet() {
        final Field field = new Field("muc#roomconfig_presencebroadcast");
        field.setValue("moderator");
        field.setValues(ImmutableList.of("participant", "visitor"));

        assertThat(field.getValues()).containsExactly("participant", "visitor").inOrder();
    }

    @Test
    public void aFieldWithNoValueReadsAsNull() {
        assertThat(new Field("var").getValue()).isNull();
        assertThat(new Field("var").getValues()).isEmpty();
    }

    @Test
    public void theLabelAndTypeAreReadOffTheAttributes() {
        final Element element = new Element("field");
        element.setAttribute("var", "muc#roomconfig_roomsecret");
        element.setAttribute("label", "Password");
        element.setAttribute("type", "text-private");

        final Field field = Field.parse(element);

        assertThat(field.getLabel()).isEqualTo("Password");
        assertThat(field.getType()).isEqualTo("text-private");
    }

    @Test
    public void aFieldIsRequiredOnlyWhenItSaysSo() {
        final Field optional = new Field("var");
        assertThat(optional.isRequired()).isFalse();

        final Element element = new Element("field");
        element.addChild("required");
        assertThat(Field.parse(element).isRequired()).isTrue();
    }

    @Test
    public void onlyValueChildrenSurviveTheStrip() {
        // What gets submitted back has to be the answer, not the server's description of the
        // question -- the label, options and required marker are all dropped.
        final Element element = new Element("field");
        element.setAttribute("var", "muc#roomconfig_roomname");
        element.addChild("value").setContent("lobby");
        element.addChild("required");
        element.addChild("option").setContent("something");

        final Field field = Field.parse(element);
        field.removeNonValueChildren();

        assertThat(field.getValues()).containsExactly("lobby");
        assertThat(field.isRequired()).isFalse();
        assertThat(field.getChildren()).hasSize(1);
    }

    @Test
    public void parseReturnsAViewOntoTheOriginalElement() {
        // Data.put depends on this: it edits the Field that getFieldByName handed back and
        // expects the change to land on the form it came from.
        final Element element = new Element("field");
        element.setAttribute("var", "muc#roomconfig_roomname");
        element.addChild("value").setContent("before");

        Field.parse(element).setValue("after");

        assertThat(element.findChildContent("value")).isEqualTo("after");
    }
}
