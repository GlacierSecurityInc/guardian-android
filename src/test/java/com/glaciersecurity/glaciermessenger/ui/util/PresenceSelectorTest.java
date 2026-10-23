package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;

/**
 * {@link PresenceSelector#getNextCounterpart} turns "which of this contact's devices did the user
 * pick" into the JID a direct RTP session is addressed to. The empty-resource case is the one that
 * matters: it means "no particular device", and it has to produce a bare JID rather than a full
 * one with an empty resource, which no client would answer.
 *
 * <p>Plain JUnit: Jid is pure Java.
 */
public class PresenceSelectorTest {

    private static final Jid BARE = Jid.of("alice@example.com");

    @Test
    public void anEmptyResourceMeansTheBareJid() {
        final Jid selected = PresenceSelector.getNextCounterpart(BARE, "");
        assertThat(selected).isEqualTo(BARE.asBareJid());
        assertThat(selected.getResource()).isNull();
    }

    @Test
    public void aChosenResourceProducesAFullJid() {
        final Jid selected = PresenceSelector.getNextCounterpart(BARE, "phone");
        assertThat(selected.asBareJid()).isEqualTo(BARE);
        assertThat(selected.getResource()).isEqualTo("phone");
        assertThat(selected.toString()).isEqualTo("alice@example.com/phone");
    }

    /** The starting JID may already be full; the picked resource replaces it rather than nesting. */
    @Test
    public void aResourceOnAnAlreadyFullJidIsReplaced() {
        final Jid full = Jid.of("alice@example.com/laptop");
        final Jid selected = PresenceSelector.getNextCounterpart(full, "phone");
        assertThat(selected.toString()).isEqualTo("alice@example.com/phone");
    }

    @Test
    public void anEmptyResourceStripsAnExistingOne() {
        final Jid full = Jid.of("alice@example.com/laptop");
        assertThat(PresenceSelector.getNextCounterpart(full, "")).isEqualTo(BARE);
    }

    @Test
    public void aDomainOnlyJidIsHandled() {
        final Jid domain = Jid.of("example.com");
        assertThat(PresenceSelector.getNextCounterpart(domain, "")).isEqualTo(domain);
        assertThat(PresenceSelector.getNextCounterpart(domain, "gateway").toString())
                .isEqualTo("example.com/gateway");
    }
}
