package com.glaciersecurity.glaciermessenger.entities;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import im.conversations.android.xmpp.model.stanza.Iq;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * A MUC only gets OMEMO by default while it is members-only and non-anonymous, because OMEMO
 * needs each occupant's real JID to fetch a bundle. Room config is server-side state the owner
 * can change at any time, and status 104 sends us straight back through
 * {@link MucOptions#updateConfiguration}, so that check on its own means a room can stop being
 * OMEMO-capable underneath us -- and the fallback is plaintext, with nothing said.
 *
 * <p>{@code ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS} is the latch that prevents it. These tests
 * pin where the latch is written: on observing the configuration, not on the first encrypted
 * send, because a room joined but not yet written to has sent nothing to latch on.
 *
 * <p>Robolectric rather than plain JUnit because Conversation's attributes are backed by
 * org.json, whose plain-JUnit stub throws on every call.
 */
@RunWith(RobolectricTestRunner.class)
public class MucConfigurationLatchTest {

    private static final String MEMBERS_ONLY = "muc_membersonly";
    private static final String NON_ANONYMOUS = "muc_nonanonymous";
    private static final String SEMI_ANONYMOUS = "muc_semianonymous";

    private static Conversation muc() {
        final Account account = new Account(Jid.of("self@example.com"), "");
        return new Conversation(
                "room",
                account,
                Jid.of("room@conference.example.com"),
                Conversation.MODE_MULTI);
    }

    /** A disco#info result advertising exactly the given features. */
    private static ServiceDiscoveryResult disco(final String... features) {
        final Iq packet = new Iq(Iq.Type.RESULT);
        final Element query = packet.query("http://jabber.org/protocol/disco#info");
        query.addChild("identity").setAttribute("category", "conference").setAttribute("type", "text");
        for (final String feature : features) {
            query.addChild("feature").setAttribute("var", feature);
        }
        return new ServiceDiscoveryResult(packet);
    }

    /** What a destroyed room, or a rejoin with our affiliation stripped, answers with. */
    private static ServiceDiscoveryResult uninformativeDisco() {
        return new ServiceDiscoveryResult(new Iq(Iq.Type.RESULT));
    }

    private static boolean latched(final Conversation conversation) {
        return conversation.getBooleanAttribute(
                Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, false);
    }

    @Test
    public void observingAPrivateNonAnonymousRoomLatchesItWithoutSendingAnything() {
        final Conversation conversation = muc();
        assertThat(latched(conversation)).isFalse();

        conversation.getMucOptions().updateConfiguration(disco(MEMBERS_ONLY, NON_ANONYMOUS));

        assertThat(conversation.getMucOptions().isPrivateAndNonAnonymous()).isTrue();
        assertThat(latched(conversation)).isTrue();
    }

    @Test
    public void aRoomThatTurnsAnonymousAfterwardsKeepsTheLatch() {
        final Conversation conversation = muc();
        final MucOptions mucOptions = conversation.getMucOptions();
        mucOptions.updateConfiguration(disco(MEMBERS_ONLY, NON_ANONYMOUS));

        // What status 104 delivers after an owner changes muc#roomconfig_whois.
        mucOptions.updateConfiguration(disco(MEMBERS_ONLY, SEMI_ANONYMOUS));

        assertThat(mucOptions.isPrivateAndNonAnonymous()).isFalse();
        assertThat(latched(conversation)).isTrue();
    }

    @Test
    public void aRoomThatWasNeverPrivateAndNonAnonymousIsNotLatched() {
        final Conversation conversation = muc();

        conversation.getMucOptions().updateConfiguration(disco(SEMI_ANONYMOUS));

        assertThat(latched(conversation)).isFalse();
    }

    @Test
    public void anUninformativeDiscoResultDoesNotClearTheLatch() {
        final Conversation conversation = muc();
        final MucOptions mucOptions = conversation.getMucOptions();
        mucOptions.updateConfiguration(disco(MEMBERS_ONLY, NON_ANONYMOUS));

        assertThat(mucOptions.updateConfiguration(uninformativeDisco())).isFalse();

        assertThat(mucOptions.isPrivateAndNonAnonymous()).isTrue();
        assertThat(latched(conversation)).isTrue();
    }
}
