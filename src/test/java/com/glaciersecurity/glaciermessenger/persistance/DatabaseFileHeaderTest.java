package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Everything downstream of encryption branches on this classification, and getting it wrong is
 * not a visible failure -- misreading an encrypted file as plaintext makes the app try to open it with
 * the framework opener, which throws something that reads like corruption. So the boundary cases get
 * pinned here rather than discovered on a device.
 *
 * <p>This lives in the JVM tier on purpose: {@link DatabaseFileHeader} deliberately imports neither
 * {@code net.zetetic} nor {@code android.database}, which is what keeps it reachable here at all --
 * sqlcipher-android is excluded from the unit-test runtime classpath because its native library
 * cannot load on the JVM.
 */
public class DatabaseFileHeaderTest {

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void realSqliteMagicIsPlaintext() {
        assertThat(DatabaseFileHeader.classify(MAGIC))
                .isEqualTo(DatabaseFileHeader.Format.PLAINTEXT);
    }

    /**
     * The trailing NUL is part of the magic. A file starting with the readable text but not the
     * terminator is not a SQLite database, and must not be treated as one.
     */
    @Test
    public void magicWithoutTheTrailingNulIsNotPlaintext() {
        final byte[] header = Arrays.copyOf(MAGIC, MAGIC.length);
        header[header.length - 1] = ' ';
        assertThat(DatabaseFileHeader.classify(header))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }

    @Test
    public void randomBytesLookEncrypted() {
        final byte[] header = new byte[16];
        Arrays.fill(header, (byte) 0x7f);
        assertThat(DatabaseFileHeader.classify(header))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }

    @Test
    public void headerShorterThanTheMagicIsNotPlaintext() {
        assertThat(DatabaseFileHeader.classify(Arrays.copyOf(MAGIC, MAGIC.length - 1)))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }

    @Test
    public void nullHeaderIsNotPlaintext() {
        assertThat(DatabaseFileHeader.classify((byte[]) null))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }

    @Test
    public void missingFileIsAbsent() {
        assertThat(DatabaseFileHeader.classify(new File(folder.getRoot(), "nope")))
                .isEqualTo(DatabaseFileHeader.Format.ABSENT);
    }

    @Test
    public void nullFileIsAbsent() {
        assertThat(DatabaseFileHeader.classify((File) null))
                .isEqualTo(DatabaseFileHeader.Format.ABSENT);
    }

    /**
     * A zero-length file is what {@code SQLiteOpenHelper} leaves behind if it is interrupted between
     * creating the file and writing page 1. It is not an encrypted database and must not be mistaken
     * for one -- the recovery for ABSENT is "create it", which is correct here.
     */
    @Test
    public void emptyFileIsAbsentRatherThanUnreadable() throws IOException {
        final File file = folder.newFile("empty.db");
        assertThat(file.length()).isEqualTo(0);
        assertThat(DatabaseFileHeader.classify(file)).isEqualTo(DatabaseFileHeader.Format.ABSENT);
    }

    @Test
    public void plaintextFileOnDiskIsRecognised() throws IOException {
        final File file = folder.newFile("plain.db");
        try (final FileOutputStream out = new FileOutputStream(file)) {
            out.write(MAGIC);
            out.write(new byte[512]);
        }
        assertThat(DatabaseFileHeader.classify(file))
                .isEqualTo(DatabaseFileHeader.Format.PLAINTEXT);
    }

    /** A file too short to contain the magic at all is unreadable, not absent -- it has content. */
    @Test
    public void truncatedFileOnDiskIsUnreadable() throws IOException {
        final File file = folder.newFile("truncated.db");
        try (final FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {'S', 'Q', 'L'});
        }
        assertThat(DatabaseFileHeader.classify(file))
                .isEqualTo(DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE);
    }
}
