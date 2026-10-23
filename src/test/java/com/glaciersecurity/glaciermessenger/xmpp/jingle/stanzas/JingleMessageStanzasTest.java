package com.glaciersecurity.glaciermessenger.xmpp.jingle.stanzas;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import org.junit.Test;

import java.util.List;

/**
 * The two XEP-0353 elements this app parses off an incoming message: {@code <propose/>}, which
 * offers a call and says what kind, and {@code <proceed/>}, which accepts one.
 *
 * <p>{@code Propose.getDescriptions} is the interesting half. It is how the app decides whether a
 * ringing notification is for a voice call, a video call or a file transfer, from a namespace the
 * caller chose -- so an unrecognised one has to reach the generic wrapper rather than be dropped,
 * or the call rings as nothing.
 *
 * <p>Both classes are parse-only on this line: {@code JingleRtpConnection} upgrades an incoming
 * element and reads it, and neither is ever serialised back out. That matters for the last test,
 * which pins a bug the parse path cannot expose.
 */
public class JingleMessageStanzasTest {

    private static Propose proposeWith(final String... descriptionNamespaces) {
        final Element element = new Element("propose", Namespace.JINGLE_MESSAGE);
        element.setAttribute("id", "a-session-id");
        for (final String namespace : descriptionNamespaces) {
            element.addChild("description", namespace);
        }
        return Propose.upgrade(element);
    }

    // ---- propose ----------------------------------------------------------------------------

    @Test
    public void aProposeWithNoDescriptionOffersNothing() {
        assertThat(proposeWith().getDescriptions()).isEmpty();
    }

    @Test
    public void aFileTransferProposalIsRecognised() {
        final List<GenericDescription> descriptions =
                proposeWith(Namespace.JINGLE_APPS_FILE_TRANSFER).getDescriptions();

        assertThat(descriptions).hasSize(1);
        assertThat(descriptions.get(0)).isInstanceOf(FileTransferDescription.class);
    }

    @Test
    public void anUnknownProposalStillParsesAsAGenericDescription() {
        // The caller picks this namespace. Dropping it would leave a proposal that rings as
        // nothing at all, rather than one the app can decline.
        final List<GenericDescription> descriptions =
                proposeWith("urn:example:jingle:apps:holograms:1").getDescriptions();

        assertThat(descriptions).hasSize(1);
        assertThat(descriptions.get(0).getNamespace())
                .isEqualTo("urn:example:jingle:apps:holograms:1");
    }

    @Test
    public void severalDescriptionsAreAllReported() {
        final List<GenericDescription> descriptions =
                proposeWith(
                                Namespace.JINGLE_APPS_FILE_TRANSFER,
                                "urn:example:jingle:apps:holograms:1")
                        .getDescriptions();

        assertThat(descriptions).hasSize(2);
    }

    @Test
    public void childrenThatAreNotDescriptionsAreIgnored() {
        final Element element = new Element("propose", Namespace.JINGLE_MESSAGE);
        element.addChild("description", Namespace.JINGLE_APPS_FILE_TRANSFER);
        element.addChild("device").setAttribute("id", "1");
        element.addChild("something-else");

        assertThat(Propose.upgrade(element).getDescriptions()).hasSize(1);
    }

    @Test
    public void proposeUpgradeRejectsTheWrongElement() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Propose.upgrade(new Element("proceed", Namespace.JINGLE_MESSAGE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Propose.upgrade(new Element("propose", Namespace.JINGLE)));
    }

    @Test
    public void proposeUpgradeCarriesTheSessionIdAcross() {
        assertThat(proposeWith().getAttribute("id")).isEqualTo("a-session-id");
    }

    // ---- proceed -----------------------------------------------------------------------------

    private static Proceed proceedWith(final Element... children) {
        final Element element = new Element("proceed", Namespace.JINGLE_MESSAGE);
        element.setAttribute("id", "a-session-id");
        for (final Element child : children) {
            element.addChild(child);
        }
        return Proceed.upgrade(element);
    }

    private static Element device(final String id) {
        final Element device = new Element("device");
        if (id != null) {
            device.setAttribute("id", id);
        }
        return device;
    }

    @Test
    public void aProceedNamingADeviceReportsIt() {
        // Which of the peer's devices answered, so the rest of the call is addressed to it
        // rather than to the bare account.
        assertThat(proceedWith(device("4242")).getDeviceId()).isEqualTo(4242);
    }

    @Test
    public void aProceedWithNoDeviceHasNoDeviceId() {
        assertThat(proceedWith().getDeviceId()).isNull();
    }

    @Test
    public void aDeviceWithNoIdHasNoDeviceId() {
        assertThat(proceedWith(device(null)).getDeviceId()).isNull();
    }

    @Test
    public void anUnparseableDeviceIdIsNullRatherThanAnError() {
        // It arrives from the peer mid-call-setup; throwing here would strand the session.
        assertThat(proceedWith(device("not-a-number")).getDeviceId()).isNull();
        assertThat(proceedWith(device("")).getDeviceId()).isNull();
    }

    @Test
    public void proceedUpgradeRejectsTheWrongElement() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Proceed.upgrade(new Element("propose", Namespace.JINGLE_MESSAGE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Proceed.upgrade(new Element("proceed", Namespace.JINGLE)));
    }

    @Test
    public void aParsedProceedIsNamedProposeRatherThanProceed() {
        // Characterisation of a real bug, pinned rather than fixed here. Proceed's private
        // constructor calls super("propose", ...), so the object upgrade() hands back is an
        // element named "propose" -- which in XEP-0353 means "I am offering a call", the opposite
        // of what a proceed says.
        //
        // Harmless today only because Proceed is parse-only: JingleRtpConnection upgrades an
        // incoming element and reads getDeviceId off it, and nothing ever serialises one back
        // out. The first caller to write one would send an offer where an acceptance was meant.
        assertThat(proceedWith().getName()).isEqualTo("propose");
    }
}
