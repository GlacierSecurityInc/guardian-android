package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers: "Message Correction" (Settings / Interface).
 *
 * <p>Edits are persisted as a JSON blob on the message row, so a malformed or missing blob has
 * to degrade to "no edits" rather than throw while a conversation is being loaded.
 */
public class EditTest {

    @Test
    public void readsBackWhatItWrote() throws Exception {
        final List<Edit> edits =
                Arrays.asList(new Edit("edited-1", "server-1"), new Edit("edited-2", "server-2"));

        final List<Edit> parsed = Edit.fromJson(Edit.toJson(edits));

        assertEquals(edits, parsed);
    }

    @Test
    public void keepsAnEditWhoseServerIdIsUnknown() throws Exception {
        final List<Edit> edits = Collections.singletonList(new Edit("edited-1", null));

        final List<Edit> parsed = Edit.fromJson(Edit.toJson(edits));

        assertEquals(1, parsed.size());
        assertEquals("edited-1", parsed.get(0).getEditedId());
        assertEquals(edits, parsed);
    }

    @Test
    public void treatsAMissingOrUnreadableBlobAsNoEdits() {
        assertTrue(Edit.fromJson(null).isEmpty());
        assertTrue(Edit.fromJson("").isEmpty());
        assertTrue(Edit.fromJson("not json at all").isEmpty());
        assertTrue(Edit.fromJson("{\"edited_id\":\"x\"}").isEmpty());
    }

    @Test
    public void findsAPreviouslyEditedRemoteId() {
        final List<Edit> edits = Collections.singletonList(new Edit("edited-1", "server-1"));

        assertTrue(Edit.wasPreviouslyEditedRemoteMsgId(edits, "edited-1"));
        assertFalse(Edit.wasPreviouslyEditedRemoteMsgId(edits, "server-1"));
        assertFalse(Edit.wasPreviouslyEditedRemoteMsgId(edits, "other"));
    }

    @Test
    public void findsAPreviouslyEditedServerId() {
        final List<Edit> edits = Collections.singletonList(new Edit("edited-1", "server-1"));

        assertTrue(Edit.wasPreviouslyEditedServerMsgId(edits, "server-1"));
        assertFalse(Edit.wasPreviouslyEditedServerMsgId(edits, "edited-1"));
    }

    /**
     * A null id must never match a null needle -- both sides are routinely null and matching
     * them would collapse unrelated messages onto one correction.
     */
    @Test
    public void aNullIdMatchesNothing() {
        final List<Edit> edits = Collections.singletonList(new Edit(null, null));

        assertFalse(Edit.wasPreviouslyEditedRemoteMsgId(edits, null));
        assertFalse(Edit.wasPreviouslyEditedServerMsgId(edits, null));
        assertNull(edits.get(0).getEditedId());
    }
}
