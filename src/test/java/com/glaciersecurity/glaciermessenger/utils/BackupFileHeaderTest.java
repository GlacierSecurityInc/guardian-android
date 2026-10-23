package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * The header is the first thing read back from a .ceb backup, and the IV and salt it carries
 * are what make the rest of the file decryptable. A round trip that loses a byte here is an
 * unrestorable backup — which is only discovered when someone needs it.
 */
public class BackupFileHeaderTest {

    private static final String APP = "Guardian";
    private static final String JID = "testuser@example.com";
    private static final long TIMESTAMP = 1753212981000L;

    @Test
    public void roundTripPreservesEveryField() throws IOException {
        final byte[] iv = fill(12, (byte) 0xA1);
        final byte[] salt = fill(16, (byte) 0xB2);

        final BackupFileHeader read = readBack(
                new BackupFileHeader(APP, Jid.of(JID), TIMESTAMP, iv, salt));

        assertThat(read.getApp()).isEqualTo(APP);
        assertThat(read.getJid().toEscapedString()).isEqualTo(JID);
        assertThat(read.getTimestamp()).isEqualTo(TIMESTAMP);
        assertThat(read.getIv()).isEqualTo(iv);
        assertThat(read.getSalt()).isEqualTo(salt);
    }

    @Test
    public void ivAndSaltAreNotConfusedWithEachOther() throws IOException {
        // They are adjacent, fixed-width and different lengths; swapping them still reads
        // cleanly but decrypts to nothing.
        final byte[] iv = sequence(12, 0);
        final byte[] salt = sequence(16, 100);

        final BackupFileHeader read = readBack(
                new BackupFileHeader(APP, Jid.of(JID), TIMESTAMP, iv, salt));

        assertThat(read.getIv()).hasLength(12);
        assertThat(read.getSalt()).hasLength(16);
        assertThat(read.getIv()[0]).isEqualTo((byte) 0);
        assertThat(read.getSalt()[0]).isEqualTo((byte) 100);
    }

    @Test
    public void aFullJidIsStoredBare() throws IOException {
        final BackupFileHeader read = readBack(new BackupFileHeader(
                APP, Jid.of(JID + "/phone"), TIMESTAMP, fill(12, (byte) 1), fill(16, (byte) 2)));

        assertThat(read.getJid().toEscapedString()).isEqualTo(JID);
    }

    @Test
    public void anOlderBackupIsRejectedAsOutdatedNotAsCorrupt() {
        // Distinguishing the two is what lets the restore screen tell the user their backup is
        // from an older release rather than that the file is broken.
        final byte[] header = handWrittenHeader(1);

        assertThrows(BackupFileHeader.OutdatedBackupFileVersion.class,
                () -> BackupFileHeader.read(new DataInputStream(new ByteArrayInputStream(header))));
    }

    @Test
    public void aNewerBackupIsRejectedWithAnExplanation() {
        final byte[] header = handWrittenHeader(3);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BackupFileHeader.read(new DataInputStream(new ByteArrayInputStream(header))));
        assertThat(thrown).hasMessageThat().contains("version was 3");
    }

    @Test
    public void aTruncatedHeaderFailsAsIoNotAsSilentlyEmpty() {
        final byte[] header = handWrittenHeader(2);
        final byte[] truncated = Arrays.copyOf(header, header.length - 4);

        assertThrows(EOFException.class,
                () -> BackupFileHeader.read(
                        new DataInputStream(new ByteArrayInputStream(truncated))));
    }

    private static BackupFileHeader readBack(final BackupFileHeader header) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            header.write(out);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return BackupFileHeader.read(in);
        }
    }

    private static byte[] handWrittenHeader(final int version) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(version);
            out.writeUTF(APP);
            out.writeUTF(JID);
            out.writeLong(TIMESTAMP);
            out.write(fill(12, (byte) 1));
            out.write(fill(16, (byte) 2));
        } catch (final IOException impossible) {
            throw new AssertionError(impossible);
        }
        return bytes.toByteArray();
    }

    private static byte[] fill(final int length, final byte value) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static byte[] sequence(final int length, final int start) {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (start + i);
        }
        return bytes;
    }
}
