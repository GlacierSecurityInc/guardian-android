package com.glaciersecurity.glaciermessenger.persistance;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.utils.Log;

/**
 * Loads {@code libsqlcipher.so}, once per process, before anything touches a SQLCipher database.
 *
 * <p>sqlcipher-android does <b>not</b> load its own native library -- there is no static
 * initializer doing it and, unlike the retired net.sqlcipher artifact, no {@code loadLibs(Context)}
 * to call. Nothing fails at class-load time either; the classes resolve fine and the first actual
 * open dies deep inside the connection pool with
 * {@code UnsatisfiedLinkError: No implementation found for long
 * net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)}, which reads like a packaging
 * problem rather than a missing call. Hence this class, and hence {@link #ensureLoaded()} at the top
 * of every entry point that can be the first to open a database.
 */
public final class SqlCipherNative {

    private static boolean loaded;
    private static UnsatisfiedLinkError failure;

    private SqlCipherNative() { /* utility class */ }

    /**
     * @throws UnsatisfiedLinkError if the native library is unavailable for this ABI. Latched, so a
     *     broken install fails the same way every time instead of re-attempting a load that cannot
     *     succeed.
     */
    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (failure != null) {
            throw failure;
        }
        try {
            System.loadLibrary("sqlcipher");
            loaded = true;
        } catch (final UnsatisfiedLinkError e) {
            failure = e;
            Log.e(Config.LOGTAG, "could not load libsqlcipher.so", e);
            throw e;
        }
    }

    /** Whether the native library is usable, without throwing. For diagnostics and tests. */
    public static synchronized boolean isAvailable() {
        try {
            ensureLoaded();
            return true;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }
}
