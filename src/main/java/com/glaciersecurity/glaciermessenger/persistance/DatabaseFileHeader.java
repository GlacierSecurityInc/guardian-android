package com.glaciersecurity.glaciermessenger.persistance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tells an unencrypted SQLite file from an encrypted one by looking at its first bytes.
 *
 * <p>A plain SQLite database starts with the 16-byte magic {@code "SQLite format 3\0"}.
 * SQLCipher encrypts page 1 in full, magic included, so an encrypted database starts with what looks
 * like random bytes. That one comparison is the only reliable way to ask "which format is this file
 * in?" without attempting an open, and attempting an open is exactly what we cannot afford to do
 * blindly: the framework opener throws on an encrypted file and the SQLCipher opener throws on a
 * plaintext one, so whichever we guess wrong about produces an exception that reads like corruption.
 *
 * <p>Deliberately free of any {@code net.zetetic} or {@code android.database} import, so it stays on
 * the JVM unit-test classpath -- see the net.zetetic exclusion in build.gradle.
 */
public final class DatabaseFileHeader {

    /** The 16 bytes every unencrypted SQLite 3 database begins with, including the trailing NUL. */
    private static final byte[] SQLITE_MAGIC =
            "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private DatabaseFileHeader() { /* utility class */ }

    public enum Format {
        /** No file, or a zero-length one. A fresh install; whoever opens it next creates it. */
        ABSENT,
        /** Unencrypted SQLite: a file whose migration to SQLCipher has not run yet. */
        PLAINTEXT,
        /**
         * Not plaintext SQLite. Almost always SQLCipher, but a truncated or genuinely corrupt file
         * lands here too -- the header alone cannot separate those, and callers should not pretend
         * otherwise.
         */
        ENCRYPTED_OR_UNREADABLE
    }

    @NonNull
    public static Format classify(@Nullable final File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return Format.ABSENT;
        }
        final byte[] header = new byte[SQLITE_MAGIC.length];
        try (final InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < header.length) {
                final int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < header.length) {
                // Shorter than the magic itself. Not a usable database either way.
                return Format.ENCRYPTED_OR_UNREADABLE;
            }
        } catch (final IOException e) {
            return Format.ENCRYPTED_OR_UNREADABLE;
        }
        return classify(header);
    }

    /** Header-bytes overload, so the comparison itself is testable without touching the filesystem. */
    @NonNull
    public static Format classify(@Nullable final byte[] header) {
        if (header == null || header.length < SQLITE_MAGIC.length) {
            return Format.ENCRYPTED_OR_UNREADABLE;
        }
        for (int i = 0; i < SQLITE_MAGIC.length; ++i) {
            if (header[i] != SQLITE_MAGIC[i]) {
                return Format.ENCRYPTED_OR_UNREADABLE;
            }
        }
        return Format.PLAINTEXT;
    }
}
