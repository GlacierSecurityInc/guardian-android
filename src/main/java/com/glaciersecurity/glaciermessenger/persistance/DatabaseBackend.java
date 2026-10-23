package com.glaciersecurity.glaciermessenger.persistance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
// The database is opened through SQLCipher. Only the three types that name a SQLite
// *implementation* move; android.database.Cursor stays, because SQLCipher's cursors implement it,
// and android.database.sqlite.SQLiteException stays because SQLCipher throws it -- its own
// SQLiteNotADatabaseException extends it. That is what keeps this swap off ~32 other files.
import android.database.sqlite.SQLiteException;
import net.zetetic.database.DatabaseUtils;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Pair;

import com.glaciersecurity.glaciermessenger.entities.CoreProfile;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.entities.GlacierPhoneEntity;
import com.glaciersecurity.glaciermessenger.entities.HistoryItem;
import com.glaciersecurity.glaciermessenger.entities.LatestVersionEntity;
import com.glaciersecurity.glaciermessenger.entities.SmsProfile;
import com.glaciersecurity.glaciermessenger.entities.TwilioConversationEntity;
import com.glaciersecurity.glaciermessenger.entities.TwilioMediaMessageEntity;
import com.glaciersecurity.glaciermessenger.entities.TwilioMessageEntity;
import com.glaciersecurity.glaciermessenger.entities.VoicemailRecord;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.SmsMessagePreviewUtils;

import com.glaciersecurity.glaciermessenger.entities.CognitoAccount;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;

import org.json.JSONException;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.AxolotlService;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.FingerprintStatus;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.SQLiteAxolotlStore;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Contact;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.PresenceTemplate;
import com.glaciersecurity.glaciermessenger.entities.Roster;
import com.glaciersecurity.glaciermessenger.entities.ServiceDiscoveryResult;
import com.glaciersecurity.glaciermessenger.services.QuickConversationsService;
import com.glaciersecurity.glaciermessenger.services.ShortcutService;
import com.glaciersecurity.glaciermessenger.utils.CryptoHelper;
import com.glaciersecurity.glaciermessenger.utils.CursorUtils;
import com.glaciersecurity.glaciermessenger.utils.FtsUtils;
import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;
import com.glaciersecurity.glaciermessenger.utils.MimeUtils;
import com.glaciersecurity.glaciermessenger.utils.Resolver;
import com.glaciersecurity.glaciermessenger.xmpp.InvalidJid;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.mam.MamReference;

public class DatabaseBackend extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "history";
    private static final int DATABASE_VERSION = 74;

    private static DatabaseBackend instance = null;
    private static Context dbcontext = null;

    private static final String CREATE_CONTATCS_STATEMENT = "create table "
            + Contact.TABLENAME + "(" + Contact.ACCOUNT + " TEXT, "
            + Contact.SERVERNAME + " TEXT, " + Contact.SYSTEMNAME + " TEXT,"
            + Contact.PRESENCE_NAME + " TEXT,"
            + Contact.JID + " TEXT," + Contact.KEYS + " TEXT,"
            + Contact.PHOTOURI + " TEXT," + Contact.OPTIONS + " NUMBER,"
            + Contact.SYSTEMACCOUNT + " NUMBER, " + Contact.AVATAR + " TEXT, "
            + Contact.LAST_PRESENCE + " TEXT, " + Contact.LAST_TIME + " NUMBER, "
            + Contact.RTP_CAPABILITY + " TEXT,"
            + Contact.GROUPS + " TEXT, FOREIGN KEY(" + Contact.ACCOUNT + ") REFERENCES "
            + Account.TABLENAME + "(" + Account.UUID
            + ") ON DELETE CASCADE, UNIQUE(" + Contact.ACCOUNT + ", "
            + Contact.JID + ") ON CONFLICT REPLACE);";

    private static final String CREATE_COGNITO_ACCOUNT_TABLE =
            "CREATE TABLE " + CognitoAccount.TABLENAME + " ("
                    + CognitoAccount.UUID         + " TEXT PRIMARY KEY, "
                    + CognitoAccount.USERNAME     + " TEXT, "
                    + CognitoAccount.ORGANIZATION + " TEXT, "
                    + CognitoAccount.ACCOUNT      + " TEXT"
                    + ")";
    private static final String CREATE_ORGANIZATION_TABLE = "create table "
            + GlacierOrgEntity.TABLENAME + " ("
            + GlacierOrgEntity.DNS_ENABLED + " INTEGER, "
            + GlacierOrgEntity.DNS_ID + " TEXT, "
            + GlacierOrgEntity.FORCE_TRACKING + " INTEGER, "
            + GlacierOrgEntity.ORGANIZATION + " TEXT PRIMARY KEY, "
            + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + " INTEGER, "
            + GlacierOrgEntity.SMS_ENABLED + " INTEGER, "
            + GlacierOrgEntity.TRACKING_ENABLED + " INTEGER, "
            + GlacierOrgEntity.TRACKING_FREQUENCY + " TEXT, "
            + GlacierOrgEntity.UPLOAD_ENABLED + " INTEGER, "
            + GlacierOrgEntity.VOICEMAIL_ENABLED + " INTEGER, "
            + "UNIQUE(" + GlacierOrgEntity.ORGANIZATION + ") ON CONFLICT REPLACE);";

    private static final String CREATE_LATEST_VERSIONS_TABLE = "create table "
            + LatestVersionEntity.TABLENAME + " ("
            + LatestVersionEntity.ALT_OS + " TEXT, "
            + LatestVersionEntity.ALT_OS_NEW + " TEXT, "
            + LatestVersionEntity.ANDROID_OS + " TEXT, "
            + LatestVersionEntity.SAMSUNG_OS + " TEXT, "
            + LatestVersionEntity.GLACIER_ANDROID + " TEXT, "
            + LatestVersionEntity.KEY + " TEXT PRIMARY KEY, "
            + "UNIQUE(" + LatestVersionEntity.KEY + ") ON CONFLICT REPLACE);";

    private static final String CREATE_DISCOVERY_RESULTS_STATEMENT = "create table "
            + ServiceDiscoveryResult.TABLENAME + "("
            + ServiceDiscoveryResult.HASH + " TEXT, "
            + ServiceDiscoveryResult.VER + " TEXT, "
            + ServiceDiscoveryResult.RESULT + " TEXT, "
            + "UNIQUE(" + ServiceDiscoveryResult.HASH + ", "
            + ServiceDiscoveryResult.VER + ") ON CONFLICT REPLACE);";

    private static final String CREATE_PRESENCE_TEMPLATES_STATEMENT = "CREATE TABLE "
            + PresenceTemplate.TABELNAME + "("
            + PresenceTemplate.UUID + " TEXT, "
            + PresenceTemplate.LAST_USED + " NUMBER,"
            + PresenceTemplate.MESSAGE + " TEXT,"
            + PresenceTemplate.STATUS + " TEXT, "
            + "UNIQUE(" + PresenceTemplate.MESSAGE + "," + PresenceTemplate.STATUS + ") ON CONFLICT REPLACE);";

    private static final String CREATE_PREKEYS_STATEMENT = "CREATE TABLE "
            + SQLiteAxolotlStore.PREKEY_TABLENAME + "("
            + SQLiteAxolotlStore.ACCOUNT + " TEXT,  "
            + SQLiteAxolotlStore.ID + " INTEGER, "
            + SQLiteAxolotlStore.KEY + " TEXT, FOREIGN KEY("
            + SQLiteAxolotlStore.ACCOUNT
            + ") REFERENCES " + Account.TABLENAME + "(" + Account.UUID + ") ON DELETE CASCADE, "
            + "UNIQUE( " + SQLiteAxolotlStore.ACCOUNT + ", "
            + SQLiteAxolotlStore.ID
            + ") ON CONFLICT REPLACE"
            + ");";

    private static final String CREATE_SIGNED_PREKEYS_STATEMENT = "CREATE TABLE "
            + SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME + "("
            + SQLiteAxolotlStore.ACCOUNT + " TEXT,  "
            + SQLiteAxolotlStore.ID + " INTEGER, "
            + SQLiteAxolotlStore.KEY + " TEXT, FOREIGN KEY("
            + SQLiteAxolotlStore.ACCOUNT
            + ") REFERENCES " + Account.TABLENAME + "(" + Account.UUID + ") ON DELETE CASCADE, "
            + "UNIQUE( " + SQLiteAxolotlStore.ACCOUNT + ", "
            + SQLiteAxolotlStore.ID
            + ") ON CONFLICT REPLACE" +
            ");";

    private static final String CREATE_SESSIONS_STATEMENT = "CREATE TABLE "
            + SQLiteAxolotlStore.SESSION_TABLENAME + "("
            + SQLiteAxolotlStore.ACCOUNT + " TEXT,  "
            + SQLiteAxolotlStore.NAME + " TEXT, "
            + SQLiteAxolotlStore.DEVICE_ID + " INTEGER, "
            + SQLiteAxolotlStore.KEY + " TEXT, FOREIGN KEY("
            + SQLiteAxolotlStore.ACCOUNT
            + ") REFERENCES " + Account.TABLENAME + "(" + Account.UUID + ") ON DELETE CASCADE, "
            + "UNIQUE( " + SQLiteAxolotlStore.ACCOUNT + ", "
            + SQLiteAxolotlStore.NAME + ", "
            + SQLiteAxolotlStore.DEVICE_ID
            + ") ON CONFLICT REPLACE"
            + ");";

    private static final String CREATE_IDENTITIES_STATEMENT = "CREATE TABLE "
            + SQLiteAxolotlStore.IDENTITIES_TABLENAME + "("
            + SQLiteAxolotlStore.ACCOUNT + " TEXT,  "
            + SQLiteAxolotlStore.NAME + " TEXT, "
            + SQLiteAxolotlStore.OWN + " INTEGER, "
            + SQLiteAxolotlStore.FINGERPRINT + " TEXT, "
            + SQLiteAxolotlStore.CERTIFICATE + " BLOB, "
            + SQLiteAxolotlStore.TRUST + " TEXT, "
            + SQLiteAxolotlStore.ACTIVE + " NUMBER, "
            + SQLiteAxolotlStore.LAST_ACTIVATION + " NUMBER,"
            + SQLiteAxolotlStore.KEY + " TEXT, FOREIGN KEY("
            + SQLiteAxolotlStore.ACCOUNT
            + ") REFERENCES " + Account.TABLENAME + "(" + Account.UUID + ") ON DELETE CASCADE, "
            + "UNIQUE( " + SQLiteAxolotlStore.ACCOUNT + ", "
            + SQLiteAxolotlStore.NAME + ", "
            + SQLiteAxolotlStore.FINGERPRINT
            + ") ON CONFLICT IGNORE"
            + ");";

    private static final String RESOLVER_RESULTS_TABLENAME = "resolver_results";

    private static final String CREATE_RESOLVER_RESULTS_TABLE = "create table " + RESOLVER_RESULTS_TABLENAME + "("
            + Resolver.Result.DOMAIN + " TEXT,"
            + Resolver.Result.HOSTNAME + " TEXT,"
            + Resolver.Result.IP + " BLOB,"
            + Resolver.Result.PRIORITY + " NUMBER,"
            + Resolver.Result.DIRECT_TLS + " NUMBER,"
            + Resolver.Result.AUTHENTICATED + " NUMBER,"
            + Resolver.Result.PORT + " NUMBER, "
            + "UNIQUE(" + Resolver.Result.DOMAIN + ") ON CONFLICT REPLACE"
            + ");";

    private static final String CREATE_SMS_ACCOUNTS_TABLE = "create table " + SmsProfile.TABLENAME + "("
            + SmsProfile.TWILIO_ID + " TEXT,"
            + SmsProfile.PHONE_NUMBER + " TEXT,"
            + SmsProfile.DISPLAY_NAME + " TEXT,"
            + SmsProfile.COLOR + " INTEGER,"
            + SmsProfile.IS_ACTIVE + " INTEGER DEFAULT 1, "
            + "UNIQUE(" + SmsProfile.TWILIO_ID + ") ON CONFLICT REPLACE"
            + ");";


    private static final String CREATE_CALL_HISTORY_TABLE = "create table " + HistoryItem.TABLENAME + "("
            + HistoryItem.TWILIO_SID + " TEXT,"
            + HistoryItem.PROXY_SID + " TEXT,"
            + HistoryItem.DATE_CREATED + " TEXT,"
            + HistoryItem.TO + " TEXT,"
            + HistoryItem.FROM + " TEXT,"
            + HistoryItem.STATUS + " TEXT,"
            + HistoryItem.DIRECTION + " TEXT,"
            + HistoryItem.REMOTE + " TEXT,"
            + HistoryItem.IS_UNREAD + " INTEGER NOT NULL DEFAULT 0,"
            + HistoryItem.DURATION + " TEXT, FOREIGN KEY("
            + HistoryItem.PROXY_SID
            + ") REFERENCES " + SmsProfile.TABLENAME + "(" + SmsProfile.TWILIO_ID + ") ON DELETE CASCADE, "
            + "UNIQUE(" + HistoryItem.TWILIO_SID + ") ON CONFLICT REPLACE"
            + ");";


    private static final String CREATE_SMS_MESSAGE_TABLE = "CREATE TABLE " + TwilioMessageEntity.TABLE_SMS_MESSAGE + " ("
            + TwilioMessageEntity.COLUMN_MESSAGE_SID + " TEXT NOT NULL UNIQUE ON CONFLICT REPLACE, "
            + TwilioMessageEntity.COLUMN_CONVERSATION_ID + " TEXT, "
            + TwilioMessageEntity.COLUMN_TWILIO_SID + " TEXT, "
            + TwilioMessageEntity.COLUMN_MESSAGE_DATE + " TEXT, "
            + TwilioMessageEntity.COLUMN_IS_READ + " INTEGER, "
            + TwilioMessageEntity.COLUMN_IS_INCOMING + " INTEGER, "
            + TwilioMessageEntity.COLUMN_MEDIA_ITEM_UNIQUE_ID + " TEXT, "
            + TwilioMessageEntity.COLUMN_TRANSFER_PROGRESS + " DOUBLE, "
            + TwilioMessageEntity.COLUMN_MSG_TEXT + " TEXT, "
            + TwilioMessageEntity.COLUMN_MSG_SENDER_ID + " TEXT, "
            + TwilioMessageEntity.COLUMN_MSG_SENDER_DISPLAY_NAME + " TEXT, "
            + TwilioMessageEntity.COLUMN_MODIFIED + " TEXT, FOREIGN KEY("
            + TwilioMessageEntity.COLUMN_CONVERSATION_ID
            + ") REFERENCES " + TwilioConversationEntity.TABLE_SMS_CONVERSATION + "(" + TwilioConversationEntity.COLUMN_SID + ") ON DELETE CASCADE"
            + ");";

    private static final String CREATE_SMS_CONVERSATION_TABLE = "CREATE TABLE IF NOT EXISTS " + TwilioConversationEntity.TABLE_SMS_CONVERSATION + " (" +
            TwilioConversationEntity.COLUMN_SID + " TEXT UNIQUE NOT NULL PRIMARY KEY, " +
            TwilioConversationEntity.COLUMN_SMS_PROFILE_ID + " TEXT, " +
            TwilioConversationEntity.COLUMN_FRIENDLY_NAME + " TEXT, " +
            TwilioConversationEntity.COLUMN_IDENTITY_NUMBER + " TEXT, " +
            TwilioConversationEntity.COLUMN_UNIQUE_NAME + " TEXT, " +
            TwilioConversationEntity.COLUMN_STATUS + " TEXT, " +
            TwilioConversationEntity.COLUMN_STATE + " TEXT, " +
            TwilioConversationEntity.COLUMN_DATE_CREATED + " TEXT, " +
            TwilioConversationEntity.COLUMN_CREATED_BY + " TEXT, " +
            TwilioConversationEntity.COLUMN_DATE_UPDATED + " TEXT, " +
            TwilioConversationEntity.COLUMN_IS_NEW + " INTEGER NOT NULL DEFAULT 1, " +  // 1 for true, 0 for false (new)
            TwilioConversationEntity.COLUMN_IS_UNREAD + " INTEGER NOT NULL DEFAULT 1, " +  // 1 for true, 0 for false (unread)
            TwilioConversationEntity.COLUMN_UNREAD_COUNT + " INTEGER NOT NULL DEFAULT 0, " +
            TwilioConversationEntity.COLUMN_LATEST_MESSAGE + " TEXT, " +
            TwilioConversationEntity.COLUMN_LATEST_MESSAGE_IS_MEDIA + " INTEGER, " +
            TwilioConversationEntity.COLUMN_LATEST_MESSAGE_ID + " TEXT, " +
            TwilioConversationEntity.COLUMN_LATEST_MESSAGE_DATE + " TEXT"+
            ");";

    private static final String CREATE_SMS_MEDIA_ITEM_TABLE = "CREATE TABLE IF NOT EXISTS " + TwilioMediaMessageEntity.TABLE_SMS_MEDIA_ITEM + " ("
            + TwilioMediaMessageEntity.COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
            + TwilioMediaMessageEntity.COLUMN_UNIQUE_ID + " TEXT NOT NULL UNIQUE ON CONFLICT FAIL, "
            + TwilioMediaMessageEntity.COLUMN_PARENT_OBJECT_KEY + " TEXT NOT NULL, "
            + TwilioMediaMessageEntity.COLUMN_PARENT_OBJECT_COLLECTION + " TEXT, "
            + TwilioMediaMessageEntity.COLUMN_PARENT_THREAD_KEY + " TEXT NOT NULL, "
            + TwilioMediaMessageEntity.COLUMN_TRANSFER_PROGRESS + " DOUBLE, "
            + TwilioMediaMessageEntity.COLUMN_FILENAME + " TEXT, "
            + TwilioMediaMessageEntity.COLUMN_MODIFIED + " DATE, FOREIGN KEY (" + TwilioMediaMessageEntity.COLUMN_PARENT_OBJECT_KEY + ") "
            + "REFERENCES " + TwilioMessageEntity.TABLE_SMS_MESSAGE  + "(" + TwilioMessageEntity.COLUMN_MESSAGE_SID + ") ON DELETE CASCADE"
            + ")";

    private static final String CREATE_VOICEMAIL_RECORD_TABLE = "CREATE TABLE " + VoicemailRecord.TABLENAME + " (" +
            VoicemailRecord.DURATION + " TEXT, " +
            VoicemailRecord.CALL_SID + " TEXT, " +
            VoicemailRecord.TIME + " TEXT, " +
            VoicemailRecord.FROM + " TEXT, " +
            VoicemailRecord.RECORDING_SID + " TEXT NOT NULL UNIQUE ON CONFLICT REPLACE, " +
            VoicemailRecord.TO + " TEXT, " +
            VoicemailRecord.URL + " TEXT, " +
            VoicemailRecord.STATUS + " TEXT" +
            ");";

    private static final String CREATE_CORE_PROFILE_TABLE = "CREATE TABLE " + CoreProfile.TABLENAME + " (" +
            CoreProfile.PROFILE_NAME + " TEXT NOT NULL UNIQUE ON CONFLICT REPLACE, " +
            CoreProfile.DISPLAY_NAME + " TEXT, " +
            CoreProfile.COLUMN_CONFIG + " TEXT," +
            CoreProfile.IS_RUNNING + " INTEGER NOT NULL DEFAULT 0, " +
            CoreProfile.LEGACY_NAME + " TEXT" +
            ");";

    private static final String CREATE_MESSAGE_TIME_INDEX = "CREATE INDEX message_time_index ON " + Message.TABLENAME + "(" + Message.TIME_SENT + ")";
    private static final String CREATE_MESSAGE_CONVERSATION_INDEX = "CREATE INDEX message_conversation_index ON " + Message.TABLENAME + "(" + Message.CONVERSATION + ")";
    private static final String CREATE_MESSAGE_DELETED_INDEX = "CREATE INDEX message_deleted_index ON " + Message.TABLENAME + "(" + Message.DELETED + ")";
    private static final String CREATE_MESSAGE_RELATIVE_FILE_PATH_INDEX = "CREATE INDEX message_file_path_index ON " + Message.TABLENAME + "(" + Message.RELATIVE_FILE_PATH + ")";
    private static final String CREATE_MESSAGE_TYPE_INDEX = "CREATE INDEX message_type_index ON " + Message.TABLENAME + "(" + Message.TYPE + ")";

    private static final String CREATE_MESSAGE_INDEX_TABLE = "CREATE VIRTUAL TABLE messages_index USING fts4 (uuid,body,notindexed=\"uuid\",content=\"" + Message.TABLENAME + "\",tokenize='unicode61')";
    private static final String CREATE_MESSAGE_INSERT_TRIGGER = "CREATE TRIGGER after_message_insert AFTER INSERT ON " + Message.TABLENAME + " BEGIN INSERT INTO messages_index(rowid,uuid,body) VALUES(NEW.rowid,NEW.uuid,NEW.body); END;";
    private static final String CREATE_MESSAGE_UPDATE_TRIGGER = "CREATE TRIGGER after_message_update UPDATE OF uuid,body ON " + Message.TABLENAME + " BEGIN UPDATE messages_index SET body=NEW.body,uuid=NEW.uuid WHERE rowid=OLD.rowid; END;";
    // BEFORE DELETE: messages_index is external-content, so removing a posting re-reads the row
    // being deleted. Fired AFTER, the row is gone, the posting survives and a later message
    // reusing its rowid matches the deleted message's terms.
    private static final String CREATE_MESSAGE_DELETE_TRIGGER = "CREATE TRIGGER before_message_delete BEFORE DELETE ON " + Message.TABLENAME + " BEGIN DELETE FROM messages_index WHERE rowid=OLD.rowid; END;";
    private static final String COPY_PREEXISTING_ENTRIES = "INSERT INTO messages_index(messages_index) VALUES('rebuild');";

    private DatabaseBackend(Context context) {
        // Step one of two. The types move to SQLCipher here; the data does not. An empty
        // password makes SQLCipher open the file as ordinary SQLite, byte for byte, so this change
        // is reversible by reverting code alone -- no user's database is touched. The migrator
        // replaces "" with the Keystore-sealed passphrase from DatabasePassphrase.
        super(context, DATABASE_NAME, "", null, DATABASE_VERSION, 0, null, null, false);
    }

    private static ContentValues createFingerprintStatusContentValues(FingerprintStatus.Trust trust, boolean active) {
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.TRUST, trust.toString());
        values.put(SQLiteAxolotlStore.ACTIVE, active ? 1 : 0);
        return values;
    }

    /**
     * Discards messages_index and re-derives it from messages. Kept as a manual repair hook: no
     * caller in the tree, deliberately, for use from a debug path or a one-off fix when an index
     * is found stale. Not for {@code onUpgrade} -- that must use the database handed to it, since
     * {@code getWritableDatabase()} re-enters the helper mid-upgrade.
     */
    public void rebuildMessagesIndex() {
        final SQLiteDatabase db = getWritableDatabase();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        db.execSQL(COPY_PREEXISTING_ENTRIES);
    }

    private static void addColumnIfNotExists(final SQLiteDatabase db, final String table, final String columnDefinition) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition);
        } catch (final SQLiteException e) {
            // ignore, column already exists
        }
    }

    /**
     * Set once {@link #isNewerThanApp} has seen an on-disk schema newer than {@link
     * #DATABASE_VERSION}. Volatile rather than synchronized: it is written once during startup and
     * read from arbitrary threads afterwards.
     */
    private static volatile boolean databaseNewerThanApp = false;

    /** The on-disk schema version recorded when {@link #databaseNewerThanApp} was set. */
    private static volatile int onDiskVersion = 0;

    /**
     * Set once {@link #isNewerThanApp} has reached a definite answer, so the negative result is
     * cached too. Without this the probe below would re-open the database file and read a pragma
     * on every single {@link #getReadableDatabase()} call, on all of this class's call sites.
     *
     * <p>Safe to cache for the life of the process: the on-disk version only ever changes through
     * {@link #onUpgrade}, which raises it to {@link #DATABASE_VERSION} and no higher, and an
     * install that would lower it replaces the process.
     */
    private static volatile boolean downgradeChecked = false;

    /**
     * Whether the last probe found the database file encrypted. Not latched by {@link
     * #downgradeChecked}: unlike the schema version, this can change while the process runs, once a
     * migration converts the file in place.
     */
    private static volatile boolean databaseEncrypted = false;

    /**
     * Whether the database on disk was written by a newer build than this one. Only meaningful
     * after {@link #isNewerThanApp} has run, which {@code GlacierGuardian.onCreate} does before
     * anything touches the database.
     */
    public static boolean isDatabaseNewerThanApp() {
        return databaseNewerThanApp;
    }

    /** The schema version found on disk, for showing the user which build wrote it. */
    public static int getOnDiskVersion() {
        return onDiskVersion;
    }

    /**
     * Whether the database file is encrypted and therefore unreadable by this build. Only
     * meaningful after {@link #accessState} has run.
     */
    public static boolean isDatabaseEncrypted() {
        return databaseEncrypted;
    }

    /**
     * What this build can do with the database file on disk.
     *
     * <p>A boolean such as {@link #isNewerThanApp} conflates three different situations behind a
     * single {@code false}, which is not enough once the file might be encrypted -- see {@link
     * #accessState}.
     */
    public enum DatabaseAccess {
        /** Readable by this build, at a schema version it understands. */
        OK,
        /** Plain SQLite, but written by a newer build. Opening it would run a downgrade. */
        TOO_NEW,
        /**
         * Encrypted. This build links no SQLCipher, so it cannot open the file at all -- and
         * must not try, because the framework opener reports it as a corrupt database.
         */
        ENCRYPTED,
        /** Unreadable for some other reason: corrupt, locked, or caught mid-write. */
        UNREADABLE
    }

    /** The schema version this build understands. */
    public static int getAppDatabaseVersion() {
        return DATABASE_VERSION;
    }

    /**
     * Report whether the database file was written by a newer build, without letting {@link
     * SQLiteOpenHelper} open it.
     *
     * <p>SQLite has no way back from a newer schema: there is no reverse of the migrations in
     * {@link #onUpgrade}, and {@link SQLiteOpenHelper}'s default {@code onDowngrade} throws, which
     * used to crash the app on every launch from {@code GlacierGuardian.onCreate}. Rather than
     * destroy the user's history to get a schema this build understands, detect the mismatch up
     * front and let the UI explain it.
     *
     * <p>Opens read-only and by path, so this neither runs a migration nor creates the file. The
     * result is cached in {@link #databaseNewerThanApp}, so later calls are cheap.
     *
     * <p>Only reachable by installing an older build over a newer one, which Play Store upgrades
     * never do -- it is a sideload/test-device situation.
     */
    public static boolean isNewerThanApp(final Context context) {
        return accessState(context) == DatabaseAccess.TOO_NEW;
    }

    /**
     * Classify the database file before anything tries to open it.
     *
     * <p>Because the database may be encrypted, the format of the file is a question worth asking. The framework opener throws on
     * an encrypted file, and that exception is indistinguishable from the corrupt-or-locked case the
     * {@code catch} below was written for -- so an encrypted database would be logged as
     * "could not read database version", answered {@code false}, and handed straight to {@link
     * SQLiteOpenHelper}, which fails with "file is not a database" out of {@code
     * GlacierGuardian.onCreate} before any UI exists. That is a crash loop, and it is precisely the
     * failure this guard was built to prevent, wearing a different hat.
     *
     * <p>Reading the 16-byte magic first removes the ambiguity: an unencrypted database announces
     * itself, and SQLCipher encrypts page one in full, so anything else is either encrypted or not a
     * database. That distinction cannot be recovered after the fact, because SQLCipher also encrypts
     * {@code user_version}.
     */
    public static DatabaseAccess accessState(final Context context) {
        if (downgradeChecked) {
            return databaseNewerThanApp ? DatabaseAccess.TOO_NEW : DatabaseAccess.OK;
        }
        final File file = context.getDatabasePath(DATABASE_NAME);
        final DatabaseFileHeader.Format format = DatabaseFileHeader.classify(file);

        if (format == DatabaseFileHeader.Format.ABSENT) {
            // Nothing on disk yet; this build is about to create it at its own version.
            databaseEncrypted = false;
            downgradeChecked = true;
            return DatabaseAccess.OK;
        }

        if (format == DatabaseFileHeader.Format.ENCRYPTED_OR_UNREADABLE) {
            // An encrypted database and a corrupt one have the same header -- neither carries the
            // SQLite magic -- so the header cannot tell them apart. The credential can: a passphrase
            // is minted before the file is ever encrypted, and it lives outside the database, so it
            // is still there after a downgrade. No passphrase means nothing ever encrypted this
            // file, which makes unreadable bytes corruption rather than ciphertext.
            if (!DatabasePassphrase.hasKey(context, DatabasePassphrase.HISTORY_KEY_ID)) {
                Log.w(
                        Config.LOGTAG,
                        "database on disk is not plain SQLite and no passphrase was ever minted for"
                                + " it; treating it as corrupt rather than encrypted");
                return DatabaseAccess.UNREADABLE;
            }
            // Deliberately not cached, and deliberately not handed to SQLiteOpenHelper. Not cached
            // because a migration can encrypt the file while this process runs, so the answer is not
            // stable the way a schema version is; not opened because this build links no SQLCipher
            // and the framework opener would report it as corruption.
            databaseEncrypted = true;
            Log.e(
                    Config.LOGTAG,
                    "database on disk is encrypted; this build cannot open it, and is refusing to"
                            + " rather than reporting it as corrupt");
            return DatabaseAccess.ENCRYPTED;
        }

        android.database.sqlite.SQLiteDatabase probe = null;
        try {
            probe =
                    // Fully qualified on purpose. `SQLiteDatabase` now means SQLCipher's class in
                    // this file, but this probe deliberately reads a *plaintext* file with the
                    // platform opener -- the header check above has already established the format,
                    // and keeping the framework here leaves the guard's behaviour identical to
                    // before the swap.
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                            file.getPath(),
                            null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            final int version = probe.getVersion();
            databaseEncrypted = false;
            if (version > DATABASE_VERSION) {
                onDiskVersion = version;
                databaseNewerThanApp = true;
                downgradeChecked = true;
                Log.e(
                        Config.LOGTAG,
                        "database on disk is version "
                                + version
                                + " but this build only understands "
                                + DATABASE_VERSION
                                + "; refusing to open it rather than discarding history");
                return DatabaseAccess.TOO_NEW;
            }
            downgradeChecked = true;
            return DatabaseAccess.OK;
        } catch (final SQLiteException e) {
            // Plain SQLite by its header, but unreadable anyway (corrupt, locked, mid-write). That
            // is not a downgrade, so let the normal open path deal with it and report the real
            // error. Deliberately not cached: a transient lock should be re-probed rather than
            // latched into a permanent "fine" answer.
            Log.w(Config.LOGTAG, "could not read database version: " + e.getMessage());
            return DatabaseAccess.UNREADABLE;
        } finally {
            if (probe != null) {
                probe.close();
            }
        }
    }

    /** In-memory stand-in handed out while the real database is unreadable. */
    private static SQLiteDatabase placeholder;

    /**
     * An empty in-memory database with this build's schema, used in place of the real one when the
     * file on disk is too new.
     *
     * <p>Every caller gets a working handle, so nothing throws, and the file on disk is never
     * opened -- the user's history stays exactly as the newer build left it. Anything written here
     * is discarded when the process dies, which is the intended outcome: the app is refusing to
     * run, and {@code ConversationActivity} tells the user why rather than letting them get far
     * enough to enter data worth keeping.
     */
    private static synchronized SQLiteDatabase placeholderDatabase(final DatabaseBackend backend) {
        if (placeholder == null || !placeholder.isOpen()) {
            placeholder = SQLiteDatabase.create(null);
            backend.onCreate(placeholder);
        }
        return placeholder;
    }

    /**
     * Both accessors funnel through here so that a database newer than this build can never reach
     * {@link SQLiteOpenHelper}, whose default {@code onDowngrade} throws.
     *
     * <p>Guarding {@code GlacierGuardian.onCreate} alone is not enough: {@code
     * BootCompletedReceiver} reads the database from {@code ACTION_MY_PACKAGE_REPLACED}, which the
     * system delivers as soon as the downgrade is installed, and there are well over a hundred
     * other call sites. Checking here covers all of them.
     */
    private SQLiteDatabase openOrPlaceholder(final boolean writable) {
        if (dbcontext != null) {
            switch (accessState(dbcontext)) {
                case TOO_NEW:
                case ENCRYPTED:
                    // Both mean "this build cannot read that file". Hand back the placeholder so
                    // nothing throws and the file on disk is never opened.
                    return placeholderDatabase(this);
                case UNREADABLE:
                    // Unchanged from before the header check: a corrupt or locked plain-SQLite
                    // database still goes to the real opener, so the framework reports the actual
                    // error rather than this class guessing at it.
                    break;
                case OK:
                default:
                    break;
            }
        }
        return writable ? super.getWritableDatabase() : super.getReadableDatabase();
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        return openOrPlaceholder(false);
    }

    @Override
    public SQLiteDatabase getWritableDatabase() {
        return openOrPlaceholder(true);
    }

    /**
     * Unreachable in practice -- {@link #openOrPlaceholder} intercepts a too-new database before
     * {@link SQLiteOpenHelper} ever inspects the version. Overridden anyway so that if it somehow
     * is reached, the reason is recorded and legible rather than a bare framework exception.
     */
    @Override
    public void onDowngrade(
            final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        onDiskVersion = oldVersion;
        databaseNewerThanApp = true;
        throw new SQLiteException(
                "database is version "
                        + oldVersion
                        + ", this build understands "
                        + newVersion);
    }

    public static synchronized DatabaseBackend getInstance(Context context) {
        if (instance == null) {
            // sqlcipher-android loads no native library of its own, and nothing fails until the
            // first open -- which would then die inside the connection pool with an
            // UnsatisfiedLinkError that reads like a packaging fault. This is the single
            // construction point, and accessState() deliberately does not need it: it reads the
            // file header and, for a plaintext database, uses the platform opener.
            SqlCipherNative.ensureLoaded();
            instance = new DatabaseBackend(context);
            dbcontext = context;
        }
        return instance;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.execSQL("PRAGMA foreign_keys=ON");
        db.rawQuery("PRAGMA secure_delete=ON", null).close();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table " + Account.TABLENAME + "(" + Account.UUID + " TEXT PRIMARY KEY,"
                + Account.USERNAME + " TEXT,"
                + Account.SERVER + " TEXT,"
                + Account.PASSWORD + " TEXT,"
                + Account.DISPLAY_NAME + " TEXT, "
                + Account.TIMER + " INTEGER DEFAULT 0, "
                + Account.STATUS + " TEXT,"
                + Account.STATUS_MESSAGE + " TEXT,"
                + Account.ROSTERVERSION + " TEXT,"
                + Account.OPTIONS + " NUMBER, "
                + Account.AVATAR + " TEXT, "
                + Account.KEYS + " TEXT, "
                + Account.HOSTNAME + " TEXT, "
                + Account.RESOURCE + " TEXT,"
                + Account.PINNED_MECHANISM + " TEXT,"
                + Account.PINNED_CHANNEL_BINDING + " TEXT,"
                + Account.FAST_MECHANISM + " TEXT,"
                + Account.FAST_TOKEN + " TEXT,"+ Account.ADD_TO_CORE_VPN + " INTEGER,"
                + Account.ADD_USER_TO_SMS + " INTEGER,"
                + Account.ADD_USER_TO_PURCHASE_NUMBERS + " INTEGER,"
                + Account.CORE_REGION + " TEXT,"
                + Account.EXTENSION_VOICESERVER + " TEXT,"
                + Account.FIRST_NAME + " TEXT,"
                + Account.IS_SMS_ENABLED + " INTEGER,"
                + Account.LAST_NAME + " TEXT,"
                + Account.ORGANIZATION + " TEXT, "
                + Account.PORT + " NUMBER DEFAULT 5222)");
        db.execSQL("create table " + Conversation.TABLENAME + " ("
                + Conversation.UUID + " TEXT PRIMARY KEY, " + Conversation.NAME
                + " TEXT, " + Conversation.CONTACT + " TEXT, "
                + Conversation.ACCOUNT + " TEXT, "
                + Conversation.CONTACTJID + " TEXT, "
                + Conversation.CREATED + " NUMBER, "
                + Conversation.STATUS + " NUMBER, "
                + Conversation.MODE + " NUMBER, "
                + Conversation.TIMER + " INTEGER DEFAULT 0, "
                + Conversation.ATTRIBUTES + " TEXT, FOREIGN KEY("
                + Conversation.ACCOUNT + ") REFERENCES " + Account.TABLENAME
                + "(" + Account.UUID + ") ON DELETE CASCADE);");
        db.execSQL("create table " + Message.TABLENAME + "( " + Message.UUID
                + " TEXT PRIMARY KEY, " + Message.CONVERSATION + " TEXT, "
                + Message.TIME_SENT + " NUMBER, " + Message.COUNTERPART
                + " TEXT, " + Message.TRUE_COUNTERPART + " TEXT,"
                + Message.BODY + " TEXT, " + Message.ENCRYPTION + " NUMBER, "
                + Message.STATUS + " NUMBER," + Message.TYPE + " NUMBER, "
                + Message.TIMER + " INTEGER DEFAULT 0, "
                + Message.ENDTIME + " NUMBER, "
                + Message.RELATIVE_FILE_PATH + " TEXT, "
                + Message.SERVER_MSG_ID + " TEXT, "
                + Message.FINGERPRINT + " TEXT, "
                + Message.CARBON + " INTEGER, "
                + Message.EDITED + " TEXT, "
                + Message.READ + " NUMBER DEFAULT 1, "
                + Message.OOB + " INTEGER, "
                + Message.ERROR_MESSAGE + " TEXT,"
                + Message.READ_BY_MARKERS + " TEXT,"
                + Message.MARKABLE + " NUMBER DEFAULT 0,"
                + Message.DELETED + " NUMBER DEFAULT 0,"
                + Message.BODY_LANGUAGE + " TEXT,"
                + Message.OCCUPANT_ID + " TEXT,"
                + Message.REACTIONS + " TEXT,"
                + Message.REMOTE_MSG_ID + " TEXT, FOREIGN KEY("
                + Message.CONVERSATION + ") REFERENCES "
                + Conversation.TABLENAME + "(" + Conversation.UUID
                + ") ON DELETE CASCADE);");
        db.execSQL(CREATE_MESSAGE_TIME_INDEX);
        db.execSQL(CREATE_MESSAGE_CONVERSATION_INDEX);
        db.execSQL(CREATE_MESSAGE_DELETED_INDEX);
        db.execSQL(CREATE_MESSAGE_RELATIVE_FILE_PATH_INDEX);
        db.execSQL(CREATE_MESSAGE_TYPE_INDEX);
        db.execSQL(CREATE_CONTATCS_STATEMENT);
        db.execSQL(CREATE_DISCOVERY_RESULTS_STATEMENT);
        db.execSQL(CREATE_SESSIONS_STATEMENT);
        db.execSQL(CREATE_PREKEYS_STATEMENT);
        db.execSQL(CREATE_SIGNED_PREKEYS_STATEMENT);
        db.execSQL(CREATE_IDENTITIES_STATEMENT);
        db.execSQL(CREATE_PRESENCE_TEMPLATES_STATEMENT);
        db.execSQL(CREATE_RESOLVER_RESULTS_TABLE);
        db.execSQL(CREATE_MESSAGE_INDEX_TABLE);
        db.execSQL(CREATE_MESSAGE_INSERT_TRIGGER);
        db.execSQL(CREATE_MESSAGE_UPDATE_TRIGGER);
        db.execSQL(CREATE_MESSAGE_DELETE_TRIGGER);
        db.execSQL(CREATE_COGNITO_ACCOUNT_TABLE);
        db.execSQL(CREATE_ORGANIZATION_TABLE);
        db.execSQL(CREATE_LATEST_VERSIONS_TABLE);
        db.execSQL(CREATE_SMS_ACCOUNTS_TABLE);
        db.execSQL(CREATE_CALL_HISTORY_TABLE);
        db.execSQL(CREATE_SMS_CONVERSATION_TABLE);
        db.execSQL(CREATE_SMS_MESSAGE_TABLE);
        db.execSQL(CREATE_SMS_MEDIA_ITEM_TABLE);
        db.execSQL(CREATE_VOICEMAIL_RECORD_TABLE);
        db.execSQL(CREATE_CORE_PROFILE_TABLE);

    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Set by any step that leaves messages_index empty or stale; acted on once at the end,
        // still inside the transaction SQLiteOpenHelper commits the version bump in.
        boolean rebuildMessageIndex = false;
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("update " + Account.TABLENAME + " set "
                    + Account.OPTIONS + " = " + Account.OPTIONS + " | 8");
        }
        if (oldVersion < 3 && newVersion >= 3) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.TYPE + " NUMBER");
        }
        if (oldVersion < 5 && newVersion >= 5) {
            db.execSQL("DROP TABLE " + Contact.TABLENAME);
            db.execSQL(CREATE_CONTATCS_STATEMENT);
            db.execSQL("UPDATE " + Account.TABLENAME + " SET "
                    + Account.ROSTERVERSION + " = NULL");
        }
        if (oldVersion < 6 && newVersion >= 6) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.TRUE_COUNTERPART + " TEXT");
        }
        if (oldVersion < 7 && newVersion >= 7) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.REMOTE_MSG_ID + " TEXT");
            db.execSQL("ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN "
                    + Contact.AVATAR + " TEXT");
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN "
                    + Account.AVATAR + " TEXT");
        }
        if (oldVersion < 8 && newVersion >= 8) {
            db.execSQL("ALTER TABLE " + Conversation.TABLENAME + " ADD COLUMN "
                    + Conversation.ATTRIBUTES + " TEXT");
        }
        if (oldVersion < 9 && newVersion >= 9) {
            db.execSQL("ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN "
                    + Contact.LAST_TIME + " NUMBER");
            db.execSQL("ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN "
                    + Contact.LAST_PRESENCE + " TEXT");
        }
        if (oldVersion < 10 && newVersion >= 10) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.RELATIVE_FILE_PATH + " TEXT");
        }
        if (oldVersion < 11 && newVersion >= 11) {
            db.execSQL("ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN "
                    + Contact.GROUPS + " TEXT");
            db.execSQL("delete from " + Contact.TABLENAME);
            db.execSQL("update " + Account.TABLENAME + " set " + Account.ROSTERVERSION + " = NULL");
        }
        if (oldVersion < 12 && newVersion >= 12) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.SERVER_MSG_ID + " TEXT");
        }
        if (oldVersion < 13 && newVersion >= 13) {
            db.execSQL("delete from " + Contact.TABLENAME);
            db.execSQL("update " + Account.TABLENAME + " set " + Account.ROSTERVERSION + " = NULL");
        }
        if (oldVersion < 14 && newVersion >= 14) {
            canonicalizeJids(db);
        }
        if (oldVersion < 15 && newVersion >= 15) {
            recreateAxolotlDb(db);
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.FINGERPRINT + " TEXT");
        }
        if (oldVersion < 16 && newVersion >= 16) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN "
                    + Message.CARBON + " INTEGER");
        }
        if (oldVersion < 19 && newVersion >= 19) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.DISPLAY_NAME + " TEXT");
        }
        if (oldVersion < 20 && newVersion >= 20) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.HOSTNAME + " TEXT");
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.PORT + " NUMBER DEFAULT 5222");
        }
        if (oldVersion < 26 && newVersion >= 26) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.STATUS + " TEXT");
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.STATUS_MESSAGE + " TEXT");
        }
        if (oldVersion < 40 && newVersion >= 40) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.RESOURCE + " TEXT");
        }
        /* Any migrations that alter the Account table need to happen BEFORE this migration, as it
         * depends on account de-serialization.
         */
        if (oldVersion < 17 && newVersion >= 17 && newVersion < 31) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                String ownDeviceIdString = account.getKey(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID);
                if (ownDeviceIdString == null) {
                    continue;
                }
                final int ownDeviceId;
                try {
                    ownDeviceId = Integer.parseInt(ownDeviceIdString);
                } catch (final NumberFormatException e) {
                    continue;
                }
                SignalProtocolAddress ownAddress = new SignalProtocolAddress(account.getJid().asBareJid().toString(), ownDeviceId);
                deleteSession(db, account, ownAddress);
                IdentityKeyPair identityKeyPair = loadOwnIdentityKeyPair(db, account);
                if (identityKeyPair != null) {
                    String[] selectionArgs = {
                            account.getUuid(),
                            CryptoHelper.bytesToHex(identityKeyPair.getPublicKey().serialize())
                    };
                    ContentValues values = new ContentValues();
                    values.put(SQLiteAxolotlStore.TRUSTED, 2);
                    db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, values,
                            SQLiteAxolotlStore.ACCOUNT + " = ? AND "
                                    + SQLiteAxolotlStore.FINGERPRINT + " = ? ",
                            selectionArgs);
                }
            }
        }
        if (oldVersion < 18 && newVersion >= 18) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.READ + " NUMBER DEFAULT 1");
        }

        if (oldVersion < 21 && newVersion >= 21) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                account.unsetPgpSignature();
                db.update(Account.TABLENAME, account.getContentValues(), Account.UUID
                        + "=?", new String[]{account.getUuid()});
            }
        }

        if (oldVersion >= 15 && oldVersion < 22 && newVersion >= 22) {
            db.execSQL("ALTER TABLE " + SQLiteAxolotlStore.IDENTITIES_TABLENAME + " ADD COLUMN " + SQLiteAxolotlStore.CERTIFICATE);
        }

        if (oldVersion < 23 && newVersion >= 23) {
            db.execSQL(CREATE_DISCOVERY_RESULTS_STATEMENT);
        }

        if (oldVersion < 24 && newVersion >= 24) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.EDITED + " TEXT");
        }

        if (oldVersion < 25 && newVersion >= 25) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.OOB + " INTEGER");
        }

        if (oldVersion < 26 && newVersion >= 26) {
            db.execSQL(CREATE_PRESENCE_TEMPLATES_STATEMENT);
        }

        if (oldVersion < 27 && newVersion >= 27) {
            db.execSQL("DELETE FROM " + ServiceDiscoveryResult.TABLENAME);
        }

        if (oldVersion < 28 && newVersion >= 28) {
            canonicalizeJids(db);
        }

        if (oldVersion < 29 && newVersion >= 29) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.ERROR_MESSAGE + " TEXT");
        }
        if (oldVersion >= 15 && oldVersion < 31 && newVersion >= 31) {
            db.execSQL("ALTER TABLE " + SQLiteAxolotlStore.IDENTITIES_TABLENAME + " ADD COLUMN " + SQLiteAxolotlStore.TRUST + " TEXT");
            db.execSQL("ALTER TABLE " + SQLiteAxolotlStore.IDENTITIES_TABLENAME + " ADD COLUMN " + SQLiteAxolotlStore.ACTIVE + " NUMBER");
            HashMap<Integer, ContentValues> migration = new HashMap<>();
            migration.put(0, createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, true));
            migration.put(1, createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, true));
            migration.put(2, createFingerprintStatusContentValues(FingerprintStatus.Trust.UNTRUSTED, true));
            migration.put(3, createFingerprintStatusContentValues(FingerprintStatus.Trust.COMPROMISED, false));
            migration.put(4, createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, false));
            migration.put(5, createFingerprintStatusContentValues(FingerprintStatus.Trust.TRUSTED, false));
            migration.put(6, createFingerprintStatusContentValues(FingerprintStatus.Trust.UNTRUSTED, false));
            migration.put(7, createFingerprintStatusContentValues(FingerprintStatus.Trust.VERIFIED_X509, true));
            migration.put(8, createFingerprintStatusContentValues(FingerprintStatus.Trust.VERIFIED_X509, false));
            for (Map.Entry<Integer, ContentValues> entry : migration.entrySet()) {
                String whereClause = SQLiteAxolotlStore.TRUSTED + "=?";
                String[] where = {String.valueOf(entry.getKey())};
                db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, entry.getValue(), whereClause, where);
            }

        }
        if (oldVersion >= 15 && oldVersion < 32 && newVersion >= 32) {
            db.execSQL("ALTER TABLE " + SQLiteAxolotlStore.IDENTITIES_TABLENAME + " ADD COLUMN " + SQLiteAxolotlStore.LAST_ACTIVATION + " NUMBER");
            ContentValues defaults = new ContentValues();
            defaults.put(SQLiteAxolotlStore.LAST_ACTIVATION, System.currentTimeMillis());
            db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, defaults, null, null);
        }
        if (oldVersion >= 15 && oldVersion < 33 && newVersion >= 33) {
            String whereClause = SQLiteAxolotlStore.OWN + "=1";
            db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, createFingerprintStatusContentValues(FingerprintStatus.Trust.VERIFIED, true), whereClause, null);
        }

        if (oldVersion < 34 && newVersion >= 34) {
            db.execSQL(CREATE_MESSAGE_TIME_INDEX);

            final File oldPicturesDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/Glacier/");
            final File oldFilesDirectory = new File(Environment.getExternalStorageDirectory() + "/Glacier/");
            final File newFilesDirectory = new File(Environment.getExternalStorageDirectory() + "/Glacier/Media/Conversations Files/");
            final File newVideosDirectory = new File(Environment.getExternalStorageDirectory() + "/Glacier/Media/Conversations Videos/");
            if (oldPicturesDirectory.exists() && oldPicturesDirectory.isDirectory()) {
                final File newPicturesDirectory = new File(Environment.getExternalStorageDirectory() + "/Glacier/Media/Conversations Images/");
                newPicturesDirectory.getParentFile().mkdirs();
                if (oldPicturesDirectory.renameTo(newPicturesDirectory)) {
                }
            }
            if (oldFilesDirectory.exists() && oldFilesDirectory.isDirectory()) {
                newFilesDirectory.mkdirs();
                newVideosDirectory.mkdirs();
                final File[] files = oldFilesDirectory.listFiles();
                if (files == null) {
                    return;
                }
                for (File file : files) {
                    if (file.getName().equals(".nomedia")) {
                        if (file.delete()) {
                        }
                    } else if (file.isFile()) {
                        final String name = file.getName();
                        boolean isVideo = false;
                        int start = name.lastIndexOf('.') + 1;
                        if (start < name.length()) {
                            String mime = MimeUtils.guessMimeTypeFromExtension(name.substring(start));
                            isVideo = mime != null && mime.startsWith("video/");
                        }
                        File dst = new File((isVideo ? newVideosDirectory : newFilesDirectory).getAbsolutePath() + "/" + file.getName());
                        if (file.renameTo(dst)) {
                        }
                    }
                }
            }
        }
        if (oldVersion < 35 && newVersion >= 35) {
            db.execSQL(CREATE_MESSAGE_CONVERSATION_INDEX);
        }
        if (oldVersion < 36 && newVersion >= 36) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                account.setOption(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE, true);
                account.setOption(Account.OPTION_LOGGED_IN_SUCCESSFULLY, false);
                db.update(Account.TABLENAME, account.getContentValues(), Account.UUID
                        + "=?", new String[]{account.getUuid()});
            }
        }

        if (oldVersion < 37 && newVersion >= 37) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.READ_BY_MARKERS + " TEXT");
        }

        if (oldVersion < 38 && newVersion >= 38) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.MARKABLE + " NUMBER DEFAULT 0");
        }

        if (oldVersion < 39 && newVersion >= 39) {
            db.execSQL(CREATE_RESOLVER_RESULTS_TABLE);
        }

        if (QuickConversationsService.isQuicksy() && oldVersion < 43 && newVersion >= 43) {
            List<Account> accounts = getAccounts(db);
            for (Account account : accounts) {
                account.setOption(Account.OPTION_MAGIC_CREATE, true);
                db.update(Account.TABLENAME, account.getContentValues(), Account.UUID
                        + "=?", new String[]{account.getUuid()});
            }
        }

        if (oldVersion < 44 && newVersion >= 44) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.DELETED + " NUMBER DEFAULT 0");
            db.execSQL(CREATE_MESSAGE_DELETED_INDEX);
            db.execSQL(CREATE_MESSAGE_RELATIVE_FILE_PATH_INDEX);
            db.execSQL(CREATE_MESSAGE_TYPE_INDEX);
        }

        if (oldVersion < 45 && newVersion >= 45) {
            addColumnIfNotExists(db, Message.TABLENAME, Message.BODY_LANGUAGE + " TEXT");
        }

        if (oldVersion < 46 && newVersion >= 46) {
            final long start = SystemClock.elapsedRealtime();
            db.rawQuery("PRAGMA secure_delete = FALSE", null).close();
            db.execSQL("update " + Message.TABLENAME + " set " + Message.EDITED + "=NULL");
            db.rawQuery("PRAGMA secure_delete=ON", null).close();
            final long diff = SystemClock.elapsedRealtime() - start;
        }
        if (oldVersion < 47 && newVersion >= 47) {
            db.execSQL("ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN " + Contact.PRESENCE_NAME + " TEXT");
        }
        if (oldVersion < 48 && newVersion >= 48) {
            db.execSQL("ALTER TABLE " + Contact.TABLENAME + " ADD COLUMN " + Contact.RTP_CAPABILITY + " TEXT");
        }
        if (oldVersion < 49 && newVersion >= 49) {
            db.beginTransaction();
            db.execSQL("DROP TRIGGER IF EXISTS after_message_insert;");
            db.execSQL("DROP TRIGGER IF EXISTS after_message_update;");
            db.execSQL("DROP TRIGGER IF EXISTS after_message_delete;");
            db.execSQL("DROP TABLE IF EXISTS messages_index;");
            // a hack that should not be necessary, but
            // there was at least one occurence when SQLite failed at this
            db.execSQL("DROP TABLE IF EXISTS messages_index_docsize;");
            db.execSQL("DROP TABLE IF EXISTS messages_index_segdir;");
            db.execSQL("DROP TABLE IF EXISTS messages_index_segments;");
            db.execSQL("DROP TABLE IF EXISTS messages_index_stat;");
            db.execSQL(CREATE_MESSAGE_INDEX_TABLE);
            db.execSQL(CREATE_MESSAGE_INSERT_TRIGGER);
            db.execSQL(CREATE_MESSAGE_UPDATE_TRIGGER);
            db.execSQL(CREATE_MESSAGE_DELETE_TRIGGER);
            db.setTransactionSuccessful();
            db.endTransaction();
            rebuildMessageIndex = true;
        }
        if (oldVersion < 50 && newVersion >= 50) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.PINNED_MECHANISM + " TEXT");
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.PINNED_CHANNEL_BINDING + " TEXT");
        }
        if (oldVersion < 51 && newVersion >= 51) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.FAST_MECHANISM + " TEXT");
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.FAST_TOKEN + " TEXT");
        }
        if (oldVersion < 52 && newVersion >= 52) {
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.OCCUPANT_ID + " TEXT");
            db.execSQL("ALTER TABLE " + Message.TABLENAME + " ADD COLUMN " + Message.REACTIONS + " TEXT");
        }
        if (oldVersion < 53 && newVersion >= 53) {
            db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.ORGANIZATION + " TEXT");
        }
        if (oldVersion < 54 && newVersion >= 54) {
            try {
                db.execSQL(CREATE_COGNITO_ACCOUNT_TABLE);
            }catch (Exception exc) {
                //table might already exist
            }
        }
        if (oldVersion < 55 && newVersion >= 55) {
            try {
                db.execSQL(CREATE_ORGANIZATION_TABLE);
                db.execSQL(CREATE_LATEST_VERSIONS_TABLE);

                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.ADD_TO_CORE_VPN + " INTEGER");

                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.ADD_USER_TO_SMS + " INTEGER");
                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.ADD_USER_TO_PURCHASE_NUMBERS + " INTEGER");
                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.CORE_REGION + " TEXT");
                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.EXTENSION_VOICESERVER + " TEXT");
                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.FIRST_NAME + " TEXT");
                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.IS_SMS_ENABLED + " INTEGER");
                db.execSQL("ALTER TABLE " + Account.TABLENAME + " ADD COLUMN " + Account.LAST_NAME + " TEXT");

            }catch (Exception exc) {
                //table might already exist
            }
        }
        if (oldVersion < 56 && newVersion >= 56) {
            try {
                db.execSQL(CREATE_SMS_ACCOUNTS_TABLE);
                db.execSQL(CREATE_CALL_HISTORY_TABLE);
                db.execSQL(CREATE_SMS_CONVERSATION_TABLE);
                db.execSQL(CREATE_SMS_MESSAGE_TABLE);
                db.execSQL(CREATE_SMS_MEDIA_ITEM_TABLE);
                db.execSQL(CREATE_VOICEMAIL_RECORD_TABLE);
                db.execSQL(CREATE_CORE_PROFILE_TABLE);

            } catch (Exception ignored) {

            }
        }
        if (oldVersion < 57 && newVersion >= 57) {
            addColumnIfNotExists(db, HistoryItem.TABLENAME, HistoryItem.REMOTE + " TEXT");
            addColumnIfNotExists(db, CoreProfile.TABLENAME,
                    CoreProfile.IS_RUNNING + " INTEGER NOT NULL DEFAULT 0");
        }
            if (oldVersion < 58 && newVersion >= 58) {
                addColumnIfNotExists(db, Message.TABLENAME, Message.TIMER + " INTEGER DEFAULT 0");
                addColumnIfNotExists(db, Message.TABLENAME, Message.ENDTIME + " NUMBER");
                addColumnIfNotExists(db, Account.TABLENAME, Account.TIMER + " INTEGER DEFAULT 0");
                addColumnIfNotExists(db, Conversation.TABLENAME, Conversation.TIMER + " INTEGER DEFAULT 0");
            }

            if (oldVersion < 59 && newVersion >= 59) {
                addColumnIfNotExists(db, LatestVersionEntity.TABLENAME, LatestVersionEntity.SAMSUNG_OS + " TEXT");
            }
            if (oldVersion < 60 && newVersion >= 60) {
                addColumnIfNotExists(db, LatestVersionEntity.TABLENAME, LatestVersionEntity.SAMSUNG_OS + " TEXT");
                addColumnIfNotExists(db, Message.TABLENAME, Message.BODY_LANGUAGE + " TEXT");
            }
        if (oldVersion < 61 && newVersion >= 61) {
            addColumnIfNotExists(db, Contact.TABLENAME,  Contact.PRESENCE_NAME + " TEXT");
            addColumnIfNotExists(db, Contact.TABLENAME,  Contact.RTP_CAPABILITY + " TEXT");
        }

        if (oldVersion < 63 && newVersion >= 63) {
            addColumnIfNotExists(db, SmsProfile.TABLENAME, SmsProfile.IS_ACTIVE + " INTEGER NOT NULL DEFAULT 1");
        }

        if (oldVersion < 64 && newVersion >= 64) {
            addColumnIfNotExists(db, CoreProfile.TABLENAME, CoreProfile.LEGACY_NAME + " TEXT");
        }

        if (oldVersion < 65 && newVersion >= 65) {
            addColumnIfNotExists(db, TwilioConversationEntity.TABLE_SMS_CONVERSATION,
                    TwilioConversationEntity.COLUMN_LATEST_MESSAGE_IS_MEDIA + " INTEGER");
        }
        if (oldVersion < 66 && newVersion >= 66) {
            addColumnIfNotExists(db,
                    TwilioConversationEntity.TABLE_SMS_CONVERSATION,
                    TwilioConversationEntity.COLUMN_UNREAD_COUNT + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 67 && newVersion >= 67) {
            addColumnIfNotExists(db, HistoryItem.TABLENAME,
                    HistoryItem.IS_UNREAD + " INTEGER NOT NULL DEFAULT 0");
        }
        // In DatabaseBackend.onUpgrade(), add a new version block:
        if (oldVersion < 68 && newVersion >= 68) {
            // Clear pinned mechanism for any account pinned to DIGEST-MD5 (priority 10)
            // so it will renegotiate with a stronger mechanism on next connect.
            ContentValues cv = new ContentValues();
            cv.put(Account.PINNED_MECHANISM, -1);
            int updated = db.update(
                    Account.TABLENAME,
                    cv,
                    Account.PINNED_MECHANISM + " = ?",
                    // 10 was DIGEST-MD5's SaslMechanism priority. The class is gone, so the
                    // literal is the only remaining record of it; the migration still has to run
                    // for rows written before it was removed.
                    new String[]{ "10" }
);
            if (updated > 0) {
                Log.w(Config.LOGTAG, "Cleared DIGEST-MD5 pinned mechanism for " + updated + " account(s)");
            }
        }

        if (oldVersion < 69 && newVersion >= 69) {
            db.beginTransaction();
            try {
                db.execSQL(
                        "CREATE TABLE cognito_account_new ("
                                + CognitoAccount.UUID         + " TEXT PRIMARY KEY, "
                                + CognitoAccount.USERNAME     + " TEXT, "
                                + CognitoAccount.ORGANIZATION + " TEXT, "
                                + CognitoAccount.ACCOUNT      + " TEXT"
                                + ")");
                db.execSQL(
                        "INSERT INTO cognito_account_new ("
                                + CognitoAccount.UUID         + ", "
                                + CognitoAccount.USERNAME     + ", "
                                + CognitoAccount.ORGANIZATION + ", "
                                + CognitoAccount.ACCOUNT
                                + ") SELECT "
                                + CognitoAccount.UUID         + ", "
                                + CognitoAccount.USERNAME     + ", "
                                + CognitoAccount.ORGANIZATION + ", "
                                + CognitoAccount.ACCOUNT
                                + " FROM " + CognitoAccount.TABLENAME);
                db.execSQL("DROP TABLE " + CognitoAccount.TABLENAME);
                db.execSQL("ALTER TABLE cognito_account_new RENAME TO " + CognitoAccount.TABLENAME);
                db.setTransactionSuccessful();
            } catch (Exception e) {
                Log.e(Config.LOGTAG,
                        "DB migration v69: failed to drop PASSWORD column — data intact, retrying next launch", e);

            } finally {
                db.endTransaction();
            }
        }

        if (oldVersion < 70 && newVersion >= 70) {
            db.beginTransaction();
            try {
                db.execSQL(
                        "CREATE TABLE glacier_organization_new ("
                                + GlacierOrgEntity.DNS_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.DNS_ID + " TEXT, "
                                + GlacierOrgEntity.FORCE_TRACKING + " INTEGER, "
                                + GlacierOrgEntity.ORGANIZATION + " TEXT PRIMARY KEY, "
                                + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.SMS_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.TRACKING_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.TRACKING_FREQUENCY + " TEXT, "
                                + GlacierOrgEntity.UPLOAD_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.VOICEMAIL_ENABLED + " INTEGER, "
                                + "UNIQUE(" + GlacierOrgEntity.ORGANIZATION + ") ON CONFLICT REPLACE);"
);
                db.execSQL(
                        "INSERT INTO glacier_organization_new ("
                                + GlacierOrgEntity.DNS_ENABLED + ", "
                                + GlacierOrgEntity.DNS_ID + ", "
                                + GlacierOrgEntity.FORCE_TRACKING + ", "
                                + GlacierOrgEntity.ORGANIZATION + ", "
                                + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + ", "
                                + GlacierOrgEntity.SMS_ENABLED + ", "
                                + GlacierOrgEntity.TRACKING_ENABLED + ", "
                                + GlacierOrgEntity.TRACKING_FREQUENCY + ", "
                                + GlacierOrgEntity.UPLOAD_ENABLED + ", "
                                + GlacierOrgEntity.VOICEMAIL_ENABLED
                                + ") SELECT "
                                + GlacierOrgEntity.DNS_ENABLED + ", "
                                + GlacierOrgEntity.DNS_ID + ", "
                                + GlacierOrgEntity.FORCE_TRACKING + ", "
                                + GlacierOrgEntity.ORGANIZATION + ", "
                                + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + ", "
                                + GlacierOrgEntity.SMS_ENABLED + ", "
                                + GlacierOrgEntity.TRACKING_ENABLED + ", "
                                + GlacierOrgEntity.TRACKING_FREQUENCY + ", "
                                + GlacierOrgEntity.UPLOAD_ENABLED + ", "
                                + GlacierOrgEntity.VOICEMAIL_ENABLED
                                + " FROM " + GlacierOrgEntity.TABLENAME
);
                db.execSQL("DROP TABLE " + GlacierOrgEntity.TABLENAME);
                db.execSQL("ALTER TABLE glacier_organization_new RENAME TO " + GlacierOrgEntity.TABLENAME);
                db.setTransactionSuccessful();
            } catch (Exception e) {
                Log.e(Config.LOGTAG,
                        "DB migration v70: failed to drop API_KEY column — data intact, retrying next launch", e);
            } finally {
                db.endTransaction();
            }
        }

        if (oldVersion < 72 && newVersion >= 72) {
            // The location_history table backed a team map heatmap that no longer
            // exists. Nothing ever read it, so drop it rather than keeping 30 days
            // of the device's own fixes on disk. Dropping the table takes its
            // location_history_time_index with it.
            //
            // Deliberately unguarded: if this throws, SQLiteOpenHelper rolls the
            // upgrade back without recording version 72, so the drop is retried on
            // the next launch. Swallowing it here would commit the new version and
            // leave the location rows on disk permanently.
            db.execSQL("DROP TABLE IF EXISTS location_history");
        }

        if (oldVersion < 73 && newVersion >= 73) {
            // Drop glacier_organization's retention column. The value arrives on every org sync
            // and is acted on via the SharedPreference, so nothing ever read it back out of the
            // table.
            //
            // RENAME/ADD/DROP COLUMN on a table this small is not worth the version checks; the
            // v70 block already establishes the rebuild pattern. A device upgrading from below 70
            // ran that block first and no longer has the column, so the INSERT..SELECT here names
            // only the surviving columns and works either way.
            db.beginTransaction();
            try {
                final String columns =
                        GlacierOrgEntity.DNS_ENABLED + ", "
                                + GlacierOrgEntity.DNS_ID + ", "
                                + GlacierOrgEntity.FORCE_TRACKING + ", "
                                + GlacierOrgEntity.ORGANIZATION + ", "
                                + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + ", "
                                + GlacierOrgEntity.SMS_ENABLED + ", "
                                + GlacierOrgEntity.TRACKING_ENABLED + ", "
                                + GlacierOrgEntity.TRACKING_FREQUENCY + ", "
                                + GlacierOrgEntity.UPLOAD_ENABLED + ", "
                                + GlacierOrgEntity.VOICEMAIL_ENABLED;
                db.execSQL(
                        "CREATE TABLE glacier_organization_new ("
                                + GlacierOrgEntity.DNS_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.DNS_ID + " TEXT, "
                                + GlacierOrgEntity.FORCE_TRACKING + " INTEGER, "
                                + GlacierOrgEntity.ORGANIZATION + " TEXT PRIMARY KEY, "
                                + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.SMS_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.TRACKING_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.TRACKING_FREQUENCY + " TEXT, "
                                + GlacierOrgEntity.UPLOAD_ENABLED + " INTEGER, "
                                + GlacierOrgEntity.VOICEMAIL_ENABLED + " INTEGER, "
                                + "UNIQUE(" + GlacierOrgEntity.ORGANIZATION + ") ON CONFLICT REPLACE);");
                db.execSQL(
                        "INSERT INTO glacier_organization_new (" + columns + ") SELECT " + columns
                                + " FROM " + GlacierOrgEntity.TABLENAME);
                db.execSQL("DROP TABLE " + GlacierOrgEntity.TABLENAME);
                db.execSQL("ALTER TABLE glacier_organization_new RENAME TO "
                        + GlacierOrgEntity.TABLENAME);
                db.setTransactionSuccessful();
            } catch (Exception e) {
                Log.e(Config.LOGTAG,
                        "DB migration v73: failed to drop retention column — data intact, retrying next launch", e);
            } finally {
                db.endTransaction();
            }
        }
        if (oldVersion < 74 && newVersion >= 74) {
            // Swap the delete trigger to BEFORE DELETE; the rebuild below clears what the old
            // one left behind.
            db.execSQL("DROP TRIGGER IF EXISTS after_message_delete");
            db.execSQL("DROP TRIGGER IF EXISTS before_message_delete");
            db.execSQL(CREATE_MESSAGE_DELETE_TRIGGER);
            rebuildMessageIndex = true;
        }
        if (rebuildMessageIndex) {
            // Here rather than in a startup task: onUpgrade shares a transaction with the version
            // bump, so a rebuild deferred past it is lost if the process dies -- the next launch
            // sees the new version, skips every block above and leaves the index broken for good.
            // Inline it commits with the version or rolls back with it and is retried. Measured
            // 0.09s per 50k messages. One flag for all steps, so it runs at most once.
            final Stopwatch stopwatch = Stopwatch.createStarted();
            db.execSQL(COPY_PREEXISTING_ENTRIES);
        }
    }

    private void canonicalizeJids(SQLiteDatabase db) {
        // migrate db to new, canonicalized JID domainpart representation

        // Conversation table
        Cursor cursor = db.rawQuery("select * from " + Conversation.TABLENAME, new String[0]);
        final int conversationContactJidIndex = cursor.getColumnIndex(Conversation.CONTACTJID);
        final int conversationUuidIndex = cursor.getColumnIndex(Conversation.UUID);
        while (cursor.moveToNext()) {
            String newJid;
            try {
                newJid = Jid.of(cursor.getString(conversationContactJidIndex)).toString();
            } catch (IllegalArgumentException ignored) {
                Log.e(Config.LOGTAG, "Failed to migrate Conversation CONTACTJID "
                        + cursor.getString(conversationContactJidIndex)
                        + ": " + ignored + ". Skipping...");
                continue;
            }

            final String[] updateArgs = {
                    newJid,
                    cursor.getString(conversationUuidIndex),
            };
            db.execSQL("update " + Conversation.TABLENAME
                    + " set " + Conversation.CONTACTJID + " = ? "
                    + " where " + Conversation.UUID + " = ?", updateArgs);
        }
        cursor.close();

        // Contact table
        cursor = db.rawQuery("select * from " + Contact.TABLENAME, new String[0]);
        final int contactJidIndex = cursor.getColumnIndex(Contact.JID);
        final int contactAccountIndex = cursor.getColumnIndex(Contact.ACCOUNT);
        while (cursor.moveToNext()) {
            String newJid;
            try {
                newJid = Jid.of(cursor.getString(contactJidIndex)).toString();
            } catch (final IllegalArgumentException e) {
                Log.e(Config.LOGTAG, "Failed to migrate Contact JID "
                        + cursor.getString(contactJidIndex)
                        + ":  Skipping...", e);
                continue;
            }

            final String[] updateArgs = {
                    newJid,
                    cursor.getString(contactAccountIndex),
                    cursor.getString(contactJidIndex),
            };
            db.execSQL("update " + Contact.TABLENAME
                    + " set " + Contact.JID + " = ? "
                    + " where " + Contact.ACCOUNT + " = ? "
                    + " AND " + Contact.JID + " = ?", updateArgs);
        }
        cursor.close();

        // Account table
        cursor = db.rawQuery("select * from " + Account.TABLENAME, new String[0]);
        final int accountUsernameIndex = cursor.getColumnIndex(Account.USERNAME);
        final int accountServerIndex = cursor.getColumnIndex(Account.SERVER);
        final int accountUuidIndex = cursor.getColumnIndex(Account.UUID);
        while (cursor.moveToNext()) {
            String newServer;
            try {
                newServer = Jid.of(
                        cursor.getString(accountUsernameIndex),
                        cursor.getString(accountServerIndex),
                        null
).getDomain().toEscapedString();
            } catch (IllegalArgumentException ignored) {
                Log.e(Config.LOGTAG, "Failed to migrate Account SERVER "
                        + cursor.getString(accountServerIndex)
                        + ": " + ignored + ". Skipping...");
                continue;
            }

            String[] updateArgs = {
                    newServer,
                    cursor.getString(accountUuidIndex),
            };
            db.execSQL("update " + Account.TABLENAME
                    + " set " + Account.SERVER + " = ? "
                    + " where " + Account.UUID + " = ?", updateArgs);
        }
        cursor.close();
    }

    public void createConversation(Conversation conversation) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(Conversation.TABLENAME, null, conversation.getContentValues());
    }

    public void createMessage(Message message) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(Message.TABLENAME, null, message.getContentValues());
    }

    public void createAccount(Account account) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(Account.TABLENAME, null, account.getContentValues());
    }

    public void createCognitoAccount(CognitoAccount account) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(CognitoAccount.TABLENAME, null, account.getContentValues());
    }

    public CognitoAccount getCognitoAccount(final Account account) {
        SQLiteDatabase db = this.getReadableDatabase();
        String args[] = {account.getUuid()};
        Cursor cursor = db.query(CognitoAccount.TABLENAME, null,
                CognitoAccount.ACCOUNT + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        CognitoAccount cognitoAccount = CognitoAccount.fromCursor(cursor, dbcontext);
        cursor.close();
        return cognitoAccount;
    }

    private void deleteCognitoAccount(Account account) {
        final SQLiteDatabase db = this.getWritableDatabase();
        final String accountUuid = account.getUuid();
        final String[] args = {accountUuid};

        final java.util.ArrayList<String> credentialIds = new java.util.ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = db.query(
                    CognitoAccount.TABLENAME,
                    new String[]{CognitoAccount.UUID},
                    CognitoAccount.ACCOUNT + "=?",
                    args,
                    null,
                    null,
                    null);
            while (cursor.moveToNext()) {
                credentialIds.add(cursor.getString(cursor.getColumnIndexOrThrow(CognitoAccount.UUID)));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        db.delete(CognitoAccount.TABLENAME, CognitoAccount.ACCOUNT + "=?", args);
        for (final String credentialId : credentialIds) {
            CognitoAccount.deleteStoredCredential(dbcontext, credentialId);
        }
    }

    public void createSmsProfile(SmsProfile smsProfile) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.insertWithOnConflict(SmsProfile.TABLENAME, null, smsProfile.getContentValues(), SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception c){
        }
    }

    public boolean doesVoicemailExist(String recordingSid) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;

        try {
            // Query the database to see if a voicemail with the given recording_sid already exists
            cursor = db.query(VoicemailRecord.TABLENAME, // Table name
                    new String[]{VoicemailRecord.RECORDING_SID}, // Columns to retrieve
                    VoicemailRecord.RECORDING_SID + " = ?", // WHERE clause
                    new String[]{recordingSid}, // WHERE clause arguments
                    null, // GROUP BY
                    null, // HAVING
                    null); // ORDER BY

            // If the cursor contains results, the entry already exists
            return cursor != null && cursor.moveToFirst();
        } finally {
            if (cursor != null) cursor.close();
        }
    }


    public void addVoicemailRecord(VoicemailRecord voicemailRecord) {
        final var db = this.getWritableDatabase();
        db.insertWithOnConflict(VoicemailRecord.TABLENAME, null, voicemailRecord.getContentValues(), SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void addCoreProfile(CoreProfile coreProfile) {
        final var db = this.getWritableDatabase();
        db.insertWithOnConflict(CoreProfile.TABLENAME, null, coreProfile.getContentValues(), SQLiteDatabase.CONFLICT_REPLACE);
    }

    // transparently re-saves a CoreProfile loaded from a row without the encrypted prefix
    // so its config is persisted encrypted going forward. getContentValues() always emits
    // the encrypted format, so PROFILE_NAME's UNIQUE ON CONFLICT REPLACE constraint makes this
    // a safe in-place upgrade of that one row.
    private void migrateConfigEncryptionIfNeeded(CoreProfile coreProfile) {
        if (coreProfile != null && coreProfile.needsConfigMigration()) {
            addCoreProfile(coreProfile);
        }
    }


    public SmsProfile getSmsProfile(final String id) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {id};
        Cursor cursor = db.query(SmsProfile.TABLENAME, null,
                SmsProfile.TWILIO_ID + "=? AND " + SmsProfile.IS_ACTIVE + "=1", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        SmsProfile smsProfile = SmsProfile.fromCursor(cursor);
        cursor.close();
        return smsProfile;
    }

    public SmsProfile getSmsProfileFromProxy(String proxyNumber) {
        if (proxyNumber == null) {
            return null;
        }
        proxyNumber = PhoneUtils.getStripPhoneNumberWithCountryCode(proxyNumber);
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {proxyNumber};
        Cursor cursor = db.query(SmsProfile.TABLENAME, null,
                SmsProfile.PHONE_NUMBER + "=? AND " + SmsProfile.IS_ACTIVE + "=1", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        SmsProfile smsProfile = SmsProfile.fromCursor(cursor);
        cursor.close();
        return smsProfile;
    }

    public ArrayList<SmsProfile> getAllSmsProfiles() {
        SQLiteDatabase db = this.getReadableDatabase();
        ArrayList<SmsProfile> smsProfiles = new ArrayList<>();
        try (Cursor cursor = db.query(SmsProfile.TABLENAME, null,
                SmsProfile.IS_ACTIVE + "=1", null, null, null, null)) {
            while (cursor.moveToNext()) {
                smsProfiles.add(SmsProfile.fromCursor(cursor));
            }
        }
        return smsProfiles;
    }

    public void updateSmsProfile(SmsProfile smsProfile) {
        final var db = this.getWritableDatabase();
        final String[] args = {smsProfile.getId()};
        db.update(SmsProfile.TABLENAME, smsProfile.getContentValues(), SmsProfile.TWILIO_ID + "=?", args);
    }

    public void deleteSmsProfile(String id) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SmsProfile.IS_ACTIVE, 0);
        db.update(SmsProfile.TABLENAME, values, SmsProfile.TWILIO_ID + "=?", new String[]{id});
    }

    public void deleteSmsProfileAndData(SmsProfile smsProfile) {
        if (smsProfile == null) {
            return;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(HistoryItem.TABLENAME, HistoryItem.PROXY_SID + "=?", new String[]{smsProfile.getId()});
            db.delete(TwilioConversationEntity.TABLE_SMS_CONVERSATION,
                    TwilioConversationEntity.COLUMN_SMS_PROFILE_ID + "=?", new String[]{smsProfile.getId()});
            db.delete(VoicemailRecord.TABLENAME, VoicemailRecord.TO + "=?",
                    new String[]{smsProfile.getUnformattedNumber()});
            ContentValues values = new ContentValues();
            values.put(SmsProfile.IS_ACTIVE, 0);
            db.update(SmsProfile.TABLENAME, values, SmsProfile.TWILIO_ID + "=?", new String[]{smsProfile.getId()});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deactivateAllSmsProfiles() {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SmsProfile.IS_ACTIVE, 0);
        db.update(SmsProfile.TABLENAME, values, null, null);
    }

    public void createCallHistory(HistoryItem historyItem) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            HistoryItem existing = getCallHistoryItem(historyItem.getSid());
            if (existing != null) {
                historyItem.setUnread(existing.isUnread());
            }
            db.insertOrThrow(HistoryItem.TABLENAME, null, historyItem.getContentValues());
        } catch (Exception c){
        }
    }

    public HistoryItem getCallHistoryItem(final String sid) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {sid};
        Cursor cursor = db.query(HistoryItem.TABLENAME, null,
                HistoryItem.TWILIO_SID + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        HistoryItem historyItem = HistoryItem.fromCursor(cursor);
        cursor.close();
        return historyItem;
    }

    public ArrayList<HistoryItem> getCallHistoryForProfile(String proxyId) {
            ArrayList<HistoryItem> results = new ArrayList<>();
            SQLiteDatabase db = this.getReadableDatabase();

            String selection = HistoryItem.PROXY_SID + " = ? ";
            String[] selectionArgs = {proxyId};

            Cursor cursor = db.query(HistoryItem.TABLENAME, null, selection, selectionArgs, null, null, HistoryItem.DATE_CREATED + " DESC");

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    HistoryItem historyItem = HistoryItem.fromCursor(cursor);
                    results.add(historyItem);
                } while (cursor.moveToNext());
                cursor.close();
            }
            return results;
        }

public void addCallHistoryList(ArrayList<HistoryItem> callHistoryList) {
    ArrayList<HistoryItem> safeCopy = new ArrayList<>(callHistoryList);
    for (HistoryItem historyItem : safeCopy) {
        this.createCallHistory(historyItem);
    }
}

    public int countUnreadMissedCalls(String proxySid) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection = HistoryItem.IS_UNREAD + " = 1";
        List<String> args = new ArrayList<>();
        if (proxySid != null) {
            selection += " AND " + HistoryItem.PROXY_SID + " = ?";
            args.add(proxySid);
        }
        long count = DatabaseUtils.queryNumEntries(db, HistoryItem.TABLENAME, selection,
                args.isEmpty() ? null : args.toArray(new String[0]));
        return (int) count;
    }

    public boolean hasUnreadMissedCalls(String proxySid) {
        return countUnreadMissedCalls(proxySid) > 0;
    }

    public void markMissedCallsReadForProxy(String proxySid) {
        if (proxySid == null) {
            return;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(HistoryItem.IS_UNREAD, 0);
        db.update(HistoryItem.TABLENAME, values,
                HistoryItem.PROXY_SID + " = ? AND " + HistoryItem.IS_UNREAD + " = 1",
                new String[]{proxySid});
    }

    public List<PresenceTemplate> getPresenceTemplatesforProxy() {
        ArrayList<PresenceTemplate> templates = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(PresenceTemplate.TABELNAME, null, null, null, null, null, PresenceTemplate.LAST_USED + " desc");
        while (cursor.moveToNext()) {
            templates.add(PresenceTemplate.fromCursor(cursor));
        }
        cursor.close();
        return templates;
    }

    public void updateCallHistoryItem(HistoryItem historyItem) {
        final var db = this.getWritableDatabase();
        final String[] args = {historyItem.getSid()};
        db.update(SmsProfile.TABLENAME, historyItem.getContentValues(), HistoryItem.TWILIO_SID + "=?", args);
    }


    public void deleteCallHistory(String id) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {id};
        db.delete(HistoryItem.TABLENAME, HistoryItem.TWILIO_SID + "=?", args);
    }

    public void createGlacierOrg(GlacierOrgEntity glacierOrg) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.insertOrThrow(GlacierOrgEntity.TABLENAME, null, glacierOrg.getContentValues());
        } catch (Exception c){
        }
    }

    public GlacierOrgEntity getOrganization(final String org) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {org};
        Cursor cursor = db.query(GlacierOrgEntity.TABLENAME, null,
                GlacierOrgEntity.ORGANIZATION + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        GlacierOrgEntity glacierOrg = GlacierOrgEntity.fromCursor(cursor);
        cursor.close();
        return glacierOrg;
    }

    public List<GlacierOrgEntity> getOrganizations() {
        final SQLiteDatabase db = this.getReadableDatabase();
        final List<GlacierOrgEntity> organizations = new ArrayList<>();
        try (final Cursor cursor = db.query(GlacierOrgEntity.TABLENAME, null, null, null, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                organizations.add(GlacierOrgEntity.fromCursor(cursor));
            }
        }
        return organizations;
    }

    public void removeDisappearingMessages(ArrayList<String> uuids) {
        StringBuilder sb = new StringBuilder(Message.UUID + " IN (");
        for (int i=0; i<uuids.size(); i++) {
            if (i == 0)
                sb.append("?");
            else
                sb.append(",?");
        }
        sb.append(")");
        String[] idsarr = new String[uuids.size()];
        uuids.toArray(idsarr);

        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(Message.TABLENAME, sb.toString(), idsarr);
    }

    public void updateOrganization(GlacierOrgEntity org) {
        final var db = this.getWritableDatabase();
        final String[] args = {org.getOrganization()};
        db.update(GlacierOrgEntity.TABLENAME, org.getContentValues(), GlacierOrgEntity.ORGANIZATION + "=?", args);
    }

    public void updateDnsId(GlacierOrgEntity glacierOrgEntity, String dnsId) {
        final var db = this.getWritableDatabase();
        final String[] args = {glacierOrgEntity.getOrganization()};
        final ContentValues contentValues = new ContentValues();
        contentValues.put(GlacierOrgEntity.DNS_ID, dnsId);
        db.update(GlacierOrgEntity.TABLENAME, contentValues, GlacierOrgEntity.ORGANIZATION + "=?", args);
    }

    public void deleteOrganization(String org) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {org};
        db.delete(GlacierOrgEntity.TABLENAME, GlacierOrgEntity.ORGANIZATION + "=?", args);
    }

    public void createLatestVersion(LatestVersionEntity lv) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.insertOrThrow(LatestVersionEntity.TABLENAME, null, lv.getContentValues());
        } catch (Exception c){
        }
    }

    public int getSmsConversationCount() {
        final SQLiteDatabase db = this.getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(db, TwilioConversationEntity.TABLE_SMS_CONVERSATION);
    }


    public LatestVersionEntity getLatestVersion(final String key) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {key};
        Cursor cursor = db.query(LatestVersionEntity.TABLENAME, null,
                LatestVersionEntity.KEY + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        LatestVersionEntity latestVersion = LatestVersionEntity.fromCursor(cursor);
        cursor.close();
        return latestVersion;
    }

    public void updateLatestVersion(LatestVersionEntity latestVersionEntity) {
        final var db = this.getWritableDatabase();
        final String[] args = {latestVersionEntity.getKey()};
        db.update(LatestVersionEntity.TABLENAME, latestVersionEntity.getContentValues(), LatestVersionEntity.KEY + "=?", args);
    }

    public void deleteLatestVersion(String org) {
        String args[] = {org};
        this.getWritableDatabase().delete(LatestVersionEntity.TABLENAME, LatestVersionEntity.KEY + "=?", args);
    }

    public void createPhone(GlacierPhoneEntity glacierPhone) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.insertOrThrow(GlacierPhoneEntity.TABLENAME, null, glacierPhone.getContentValues());
        } catch (Exception c){
        }
    }

    public GlacierPhoneEntity getPhone(final String key) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {key};
        Cursor cursor = db.query(GlacierPhoneEntity.TABLENAME, null,
                GlacierPhoneEntity.USERNAME + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        GlacierPhoneEntity glacierPhoneEntity = GlacierPhoneEntity.fromCursor(cursor);
        cursor.close();
        return glacierPhoneEntity;
    }

    public void updatePhone(GlacierPhoneEntity glacierPhoneEntity) {
        final var db = this.getWritableDatabase();
        final String[] args = {glacierPhoneEntity.getJid()};
        db.update(GlacierPhoneEntity.TABLENAME, glacierPhoneEntity.getContentValues(), GlacierPhoneEntity.USERNAME + "=?", args);
    }

    public void deletePhone(String org) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {org};
        db.delete(GlacierPhoneEntity.TABLENAME, GlacierPhoneEntity.USERNAME + "=?", args);
    }


    public void insertDiscoveryResult(ServiceDiscoveryResult result) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.insert(ServiceDiscoveryResult.TABLENAME, null, result.getContentValues());
    }

    public ServiceDiscoveryResult findDiscoveryResult(final String hash, final String ver) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {hash, ver};
        Cursor cursor = db.query(ServiceDiscoveryResult.TABLENAME, null,
                ServiceDiscoveryResult.HASH + "=? AND " + ServiceDiscoveryResult.VER + "=?",
                selectionArgs, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();

        ServiceDiscoveryResult result = null;
        try {
            result = new ServiceDiscoveryResult(cursor);
        } catch (JSONException e) { /* result is still null */ }

        cursor.close();
        return result;
    }

    public void saveResolverResult(String domain, Resolver.Result result) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues contentValues = result.toContentValues();
        contentValues.put(Resolver.Result.DOMAIN, domain);
        db.insert(RESOLVER_RESULTS_TABLENAME, null, contentValues);
    }

    public synchronized Resolver.Result findResolverResult(String domain) {
        SQLiteDatabase db = this.getReadableDatabase();
        String where = Resolver.Result.DOMAIN + "=?";
        String[] whereArgs = {domain};
        final Cursor cursor = db.query(RESOLVER_RESULTS_TABLENAME, null, where, whereArgs, null, null, null);
        Resolver.Result result = null;
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    result = Resolver.Result.fromCursor(cursor);
                }
            } catch (Exception e) {
                return null;
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    public void insertPresenceTemplate(PresenceTemplate template) {
        SQLiteDatabase db = this.getWritableDatabase();
        String whereToDelete = PresenceTemplate.MESSAGE + "=?";
        String[] whereToDeleteArgs = {template.getStatusMessage()};
        db.delete(PresenceTemplate.TABELNAME, whereToDelete, whereToDeleteArgs);
        db.delete(PresenceTemplate.TABELNAME, PresenceTemplate.UUID + " not in (select " + PresenceTemplate.UUID + " from " + PresenceTemplate.TABELNAME + " order by " + PresenceTemplate.LAST_USED + " desc limit 9)", null);
        db.insert(PresenceTemplate.TABELNAME, null, template.getContentValues());
    }

    public List<PresenceTemplate> getPresenceTemplates() {
        ArrayList<PresenceTemplate> templates = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(PresenceTemplate.TABELNAME, null, null, null, null, null, PresenceTemplate.LAST_USED + " desc");
        while (cursor.moveToNext()) {
            templates.add(PresenceTemplate.fromCursor(cursor));
        }
        cursor.close();
        return templates;
    }

    public CopyOnWriteArrayList<Conversation> getConversations(int status) {
        CopyOnWriteArrayList<Conversation> list = new CopyOnWriteArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {Integer.toString(status)};
        Cursor cursor = db.rawQuery("select * from " + Conversation.TABLENAME
                + " where " + Conversation.STATUS + " = ? and " + Conversation.CONTACTJID + " is not null order by "
                + Conversation.CREATED + " desc", selectionArgs);
        while (cursor.moveToNext()) {
            final Conversation conversation = Conversation.fromCursor(cursor);
            if (conversation.getJid() instanceof InvalidJid) {
                continue;
            }
            list.add(conversation);
        }
        cursor.close();
        return list;
    }

    public ArrayList<Message> getMessages(Conversation conversations, int limit) {
        return getMessages(conversations, limit, -1);
    }

    public ArrayList<Message> getMessages(Conversation conversation, int limit, long timestamp) {
        ArrayList<Message> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor;
        if (timestamp == -1) {
            String[] selectionArgs = {conversation.getUuid()};
            cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                    + "=?", selectionArgs, null, null, Message.TIME_SENT
                    + " DESC", String.valueOf(limit));
        } else {
            String[] selectionArgs = {conversation.getUuid(),
                    Long.toString(timestamp)};
            cursor = db.query(Message.TABLENAME, null, Message.CONVERSATION
                            + "=? and " + Message.TIME_SENT + "<?", selectionArgs,
                    null, null, Message.TIME_SENT + " DESC",
                    String.valueOf(limit));
        }
        CursorUtils.upgradeCursorWindowSize(cursor);
        while (cursor.moveToNext()) {
            try {
                list.add(0, Message.fromCursor(cursor, conversation));
            } catch (final Exception e) {
                Log.e(Config.LOGTAG, "unable to restore message", e);
            }
        }
        cursor.close();
        return list;
    }

    public Cursor getMessageSearchCursor(final List<String> term, final String uuid) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final StringBuilder SQL = new StringBuilder();
        final String[] selectionArgs;
        SQL.append("SELECT " + Message.TABLENAME + ".*," + Conversation.TABLENAME + "." + Conversation.CONTACTJID + "," + Conversation.TABLENAME + "." + Conversation.ACCOUNT + "," + Conversation.TABLENAME + "." + Conversation.MODE + " FROM " + Message.TABLENAME + " JOIN " + Conversation.TABLENAME + " ON " + Message.TABLENAME + "." + Message.CONVERSATION + "=" + Conversation.TABLENAME + "." + Conversation.UUID + " JOIN messages_index ON messages_index.rowid=messages.rowid WHERE " + Message.ENCRYPTION + " NOT IN(" + Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE + "," + Message.ENCRYPTION_PGP + "," + Message.ENCRYPTION_DECRYPTION_FAILED + "," + Message.ENCRYPTION_AXOLOTL_FAILED + ") AND " + Message.TYPE + " IN(" + Message.TYPE_TEXT + "," + Message.TYPE_PRIVATE + ") AND messages_index.body MATCH ?");
        if (uuid == null) {
            selectionArgs = new String[]{FtsUtils.toMatchString(term)};
        } else {
            selectionArgs = new String[]{FtsUtils.toMatchString(term), uuid};
            SQL.append(" AND " + Conversation.TABLENAME + '.' + Conversation.UUID + "=?");
        }
        SQL.append(" ORDER BY " + Message.TIME_SENT + " DESC limit " + Config.MAX_SEARCH_RESULTS);
        return db.rawQuery(SQL.toString(), selectionArgs);
    }

    public List<String> markFileAsDeleted(final File file, final boolean internal) {
        SQLiteDatabase db = this.getReadableDatabase();
        String selection;
        String[] selectionArgs;
        if (internal) {
            final String name = file.getName();
            if (name.endsWith(".pgp")) {
                selection = "(" + Message.RELATIVE_FILE_PATH + " IN(?,?) OR (" + Message.RELATIVE_FILE_PATH + "=? and encryption in(1,4))) and type in (1,2,5)";
                selectionArgs = new String[]{file.getAbsolutePath(), name, name.substring(0, name.length() - 4)};
            } else {
                selection = Message.RELATIVE_FILE_PATH + " IN(?,?) and type in (1,2,5)";
                selectionArgs = new String[]{file.getAbsolutePath(), name};
            }
        } else {
            selection = Message.RELATIVE_FILE_PATH + "=? and type in (1,2,5)";
            selectionArgs = new String[]{file.getAbsolutePath()};
        }
        final List<String> uuids = new ArrayList<>();
        Cursor cursor = db.query(Message.TABLENAME, new String[]{Message.UUID}, selection, selectionArgs, null, null, null);
        while (cursor != null && cursor.moveToNext()) {
            uuids.add(cursor.getString(0));
        }
        if (cursor != null) {
            cursor.close();
        }
        markFileAsDeleted(uuids);
        return uuids;
    }

    public void markFileAsDeleted(List<String> uuids) {
        SQLiteDatabase db = this.getReadableDatabase();
        final ContentValues contentValues = new ContentValues();
        final String where = Message.UUID + "=?";
        contentValues.put(Message.DELETED, 1);
        db.beginTransaction();
        for (String uuid : uuids) {
            db.update(Message.TABLENAME, contentValues, where, new String[]{uuid});
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    public void markFilesAsChanged(List<FilePathInfo> files) {
        SQLiteDatabase db = this.getReadableDatabase();
        final String where = Message.UUID + "=?";
        db.beginTransaction();
        for (FilePathInfo info : files) {
            final ContentValues contentValues = new ContentValues();
            contentValues.put(Message.DELETED, info.deleted ? 1 : 0);
            db.update(Message.TABLENAME, contentValues, where, new String[]{info.uuid.toString()});
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    /**
     * Persists relocated media paths for files that were moved out of a pre-7.x storage location
     * into the app-owned media directory.
     */
    public void updateFilePaths(final List<FilePathInfo> files) {
        final SQLiteDatabase db = this.getWritableDatabase();
        final String where = Message.UUID + "=?";
        db.beginTransaction();
        try {
            for (final FilePathInfo info : files) {
                final ContentValues contentValues = new ContentValues();
                contentValues.put(Message.RELATIVE_FILE_PATH, info.path);
                db.update(Message.TABLENAME, contentValues, where, new String[]{info.uuid.toString()});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<FilePathInfo> getFilePathInfo() {
        final SQLiteDatabase db = this.getReadableDatabase();
        final Cursor cursor = db.query(Message.TABLENAME, new String[]{Message.UUID, Message.RELATIVE_FILE_PATH, Message.DELETED}, "type in (1,2,5) and " + Message.RELATIVE_FILE_PATH + " is not null", null, null, null, null);
        final List<FilePathInfo> list = new ArrayList<>();
        while (cursor != null && cursor.moveToNext()) {
            if (isUuid(cursor.getString(0))) {
                list.add(new FilePathInfo(cursor.getString(0), cursor.getString(1), cursor.getInt(2) > 0));
            }
        }
        if (cursor != null) {
            cursor.close();
        }
        return list;
    }

    public List<FilePath> getRelativeFilePaths(String account, Jid jid, int limit) {
        return getRelativeFilePaths(account, jid, limit, 0);
    }

    public List<FilePath> getRelativeFilePaths(String account, Jid jid, int limit, int offset) {
        // Hard cap: prevents a caller from inadvertently (or maliciously) requesting
        // a result set large enough to exhaust heap.
        final int MAX_ATTACHMENT_LIMIT = 10_000;

        final SQLiteDatabase db = this.getReadableDatabase();

        // Base query — no LIMIT clause yet.
        final String baseSQL =
                "SELECT uuid, relativeFilePath"
                        + " FROM messages"
                        + " WHERE type IN (1,2,5)"
                        // Deliberately not filtering on deleted: that flag is only refreshed by
                        // checkForDeletedFiles on the file observer thread, so a stale 1 would keep
                        // hiding media that has since come back. convertToAttachments decides what
                        // to show from whether the file is on disk right now.
                        + "   AND " + Message.RELATIVE_FILE_PATH + " IS NOT NULL"
                        // Filtered here rather than after the query so LIMIT/OFFSET paging in
                        // getAttachments never sees a short batch and stops early.
                        + "   AND uuid GLOB '" + UUID_GLOB + "'"
                        + "   AND conversationUuid = ("
                        + "         SELECT uuid FROM conversations"
                        + "         WHERE accountUuid = ?"
                        + "           AND (contactJid = ? OR contactJid LIKE ?)"
                        + ")"
                        + " ORDER BY timeSent DESC";

        final String sql;
        final String[] args;

        if (limit > 0) {
            // Clamp to the hard cap so the parameter value is always safe.
            final int clampedLimit = Math.min(limit, MAX_ATTACHMENT_LIMIT);

            // Pass the limit value as a bound parameter — SQLite will treat it as
            // a plain integer literal, making SQL injection impossible regardless
            // of where the value originated.
            sql  = baseSQL + " LIMIT ? OFFSET ?";
            args = new String[]{
                    account,
                    jid.toString(),
                    jid.toString() + "/%",
                    String.valueOf(clampedLimit),  // ← bound parameter, not concatenation
                    String.valueOf(Math.max(0, offset))
            };
        } else {
            // limit ≤ 0  →  return all rows, no LIMIT clause at all.
            sql  = baseSQL;
            args = new String[]{
                    account,
                    jid.toString(),
                    jid.toString() + "/%"
            };
        }
        // ────────────────────────────────────────────────────────────────────

        final Cursor cursor = db.rawQuery(sql, args);
        final List<FilePath> filePaths = new ArrayList<>();
        while (cursor.moveToNext()) {
            filePaths.add(new FilePath(cursor.getString(0), cursor.getString(1)));
        }
        cursor.close();
        return filePaths;
    }

    public Message getMessageWithServerMsgId(
            final Conversation conversation, final String messageId) {
        final var db = this.getReadableDatabase();
        final String sql =
                "select * from messages where conversationUuid=? and serverMsgId=? LIMIT 1";
        final String[] args = {conversation.getUuid(), messageId};
        final Cursor cursor = db.rawQuery(sql, args);
        if (cursor == null) {
            return null;
        }
        final Message message;
        if (cursor.moveToFirst()) {
            message = Message.fromCursor(cursor, conversation);
        } else {
            message = null;
        }
        cursor.close();
        return message;
    }

    public Message getMessageWithUuidOrRemoteId(
            final Conversation conversation, final String messageId) {
        final var db = this.getReadableDatabase();
        final String sql =
                "select * from messages where conversationUuid=? and (uuid=? OR remoteMsgId=?) LIMIT 1";
        final String[] args = {conversation.getUuid(), messageId, messageId};
        final Cursor cursor = db.rawQuery(sql, args);
        if (cursor == null) {
            return null;
        }
        final Message message;
        if (cursor.moveToFirst()) {
            message = Message.fromCursor(cursor, conversation);
        } else {
            message = null;
        }
        cursor.close();
        return message;
    }

        public void addSMSConversation(com.twilio.conversations.Conversation conversation, SmsProfile sp, String identity_number) {
            final var db = this.getWritableDatabase();
            try {
                db.insertWithOnConflict(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, getContentValuesForSmsConversation(conversation, sp, identity_number), SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception e) {
                Log.e(Config.LOGTAG,"Failed to add convo to db", e);
            }
        }

    public void addSMSConversation(com.twilio.conversations.Conversation conversation, String proxy, String identity_number) {
        final var db = this.getWritableDatabase();
        try {
            db.insertWithOnConflict(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, getContentValuesForSmsConversationOld(conversation, proxy, identity_number), SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.e(Config.LOGTAG,"Failed to add convo to db", e);
        }
    }


    public void getConversationBySidAndUpdate(com.twilio.conversations.Conversation conversation, com.twilio.conversations.Message lastMessage) {
         if (lastMessage == null) {
             return;
         }
         SQLiteDatabase db = this.getReadableDatabase();
         String selection = TwilioConversationEntity.COLUMN_SID + " = ?";
         String[] selectionArgs = { conversation.getSid() };
         final Cursor cursor = db.query(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, selection, selectionArgs, null, null, null);
         if (cursor != null && cursor.moveToFirst()) {
            ContentValues values = new ContentValues();
            SmsMessagePreviewUtils.SmsPreview preview = SmsMessagePreviewUtils.buildPreview(lastMessage);
            values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE, preview.getText()); // Update latest message
            values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_IS_MEDIA, preview.isMedia() ? 1 : 0);
            values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_ID, lastMessage.getSid()); // Update latest message ID
            values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_DATE, lastMessage.getDateCreated()); // Update latest message date
            values.put(TwilioConversationEntity.COLUMN_IS_UNREAD, 1);  // Update unread status

            // Update the row with the new values
            int rowsUpdated = db.update(TwilioConversationEntity.TABLE_SMS_CONVERSATION, values,
                    TwilioConversationEntity.COLUMN_SID + " = ?", new String[]{conversation.getSid()});


            // Close the cursor
            cursor.close();
        }
    }

    private ContentValues getContentValuesForSmsMessage(com.twilio.conversations.Message message){
        ContentValues values = new ContentValues();
        values.put(TwilioMessageEntity.COLUMN_MESSAGE_SID,message.getSid() );
        values.put(TwilioMessageEntity.COLUMN_CONVERSATION_ID, message.getConversation().getSid());
        values.put(TwilioMessageEntity.COLUMN_MESSAGE_DATE, message.getDateCreated());
        values.put(TwilioMessageEntity.COLUMN_MSG_TEXT, message.getBody());
        values.put(TwilioMessageEntity.COLUMN_MSG_SENDER_DISPLAY_NAME, message.getAuthor());
        values.put(TwilioMessageEntity.COLUMN_MESSAGE_DATE, message.getDateCreated());
        return values;

    }

    private ContentValues getContentValuesForSmsConversation(com.twilio.conversations.Conversation conversation, SmsProfile sp, String identity_number) {
        ContentValues values = new ContentValues();
        values.put(TwilioConversationEntity.COLUMN_SID, conversation.getSid());
        values.put(TwilioConversationEntity.COLUMN_SMS_PROFILE_ID, sp.getId());
        values.put(TwilioConversationEntity.COLUMN_FRIENDLY_NAME, conversation.getFriendlyName());
        values.put(TwilioConversationEntity.COLUMN_IDENTITY_NUMBER, identity_number);
        values.put(TwilioConversationEntity.COLUMN_UNIQUE_NAME, conversation.getUniqueName());
        values.put(TwilioConversationEntity.COLUMN_STATUS, conversation.getStatus().toString());
        values.put(TwilioConversationEntity.COLUMN_STATE, conversation.getState().toString());
        values.put(TwilioConversationEntity.COLUMN_DATE_CREATED, conversation.getDateCreated());
        values.put(TwilioConversationEntity.COLUMN_CREATED_BY, conversation.getCreatedBy());
        values.put(TwilioConversationEntity.COLUMN_DATE_UPDATED, conversation.getDateUpdated());
        values.put(TwilioConversationEntity.COLUMN_IS_NEW, 1);
        values.put(TwilioConversationEntity.COLUMN_IS_UNREAD, 1);
        values.put(TwilioConversationEntity.COLUMN_UNREAD_COUNT, 0);
        values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_ID, conversation.getLastMessageIndex());
        if (conversation.getLastMessageDate() != null) {
            values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_DATE, conversation.getLastMessageDate().toString());
        }
        return values;
    }
    private ContentValues getContentValuesForSmsConversationOld(com.twilio.conversations.Conversation conversation, String proxy, String identity_number) {
        ContentValues values = new ContentValues();
        values.put(TwilioConversationEntity.COLUMN_SID, conversation.getSid());
        values.put(TwilioConversationEntity.COLUMN_SMS_PROFILE_ID, proxy);
        values.put(TwilioConversationEntity.COLUMN_FRIENDLY_NAME, conversation.getFriendlyName());
        values.put(TwilioConversationEntity.COLUMN_IDENTITY_NUMBER, identity_number);
        values.put(TwilioConversationEntity.COLUMN_UNIQUE_NAME, conversation.getUniqueName());
        values.put(TwilioConversationEntity.COLUMN_STATUS, conversation.getStatus().toString());
        values.put(TwilioConversationEntity.COLUMN_STATE, conversation.getState().toString());
        values.put(TwilioConversationEntity.COLUMN_DATE_CREATED, conversation.getDateCreated());
        values.put(TwilioConversationEntity.COLUMN_CREATED_BY, conversation.getCreatedBy());
        values.put(TwilioConversationEntity.COLUMN_DATE_UPDATED, conversation.getDateUpdated());
        values.put(TwilioConversationEntity.COLUMN_IS_NEW, 1);
        values.put(TwilioConversationEntity.COLUMN_IS_UNREAD, 1);
        values.put(TwilioConversationEntity.COLUMN_UNREAD_COUNT, 0);
        values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_ID, conversation.getLastMessageIndex());
        if (conversation.getLastMessageDate() != null) {
            values.put(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_DATE, conversation.getLastMessageDate().toString());
        }
        return values;
    }

    public void updateSmsConversationUnreadCount(String conversationSid, int unreadCount) {
        if (conversationSid == null || conversationSid.isEmpty()) {
            return;
        }
        final SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(TwilioConversationEntity.COLUMN_UNREAD_COUNT, unreadCount);
        values.put(TwilioConversationEntity.COLUMN_IS_UNREAD, unreadCount > 0 ? 1 : 0);
        try {
            db.update(TwilioConversationEntity.TABLE_SMS_CONVERSATION, values,
                    TwilioConversationEntity.COLUMN_SID + " = ?", new String[]{conversationSid});
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Failed to update SMS unread count", e);
        }
    }

        public void addSmsMessages(List<com.twilio.conversations.Message> result) {
        final var db = this.getWritableDatabase();
            Iterator<com.twilio.conversations.Message> iterator = result.iterator();
            while (iterator.hasNext()) {
                com.twilio.conversations.Message message = iterator.next();
                try {
                    db.insertWithOnConflict(
                            TwilioMessageEntity.TABLE_SMS_MESSAGE,
                            null,
                            getContentValuesForSmsMessage(message),
                            SQLiteDatabase.CONFLICT_REPLACE
);
                } catch (Exception e) {
                    Log.e(Config.LOGTAG, "Failed to add message to db", e);
                }
            }
    }

    public void addSmsMessage(com.twilio.conversations.Message message) {
        final var db = this.getWritableDatabase();
        try {
            db.insertWithOnConflict(TwilioMessageEntity.TABLE_SMS_MESSAGE, null, getContentValuesForSmsMessage(message), SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Failed to add message to db", e);
        }
        updateLastMessageInSmsConversation(message);
    }

    private void updateLastMessageInSmsConversation(com.twilio.conversations.Message message) {
        getConversationBySidAndUpdate(message.getConversation(), message);
    }

    public ArrayList<TwilioConversationEntity> getSmsConversationsObjForProxy(SmsProfile smsProfile) {
        final SQLiteDatabase db = this.getReadableDatabase();
        ArrayList<TwilioConversationEntity> conversationsForProxy = new ArrayList<>();
        String selection = TwilioConversationEntity.COLUMN_SMS_PROFILE_ID + " = ?";
        String[] selectionArgs = { smsProfile.getId() };
        int attempts = 0;
        while (attempts < 5) {
            Cursor cursor = null;
            conversationsForProxy.clear();
            try {
                cursor = db.query(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, selection, selectionArgs, null, null, null);
                while (cursor != null && cursor.moveToNext()) {
                    conversationsForProxy.add(TwilioConversationEntity.fromCursor(cursor));
                }
                return conversationsForProxy;
            } catch (final Exception e) {
                attempts++;
                SystemClock.sleep(50);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return conversationsForProxy;

    }

    public ArrayList<TwilioMessageEntity> getSmsMessagesForConversation(String conversationSid) {
        final SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        ArrayList<TwilioMessageEntity> messages = new ArrayList<>();
        try {
            cursor = db.query(TwilioMessageEntity.TABLE_SMS_MESSAGE, null,
                    TwilioMessageEntity.COLUMN_CONVERSATION_ID + "=?",
                    new String[]{conversationSid}, null, null,
                    TwilioMessageEntity.COLUMN_MESSAGE_DATE + " ASC");
            while (cursor != null && cursor.moveToNext()) {
                messages.add(TwilioMessageEntity.fromCursor(cursor));
            }
        } catch (Exception ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return messages;
    }

    public Pair<SmsMessagePreviewUtils.SmsPreview, String> getLastSmsPreviewForConversation(String conversationSid) {
        final SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TwilioMessageEntity.TABLE_SMS_MESSAGE, null,
                    TwilioMessageEntity.COLUMN_CONVERSATION_ID + "=?",
                    new String[]{conversationSid}, null, null,
                    TwilioMessageEntity.COLUMN_MESSAGE_DATE + " DESC", "1");
            if (cursor != null && cursor.moveToFirst()) {
                String body = cursor.getString(cursor.getColumnIndexOrThrow(TwilioMessageEntity.COLUMN_MSG_TEXT));
                String author = cursor.getString(cursor.getColumnIndexOrThrow(TwilioMessageEntity.COLUMN_MSG_SENDER_DISPLAY_NAME));
                SmsMessagePreviewUtils.SmsPreview preview;
                if (body == null || body.trim().isEmpty()) {
                    preview = getLastSmsConversationPreview(conversationSid);
                } else {
                    preview = SmsMessagePreviewUtils.fromStoredPreview(body, false);
                }
                return new Pair<>(preview, author);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private SmsMessagePreviewUtils.SmsPreview getLastSmsConversationPreview(String conversationSid) {
        final SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null,
                    TwilioConversationEntity.COLUMN_SID + "=?",
                    new String[]{conversationSid}, null, null, null, "1");
            if (cursor != null && cursor.moveToFirst()) {
                String previewText =
                        cursor.getString(cursor.getColumnIndexOrThrow(TwilioConversationEntity.COLUMN_LATEST_MESSAGE));
                int isMediaIndex = cursor.getColumnIndexOrThrow(TwilioConversationEntity.COLUMN_LATEST_MESSAGE_IS_MEDIA);
                Boolean isMedia = cursor.isNull(isMediaIndex) ? null : cursor.getInt(isMediaIndex) != 0;
                return SmsMessagePreviewUtils.fromStoredPreview(previewText, isMedia);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return SmsMessagePreviewUtils.fromStoredPreview(null, null);
    }

    public int getSmsMessagesCount(String conversationSid) {
        if (conversationSid == null) {
            return 0;
        }
        final SQLiteDatabase db = this.getReadableDatabase();
        return (int) DatabaseUtils.queryNumEntries(db, TwilioMessageEntity.TABLE_SMS_MESSAGE,
                TwilioMessageEntity.COLUMN_CONVERSATION_ID + "=?", new String[]{conversationSid});
    }

    public TwilioConversationEntity getConversationBetweenContactAndProxy(String convName, SmsProfile smsProfile) {

        if (convName == null || smsProfile == null){
            return null;
        }
            final SQLiteDatabase db = this.getReadableDatabase();
            convName = PhoneUtils.getStripPhoneNumberWithCountryCode(convName);
            String selection = TwilioConversationEntity.COLUMN_SMS_PROFILE_ID + " = ? AND " + TwilioConversationEntity.COLUMN_FRIENDLY_NAME + " = ?";
            String[] selectionArgs = { smsProfile.getId() , convName};
            try {
                final Cursor cursor = db.query(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, selection, selectionArgs, null, null, null);
                if (cursor.getCount() == 0) {
                    return getConversationBetweenContactWithoutCountryCodeAndProxy(db, convName, smsProfile);
                } else {
                    cursor.moveToFirst();
                    return TwilioConversationEntity.fromCursor(cursor);
                }
            } catch (final Exception e) {
                return null;
            }

    }

    public TwilioConversationEntity getConversationBetweenContactWithoutCountryCodeAndProxy(SQLiteDatabase db,String convName, SmsProfile smsProfile) {
        {
            convName = PhoneUtils.getStripPhoneNumber(convName);
            String selection = TwilioConversationEntity.COLUMN_SMS_PROFILE_ID + " = ? AND " + TwilioConversationEntity.COLUMN_FRIENDLY_NAME + " = ?";
            String[] selectionArgs = { smsProfile.getId() , convName};
            try {
                final Cursor cursor = db.query(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, selection, selectionArgs, null, null, null);
                if (cursor.getCount() == 0) {
                    return null;
                } else {
                    cursor.moveToFirst();
                    return TwilioConversationEntity.fromCursor(cursor);
                }
            } catch (final Exception e) {
                return null;
            }

        }
    }

    public ArrayList<HistoryItem> getHistoryObjForProxy(SmsProfile smsProfile) {
        final SQLiteDatabase db = this.getReadableDatabase();
        ArrayList<HistoryItem> historyItems = new ArrayList<>();
        String selection = HistoryItem.PROXY_SID + " = ?";
        String[] selectionArgs = { smsProfile.getId() };
        try {
            final Cursor cursor = db.query(HistoryItem.TABLENAME, null, selection, selectionArgs, null, null, HistoryItem.DATE_CREATED + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                historyItems.add(HistoryItem.fromCursor(cursor));
            }
        } catch (final Exception e) {
            return historyItems;
        }
        return historyItems;
    }

    public ArrayList<HistoryItem> getHistoryObjForProxyAndRemote(String remoteNumber, SmsProfile smsProfile) {
        final SQLiteDatabase db = this.getReadableDatabase();
        ArrayList<HistoryItem> historyItems = new ArrayList<>();
        String selection = HistoryItem.PROXY_SID + " = ? AND " + HistoryItem.REMOTE + " = ?";
        String[] selectionArgs = { smsProfile.getId(), remoteNumber };
        try {
            final Cursor cursor = db.query(HistoryItem.TABLENAME, null, selection, selectionArgs, null, null, HistoryItem.DATE_CREATED + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                historyItems.add(HistoryItem.fromCursor(cursor));
            }
        } catch (final Exception e) {
            return historyItems;
        }
        return historyItems;
    }

    public CoreProfile getRunningCoreProfile() {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {"1"};
        Cursor cursor = db.query(CoreProfile.TABLENAME, null,
                CoreProfile.IS_RUNNING + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        CoreProfile coreProfile = CoreProfile.fromCursor(cursor);
        cursor.close();
        migrateConfigEncryptionIfNeeded(coreProfile);
        return coreProfile;
    }

    public Set<String> getRunningCoreProfileNames() {
        final Set<String> names = new HashSet<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {"1"};
        Cursor cursor = db.query(CoreProfile.TABLENAME, new String[]{CoreProfile.PROFILE_NAME},
                CoreProfile.IS_RUNNING + "=?", args, null, null, null);
        try {
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name != null) {
                    names.add(name);
                }
            }
        } finally {
            cursor.close();
        }
        return names;
    }

    public void setProfileRunning(CoreProfile cp) {
        final SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(CoreProfile.IS_RUNNING, 0);
        db.update(CoreProfile.TABLENAME, values,
                CoreProfile.IS_RUNNING + " = ?", new String[] { "1" });

        ContentValues values2 = new ContentValues();
        values2.put(CoreProfile.IS_RUNNING, 1);

        db.update(CoreProfile.TABLENAME, values2,
                CoreProfile.PROFILE_NAME + " = ?", new String[] { cp.getProfName() });

    }

    public void setCoreProfilesRunning(Collection<String> runningProfileNames) {
        final SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues resetValues = new ContentValues();
            resetValues.put(CoreProfile.IS_RUNNING, 0);
            db.update(CoreProfile.TABLENAME, resetValues, null, null);

            if (runningProfileNames != null && !runningProfileNames.isEmpty()) {
                ContentValues setValues = new ContentValues();
                setValues.put(CoreProfile.IS_RUNNING, 1);
                for (String profileName : runningProfileNames) {
                    db.update(CoreProfile.TABLENAME, setValues,
                            CoreProfile.PROFILE_NAME + " = ?", new String[]{profileName});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }


    public void deleteCoreProfile(CoreProfile coreProfile) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {coreProfile.getProfName()};
        db.delete(CoreProfile.TABLENAME, CoreProfile.PROFILE_NAME + "=?", args);
        CoreProfile.deleteProfileKey(coreProfile.getProfName());
    }

    public ArrayList<CoreProfile> getCoreProfiles() {
        SQLiteDatabase db = this.getReadableDatabase();
        ArrayList<CoreProfile> coreProfiles = new ArrayList<>();
        Cursor cursor = db.query(CoreProfile.TABLENAME, null, null, null, null, null, null);
        while (cursor.moveToNext()) {
            coreProfiles.add(CoreProfile.fromCursor(cursor));
        }
        cursor.close();
        // Migrate any rows still needing it after the read cursor is closed, so this
        // write doesn't race the still-open query above.
        for (final CoreProfile coreProfile : coreProfiles) {
            migrateConfigEncryptionIfNeeded(coreProfile);
        }
        Collections.sort(coreProfiles);
        return coreProfiles;
    }

    public void deleteAllCoreProfiles() {
        SQLiteDatabase db = this.getWritableDatabase();
        final Cursor cursor = db.query(
                CoreProfile.TABLENAME, new String[]{CoreProfile.PROFILE_NAME}, null, null, null, null, null);
        try {
            while (cursor.moveToNext()) {
                CoreProfile.deleteProfileKey(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        db.delete(CoreProfile.TABLENAME, null, null);
    }

   public void deleteAllPhoneDB() {
       SQLiteDatabase db = this.getWritableDatabase();
       db.delete(HistoryItem.TABLENAME, null, null);
        db.delete(VoicemailRecord.TABLENAME, null, null);
       db.delete(TwilioMessageEntity.TABLE_SMS_MESSAGE, null, null);
       db.delete(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, null);
       db.delete(SmsProfile.TABLENAME, null, null);
    }

    public void deleteAllSmsProfiles(){
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(SmsProfile.TABLENAME, null, null);
    }

    public void deleteAllConvAndMessages(){
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TwilioMessageEntity.TABLE_SMS_MESSAGE, null, null);
        db.delete(TwilioConversationEntity.TABLE_SMS_CONVERSATION, null, null);
    }

    public void clearRunningCoreProfiles() {
        final SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(CoreProfile.IS_RUNNING, 0);
        db.update(CoreProfile.TABLENAME, values,
                CoreProfile.IS_RUNNING + " = ?", new String[] { "1" });

    }

    public ArrayList<VoicemailRecord> getVoicemailsForProxy(SmsProfile smsProfile) {
        final ArrayList<VoicemailRecord> voicemailRecords = new ArrayList<>();
        if (smsProfile == null) {
            Log.w(Config.LOGTAG, "getVoicemailsForProxy called with null SmsProfile");
            return voicemailRecords;
        }

        final String unformattedNumber = smsProfile.getUnformattedNumber();
        if (unformattedNumber == null) {
            Log.w(Config.LOGTAG, "getVoicemailsForProxy missing unformatted number for profile " + smsProfile.getId());
            return voicemailRecords;
        }

        final SQLiteDatabase db = this.getReadableDatabase();
        String selection = VoicemailRecord.TO + " = ?";
        String[] selectionArgs = { unformattedNumber };
        try {
            final Cursor cursor = db.query(VoicemailRecord.TABLENAME, null, selection, selectionArgs, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                voicemailRecords.add(VoicemailRecord.fromCursor(cursor));
            }

        } catch (final Exception e) {
            return voicemailRecords;
        }
        return voicemailRecords;
    }

    public VoicemailRecord getVoicemailRecord(String recordingSid) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] args = {recordingSid};
        Cursor cursor = db.query(VoicemailRecord.TABLENAME, null,
                VoicemailRecord.RECORDING_SID + "=?", args, null, null, null);
        if (cursor.getCount() == 0) {
            cursor.close();
            return null;
        }
        cursor.moveToFirst();
        VoicemailRecord vm = VoicemailRecord.fromCursor(cursor);
        cursor.close();
        return vm;
    }

    public void updateVoicemailStatusForProxy(String status, SmsProfile sp) {
        SQLiteDatabase db = this.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(VoicemailRecord.STATUS, status);

        String selection = VoicemailRecord.TO + " = ?";
        String[] selectionArgs = { sp.getUnformattedNumber() };

        db.update(
                VoicemailRecord.TABLENAME,
                values,
                selection,
                selectionArgs
);
    }

    public void removeHistoryLog(String sid) {
        String args[] = {sid};
        this.getWritableDatabase().delete(HistoryItem.TABLENAME, HistoryItem.TWILIO_SID + "=?", args);

    }

    public void deleteAllHistoryLogsForProxy(SmsProfile smsProfile) {
        if (smsProfile == null) {
            return;
        }
        final SQLiteDatabase db = this.getWritableDatabase();
        final String profileId = smsProfile.getId();
        final String profileNumber = smsProfile.getUnformattedNumber();

        if (profileId != null && profileNumber != null) {
            db.delete(
                    HistoryItem.TABLENAME,
                    HistoryItem.PROXY_SID + "=? OR " + HistoryItem.PROXY_SID + "=?",
                    new String[]{profileId, profileNumber});
        } else if (profileId != null) {
            db.delete(HistoryItem.TABLENAME, HistoryItem.PROXY_SID + "=?", new String[]{profileId});
        } else if (profileNumber != null) {
            db.delete(HistoryItem.TABLENAME, HistoryItem.PROXY_SID + "=?", new String[]{profileNumber});
        } else {
            db.delete(HistoryItem.TABLENAME, HistoryItem.PROXY_SID + " IS NULL", null);
        }
    }

    public void removeTwilioConversation(String sid) {
        String args[] = {sid};
        this.getWritableDatabase().delete(TwilioConversationEntity.TABLE_SMS_CONVERSATION, TwilioConversationEntity.COLUMN_SID + "=?", args);

    }

    public void deleteAllTwilioConversationsForProxy(SmsProfile smsProfile) {
        this.getWritableDatabase().delete(TwilioConversationEntity.TABLE_SMS_CONVERSATION, TwilioConversationEntity.COLUMN_SMS_PROFILE_ID + "=?", new String[]{smsProfile.getId()});
    }

    public void updateDnsId(String org, String dnsId) {
        final var db = this.getWritableDatabase();
        final String[] args = {org};
        final ContentValues contentValues = new ContentValues();
        contentValues.put(GlacierOrgEntity.DNS_ID, dnsId);
        db.update(GlacierOrgEntity.TABLENAME, contentValues, GlacierOrgEntity.ORGANIZATION + "=?", args);
    }

    public String getDnsId(String org) {
        SQLiteDatabase db = this.getReadableDatabase();
        String dnsId = null;

        Cursor cursor = db.query(
                GlacierOrgEntity.TABLENAME,
                new String[]{GlacierOrgEntity.DNS_ID},
                GlacierOrgEntity.ORGANIZATION + "=?",
                new String[]{org},
                null,
                null,
                null
);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                dnsId = cursor.getString(cursor.getColumnIndexOrThrow(GlacierOrgEntity.DNS_ID));
            }
            cursor.close();
        }

        return dnsId;
    }


    private static final String UUID_GLOB;

    static {
        final String h = "[0-9a-fA-F]";
        UUID_GLOB = Strings.repeat(h, 8) + "-" + Strings.repeat(h, 4) + "-" + Strings.repeat(h, 4)
                + "-" + Strings.repeat(h, 4) + "-" + Strings.repeat(h, 12);
    }

    // messages.uuid is free text; one non-UUID row must not abort the whole scan.
    private static boolean isUuid(final String uuid) {
        try {
            UUID.fromString(uuid);
            return true;
        } catch (final IllegalArgumentException | NullPointerException e) {
            Log.w(Config.LOGTAG, "skipping message with malformed uuid");
            return false;
        }
    }

    public static class FilePath {
        public final UUID uuid;
        public String path;

        private FilePath(String uuid, String path) {
            this.uuid = UUID.fromString(uuid);
            this.path = path;
        }
    }

    public static class FilePathInfo extends FilePath {
        public boolean deleted;

        private FilePathInfo(String uuid, String path, boolean deleted) {
            super(uuid, path);
            this.deleted = deleted;
        }

        public boolean setDeleted(boolean deleted) {
            final boolean changed = deleted != this.deleted;
            this.deleted = deleted;
            return changed;
        }

        public boolean setPath(final String path) {
            final boolean changed = path != null && !path.equals(this.path);
            if (changed) {
                this.path = path;
            }
            return changed;
        }
    }

    public Conversation findConversation(final Account account, final Jid contactJid) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {account.getUuid(),
                contactJid.asBareJid().toString() + "/%",
                contactJid.asBareJid().toString()
        };
        try(final Cursor cursor = db.query(Conversation.TABLENAME, null,
                Conversation.ACCOUNT + "=? AND (" + Conversation.CONTACTJID
                        + " like ? OR " + Conversation.CONTACTJID + "=?)", selectionArgs, null, null, null)) {
            if (cursor.getCount() == 0) {
                return null;
            }
            cursor.moveToFirst();
            final Conversation conversation = Conversation.fromCursor(cursor);
            if (conversation.getJid() instanceof InvalidJid) {
                return null;
            }
            return conversation;
        }
    }

    public void updateConversation(final Conversation conversation) {
        final SQLiteDatabase db = this.getWritableDatabase();
        final String[] args = {conversation.getUuid()};
        db.update(Conversation.TABLENAME, conversation.getContentValues(),
                Conversation.UUID + "=?", args);
    }

    public List<Account> getAccounts() {
        SQLiteDatabase db = this.getReadableDatabase();
        final List<Account> accounts = getAccounts(db);
        migrateAccountCredentialsIfNeeded(accounts);
        return accounts;
    }

    // Rewrites any account row that still carries a password or FAST token in its columns.
    // Account.getContentValues() stores both in KeystoreCredentialStore and writes the columns as
    // NULL, so a single update per stale row is the whole migration. Deliberately not done from
    // the private getAccounts(SQLiteDatabase) overload: that one is also called from onUpgrade,
    // which holds the only writable connection for the duration of the migration transaction.
    private void migrateAccountCredentialsIfNeeded(final List<Account> accounts) {
        for (final Account account : accounts) {
            if (account.needsCredentialMigration()) {
                updateAccount(account);
            }
        }
    }

    public List<Jid> getAccountJids(final boolean enabledOnly) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final List<Jid> jids = new ArrayList<>();
        final String[] columns = new String[]{Account.USERNAME, Account.SERVER};
        final String where = enabledOnly ? "not options & (1 <<1)" : null;
        try (final Cursor cursor = db.query(Account.TABLENAME, columns, where, null, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                jids.add(Jid.of(cursor.getString(0), cursor.getString(1), null));
            }
        } catch (final Exception e) {
            return jids;
        }
        return jids;
    }

    private List<Account> getAccounts(SQLiteDatabase db) {
        final List<Account> list = new ArrayList<>();
        try (final Cursor cursor =
                db.query(Account.TABLENAME, null, null, null, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                list.add(Account.fromCursor(cursor));
            }
        }
        return list;
    }

    public boolean updateAccount(Account account) {
        final var db = this.getWritableDatabase();
        final String[] args = {account.getUuid()};
        final int rows =
                db.update(Account.TABLENAME, account.getContentValues(), Account.UUID + "=?", args);
        return rows == 1;
    }

    public boolean deleteAccount(final Account account) {
        final var db = this.getWritableDatabase();
        final String[] args = {account.getUuid()};
        final int rows = db.delete(Account.TABLENAME, Account.UUID + "=?", args);
        deleteCognitoAccount(account);
        // The password / FAST token / JWT live outside the database, so deleting the
        // row is not enough to be rid of them.
        account.clearCredentials();
        return rows == 1;
    }


    public boolean updateMessage(final Message message, final boolean includeBody) {
        final var db = this.getWritableDatabase();
        final String[] args = {message.getUuid()};
        final var contentValues = message.getContentValues();
        contentValues.remove(Message.UUID);
        if (!includeBody) {
            contentValues.remove(Message.BODY);
        }
        final int rows = db.update(Message.TABLENAME, contentValues, Message.UUID + "=?", args);
        return rows == 1;
    }

    public boolean updateMessage(final Message message, final String uuid) {
        final var db = this.getWritableDatabase();
        final String[] args = {uuid};
        final int rows =
                db.update(Message.TABLENAME, message.getContentValues(), Message.UUID + "=?", args);
        return rows == 1;
    }

    public void readRoster(Roster roster) {
        final SQLiteDatabase db = this.getReadableDatabase();
        final String[] args = {roster.getAccount().getUuid()};
        try (final Cursor cursor =
                db.query(Contact.TABLENAME, null, Contact.ACCOUNT + "=?", args, null, null, null)) {
            while (cursor.moveToNext()) {
                roster.initContact(Contact.fromCursor(cursor));
            }
        }
    }

    public void writeRoster(final Roster roster) {
        long start = SystemClock.elapsedRealtime();
        final Account account = roster.getAccount();
        final SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        for (Contact contact : roster.getContacts()) {
            if (contact.getOption(Contact.Options.IN_ROSTER) || contact.hasAvatarOrPresenceName() || contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                db.insert(Contact.TABLENAME, null, contact.getContentValues());
            } else {
                String where = Contact.ACCOUNT + "=? AND " + Contact.JID + "=?";
                String[] whereArgs = {account.getUuid(), contact.getJid().toString()};
                db.delete(Contact.TABLENAME, where, whereArgs);
            }
        }
        db.setTransactionSuccessful();
        db.endTransaction();
        account.setRosterVersion(roster.getVersion());
        updateAccount(account);
        long duration = SystemClock.elapsedRealtime() - start;
    }

    public void deleteMessagesInConversation(Conversation conversation) {
        long start = SystemClock.elapsedRealtime();
        final SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        final String[] args = {conversation.getUuid()};
        int num = db.delete(Message.TABLENAME, Message.CONVERSATION + "=?", args);
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    public void expireOldMessages(long timestamp) {
        final String[] args = {String.valueOf(timestamp)};
        SQLiteDatabase db = this.getReadableDatabase();
        db.beginTransaction();
        db.delete(Message.TABLENAME, "timeSent<?", args);
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    public MamReference getLastMessageReceived(Account account) {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            String sql = "select messages.timeSent,messages.serverMsgId from accounts join conversations on accounts.uuid=conversations.accountUuid join messages on conversations.uuid=messages.conversationUuid where accounts.uuid=? and (messages.status=0 or messages.carbon=1 or messages.serverMsgId not null) and (conversations.mode=0 or (messages.serverMsgId not null and messages.type=4)) order by messages.timesent desc limit 1";
            String[] args = {account.getUuid()};
            cursor = db.rawQuery(sql, args);
            if (cursor.getCount() == 0) {
                return null;
            } else {
                cursor.moveToFirst();
                return new MamReference(cursor.getLong(0), cursor.getString(1));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public long getLastTimeFingerprintUsed(Account account, String fingerprint) {
        String SQL = "select messages.timeSent from accounts join conversations on accounts.uuid=conversations.accountUuid join messages on conversations.uuid=messages.conversationUuid where accounts.uuid=? and messages.axolotl_fingerprint=? order by messages.timesent desc limit 1";
        String[] args = {account.getUuid(), fingerprint};
        Cursor cursor = getReadableDatabase().rawQuery(SQL, args);
        long time;
        if (cursor.moveToFirst()) {
            time = cursor.getLong(0);
        } else {
            time = 0;
        }
        cursor.close();
        return time;
    }

    public MamReference getLastClearDate(Account account) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {Conversation.ATTRIBUTES};
        String selection = Conversation.ACCOUNT + "=?";
        String[] args = {account.getUuid()};
        Cursor cursor = db.query(Conversation.TABLENAME, columns, selection, args, null, null, null);
        MamReference maxClearDate = new MamReference(0);
        while (cursor.moveToNext()) {
            try {
                final JSONObject o = new JSONObject(cursor.getString(0));
                maxClearDate = MamReference.max(maxClearDate, MamReference.fromAttribute(o.getString(Conversation.ATTRIBUTE_LAST_CLEAR_HISTORY)));
            } catch (Exception e) {
                //ignored
            }
        }
        cursor.close();
        return maxClearDate;
    }

    private Cursor getCursorForSession(Account account, SignalProtocolAddress contact) {
        final SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {account.getUuid(),
                contact.getName(),
                Integer.toString(contact.getDeviceId())};
        return db.query(SQLiteAxolotlStore.SESSION_TABLENAME,
                null,
                SQLiteAxolotlStore.ACCOUNT + " = ? AND "
                        + SQLiteAxolotlStore.NAME + " = ? AND "
                        + SQLiteAxolotlStore.DEVICE_ID + " = ? ",
                selectionArgs,
                null, null, null);
    }

    public SessionRecord loadSession(Account account, SignalProtocolAddress contact) {
        SessionRecord session = null;
        Cursor cursor = getCursorForSession(account, contact);
        if (cursor.getCount() != 0) {
            cursor.moveToFirst();
            try {
                final int keyIndex = cursor.getColumnIndex(SQLiteAxolotlStore.KEY);
                session = new SessionRecord(Base64.decode(cursor.getString(keyIndex), Base64.DEFAULT));
            } catch (InvalidMessageException e) {
                cursor.close();
                throw new AssertionError(e);
            }
        }
        cursor.close();
        return session;
    }

    public List<Integer> getSubDeviceSessions(Account account, SignalProtocolAddress contact) {
        final SQLiteDatabase db = this.getReadableDatabase();
        return getSubDeviceSessions(db, account, contact);
    }

    private List<Integer> getSubDeviceSessions(SQLiteDatabase db, Account account, SignalProtocolAddress contact) {
        List<Integer> devices = new ArrayList<>();
        String[] columns = {SQLiteAxolotlStore.DEVICE_ID};
        String[] selectionArgs = {account.getUuid(),
                contact.getName()};
        Cursor cursor = db.query(SQLiteAxolotlStore.SESSION_TABLENAME,
                columns,
                SQLiteAxolotlStore.ACCOUNT + " = ? AND "
                        + SQLiteAxolotlStore.NAME + " = ?",
                selectionArgs,
                null, null, null);

        final int deviceIdIndex = cursor.getColumnIndex(SQLiteAxolotlStore.DEVICE_ID);
        while (cursor.moveToNext()) {
            devices.add(cursor.getInt(deviceIdIndex));
        }

        cursor.close();
        return devices;
    }

    public List<String> getKnownSignalAddresses(Account account) {
        List<String> addresses = new ArrayList<>();
        String[] colums = {"DISTINCT " + SQLiteAxolotlStore.NAME};
        String[] selectionArgs = {account.getUuid()};
        Cursor cursor = getReadableDatabase().query(SQLiteAxolotlStore.SESSION_TABLENAME,
                colums,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                selectionArgs,
                null, null, null
);
        while (cursor.moveToNext()) {
            addresses.add(cursor.getString(0));
        }
        cursor.close();
        return addresses;
    }

    public boolean containsSession(Account account, SignalProtocolAddress contact) {
        Cursor cursor = getCursorForSession(account, contact);
        int count = cursor.getCount();
        cursor.close();
        return count != 0;
    }

    public void storeSession(Account account, SignalProtocolAddress contact, SessionRecord session) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.NAME, contact.getName());
        values.put(SQLiteAxolotlStore.DEVICE_ID, contact.getDeviceId());
        values.put(SQLiteAxolotlStore.KEY, Base64.encodeToString(session.serialize(), Base64.DEFAULT));
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        db.insert(SQLiteAxolotlStore.SESSION_TABLENAME, null, values);
    }

    public void deleteSession(Account account, SignalProtocolAddress contact) {
        SQLiteDatabase db = this.getWritableDatabase();
        deleteSession(db, account, contact);
    }

    private void deleteSession(SQLiteDatabase db, Account account, SignalProtocolAddress contact) {
        String[] args = {account.getUuid(),
                contact.getName(),
                Integer.toString(contact.getDeviceId())};
        db.delete(SQLiteAxolotlStore.SESSION_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ? AND "
                        + SQLiteAxolotlStore.NAME + " = ? AND "
                        + SQLiteAxolotlStore.DEVICE_ID + " = ? ",
                args);
    }

    public void deleteAllSessions(Account account, SignalProtocolAddress contact) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {account.getUuid(), contact.getName()};
        db.delete(SQLiteAxolotlStore.SESSION_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + "=? AND "
                        + SQLiteAxolotlStore.NAME + " = ?",
                args);
    }

    private Cursor getCursorForPreKey(Account account, int preKeyId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {SQLiteAxolotlStore.KEY};
        String[] selectionArgs = {account.getUuid(), Integer.toString(preKeyId)};
        Cursor cursor = db.query(SQLiteAxolotlStore.PREKEY_TABLENAME,
                columns,
                SQLiteAxolotlStore.ACCOUNT + "=? AND "
                        + SQLiteAxolotlStore.ID + "=?",
                selectionArgs,
                null, null, null);

        return cursor;
    }

    public PreKeyRecord loadPreKey(Account account, int preKeyId) {
        PreKeyRecord record = null;
        Cursor cursor = getCursorForPreKey(account, preKeyId);
        if (cursor.getCount() != 0) {
            cursor.moveToFirst();
            try {
                final int keyIndex = cursor.getColumnIndex(SQLiteAxolotlStore.KEY);
                record = new PreKeyRecord(Base64.decode(cursor.getString(keyIndex), Base64.DEFAULT));
            } catch (InvalidMessageException e) {
                throw new AssertionError(e);
            }
        }
        cursor.close();
        return record;
    }

    public boolean containsPreKey(Account account, int preKeyId) {
        Cursor cursor = getCursorForPreKey(account, preKeyId);
        int count = cursor.getCount();
        cursor.close();
        return count != 0;
    }

    public void storePreKey(Account account, PreKeyRecord record) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ID, record.getId());
        values.put(SQLiteAxolotlStore.KEY, Base64.encodeToString(record.serialize(), Base64.DEFAULT));
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        db.insert(SQLiteAxolotlStore.PREKEY_TABLENAME, null, values);
    }

    public int deletePreKey(Account account, int preKeyId) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {account.getUuid(), Integer.toString(preKeyId)};
        return db.delete(SQLiteAxolotlStore.PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + "=? AND "
                        + SQLiteAxolotlStore.ID + "=?",
                args);
    }

    private Cursor getCursorForSignedPreKey(Account account, int signedPreKeyId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {SQLiteAxolotlStore.KEY};
        String[] selectionArgs = {account.getUuid(), Integer.toString(signedPreKeyId)};
        Cursor cursor = db.query(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                columns,
                SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.ID + "=?",
                selectionArgs,
                null, null, null);

        return cursor;
    }

    public SignedPreKeyRecord loadSignedPreKey(Account account, int signedPreKeyId) {
        SignedPreKeyRecord record = null;
        Cursor cursor = getCursorForSignedPreKey(account, signedPreKeyId);
        if (cursor.getCount() != 0) {
            cursor.moveToFirst();
            try {
                final int keyIndex = cursor.getColumnIndex(SQLiteAxolotlStore.KEY);
                record = new SignedPreKeyRecord(Base64.decode(cursor.getString(keyIndex), Base64.DEFAULT));
            } catch (InvalidMessageException e) {
                throw new AssertionError(e);
            }
        }
        cursor.close();
        return record;
    }

    public List<SignedPreKeyRecord> loadSignedPreKeys(Account account) {
        List<SignedPreKeyRecord> prekeys = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String[] columns = {SQLiteAxolotlStore.KEY};
        String[] selectionArgs = {account.getUuid()};
        Cursor cursor = db.query(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                columns,
                SQLiteAxolotlStore.ACCOUNT + "=?",
                selectionArgs,
                null, null, null);

        final int keyIndex = cursor.getColumnIndex(SQLiteAxolotlStore.KEY);
        while (cursor.moveToNext()) {
            try {
                prekeys.add(new SignedPreKeyRecord(Base64.decode(cursor.getString(keyIndex), Base64.DEFAULT)));
            } catch (InvalidMessageException ignored) {
            }
        }
        cursor.close();
        return prekeys;
    }

    public int getSignedPreKeysCount(Account account) {
        String[] columns = {"count(" + SQLiteAxolotlStore.KEY + ")"};
        String[] selectionArgs = {account.getUuid()};
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                columns,
                SQLiteAxolotlStore.ACCOUNT + "=?",
                selectionArgs,
                null, null, null);
        final int count;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        } else {
            count = 0;
        }
        cursor.close();
        return count;
    }

    public boolean containsSignedPreKey(Account account, int signedPreKeyId) {
        Cursor cursor = getCursorForPreKey(account, signedPreKeyId);
        int count = cursor.getCount();
        cursor.close();
        return count != 0;
    }

    public void storeSignedPreKey(Account account, SignedPreKeyRecord record) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ID, record.getId());
        values.put(SQLiteAxolotlStore.KEY, Base64.encodeToString(record.serialize(), Base64.DEFAULT));
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        db.insert(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, null, values);
    }

    public void deleteSignedPreKey(Account account, int signedPreKeyId) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] args = {account.getUuid(), Integer.toString(signedPreKeyId)};
        db.delete(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + "=? AND "
                        + SQLiteAxolotlStore.ID + "=?",
                args);
    }

    private Cursor getIdentityKeyCursor(Account account, String name, boolean own) {
        final SQLiteDatabase db = this.getReadableDatabase();
        return getIdentityKeyCursor(db, account, name, own);
    }

    private Cursor getIdentityKeyCursor(SQLiteDatabase db, Account account, String name, boolean own) {
        return getIdentityKeyCursor(db, account, name, own, null);
    }

    private Cursor getIdentityKeyCursor(Account account, String fingerprint) {
        final SQLiteDatabase db = this.getReadableDatabase();
        return getIdentityKeyCursor(db, account, fingerprint);
    }

    private Cursor getIdentityKeyCursor(SQLiteDatabase db, Account account, String fingerprint) {
        return getIdentityKeyCursor(db, account, null, null, fingerprint);
    }

    private Cursor getIdentityKeyCursor(SQLiteDatabase db, Account account, String name, Boolean own, String fingerprint) {
        String[] columns = {SQLiteAxolotlStore.TRUST,
                SQLiteAxolotlStore.ACTIVE,
                SQLiteAxolotlStore.LAST_ACTIVATION,
                SQLiteAxolotlStore.KEY};
        ArrayList<String> selectionArgs = new ArrayList<>(4);
        selectionArgs.add(account.getUuid());
        String selectionString = SQLiteAxolotlStore.ACCOUNT + " = ?";
        if (name != null) {
            selectionArgs.add(name);
            selectionString += " AND " + SQLiteAxolotlStore.NAME + " = ?";
        }
        if (fingerprint != null) {
            selectionArgs.add(fingerprint);
            selectionString += " AND " + SQLiteAxolotlStore.FINGERPRINT + " = ?";
        }
        if (own != null) {
            selectionArgs.add(own ? "1" : "0");
            selectionString += " AND " + SQLiteAxolotlStore.OWN + " = ?";
        }
        Cursor cursor = db.query(SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                columns,
                selectionString,
                selectionArgs.toArray(new String[selectionArgs.size()]),
                null, null, null);

        return cursor;
    }

    public IdentityKeyPair loadOwnIdentityKeyPair(Account account) {
        SQLiteDatabase db = getReadableDatabase();
        return loadOwnIdentityKeyPair(db, account, true);
    }

    private IdentityKeyPair loadOwnIdentityKeyPair(SQLiteDatabase db, Account account) {
        return loadOwnIdentityKeyPair(db, account, false);
    }

    /**
     * @param migrateIfLegacy if true, a row still holding a key that is not Keystore-wrapped is
     *                        re-persisted through {@link #storeOwnIdentityKeyPair} once decoded.
     *                        Must be false when called from within {@code onUpgrade}: that method
     *                        holds the only writable connection during the migration transaction,
     *                        and {@code storeOwnIdentityKeyPair} would re-enter
     *                        {@code getWritableDatabase()}, which is unsafe mid-upgrade.
     */
    private IdentityKeyPair loadOwnIdentityKeyPair(SQLiteDatabase db, Account account, boolean migrateIfLegacy) {
        String name = account.getJid().asBareJid().toString();
        IdentityKeyPair identityKeyPair = null;
        boolean needsKeystoreMigration = false;
        Cursor cursor = getIdentityKeyCursor(db, account, name, true);
        if (cursor.getCount() != 0) {
            cursor.moveToFirst();
            final int keyIndex = cursor.getColumnIndex(SQLiteAxolotlStore.KEY);
            try {
                final String stored = cursor.getString(keyIndex);
                final String decrypted = KeystoreCredentialStore.decryptString(omemoIdentityCredentialId(account.getUuid()), stored);
                // decrypted == null covers rows whose key is not Keystore-wrapped yet
                needsKeystoreMigration = decrypted == null;
                final String plainKey = needsKeystoreMigration ? stored : decrypted;
                identityKeyPair = new IdentityKeyPair(Base64.decode(plainKey, Base64.DEFAULT));
            } catch (Exception e) {
                try {
                    identityKeyPair = new IdentityKeyPair(Base64.decode(cursor.getString(keyIndex), Base64.DEFAULT));
                } catch (Exception e2) {
                }
            }
        }
        cursor.close();

        if (migrateIfLegacy && needsKeystoreMigration && identityKeyPair != null) {
            storeOwnIdentityKeyPair(account, identityKeyPair);
        }

        return identityKeyPair;
    }

    public static String omemoIdentityCredentialId(String accountUuid) {
        return "omemo_identity_" + accountUuid;
    }

    public Set<IdentityKey> loadIdentityKeys(Account account, String name) {
        return loadIdentityKeys(account, name, null);
    }

    public Set<IdentityKey> loadIdentityKeys(Account account, String name, FingerprintStatus status) {
        Set<IdentityKey> identityKeys = new HashSet<>();
        Cursor cursor = getIdentityKeyCursor(account, name, false);

        final int keyIndex = cursor.getColumnIndex(SQLiteAxolotlStore.KEY);
        while (cursor.moveToNext()) {
            if (status != null && !FingerprintStatus.fromCursor(cursor).equals(status)) {
                continue;
            }
            try {
                String key = cursor.getString(keyIndex);
                if (key != null) {
                    identityKeys.add(new IdentityKey(Base64.decode(key, Base64.DEFAULT), 0));
                }
            } catch (InvalidKeyException e) {
            }
        }
        cursor.close();

        return identityKeys;
    }

    public long numTrustedKeys(Account account, String name) {
        SQLiteDatabase db = getReadableDatabase();
        String[] args = {
                account.getUuid(),
                name,
                FingerprintStatus.Trust.TRUSTED.toString(),
                FingerprintStatus.Trust.VERIFIED.toString(),
                FingerprintStatus.Trust.VERIFIED_X509.toString()
        };
        return DatabaseUtils.queryNumEntries(db, SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?"
                        + " AND " + SQLiteAxolotlStore.NAME + " = ?"
                        + " AND (" + SQLiteAxolotlStore.TRUST + " = ? OR " + SQLiteAxolotlStore.TRUST + " = ? OR " + SQLiteAxolotlStore.TRUST + " = ?)"
                        + " AND " + SQLiteAxolotlStore.ACTIVE + " > 0",
                args
);
    }

    private void storeIdentityKey(Account account, String name, boolean own, String fingerprint, String base64Serialized, FingerprintStatus status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        values.put(SQLiteAxolotlStore.NAME, name);
        values.put(SQLiteAxolotlStore.OWN, own ? 1 : 0);
        values.put(SQLiteAxolotlStore.FINGERPRINT, fingerprint);
        values.put(SQLiteAxolotlStore.KEY, base64Serialized);
        values.putAll(status.toContentValues());
        String where = SQLiteAxolotlStore.ACCOUNT + "=? AND " + SQLiteAxolotlStore.NAME + "=? AND " + SQLiteAxolotlStore.FINGERPRINT + " =?";
        String[] whereArgs = {account.getUuid(), name, fingerprint};
        int rows = db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, values, where, whereArgs);
        if (rows == 0) {
            db.insert(SQLiteAxolotlStore.IDENTITIES_TABLENAME, null, values);
        }
    }

    public void storePreVerification(Account account, String name, String fingerprint, FingerprintStatus status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(SQLiteAxolotlStore.ACCOUNT, account.getUuid());
        values.put(SQLiteAxolotlStore.NAME, name);
        values.put(SQLiteAxolotlStore.OWN, 0);
        values.put(SQLiteAxolotlStore.FINGERPRINT, fingerprint);
        values.putAll(status.toContentValues());
        db.insert(SQLiteAxolotlStore.IDENTITIES_TABLENAME, null, values);
    }

    public FingerprintStatus getFingerprintStatus(Account account, String fingerprint) {
        Cursor cursor = getIdentityKeyCursor(account, fingerprint);
        final FingerprintStatus status;
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            status = FingerprintStatus.fromCursor(cursor);
        } else {
            status = null;
        }
        cursor.close();
        return status;
    }

    public boolean setIdentityKeyTrust(Account account, String fingerprint, FingerprintStatus fingerprintStatus) {
        SQLiteDatabase db = this.getWritableDatabase();
        return setIdentityKeyTrust(db, account, fingerprint, fingerprintStatus);
    }

    private boolean setIdentityKeyTrust(SQLiteDatabase db, Account account, String fingerprint, FingerprintStatus status) {
        String[] selectionArgs = {
                account.getUuid(),
                fingerprint
        };
        int rows = db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, status.toContentValues(),
                SQLiteAxolotlStore.ACCOUNT + " = ? AND "
                        + SQLiteAxolotlStore.FINGERPRINT + " = ? ",
                selectionArgs);
        return rows == 1;
    }

    public boolean setIdentityKeyCertificate(Account account, String fingerprint, X509Certificate x509Certificate) {
        SQLiteDatabase db = this.getWritableDatabase();
        String[] selectionArgs = {
                account.getUuid(),
                fingerprint
        };
        try {
            ContentValues values = new ContentValues();
            values.put(SQLiteAxolotlStore.CERTIFICATE, x509Certificate.getEncoded());
            return db.update(SQLiteAxolotlStore.IDENTITIES_TABLENAME, values,
                    SQLiteAxolotlStore.ACCOUNT + " = ? AND "
                            + SQLiteAxolotlStore.FINGERPRINT + " = ? ",
                    selectionArgs) == 1;
        } catch (CertificateEncodingException e) {
            return false;
        }
    }

    public X509Certificate getIdentityKeyCertifcate(Account account, String fingerprint) {
        SQLiteDatabase db = this.getReadableDatabase();
        String[] selectionArgs = {
                account.getUuid(),
                fingerprint
        };
        String[] colums = {SQLiteAxolotlStore.CERTIFICATE};
        String selection = SQLiteAxolotlStore.ACCOUNT + " = ? AND " + SQLiteAxolotlStore.FINGERPRINT + " = ? ";
        Cursor cursor = db.query(SQLiteAxolotlStore.IDENTITIES_TABLENAME, colums, selection, selectionArgs, null, null, null);
        if (cursor.getCount() < 1) {
            return null;
        } else {
            cursor.moveToFirst();
            final int certificateIndex = cursor.getColumnIndex(SQLiteAxolotlStore.CERTIFICATE);
            byte[] certificate = cursor.getBlob(certificateIndex);
            cursor.close();
            if (certificate == null || certificate.length == 0) {
                return null;
            }
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                return (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(certificate));
            } catch (CertificateException e) {
                return null;
            }
        }
    }

    public void storeIdentityKey(Account account, String name, IdentityKey identityKey, FingerprintStatus status) {
        storeIdentityKey(account, name, false, CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize()), Base64.encodeToString(identityKey.serialize(), Base64.DEFAULT), status);
    }

    /**
     * Persists the account's own OMEMO identity key pair, sealed with a Keystore-backed
     * AES-256-GCM key.
     *
     * <p>It fails closed rather than writing the Base64 serialization. Encryption here only fails if the
     * Android Keystore is unusable, in which case the Cognito token store, the WireGuard config,
     * and the account credential store are equally broken and the app cannot operate securely
     * anyway; losing OMEMO loudly is the right outcome, and is preferable to silently leaving a
     * recoverable private key in {@code identities.key}.
     *
     * @throws IllegalStateException if the identity key cannot be encrypted.
     */
    public void storeOwnIdentityKeyPair(Account account, IdentityKeyPair identityKeyPair) {
        final String plainKey = Base64.encodeToString(identityKeyPair.serialize(), Base64.DEFAULT);
        final String encryptedKey = KeystoreCredentialStore.encryptString(omemoIdentityCredentialId(account.getUuid()), plainKey);
        if (encryptedKey == null) {
            Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "failed to Keystore-encrypt own identity key; refusing to store it unencrypted");
            throw new IllegalStateException("OMEMO identity key encryption failed");
        }
        storeIdentityKey(account, account.getJid().asBareJid().toString(), true, CryptoHelper.bytesToHex(identityKeyPair.getPublicKey().serialize()), encryptedKey, FingerprintStatus.createActiveVerified(false));
    }


    private void recreateAxolotlDb(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.SESSION_TABLENAME);
        db.execSQL(CREATE_SESSIONS_STATEMENT);
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.PREKEY_TABLENAME);
        db.execSQL(CREATE_PREKEYS_STATEMENT);
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME);
        db.execSQL(CREATE_SIGNED_PREKEYS_STATEMENT);
        db.execSQL("DROP TABLE IF EXISTS " + SQLiteAxolotlStore.IDENTITIES_TABLENAME);
        db.execSQL(CREATE_IDENTITIES_STATEMENT);
    }

    public void wipeAxolotlDb(Account account) {
        String accountName = account.getUuid();
        SQLiteDatabase db = this.getWritableDatabase();
        String[] deleteArgs = {
                accountName
        };
        db.delete(SQLiteAxolotlStore.SESSION_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
        db.delete(SQLiteAxolotlStore.PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
        db.delete(SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
        db.delete(SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                SQLiteAxolotlStore.ACCOUNT + " = ?",
                deleteArgs);
    }

    public List<ShortcutService.FrequentContact> getFrequentContacts(int days) {
        SQLiteDatabase db = this.getReadableDatabase();
        final String SQL = "select " + Conversation.TABLENAME + "." + Conversation.ACCOUNT + "," + Conversation.TABLENAME + "." + Conversation.CONTACTJID + " from " + Conversation.TABLENAME + " join " + Message.TABLENAME + " on conversations.uuid=messages.conversationUuid where messages.status!=0 and carbon==0  and conversations.mode=0 and messages.timeSent>=? group by conversations.uuid order by count(body) desc limit 4;";
        String[] whereArgs = new String[]{String.valueOf(System.currentTimeMillis() - (Config.MILLISECONDS_IN_DAY * days))};
        Cursor cursor = db.rawQuery(SQL, whereArgs);
        ArrayList<ShortcutService.FrequentContact> contacts = new ArrayList<>();
        while (cursor.moveToNext()) {
            try {
                contacts.add(new ShortcutService.FrequentContact(cursor.getString(0), Jid.of(cursor.getString(1))));
            } catch (Exception e) {
            }
        }
        cursor.close();
        return contacts;
    }
}
