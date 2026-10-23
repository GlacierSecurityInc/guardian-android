package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

/**
 * Covers: "Message Confirmations" (Settings) -- who a message has been read by.
 *
 * <p>Markers are persisted as JSON on the message row and compared across resources: the same
 * person read from a phone and a desktop must count once, which is why the real JID is stored
 * bare.
 */
public class ReadByMarkerTest {

    private static final Jid PHONE = Jid.of("alice@example.com/phone");
    private static final Jid DESKTOP = Jid.of("alice@example.com/desktop");
    private static final Jid MUC_NICK = Jid.of("room@conference.example.com/alice");

    @Test
    public void storesTheRealJidBareSoResourcesDoNotMultiplyReaders() {
        final ReadByMarker marker = ReadByMarker.from(MUC_NICK, PHONE);

        assertEquals(MUC_NICK, marker.getFullJid());
        assertEquals(Jid.of("alice@example.com"), marker.getRealJid());
    }

    @Test
    public void readsBackWhatItWrote() {
        final Set<ReadByMarker> markers =
                Collections.singleton(ReadByMarker.from(MUC_NICK, PHONE));

        final Set<ReadByMarker> parsed =
                ReadByMarker.fromJsonString(ReadByMarker.toJson(markers).toString());

        assertEquals(markers, parsed);
    }

    @Test
    public void treatsAMissingOrUnreadableBlobAsNobody() {
        assertTrue(ReadByMarker.fromJsonString(null).isEmpty());
        assertTrue(ReadByMarker.fromJsonString("").isEmpty());
        assertTrue(ReadByMarker.fromJsonString("not json at all").isEmpty());
        assertTrue(ReadByMarker.fromJsonString("[]").isEmpty());
    }

    @Test
    public void keepsAMarkerWhoseRealJidIsUnknown() {
        final Set<ReadByMarker> parsed =
                ReadByMarker.fromJsonString("[{\"fullJid\":\"room@conference.example.com/alice\"}]");

        assertEquals(1, parsed.size());
        final ReadByMarker marker = parsed.iterator().next();
        assertEquals(MUC_NICK, marker.getFullJid());
        assertNull(marker.getRealJid());
    }

    @Test
    public void theSamePersonReadingFromTwoDevicesCountsOnce() {
        final Set<ReadByMarker> markers =
                Collections.singleton(ReadByMarker.from(MUC_NICK, PHONE));

        assertTrue(ReadByMarker.contains(ReadByMarker.from(MUC_NICK, DESKTOP), markers));
    }

    @Test
    public void aDifferentPersonIsNotAlreadyRepresented() {
        final Set<ReadByMarker> markers =
                Collections.singleton(ReadByMarker.from(MUC_NICK, PHONE));

        final ReadByMarker other =
                ReadByMarker.from(
                        Jid.of("room@conference.example.com/bob"), Jid.of("bob@example.com/x"));

        assertFalse(ReadByMarker.contains(other, markers));
    }

    /** With no real JIDs on either side -- an anonymous MUC -- the nick is all there is. */
    @Test
    public void fallsBackToTheFullJidWhenNoRealJidIsKnown() {
        final Set<ReadByMarker> markers =
                Collections.singleton(ReadByMarker.from(MUC_NICK, null));

        assertTrue(ReadByMarker.contains(ReadByMarker.from(MUC_NICK, null), markers));
        assertFalse(
                ReadByMarker.contains(
                        ReadByMarker.from(Jid.of("room@conference.example.com/bob"), null),
                        markers));
    }

    @Test
    public void nobodyIsRepresentedInAnEmptyMarkerSet() {
        assertFalse(
                ReadByMarker.contains(
                        ReadByMarker.from(MUC_NICK, PHONE), Collections.emptySet()));
    }
}
