package com.glaciersecurity.glaciermessenger.entities;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.testutil.FakeConversation;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link Message#mergeable} decides whether two consecutive messages are drawn as one bubble. It
 * is a conjunction of about twenty-five conditions, and the reason it is worth testing rather than
 * reading is that every one of them fails the same quiet way: the messages still appear, just
 * attributed to the wrong thing.
 *
 * <p>The condition that matters most is the counterpart. Merging two messages from different
 * senders would attribute one person's text to another inside a single bubble, in a group chat,
 * with no visible seam. The encryption conditions are the same idea a layer down -- an encrypted
 * message must not be merged into a plaintext one, or the lock shown against the pair claims more
 * than it should.
 *
 * <p>{@link Message} takes a {@link Conversational}, which is a six-method interface, so these
 * tests use a fake rather than building a real Conversation and Account. Robolectric rather than
 * plain JUnit because the path reaches {@code TextUtils}, whose stub answers false to
 * {@code isEmpty} for every input.
 */
@RunWith(RobolectricTestRunner.class)
public class MessageMergeTest {

    private static final Jid ALICE = Jid.of("alice@example.com");
    private static final Jid BOB = Jid.of("bob@example.com");

    private static final Conversational CONVERSATION = new FakeConversation(ALICE);

    /** A plain text message that merges with another built the same way. */
    private static Message message(final String body, final Jid counterpart, final long timeSent) {
        final Message message =
                new Message(CONVERSATION, body, Message.ENCRYPTION_NONE, Message.STATUS_SEND);
        message.setCounterpart(counterpart);
        message.setTime(timeSent);
        message.setType(Message.TYPE_TEXT);
        return message;
    }

    private static Message first() {
        return message("first", ALICE, 1_600_000_000_000L);
    }

    private static Message secondAfter(final long millis) {
        return message("second", ALICE, 1_600_000_000_000L + millis);
    }

    // ---- the baseline ---------------------------------------------------------------------

    @Test
    public void twoOrdinaryMessagesFromTheSameSenderMerge() {
        assertThat(first().mergeable(secondAfter(1000))).isTrue();
    }

    @Test
    public void nothingMergesWithNull() {
        assertThat(first().mergeable(null)).isFalse();
    }

    // ---- who sent it -----------------------------------------------------------------------

    @Test
    public void messagesFromDifferentSendersDoNotMerge() {
        // The one that would attribute one person's words to another, inside one bubble, with no
        // visible seam.
        final Message fromBob = message("second", BOB, 1_600_000_000_000L + 1000);

        assertThat(first().mergeable(fromBob)).isFalse();
    }

    @Test
    public void aMessageWithNoCounterpartNeverMerges() {
        // Unknown sender is not the same as "same sender". The counterpart has to be cleared
        // explicitly, because the constructor defaults it from the conversation -- which is also
        // why the merge check leads with an explicit null test rather than relying on equals.
        final Message anonymous = first();
        anonymous.setCounterpart(null);

        assertThat(anonymous.mergeable(secondAfter(1000))).isFalse();
    }

    // ---- encryption -------------------------------------------------------------------------

    @Test
    public void aPlaintextAndAnEncryptedMessageDoNotMerge() {
        // The lock shown against a merged pair would claim more than it should.
        final Message encrypted = secondAfter(1000);
        encrypted.setEncryption(Message.ENCRYPTION_AXOLOTL);

        assertThat(first().mergeable(encrypted)).isFalse();
    }

    @Test
    public void twoMessagesWithMatchingEncryptionMerge() {
        final Message firstEncrypted = first();
        firstEncrypted.setEncryption(Message.ENCRYPTION_AXOLOTL);
        final Message secondEncrypted = secondAfter(1000);
        secondEncrypted.setEncryption(Message.ENCRYPTION_AXOLOTL);

        assertThat(firstEncrypted.mergeable(secondEncrypted)).isTrue();
    }

    @Test
    public void pgpMessagesNeverMergeEvenWithEachOther() {
        // Only none, decrypted and axolotl are mergeable encryptions. A PGP body is still
        // ciphertext at this point, so merging two would concatenate two armoured blocks.
        final Message firstPgp = first();
        firstPgp.setEncryption(Message.ENCRYPTION_PGP);
        final Message secondPgp = secondAfter(1000);
        secondPgp.setEncryption(Message.ENCRYPTION_PGP);

        assertThat(firstPgp.mergeable(secondPgp)).isFalse();
    }

    @Test
    public void aMessageThatFailedToDecryptDoesNotMerge() {
        final Message failed = secondAfter(1000);
        failed.setEncryption(Message.ENCRYPTION_DECRYPTION_FAILED);

        assertThat(first().mergeable(failed)).isFalse();
    }

    @Test
    public void aDifferentOmemoFingerprintPreventsMerging() {
        // Same conversation, same sender, but a different device key: merging would hide that the
        // two halves of the bubble were signed by different devices.
        final Message firstMessage = first();
        firstMessage.setEncryption(Message.ENCRYPTION_AXOLOTL);
        firstMessage.setFingerprint("FINGERPRINT-A");
        final Message secondMessage = secondAfter(1000);
        secondMessage.setEncryption(Message.ENCRYPTION_AXOLOTL);
        secondMessage.setFingerprint("FINGERPRINT-B");

        assertThat(firstMessage.mergeable(secondMessage)).isFalse();
    }

    // ---- time and type -----------------------------------------------------------------------

    @Test
    public void messagesFurtherApartThanTheMergeWindowDoNotMerge() {
        final long justOutside = (Config.MESSAGE_MERGE_WINDOW * 1000L) + 1000L;

        assertThat(first().mergeable(secondAfter(justOutside))).isFalse();
    }

    @Test
    public void messagesInsideTheMergeWindowDoMerge() {
        final long justInside = Config.MESSAGE_MERGE_WINDOW * 1000L;

        assertThat(first().mergeable(secondAfter(justInside))).isTrue();
    }

    @Test
    public void onlyTextMessagesMerge() {
        // A call record or a file transfer is its own row; merging one into a text bubble would
        // hide it entirely.
        final Message rtp = secondAfter(1000);
        rtp.setType(Message.TYPE_RTP_SESSION);

        assertThat(first().mergeable(rtp)).isFalse();
    }

    @Test
    public void anEditedMessageDoesNotMergeWithAnUneditedOne() {
        final Message edited = secondAfter(1000);
        edited.putEdited("original-id", "server-id");

        assertThat(first().mergeable(edited)).isFalse();
    }

    // ---- correcting the last message -----------------------------------------------------------

    @Test
    public void anOutgoingTextMessageIsEditable() {
        assertThat(first().isEditable()).isTrue();
    }

    @Test
    public void aReceivedMessageIsNotEditable() {
        // You can correct what you sent, not what you were sent.
        final Message received =
                new Message(CONVERSATION, "body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);

        assertThat(received.isEditable()).isFalse();
    }

    @Test
    public void aCarbonCopyIsNotEditable() {
        // A carbon is this account's message seen from another device; correcting it here would
        // race the device that actually sent it.
        final Message carbon = first();
        carbon.setCarbon(true);

        assertThat(carbon.isEditable()).isFalse();
    }

    @Test
    public void aCallRecordIsNotEditable() {
        final Message rtp = first();
        rtp.setType(Message.TYPE_RTP_SESSION);

        assertThat(rtp.isEditable()).isFalse();
    }
}
