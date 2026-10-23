package com.glaciersecurity.glaciermessenger.xmpp.forms;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The two {@link Data} methods that take a {@code Bundle}, split out from {@link DataTest} because
 * they need the Robolectric tier: a stubbed {@code Bundle} answers null to {@code keySet} and
 * false to {@code containsKey}, so a plain-JUnit test of these would pass while filling nothing in.
 *
 * <p>This is the path a form takes when a person fills it in -- the UI collects answers into a
 * Bundle and hands it here. The property worth asserting is that it fills in the answers it was
 * given and <em>only</em> those: a field the user did not touch has to keep the value the server
 * sent, not be blanked because the Bundle had no entry for it.
 */
@RunWith(RobolectricTestRunner.class)
public class DataBundleTest {

    /** A form as the server sends it, with two questions and one already answered. */
    private static Data serverForm() {
        final Element element = new Element("x", Namespace.DATA);
        element.setAttribute("type", "form");
        element.addChild("title").setContent("Room configuration");
        element.addChild("instructions").setContent("Please fill this in");

        final Element formType = new Element("field");
        formType.setAttribute("var", Data.FORM_TYPE);
        formType.addChild("value").setContent("http://jabber.org/protocol/muc#roomconfig");
        element.addChild(formType);

        final Element roomName = new Element("field");
        roomName.setAttribute("var", "muc#roomconfig_roomname");
        roomName.setAttribute("label", "Room name");
        roomName.addChild("value").setContent("lobby");
        roomName.addChild("required");
        element.addChild(roomName);

        final Element secret = new Element("field");
        secret.setAttribute("var", "muc#roomconfig_roomsecret");
        secret.addChild("value").setContent("original-secret");
        element.addChild(secret);

        return Data.parse(element);
    }

    @Test
    public void anAnswerFromTheBundleReplacesTheServersValue() {
        final Bundle answers = new Bundle();
        answers.putString("muc#roomconfig_roomname", "atrium");

        final Data form = serverForm();
        form.submit(answers);

        assertThat(form.getValue("muc#roomconfig_roomname")).isEqualTo("atrium");
    }

    @Test
    public void aFieldTheBundleDoesNotMentionKeepsItsValue() {
        // The point of containsKey rather than a plain get: an absent entry means "unchanged",
        // not "clear it". Blanking it would submit an empty room secret.
        final Bundle answers = new Bundle();
        answers.putString("muc#roomconfig_roomname", "atrium");

        final Data form = serverForm();
        form.submit(answers);

        assertThat(form.getValue("muc#roomconfig_roomsecret")).isEqualTo("original-secret");
    }

    @Test
    public void submittingWithABundleAlsoMarksTheFormAsASubmission() {
        final Data form = serverForm();
        form.submit(new Bundle());

        assertThat(form.getAttribute("type")).isEqualTo("submit");
    }

    @Test
    public void submittingWithABundleStillStripsTheQuestion() {
        // It delegates to the no-argument submit, so the server's own labels and required
        // markers are dropped rather than echoed back as answers.
        final Bundle answers = new Bundle();
        answers.putString("muc#roomconfig_roomname", "atrium");

        final Data form = serverForm();
        form.submit(answers);

        assertThat(form.findChild("instructions")).isNull();
        final Field answered = form.getFieldByName("muc#roomconfig_roomname");
        assertThat(answered.isRequired()).isFalse();
        assertThat(answered.getChildren()).hasSize(1);
    }

    @Test
    public void anEntryForAFieldTheFormDoesNotHaveIsIgnored() {
        // submit(Bundle) walks the form's fields, not the bundle's keys, so a stale entry cannot
        // invent a field the server never offered.
        final Bundle answers = new Bundle();
        answers.putString("muc#roomconfig_nonexistent", "value");

        final Data form = serverForm();
        form.submit(answers);

        assertThat(form.getFieldByName("muc#roomconfig_nonexistent")).isNull();
    }

    @Test
    public void theFormTypeIsNotOverwrittenByTheBundle() {
        // FORM_TYPE is hidden from getFields, so submit(Bundle) cannot reach it even if the
        // bundle carries a key by that name -- it identifies the form, it is not an answer.
        final Bundle answers = new Bundle();
        answers.putString(Data.FORM_TYPE, "http://example.com/not-the-real-form");

        final Data form = serverForm();
        form.submit(answers);

        assertThat(form.getFormType())
                .isEqualTo("http://jabber.org/protocol/muc#roomconfig");
    }

    // ---- building a form from scratch ---------------------------------------------------

    @Test
    public void aFormCanBeBuiltFromABundle() {
        final Bundle values = new Bundle();
        values.putString("muc#roomconfig_roomname", "atrium");
        values.putString("muc#roomconfig_roomsecret", "hunter2");

        final Data form = Data.create("http://jabber.org/protocol/muc#roomconfig", values);

        assertThat(form.getAttribute("type")).isEqualTo("submit");
        assertThat(form.getFormType()).isEqualTo("http://jabber.org/protocol/muc#roomconfig");
        assertThat(form.getValue("muc#roomconfig_roomname")).isEqualTo("atrium");
        assertThat(form.getValue("muc#roomconfig_roomsecret")).isEqualTo("hunter2");
    }

    @Test
    public void aFormBuiltFromAnEmptyBundleStillIdentifiesItself() {
        // An ad-hoc command with no parameters is a real case; the form type is what the server
        // dispatches on, so it has to be there even with nothing else.
        final Data form = Data.create("http://jabber.org/protocol/muc#roomconfig", new Bundle());

        assertThat(form.getFormType()).isEqualTo("http://jabber.org/protocol/muc#roomconfig");
        assertThat(form.getFields()).isEmpty();
    }

    @Test
    public void aBuiltFormMarksItsFormTypeHidden() {
        final Data form = Data.create("http://jabber.org/protocol/muc#roomconfig", new Bundle());

        assertThat(form.getFieldByName(Data.FORM_TYPE).getType()).isEqualTo("hidden");
    }
}
