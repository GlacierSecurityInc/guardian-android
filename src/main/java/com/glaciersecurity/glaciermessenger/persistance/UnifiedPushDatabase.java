package com.glaciersecurity.glaciermessenger.persistance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.services.UnifiedPushBroker;

/**
 * Registrations for the UnifiedPush distributor, held in a SQLCipher-encrypted database.
 *
 * <p>This is the smaller of the app's two databases and it went first, deliberately: it proves
 * the SQLCipher dependency, the native library and the passphrase path on a database whose entire
 * contents are disposable, before any of that is pointed at {@code history}, which holds every message
 * the user has ever received.
 *
 * <p>Two properties make this database unusually forgiving, and <b>neither holds for {@code
 * history}</b>, so the handling here must not be copied over wholesale:
 *
 * <ul>
 *   <li>Every row is re-established by the push registration handshake. {@link #deletePushTargets()}
 *       already wipes the table as part of ordinary operation, so discarding it costs a re-register,
 *       not user data. That is why there is no migration below -- a pre-existing legacy file is
 *       simply deleted and an encrypted database is created in its place.
 *   <li>Losing the passphrase is therefore recoverable in place. {@link #recreateEncrypted} throws
 *       away the key and the file together and starts again. For {@code history} the same code would
 *       be silent, total data loss, and the design there must instead fail closed and tell the user.
 * </ul>
 */
public class UnifiedPushDatabase extends SQLiteOpenHelper {
    /**
     * The encrypted database. Deliberately <b>not</b> the name older builds use.
     *
     * <p>Encrypting in place would have been a one-way door for the file: an older build
     * that met the encrypted file would get "file is not a database" from the framework
     * helper, and could never recover, because nothing in that build knows to discard it. Giving the
     * encrypted database its own name sidesteps that entirely -- an older build looks for {@link
     * #LEGACY_PLAINTEXT_DATABASE_NAME}, does not find it, and creates a fresh one of its own, which
     * is harmless here precisely because every row is re-established by the push registration
     * handshake.
     *
     * <p>So upgrade and downgrade both work, at the cost of one unused file. That trade is only
     * available because this database's contents are disposable; it is not a pattern the {@code
     * history} migration can copy.
     */
    private static final String DATABASE_NAME = "unified-push-distributor-encrypted";

    /** The legacy database name used by older builds. Deleted on sight; never opened. */
    private static final String LEGACY_PLAINTEXT_DATABASE_NAME = "unified-push-distributor";
    private static final int DATABASE_VERSION = 1;

    private static UnifiedPushDatabase instance;

    /**
     * @throws DatabasePassphrase.UnavailableException if the device has not been unlocked since boot.
     *     Effectively unreachable: {@code UnifiedPushDistributor} ships {@code android:enabled=false}
     *     behind a signature-level permission and is only enabled once a transport is configured, and
     *     nothing in this app is {@code directBootAware}, so no caller here runs before first unlock.
     */
    public static UnifiedPushDatabase getInstance(final Context context) {
        synchronized (UnifiedPushDatabase.class) {
            if (instance == null) {
                final Context appContext = context.getApplicationContext();
                SqlCipherNative.ensureLoaded();
                resetIfAnOlderBuildHasRun(appContext);
                instance = new UnifiedPushDatabase(appContext, openPassphrase(appContext));
            }
            return instance;
        }
    }

    /**
     * Starts over whenever an older build has run since this one last did.
     *
     * <p>The presence of {@link #LEGACY_PLAINTEXT_DATABASE_NAME} is not litter, it is evidence: no
     * build of this app writes that file any more, so if it exists, an older build has run on this
     * device. That matters beyond hygiene, because such a build registers and unregisters push
     * targets in its own legacy database and cannot see the encrypted one.
     *
     * <p>Deleting only the legacy file would therefore reopen an encrypted database frozen at the
     * moment of the downgrade: registrations cancelled while downgraded would come back and be
     * renewed by {@code reconfigurePushDistributor}, and registrations made while downgraded would
     * be invisible. Neither store is a superset of the other, so there is nothing to merge -- and
     * nothing worth merging, because a registration is cheap to redo and a wrong one is not.
     *
     * <p>So both databases go, and the passphrase with them. Every row is re-established by the push
     * registration handshake, which is the same reason a downgrade is survivable at all. The
     * encrypted file is removed before the key, so a failed delete leaves a database we can still
     * open rather than one we have locked ourselves out of.
     */
    private static void resetIfAnOlderBuildHasRun(final Context context) {
        final File legacy = context.getDatabasePath(LEGACY_PLAINTEXT_DATABASE_NAME);
        if (DatabaseFileHeader.classify(legacy) == DatabaseFileHeader.Format.ABSENT) {
            return;
        }
        deleteDatabaseFiles(context, DATABASE_NAME);
        deleteDatabaseFiles(context, LEGACY_PLAINTEXT_DATABASE_NAME);
        DatabasePassphrase.forget(context, DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
    }

    /**
     * Resolves the passphrase, recreating the database from scratch if the old key is gone.
     *
     * <p>Safe only because every row is reproducible; see the class javadoc.
     */
    private static String openPassphrase(final Context context) {
        try {
            return DatabasePassphrase.require(context, DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
        } catch (final DatabasePassphrase.UnavailableException e) {
            if (e.reason == DatabasePassphrase.Unavailable.KEY_LOST) {
                Log.w(
                        Config.LOGTAG,
                        "UnifiedPush passphrase is unrecoverable; discarding the database and"
                                + " re-registering: "
                                + e.getMessage());
                return recreateEncrypted(context);
            }
            throw e;
        }
    }

    private static String recreateEncrypted(final Context context) {
        DatabasePassphrase.forget(context, DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
        deleteDatabaseFiles(context, DATABASE_NAME);
        return DatabasePassphrase.require(context, DatabasePassphrase.UNIFIED_PUSH_KEY_ID);
    }

    /**
     * Removes the database and its journal/WAL sidecars.
     *
     * <p>The sidecars matter and are easy to forget: leaving a stale {@code -wal} beside a newly
     * encrypted main file gives SQLCipher a journal it cannot read and will try to replay.
     */
    private static void deleteDatabaseFiles(final Context context, final String name) {
        final File database = context.getDatabasePath(name);
        for (final String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
            final File file = new File(database.getPath() + suffix);
            if (file.exists() && !file.delete()) {
                Log.w(Config.LOGTAG, "could not delete " + file.getName());
            }
        }
    }

    private UnifiedPushDatabase(@Nullable Context context, final String passphrase) {
        super(context, DATABASE_NAME, passphrase, null, DATABASE_VERSION, 0, null, null, false);
    }

    @Override
    public void onCreate(final SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(
                "CREATE TABLE push (account TEXT, transport TEXT, application TEXT NOT NULL, instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER DEFAULT 0)");
    }

    public boolean register(final String application, final String instance) {
        final SQLiteDatabase sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.beginTransaction();
        final Optional<String> existingApplication;
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application"},
                        "instance=?",
                        new String[] {instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                existingApplication = Optional.of(cursor.getString(0));
            } else {
                existingApplication = Optional.absent();
            }
        }
        if (existingApplication.isPresent()) {
            sqLiteDatabase.setTransactionSuccessful();
            sqLiteDatabase.endTransaction();
            return application.equals(existingApplication.get());
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put("application", application);
        contentValues.put("instance", instance);
        contentValues.put("expiration", 0);
        final long inserted = sqLiteDatabase.insert("push", null, contentValues);
        sqLiteDatabase.setTransactionSuccessful();
        sqLiteDatabase.endTransaction();
        return true;
    }

    public List<PushTarget> getRenewals(final String account, final String transport) {
        final ImmutableList.Builder<PushTarget> renewalBuilder = ImmutableList.builder();
        final long expiration = System.currentTimeMillis() + UnifiedPushBroker.TIME_TO_RENEW;
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "instance"},
                        "account <> ? OR transport <> ? OR expiration < " + expiration,
                        new String[] {account, transport},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                renewalBuilder.add(
                        new PushTarget(
                                cursor.getString(cursor.getColumnIndexOrThrow("application")),
                                cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        }
        return renewalBuilder.build();
    }

    public ApplicationEndpoint getEndpoint(
            final String account, final String transport, final String instance) {
        final long expiration = System.currentTimeMillis() + UnifiedPushBroker.TIME_TO_RENEW;
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "endpoint"},
                        "account = ? AND transport = ? AND instance = ? AND endpoint IS NOT NULL AND expiration >= "
                                + expiration,
                        new String[] {account, transport, instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new ApplicationEndpoint(
                        cursor.getString(cursor.getColumnIndexOrThrow("application")),
                        cursor.getString(cursor.getColumnIndexOrThrow("endpoint")));
            }
        }
        return null;
    }

    public List<PushTarget> deletePushTargets() {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final ImmutableList.Builder<PushTarget> builder = new ImmutableList.Builder<>();
        try (final Cursor cursor = sqLiteDatabase.query("push",new String[]{"application","instance"},null,null,null,null,null)) {
            if (cursor != null && cursor.moveToFirst()) {
                builder.add(new PushTarget(
                        cursor.getString(cursor.getColumnIndexOrThrow("application")),
                        cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        } catch (final Exception e) {
            return builder.build();
        }
        sqLiteDatabase.delete("push",null,null);
        return builder.build();
    }

    public boolean hasEndpoints(final UnifiedPushBroker.Transport transport) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.rawQuery(
                        "SELECT EXISTS(SELECT endpoint FROM push WHERE account = ? AND transport = ?)",
                        new String[] {
                            transport.account.getUuid(), transport.transport.toEscapedString()
                        })) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) > 0;
            }
        }
        return false;
    }

    @Override
    public void onUpgrade(
            final SQLiteDatabase sqLiteDatabase, final int oldVersion, final int newVersion) {}

    public boolean updateEndpoint(
            final String instance,
            final String account,
            final String transport,
            final String endpoint,
            final long expiration) {
        final SQLiteDatabase sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.beginTransaction();
        final String existingEndpoint;
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"endpoint"},
                        "instance=?",
                        new String[] {instance},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                existingEndpoint = cursor.getString(0);
            } else {
                existingEndpoint = null;
            }
        }
        final ContentValues contentValues = new ContentValues();
        contentValues.put("account", account);
        contentValues.put("transport", transport);
        contentValues.put("endpoint", endpoint);
        contentValues.put("expiration", expiration);
        sqLiteDatabase.update("push", contentValues, "instance=?", new String[] {instance});
        sqLiteDatabase.setTransactionSuccessful();
        sqLiteDatabase.endTransaction();
        return !endpoint.equals(existingEndpoint);
    }

    // scoped to the transport as well as the account so that an entity that is no longer the
    // configured push server can not deliver messages to an application it once registered
    public List<PushTarget> getPushTargets(final String account, final String transport) {
        final ImmutableList.Builder<PushTarget> renewalBuilder = ImmutableList.builder();
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        try (final Cursor cursor =
                sqLiteDatabase.query(
                        "push",
                        new String[] {"application", "instance"},
                        "account = ? AND transport = ?",
                        new String[] {account, transport},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                renewalBuilder.add(
                        new PushTarget(
                                cursor.getString(cursor.getColumnIndexOrThrow("application")),
                                cursor.getString(cursor.getColumnIndexOrThrow("instance"))));
            }
        }
        return renewalBuilder.build();
    }

    public boolean deleteInstance(final String application, final String instance) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        // scoped to the application as well as the instance so that one connector can not
        // unregister another connector's token by guessing or replaying it
        final int rows =
                sqLiteDatabase.delete(
                        "push",
                        "application=? AND instance=?",
                        new String[] {application, instance});
        return rows >= 1;
    }

    public boolean deleteApplication(final String application) {
        final SQLiteDatabase sqLiteDatabase = getReadableDatabase();
        final int rows = sqLiteDatabase.delete("push", "application=?", new String[] {application});
        return rows >= 1;
    }

    public static class ApplicationEndpoint {
        public final String application;
        public final String endpoint;

        public ApplicationEndpoint(String application, String endpoint) {
            this.application = application;
            this.endpoint = endpoint;
        }
    }

    public static class PushTarget {
        public final String application;
        public final String instance;

        public PushTarget(final String application, final String instance) {
            this.application = application;
            this.instance = instance;
        }

        @NonNull
        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("application", application)
                    .add("instance", instance)
                    .toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PushTarget that = (PushTarget) o;
            return Objects.equal(application, that.application)
                    && Objects.equal(instance, that.instance);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(application, instance);
        }
    }
}
