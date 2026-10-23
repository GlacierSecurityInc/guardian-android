package com.glaciersecurity.glaciermessenger.xmpp.forms;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

/**
 * Data is the XEP-0004 form itself. The app almost never builds one from scratch: it takes the
 * form the server sent, fills in the fields it understands and submits that same element back,
 * which is why the editing methods work through {@link Field#parse} views onto the original
 * children rather than copies.
 *
 * <p>{@code submit} is the interesting one. Sending a form back verbatim would echo the server's
 * own labels, options and required markers as though they were answers, so it strips the element
 * down to fields and their values first. Getting that wrong does not fail loudly -- the server
 * simply rejects or misreads the submission -- so the stripping is asserted explicitly.
 *
 * <p>FORM_TYPE is deliberately hidden from {@code getFields}: it identifies which form this is
 * and is not a question for the user to answer, so it must not appear in a rendered form while
 * still being submitted.
 *
 * <p>The two {@code Bundle}-taking methods are not covered here; they need the Robolectric tier
 * rather than a stub that answers null. See src/test/README.md.
 */
public class DataTest {

    /** The shape a server sends: a titled form with one question carrying label and options. */
    private static Data serverForm() {
        final Element element = new Element("x", Namespace.DATA);
        element.setAttribute("type", "form");
        element.addChild("title").setContent("Room configuration");
        element.addChild("instructions").setContent("Please fill this in");

        final Element formType = new Element("field");
        formType.setAttribute("var", Data.FORM_TYPE);
        formType.setAttribute("type", "hidden");
        formType.addChild("value").setContent("http://jabber.org/protocol/muc#roomconfig");
        element.addChild(formType);

        final Element roomName = new Element("field");
        roomName.setAttribute("var", "muc#roomconfig_roomname");
        roomName.setAttribute("label", "Room name");
        roomName.addChild("value").setContent("lobby");
        roomName.addChild("required");
        element.addChild(roomName);

        return Data.parse(element);
    }

    @Test
    public void aFreshFormCarriesTheDataNamespace() {
        assertThat(new Data().getNamespace()).isEqualTo(Namespace.DATA);
    }

    @Test
    public void theFormTypeFieldIsNotOneOfTheQuestions() {
        // It identifies the form; showing it as a question would ask the user to confirm the
        // protocol namespace.
        assertThat(serverForm().getFields()).hasSize(1);
        assertThat(serverForm().getFields().get(0).getFieldName())
                .isEqualTo("muc#roomconfig_roomname");
    }

    @Test
    public void theFormTypeIsStillReadableByName() {
        assertThat(serverForm().getFormType())
                .isEqualTo("http://jabber.org/protocol/muc#roomconfig");
    }

    @Test
    public void aFormWithNoFormTypeReportsAnEmptyOneRatherThanNull() {
        assertThat(new Data().getFormType()).isEmpty();
    }

    @Test
    public void theTitleIsReadOffTheForm() {
        assertThat(serverForm().getTitle()).isEqualTo("Room configuration");
        assertThat(new Data().getTitle()).isNull();
    }

    @Test
    public void aFieldIsFoundByItsName() {
        assertThat(serverForm().getFieldByName("muc#roomconfig_roomname")).isNotNull();
        assertThat(serverForm().getFieldByName("muc#roomconfig_nothing")).isNull();
    }

    @Test
    public void readingAValueOfAnAbsentFieldIsNullRatherThanAnError() {
        assertThat(serverForm().getValue("muc#roomconfig_nothing")).isNull();
        assertThat(serverForm().getValue("muc#roomconfig_roomname")).isEqualTo("lobby");
    }

    @Test
    public void puttingAValueOnAnExistingFieldEditsTheFormInPlace() {
        // Not appended as a second field: the server would then see two answers to one question.
        final Data data = serverForm();
        data.put("muc#roomconfig_roomname", "atrium");

        assertThat(data.getValue("muc#roomconfig_roomname")).isEqualTo("atrium");
        assertThat(data.getFields()).hasSize(1);
    }

    @Test
    public void puttingAValueOnAnUnknownFieldAddsIt() {
        final Data data = serverForm();
        data.put("muc#roomconfig_roomsecret", "hunter2");

        assertThat(data.getValue("muc#roomconfig_roomsecret")).isEqualTo("hunter2");
        assertThat(data.getFields()).hasSize(2);
    }

    @Test
    public void aMultiValuedAnswerReplacesTheWholeSet() {
        final Data data = new Data();
        data.put("muc#roomconfig_presencebroadcast", ImmutableList.of("moderator", "participant"));

        assertThat(data.getFieldByName("muc#roomconfig_presencebroadcast").getValues())
                .containsExactly("moderator", "participant")
                .inOrder();
    }

    @Test
    public void settingTheFormTypeMarksItHidden() {
        final Data data = new Data();
        data.setFormType("http://jabber.org/protocol/muc#roomconfig");

        assertThat(data.getFormType()).isEqualTo("http://jabber.org/protocol/muc#roomconfig");
        assertThat(data.getFieldByName(Data.FORM_TYPE).getType()).isEqualTo("hidden");
        // Hidden or not, it is still not a question.
        assertThat(data.getFields()).isEmpty();
    }

    @Test
    public void submittingMarksTheFormAsASubmission() {
        final Data data = serverForm();
        data.submit();

        assertThat(data.getAttribute("type")).isEqualTo("submit");
    }

    @Test
    public void submittingDropsEverythingThatIsNotAFieldOrTheTitle() {
        final Data data = serverForm();
        data.submit();

        assertThat(data.findChild("instructions")).isNull();
        assertThat(data.getTitle()).isEqualTo("Room configuration");
    }

    @Test
    public void submittingStripsTheQuestionAndKeepsTheAnswer() {
        // The label and the required marker came from the server; echoing them back as part of
        // the answer is what this strip exists to prevent.
        final Data data = serverForm();
        data.submit();

        final Field answered = data.getFieldByName("muc#roomconfig_roomname");
        assertThat(answered.getValue()).isEqualTo("lobby");
        assertThat(answered.isRequired()).isFalse();
        assertThat(answered.getChildren()).hasSize(1);
    }

    @Test
    public void submittingKeepsTheFormTypeItWillBeIdentifiedBy() {
        // getFields hides it, and submit() iterates getFields -- so the check is that the strip
        // did not take the hidden field with it.
        final Data data = serverForm();
        data.submit();

        assertThat(data.getFormType())
                .isEqualTo("http://jabber.org/protocol/muc#roomconfig");
    }

    @Test
    public void parseReturnsAViewOntoTheOriginalElement() {
        final Element element = new Element("x", Namespace.DATA);
        final Element field = new Element("field");
        field.setAttribute("var", "muc#roomconfig_roomname");
        field.addChild("value").setContent("before");
        element.addChild(field);

        Data.parse(element).put("muc#roomconfig_roomname", "after");

        assertThat(field.findChildContent("value")).isEqualTo("after");
    }
}
