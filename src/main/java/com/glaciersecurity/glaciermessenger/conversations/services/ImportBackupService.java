package com.glaciersecurity.glaciermessenger.conversations.services;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import androidx.preference.PreferenceManager;
import com.glaciersecurity.glaciermessenger.utils.CognitoClientMigrationManager;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.glaciersecurity.glaciermessenger.services.XmppConnectionService;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.base.Stopwatch;
import com.google.common.io.CountingInputStream;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.SQLiteAxolotlStore;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.entities.CognitoAccount;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.ui.ConversationActivity;
import com.glaciersecurity.glaciermessenger.utils.BackupFileHeader;
import com.glaciersecurity.glaciermessenger.utils.ContentUriValidator;
import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;
import com.glaciersecurity.glaciermessenger.utils.LogRedactor;
import com.glaciersecurity.glaciermessenger.utils.SerialSingleThreadExecutor;
import com.glaciersecurity.glaciermessenger.worker.ExportBackupWorker;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import javax.crypto.BadPaddingException;

public class ImportBackupService extends Service {

    private static final ExecutorService BACKUP_FILE_READER_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private static final int NOTIFICATION_ID = 21;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private final ImportBackupServiceBinder binder = new ImportBackupServiceBinder();
    private final SerialSingleThreadExecutor executor =
            new SerialSingleThreadExecutor(getClass().getSimpleName());
    private final Set<OnBackupProcessed> mOnBackupProcessedListeners =
            Collections.newSetFromMap(new WeakHashMap<>());
    private DatabaseBackend mDatabaseBackend;
    private NotificationManager notificationManager;

    /**
     * Set once a restore has finished successfully, so a resumed ImportBackupActivity can route
     * into the app instead of deciding an account now exists and bouncing to the login screen.
     * Survives the activity (and the process) being torn down mid-import.
     */
    public static final String PREF_RESTORE_COMPLETED = "backup_restore_completed";

    private static final Collection<String> TABLE_ALLOW_LIST =
            Arrays.asList(
                    Account.TABLENAME,
                    Conversation.TABLENAME,
                    Message.TABLENAME,
                    GlacierOrgEntity.TABLENAME,
                    SQLiteAxolotlStore.PREKEY_TABLENAME,
                    SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                    SQLiteAxolotlStore.SESSION_TABLENAME,
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME);
    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$");

    /** ExternalStorageProvider -- the only document authority whose ids map back to a path. */
    private static final String EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents";

    @Override
    public void onCreate() {
        mDatabaseBackend = DatabaseBackend.getInstance(getBaseContext());
        notificationManager =
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        final String passphrase = intent.getStringExtra("passphrase");
        final RestoreCredentials credentials = RestoreCredentials.fromIntent(intent);
        final Uri data = intent.getData();
        final Uri uri;
        if (data == null) {
            final String file = intent.getStringExtra("file");
            uri = file == null ? null : Uri.fromFile(new File(file));
        } else {
            uri = data;
        }

        if (uri == null || passphrase == null || passphrase.isEmpty()) {
            // Nothing calls back from here, so without this line a restore that never starts is
            // indistinguishable in a log from one that hung.
            Log.e(
                    Config.LOGTAG,
                    "[restore] ignoring start request: uri="
                            + (uri == null ? "missing" : "present")
                            + " passphrase="
                            + (passphrase == null || passphrase.isEmpty() ? "missing" : "present"));
            return START_NOT_STICKY;
        }
        final String mediaZip = intent.getStringExtra("media_zip_uri");
        final Uri mediaZipUri = Strings.isNullOrEmpty(mediaZip) ? null : Uri.parse(mediaZip);
        if (running.compareAndSet(false, true)) {
            executor.execute(
                    () -> {
                        startForegroundService();
                        final boolean success =
                                importBackup(uri, passphrase, credentials, mediaZipUri);
                        stopForeground(true);
                        running.set(false);
                        if (success) {
                            markRestoreCompleted();
                            notifySuccess();
                        }
                        stopSelf();
                    });
        } else {
            Log.w(
                    Config.LOGTAG,
                    "[restore] ignoring start request: another restore is already running");
        }
        return START_NOT_STICKY;
    }

    public boolean getLoadingState() {
        return running.get();
    }

    public void loadBackupFiles(final OnBackupFilesLoaded onBackupFilesLoaded) {
        executor.execute(
                () -> {
                    final ArrayList<BackupFile> backupFiles = new ArrayList<>();
                    final Set<String> apps =
                            new HashSet<>(
                                    Arrays.asList(
                                            "Conversations",
                                            "Quicksy",
                                            getString(R.string.app_name)));
                    final List<File> directories = new ArrayList<>();
                    for (final String app : apps) {
                        directories.add(FileBackend.getLegacyBackupDirectory(app));
                    }
                    for (final File directory : FileBackend.getBackupDirectoryCustomCandidates()) {
                        if (!directories.contains(directory)) {
                            directories.add(directory);
                        }
                    }
                    for (final File directory : directories) {
                        if (!directory.exists() || !directory.isDirectory()) {
                            continue;
                        }
                        final File[] files = directory.listFiles();
                        if (files == null) {
                            continue;
                        }
                        for (final File file : files) {
                            if (file.isFile() && file.getName().endsWith(".ceb")) {
                                try {
                                    backupFiles.add(BackupFile.read(file));
                                } catch (final IOException
                                        | IllegalArgumentException
                                        | BackupFileHeader.OutdatedBackupFileVersion e) {
                                }
                            } else if (file.isDirectory()) {
                                final File[] subFiles = file.listFiles();
                                if (subFiles == null) continue;
                                for (final File subFile : subFiles) {
                                    if (subFile.isFile() && subFile.getName().endsWith(".ceb")) {
                                        try {
                                            backupFiles.add(BackupFile.read(subFile));
                                        } catch (final IOException
                                                | IllegalArgumentException
                                                | BackupFileHeader.OutdatedBackupFileVersion e) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Collections.sort(
                            backupFiles,
                            Comparator.comparingLong(
                                            (BackupFile file) -> file.header.getTimestamp())
                                    .reversed());
                    onBackupFilesLoaded.onBackupFilesLoaded(backupFiles);
                });
    }

    /**
     * Lists the backups in a folder the user granted us, rather than the ones we can see on disk.
     *
     * Scoped storage only shows this app the files it wrote itself, so on a new phone the
     * on-disk scan in loadBackupFiles() finds nothing and the user has to reach for the file
     * browser -- which hands back a single document, with no way to see the media archive
     * sitting next to it. A folder grant covers both, so the archive can be paired with its
     * backup here and carried through the restore.
     */
    public void loadBackupFilesFromTree(
            final Uri treeUri, final OnBackupFolderLoaded onBackupFolderLoaded) {
        executor.execute(
                () -> {
                    final List<BackupFile> backupFiles = new ArrayList<>();
                    final List<Throwable> rejected = new ArrayList<>();
                    try {
                        collectBackupFiles(
                                treeUri,
                                DocumentsContract.getTreeDocumentId(treeUri),
                                backupFiles,
                                rejected,
                                0);
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, "could not read the chosen backup folder", e);
                        rejected.add(e);
                    }
                    Collections.sort(
                            backupFiles,
                            Comparator.comparingLong(
                                            (BackupFile file) -> file.header.getTimestamp())
                                    .reversed());
                    // Hand back why a candidate was refused, not just the survivors. A folder
                    // holding one outdated or corrupt backup would otherwise come back empty and
                    // read as though the user had picked the wrong folder -- the single-file route
                    // put these through showBackupThrowable() and said what was actually wrong.
                    onBackupFolderLoaded.onBackupFolderLoaded(
                            backupFiles, rejected.isEmpty() ? null : rejected.get(0));
                });
    }

    /**
     * The exporter writes each backup into its own directory, so one level below the folder the
     * user picks is the common case and two is the slack for someone who grants the parent.
     * Bounded because the grant may well be the whole of Download.
     */
    private static final int MAX_BACKUP_FOLDER_DEPTH = 2;

    private void collectBackupFiles(
            final Uri treeUri,
            final String documentId,
            final List<BackupFile> backupFiles,
            final List<Throwable> rejected,
            final int depth) {
        final List<String> subdirectories = new ArrayList<>();
        final List<Uri> backups = new ArrayList<>();
        Uri mediaZip = null;
        final Uri children =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (final Cursor cursor =
                getContentResolver()
                        .query(
                                children,
                                new String[] {
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE
                                },
                                null,
                                null,
                                null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                final String childId = cursor.getString(0);
                final String name = Strings.nullToEmpty(cursor.getString(1)).toLowerCase(Locale.ROOT);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    subdirectories.add(childId);
                } else if (name.endsWith(".ceb")) {
                    backups.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId));
                } else if (mediaZip == null && name.endsWith(".zip")) {
                    mediaZip = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                }
            }
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "could not list a directory of the chosen backup folder", e);
            rejected.add(e);
            return;
        }
        for (final Uri backup : backups) {
            try {
                backupFiles.add(BackupFile.read(this, backup).withMediaZip(mediaZip));
            } catch (final IOException
                    | IllegalArgumentException
                    | BackupFileHeader.OutdatedBackupFileVersion e) {
                rejected.add(e);
            }
        }
        if (depth >= MAX_BACKUP_FOLDER_DEPTH) {
            return;
        }
        for (final String subdirectory : subdirectories) {
            collectBackupFiles(treeUri, subdirectory, backupFiles, rejected, depth + 1);
        }
    }

    private void startForegroundService() {
        startForeground(NOTIFICATION_ID, createImportBackupNotification(1, 0));
    }

    private void updateImportBackupNotification(final long total, final long current) {
        final int max;
        final int progress;
        if (total == 0) {
            max = 1;
            progress = 0;
        } else {
            max = 100;
            progress = (int) (current * 100 / total);
        }
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        try {
            notificationManager.notify(
                    NOTIFICATION_ID, createImportBackupNotification(max, progress));
        } catch (final SecurityException e) {
        } catch (final RuntimeException e) {
        }
    }

    private Notification createImportBackupNotification(final int max, final int progress) {
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(getBaseContext(), "backup");
        mBuilder.setContentTitle(getString(R.string.restoring_backup))
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
                .setProgress(max, progress, max == 1 && progress == 0);
        return mBuilder.build();
    }

    private boolean importBackup(
            final Uri uri,
            final String passphrase,
            final RestoreCredentials credentials,
            final Uri mediaZipUri) {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            restoreMedia(uri, mediaZipUri, passphrase);
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "media zip restore failed, continuing with database import", e);
        }
        try {
            final long fileSize = getBackupFileSize(uri);
            final List<KeyDerivationStrategy> strategies =
                    Arrays.asList(
                            KeyDerivationStrategy.STRONG,
                            KeyDerivationStrategy.LEGACY);
            for (int i = 0; i < strategies.size(); i++) {
                final KeyDerivationStrategy strategy = strategies.get(i);
                try {
                    return importBackupAttempt(
                            uri, passphrase, fileSize, stopwatch, strategy, credentials);
                } catch (final Exception e) {
                    final boolean reasonWasCrypto = wasCryptoFailure(e);
                    final boolean hasFallback = i < (strategies.size() - 1);
                    if (reasonWasCrypto && hasFallback) {
                        continue;
                    }
                    throw e;
                }
            }
            throw new IllegalStateException("all backup KDF attempts failed");
        } catch (final Exception e) {
            final boolean reasonWasCrypto = wasCryptoFailure(e);
            if (reasonWasCrypto) {
                notifyDecryptionFailed();
            } else {
                final RestoreFailure failure = classifyRestoreFailure(e);
                Log.e(
                        Config.LOGTAG,
                        "[restore] FAILED: "
                                + failure
                                + " after "
                                + stopwatch
                                + " ("
                                + e.getClass().getSimpleName()
                                + ": "
                                + e.getMessage()
                                + ")",
                        e);
                notifyRestoreFailed(failure);
            }
            return false;
        }
    }

    private static boolean wasCryptoFailure(final Exception e) {
        return e.getCause() instanceof BadPaddingException || e instanceof ZipException;
    }

    /**
     * Turns the exception that ended a restore into something we can show the user and grep for.
     *
     * Every one of these used to surface as the same "could not restore backup", which made a
     * corrupt file, a backup from a newer build and a database that rejected a row indistinguishable
     * both to the user and in a support log.
     */
    private static RestoreFailure classifyRestoreFailure(final Exception e) {
        if (e instanceof BackupFileHeader.OutdatedBackupFileVersion) {
            return RestoreFailure.INCOMPATIBLE_FORMAT;
        }
        if (e instanceof NoAccountInBackupException) {
            return RestoreFailure.NO_ACCOUNT_IN_BACKUP;
        }
        if (e instanceof SchemaMismatchException || e instanceof SQLiteException) {
            return RestoreFailure.DATABASE_REJECTED;
        }
        if (e instanceof FileNotFoundException || e instanceof BackupFileUnreadableException) {
            return RestoreFailure.FILE_UNREADABLE;
        }
        if (e instanceof IllegalArgumentException) {
            // BackupFileHeader.read() throws this for a version it does not recognize.
            return RestoreFailure.INCOMPATIBLE_FORMAT;
        }
        if (e instanceof MalformedBackupException || e instanceof MalformedJsonException) {
            // The bytes arrived but they are not a backup: bad header, wrong keys, unknown table.
            return RestoreFailure.NOT_A_BACKUP;
        }
        if (e instanceof IOException) {
            // Every complaint about the *content* is typed above, so a bare IOException here is
            // the transport giving out: a document-provider, cloud or removable-drive stream that
            // died mid-read. The file may be perfectly good, so telling the user it is not a
            // backup would send them hunting for a different one instead of retrying.
            return RestoreFailure.FILE_UNREADABLE;
        }
        return RestoreFailure.UNKNOWN;
    }

    private void notifyRestoreFailed(final RestoreFailure failure) {
        synchronized (mOnBackupProcessedListeners) {
            for (final OnBackupProcessed l : mOnBackupProcessedListeners) {
                l.onBackupRestoreFailed(failure);
            }
        }
    }

    private void notifyDecryptionFailed() {
        synchronized (mOnBackupProcessedListeners) {
            for (final OnBackupProcessed l : mOnBackupProcessedListeners) {
                l.onBackupDecryptionFailed();
            }
        }
    }

    /** The backup stream could not be opened; distinct so it does not read as a corrupt file. */
    private static final class BackupFileUnreadableException extends IOException {
        private BackupFileUnreadableException(final String message) {
            super(message);
        }
    }

    /** Read to the end of the backup without an accounts row for the JID in the header. */
    private static final class NoAccountInBackupException extends IOException {
        private NoAccountInBackupException(final String message) {
            super(message);
        }
    }

    /**
     * The bytes were read fine but they are not a well-formed backup.
     *
     * Typed so it is distinguishable from an IOException raised by the stream underneath us --
     * an interrupted document-provider or cloud read is a transport failure, not a bad file.
     */
    private static final class MalformedBackupException extends IOException {
        private MalformedBackupException(final String message) {
            super(message);
        }
    }

    /** A row this device's schema cannot accommodate at all. */
    private static final class SchemaMismatchException extends IOException {
        private SchemaMismatchException(final String message) {
            super(message);
        }
    }

    private boolean importBackupAttempt(
            final Uri uri,
            final String passphrase,
            final long fileSize,
            final Stopwatch stopwatch,
            final KeyDerivationStrategy keyDerivationStrategy,
            final RestoreCredentials credentials)
            throws Exception {
        final SQLiteDatabase db = mDatabaseBackend.getWritableDatabase();
        // Per attempt, not per restore: a wrong-key GCM tag only fails at the end of the stream,
        // so a failed STRONG attempt can have read rows before throwing and the LEGACY retry
        // would otherwise add its counts on top of those.
        tableColumns.clear();
        reportedMissingColumns.clear();
        reportedOlderSchemaTables.clear();
        importedRowCounts.clear();
        final InputStream backupInputStream = openBackupInputStream(uri);
        if (backupInputStream == null) {
            // Thrown rather than reported here so it lands in the one place that classifies
            // and logs failures, instead of ending the restore silently mid-method.
            throw new BackupFileUnreadableException(
                    "could not open a stream for the backup file");
        }
        // Declared out here so the finally can reach the reader, which is what closes the whole
        // decrypt chain underneath it.
        CipherInputStream cipherInputStream = null;
        BufferedReader reader = null;
        try (final InputStream inputStream = backupInputStream;
                final CountingInputStream countingInputStream =
                        new CountingInputStream(inputStream);
                final DataInputStream dataInputStream =
                        new DataInputStream(countingInputStream)) {
            final BackupFileHeader backupFileHeader;
            try {
                backupFileHeader = BackupFileHeader.read(dataInputStream);
            } catch (final EOFException e) {
                // Too short to hold a header at all, so this is the wrong file rather than a read
                // that gave out. An EOF further in is left untyped and reads as a broken stream.
                throw new MalformedBackupException("file is too short to contain a backup header");
            }

            if (mDatabaseBackend.getAccountJids(false).contains(backupFileHeader.getJid())) {
                synchronized (mOnBackupProcessedListeners) {
                    for (OnBackupProcessed l : mOnBackupProcessedListeners) {
                        l.onAccountAlreadySetup();
                    }
                }
                return false;
            }

            final byte[] key =
                    keyDerivationStrategy == KeyDerivationStrategy.LEGACY
                            ? ExportBackupWorker.getLegacyKey(passphrase, backupFileHeader.getSalt())
                            : ExportBackupWorker.getKey(passphrase, backupFileHeader.getSalt());

            final AEADBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(
                    false,
                    new AEADParameters(new KeyParameter(key), 128, backupFileHeader.getIv()));
            cipherInputStream = new CipherInputStream(countingInputStream, cipher);
            try {
                reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        new GZIPInputStream(cipherInputStream), Charsets.UTF_8));
            } catch (final IOException e) {
                // That constructor reads the gzip header, so a failure here leaves the cipher
                // stream open with nothing wrapping it to close it later.
                FileBackend.close(cipherInputStream);
                throw e;
            }
            final JsonReader jsonReader = new JsonReader(reader);
            if (jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
                jsonReader.beginArray();
            } else {
                throw new MalformedBackupException("Backup file did not begin with array");
            }
            boolean committed = false;
            db.beginTransaction();
            try {
                long rowsRead = 0;
                while (jsonReader.hasNext()) {
                    if (jsonReader.peek() == JsonToken.BEGIN_OBJECT) {
                        importRow(db, jsonReader, backupFileHeader.getJid());
                        if (++rowsRead % 5000 == 0) {
                        }
                    } else if (jsonReader.peek() == JsonToken.END_ARRAY) {
                        jsonReader.endArray();
                        continue;
                    } else {
                        // Neither branch above consumes this token and nothing below does either,
                        // so a value that is neither a row nor the end of the array left
                        // hasNext() true forever -- the restore spun here updating the progress
                        // notification and never came back, with no error and no way out but
                        // force-stopping the app. A backup array holds nothing but row objects,
                        // so anything else is a malformed file and belongs with the other
                        // not-a-backup failures.
                        throw new MalformedBackupException(
                                String.format(
                                        "expected a row object in the backup array but found %s",
                                        jsonReader.peek()));
                    }
                    updateImportBackupNotification(fileSize, countingInputStream.getCount());
                }
                // Everything else in the backup hangs off the accounts row by foreign key, so
                // without it the restore is empty no matter how many rows were read. Check before
                // committing, so a failure here rolls back instead of leaving a half-restored
                // database behind a "restored" message.
                if (!accountWasImported(db, backupFileHeader.getJid())) {
                    throw new NoAccountInBackupException("backup did not restore an account row");
                }
                db.setTransactionSuccessful();
                committed = true;
            } finally {
                db.endTransaction();
                logImportSummary();
            }
            final Jid jid = backupFileHeader.getJid();
            final Cursor countCursor =
                    db.rawQuery(
                            "select count(messages.uuid) from messages join conversations on conversations.uuid=messages.conversationUuid join accounts on conversations.accountUuid=accounts.uuid where accounts.username=? and accounts.server=?",
                            new String[] {
                                jid.getEscapedLocal(), jid.getDomain().toEscapedString()
                            });
            countCursor.moveToFirst();
            final int count = countCursor.getInt(0);
            countCursor.close();
            // Past this point the rows are committed, so the restore has succeeded whatever
            // happens next. These two steps used to run bare inside the try: a throw from either
            // -- a Keystore hiccup, a service start refused in the background -- was caught by
            // importBackup() and reported as "could not restore backup" over a database that had
            // actually been restored, sending the user back to redo it.
            try {
                applyRestoreCredentials(jid, credentials);
            } catch (final Exception e) {
                Log.e(
                        Config.LOGTAG,
                        "[restore] rows are restored but the account could not be enabled; the"
                                + " user will have to log in manually",
                        e);
            }
            try {
                notifyServiceOfRestore();
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "[restore] could not notify XmppConnectionService", e);
            }
            synchronized (mOnBackupProcessedListeners) {
                for (OnBackupProcessed l : mOnBackupProcessedListeners) {
                    l.onBackupRestored();
                }
            }
            return true;
        } finally {
            // Closing the reader takes the gzip and cipher streams with it. BC's CipherInputStream
            // re-checks the GCM tag as it closes and the JSON reader stops at the closing bracket
            // rather than at end of stream, so this can throw on a restore that worked.
            FileBackend.close(reader);
        }
    }

    /**
     * Restores the media archive that belongs to this backup, by whichever route we have to it.
     *
     * An archive picked from a folder grant is the reliable one: the tree covers every file in
     * the directory, so the zip can be opened through the provider. Falling back to a path only
     * works for a backup this device wrote, and is kept for the in-app list, which hands us
     * file: uris.
     */
    private void restoreMedia(final Uri uri, final Uri mediaZipUri, final String passphrase) {
        if (mediaZipUri != null) {
            restoreMediaZip(mediaZipUri, passphrase);
            return;
        }
        final File mediaDirectory = backupDirectoryOf(uri);
        if (mediaDirectory == null) {
            // Saying nothing here was the whole problem: media restore simply did not happen and
            // no line in the log admitted it.
            Log.w(
                    Config.LOGTAG,
                    "[restore] no media archive was chosen and no readable directory for this"
                            + " backup (scheme="
                            + uri.getScheme()
                            + "); media will not be restored");
            return;
        }
        restoreMediaZip(mediaDirectory, passphrase);
    }

    /**
     * Where to look for the media zip that sits beside the backup the user picked.
     *
     * The zip is a separate file, so a restore has to go find it. Gating that on a "file" scheme
     * meant every backup opened through the document picker or shared in from another app -- a
     * content: URI, and the usual case for a migration -- skipped media outright and without a
     * word, restoring messages whose attachments all pointed at files nothing had written.
     *
     * A single-document SAF grant covers the document the user picked and nothing else, so the
     * sibling zip cannot be reached back through the provider; the only route to it is a real
     * path. Documents on primary external storage carry that path in their document id
     * ("primary:Download/Guardian_Backup/<dir>/x.ceb") -- the same mapping ImportBackupActivity
     * runs in reverse to open the picker on the backup directory.
     *
     * Scoped storage still decides whether that path can be read, and this app holds no
     * broad-storage permission, so it recovers media for a backup this device exported and the
     * user then re-picked through the browser. A backup copied in from another phone is not ours
     * and stays unreadable by path; its zip needs a grant of its own. Returning null makes that
     * case land in the log instead of passing unremarked.
     */
    private File backupDirectoryOf(final Uri uri) {
        final File backupFile = fileForBackupUri(uri);
        final File directory = backupFile == null ? null : backupFile.getParentFile();
        if (directory == null) {
            return null;
        }
        if (!directory.isDirectory()) {
            return null;
        }
        return directory;
    }

    /** The backup as a path on disk, or null when the uri does not name one we can reconstruct. */
    private File fileForBackupUri(final Uri uri) {
        final String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            final String path = uri.getPath();
            return path == null ? null : new File(path);
        }
        if (!"content".equals(scheme)
                || !EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY.equals(uri.getAuthority())
                || !DocumentsContract.isDocumentUri(this, uri)) {
            return null;
        }
        final String documentId;
        try {
            documentId = DocumentsContract.getDocumentId(uri);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final int separator = documentId.indexOf(':');
        if (separator < 0) {
            return null;
        }
        final String volume = documentId.substring(0, separator);
        final String relativePath = documentId.substring(separator + 1);
        if (!"primary".equalsIgnoreCase(volume) || relativePath.isEmpty()) {
            // Secondary and removable volumes have no path that can be rebuilt from the id.
            return null;
        }
        return new File(Environment.getExternalStorageDirectory(), relativePath);
    }

    private void restoreMediaZip(final File exportDir, final String passphrase) {
        if (exportDir == null || !exportDir.isDirectory()) {
            return;
        }
        final File[] zips = exportDir.listFiles(f -> f.isFile() && f.getName().endsWith(".zip"));
        if (zips == null || zips.length == 0) {
            return;
        }
        final File zipFile = zips[0];
        try (final FileInputStream fis = new FileInputStream(zipFile)) {
            restoreMediaZip(fis, zipFile.getName(), passphrase);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "failed to open media zip " + zipFile.getName(), e);
        }
    }

    /** The archive the user picked out of the backup folder, read through the provider. */
    private void restoreMediaZip(final Uri zipUri, final String passphrase) {
        try (final InputStream inputStream = getContentResolver().openInputStream(zipUri)) {
            if (inputStream == null) {
                Log.w(Config.LOGTAG, "could not open the chosen media archive");
                return;
            }
            restoreMediaZip(inputStream, String.valueOf(zipUri.getLastPathSegment()), passphrase);
        } catch (final IOException | SecurityException e) {
            Log.w(Config.LOGTAG, "could not open the chosen media archive", e);
        }
    }

    private void restoreMediaZip(
            final InputStream source, final String label, final String passphrase) {

        final Map<String, File> dirMap = new LinkedHashMap<>();
        dirMap.put("Pictures", FileBackend.getConversationsMediaDirectory(this, Environment.DIRECTORY_PICTURES));
        dirMap.put("Movies", FileBackend.getConversationsMediaDirectory(this, Environment.DIRECTORY_MOVIES));
        dirMap.put("Documents", FileBackend.getConversationsMediaDirectory(this, Environment.DIRECTORY_DOCUMENTS));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dirMap.put("Recordings", FileBackend.getConversationsMediaDirectory(this, Environment.DIRECTORY_RECORDINGS));
        }

        try {
            final byte[] iv = new byte[12];
            final byte[] salt = new byte[16];
            final DataInputStream headerStream = new DataInputStream(source);
            headerStream.readFully(iv);
            headerStream.readFully(salt);

            final byte[] key = ExportBackupWorker.getKey(passphrase, salt);
            final AEADBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(false, new AEADParameters(new KeyParameter(key), 128, iv));

            try (final CipherInputStream cis = new CipherInputStream(source, cipher);
                 final ZipInputStream zis = new ZipInputStream(cis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    final String entryName = entry.getName();
                    File targetBase = null;
                    String relativePath = null;
                    for (final Map.Entry<String, File> dirEntry : dirMap.entrySet()) {
                        final String dirPrefix = dirEntry.getKey() + "/";
                        if (entryName.startsWith(dirPrefix)) {
                            targetBase = dirEntry.getValue();
                            relativePath = entryName.substring(dirPrefix.length());
                            break;
                        }
                    }
                    if (targetBase == null || relativePath == null || relativePath.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }
                    final File target = new File(targetBase, relativePath);
                    // zip slip guard
                    if (!target.getCanonicalPath().startsWith(targetBase.getCanonicalPath() + File.separator)) {
                        Log.w(Config.LOGTAG, "skipping zip entry outside target directory: " + entryName);
                        zis.closeEntry();
                        continue;
                    }
                    if (entry.isDirectory()) {
                        target.mkdirs();
                    } else {
                        final File parent = target.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        try (final FileOutputStream fos = new FileOutputStream(target)) {
                            final byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
        } catch (final IOException | InvalidKeySpecException e) {
            Log.w(Config.LOGTAG, "failed to restore media zip", e);
        }
    }

    private InputStream openBackupInputStream(final Uri uri) throws FileNotFoundException {
        final String path = uri.getPath();
        if ("file".equals(uri.getScheme()) && path != null) {
            return new FileInputStream(new File(path));
        }
        return getContentResolver().openInputStream(uri);
    }

    private long getBackupFileSize(final Uri uri) {
        final String path = uri.getPath();
        if ("file".equals(uri.getScheme()) && path != null) {
            final File file = new File(path);
            return file.length();
        }
        final Cursor returnCursor = getContentResolver().query(uri, null, null, null, null);
        if (returnCursor == null) {
            return 0;
        }
        try {
            returnCursor.moveToFirst();
            return returnCursor.getLong(returnCursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
        } finally {
            returnCursor.close();
        }
    }

    private boolean accountWasImported(final SQLiteDatabase db, final Jid jid) {
        try (final Cursor cursor =
                db.rawQuery(
                        "select count(*) from " + Account.TABLENAME + " where username=? and server=?",
                        new String[] {jid.getEscapedLocal(), jid.getDomain().toEscapedString()})) {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        }
    }

    private enum KeyDerivationStrategy {
        STRONG,
        LEGACY
    }



    /** Columns each table actually has on THIS device, read once per restore. */
    private final Map<String, Set<String>> tableColumns = new LinkedHashMap<>();

    /** Column names already reported as missing, so one bad column logs once, not once per row. */
    private final Set<String> reportedMissingColumns = new HashSet<>();

    /** Tables whose backward (older-backup) column gap has already been reported. */
    private final Set<String> reportedOlderSchemaTables = new HashSet<>();

    /** Rows successfully inserted per table, for the summary line at the end of a restore. */
    private final Map<String, Integer> importedRowCounts = new LinkedHashMap<>();

    private Set<String> columnsOf(final SQLiteDatabase db, final String table) {
        final Set<String> cached = tableColumns.get(table);
        if (cached != null) {
            return cached;
        }
        final Set<String> columns = new HashSet<>();
        // Safe to interpolate: table is already checked against TABLE_ALLOW_LIST by the caller.
        try (final Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            final int nameIndex = cursor.getColumnIndex("name");
            while (nameIndex >= 0 && cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "[restore] could not read schema of table " + table, e);
        }
        tableColumns.put(table, columns);
        return columns;
    }

    /**
     * Reconciles a backup row's columns with this device's schema, in both directions.
     *
     * The exporter writes every column of the *exporting* device's schema, and the backup file
     * records no schema version, so the only thing to compare against is the row itself.
     *
     * Backup NEWER than this build -- it carries columns we have never heard of. insertOrThrow
     * rejects the whole row for one unknown column, which used to abort the entire restore over a
     * field nobody would have missed. Drop those columns and keep the row.
     *
     * Backup OLDER than this build -- it is missing columns we have since added. Every column of
     * every importable table is nullable or carries a DEFAULT, so SQLite fills these in and the
     * insert succeeds; the gap is only logged, so an old backup is identifiable in a support log
     * rather than looking identical to a current one. If a future migration ever adds a NOT NULL
     * column without a default to one of these tables, that is the change that would start failing
     * every older restore -- and this line is what will say so.
     *
     * A row that fails to insert for any other reason still aborts the restore, because that is a
     * real problem rather than a version skew.
     */
    private ContentValues dropUnknownColumns(
            final SQLiteDatabase db, final String table, final ContentValues values) {
        final Set<String> known = columnsOf(db, table);
        if (known.isEmpty()) {
            // No schema to compare against -- let the insert speak for itself.
            return values;
        }
        if (reportedOlderSchemaTables.add(table)) {
            final Set<String> absent = new LinkedHashSet<>(known);
            absent.removeAll(values.keySet());
            // Not age-related: the exporter omits these two on purpose (never export
            // reusable authentication material), so every backup lacks them by design.
            if (Account.TABLENAME.equals(table)) {
                absent.remove(Account.PASSWORD);
                absent.remove(Account.FAST_TOKEN);
            }
            if (!absent.isEmpty()) {
            }
        }
        ContentValues filtered = null;
        for (final String name : new ArrayList<>(values.keySet())) {
            if (known.contains(name)) {
                continue;
            }
            if (filtered == null) {
                filtered = new ContentValues(values);
            }
            filtered.remove(name);
            if (reportedMissingColumns.add(table + "." + name)) {
                Log.w(
                        Config.LOGTAG,
                        "[restore] dropping column "
                                + table
                                + "."
                                + name
                                + " -- not present in this device's schema (db version "
                                + mDatabaseBackend.getWritableDatabase().getVersion()
                                + "); the backup is from a newer build");
            }
        }
        return filtered == null ? values : filtered;
    }

    private void logImportSummary() {
        if (importedRowCounts.isEmpty()) {
            Log.w(Config.LOGTAG, "[restore] no rows were imported from this backup");
            return;
        }
        final StringBuilder summary = new StringBuilder("[restore] rows imported:");
        for (final Map.Entry<String, Integer> entry : importedRowCounts.entrySet()) {
            summary.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (!reportedMissingColumns.isEmpty()) {
            summary.append(" | columns dropped as unknown: ").append(reportedMissingColumns);
        }
    }

    private void importRow(
            final SQLiteDatabase db,
            final JsonReader jsonReader,
            final Jid account)
            throws IOException {
        jsonReader.beginObject();
        final String firstParameter = jsonReader.nextName();
        if (!firstParameter.equals("table")) {
            throw new MalformedBackupException("Expected key 'table'");
        }
        final String table = jsonReader.nextString();
        if (!TABLE_ALLOW_LIST.contains(table)) {
            Log.e(
                    Config.LOGTAG,
                    "[restore] backup contains table '" + table + "' which this build will not import");
            throw new MalformedBackupException(
                    String.format("%s is not recognized for import", table));
        }
        final ContentValues contentValues = new ContentValues();
        final String secondParameter = jsonReader.nextName();
        if (!secondParameter.equals("values")) {
            throw new MalformedBackupException("Expected key 'values'");
        }
        jsonReader.beginObject();
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull();
                    contentValues.putNull(name);
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong());
                } else {
                    contentValues.put(name, jsonReader.nextString());
                }
            } else {
                throw new MalformedBackupException(
                        String.format("Unexpected column name %s", name));
            }
        }
        jsonReader.endObject();
        jsonReader.endObject();
        if (Message.TABLENAME.equals(table)) {
            // Backups created before media moved into app-owned storage hold
            // legacy absolute paths (e.g. /storage/emulated/0/Pictures/<app>/...)
            // in relativeFilePath. The media zip restores those files into the
            // new app-owned directories, so rewrite the stored path to match;
            // otherwise FileBackend.getFileForPath() would look in the old
            // public location and the media would appear missing.
            final String relativeFilePath =
                    contentValues.getAsString(Message.RELATIVE_FILE_PATH);
            if (relativeFilePath != null && !relativeFilePath.isEmpty()) {
                contentValues.put(
                        Message.RELATIVE_FILE_PATH,
                        FileBackend.rewriteImportedRelativeFilePath(this, relativeFilePath));
            }
        }
        if (Account.TABLENAME.equals(table)) {
            final Jid jid =
                    Jid.of(
                            contentValues.getAsString(Account.USERNAME),
                            contentValues.getAsString(Account.SERVER),
                            null);
            if (!jid.equals(account)) {
                Log.e(
                        Config.LOGTAG,
                        "[restore] accounts row jid "
                                + LogRedactor.id(jid.toEscapedString())
                                + " does not match header jid "
                                + LogRedactor.id(account.toEscapedString()));
                throw new MalformedBackupException("jid in table did not match backup");
            }
        }
        if (SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table)
                && "1".equals(contentValues.getAsString(SQLiteAxolotlStore.OWN))) {
            // backups (old and new alike) carry the own identity key in plaintext,
            // protected only by the backup passphrase; re-wrap it with this device's
            // own Keystore key before it lands in the live DB
            final String accountUuid = contentValues.getAsString(SQLiteAxolotlStore.ACCOUNT);
            final String plainKey = contentValues.getAsString(SQLiteAxolotlStore.KEY);
            if (accountUuid != null && plainKey != null) {
                final String encrypted =
                        KeystoreCredentialStore.encryptString(
                                DatabaseBackend.omemoIdentityCredentialId(accountUuid), plainKey);
                if (encrypted != null) {
                    contentValues.put(SQLiteAxolotlStore.KEY, encrypted);
                } else {
                    Log.w(Config.LOGTAG, "failed to Keystore-encrypt imported own identity key; storing unencrypted");
                }
            }
        }
        final ContentValues insertable = dropUnknownColumns(db, table, contentValues);
        if (insertable.size() == 0) {
            throw new SchemaMismatchException(
                    String.format(
                            "every column of a %s row is unknown to this device's schema", table));
        }
        // insertOrThrow, not insert: insert() swallows SQLiteException and returns -1, so a row
        // the current schema rejects used to disappear silently. Losing the accounts row that way
        // was invisible and total -- every conversation then failed the foreign key check, every
        // message failed with it, and the restore still reported success onto an empty database.
        try {
            db.insertOrThrow(table, null, insertable);
        } catch (final SQLiteException e) {
            // Name the table and the columns involved: this is the line that tells us whether a
            // field report is a schema mismatch or something else entirely.
            Log.e(
                    Config.LOGTAG,
                    "[restore] insert into "
                            + table
                            + " failed with columns "
                            + insertable.keySet()
                            + ": "
                            + e.getMessage());
            throw e;
        }
        importedRowCounts.merge(table, 1, Integer::sum);
    }

    /**
     * The credentials the restore dialog already authenticated with, carried from the activity as
     * plain extras so the service can finish the job on its own.
     */
    private static final class RestoreCredentials {
        private final String xmppToken;
        private final String firstName;
        private final String lastName;
        private final boolean addToCoreVpn;
        private final boolean addUserToPurchaseNumbers;
        private final boolean smsEnabled;
        private final List<String> coreRegion;
        private final String cognitoUsername;
        private final String cognitoPassword;
        private final String organization;

        private RestoreCredentials(final Intent intent) {
            this.xmppToken = intent.getStringExtra("xmpp_token");
            this.firstName = intent.getStringExtra("first_name");
            this.lastName = intent.getStringExtra("last_name");
            this.addToCoreVpn = intent.getBooleanExtra("add_to_core_vpn", false);
            this.addUserToPurchaseNumbers =
                    intent.getBooleanExtra("add_user_to_purchase_numbers", false);
            this.smsEnabled = intent.getBooleanExtra("is_sms_enabled", false);
            final List<String> regions = intent.getStringArrayListExtra("core_region");
            this.coreRegion = regions == null ? Collections.emptyList() : regions;
            this.cognitoUsername = intent.getStringExtra("cognito_username");
            this.cognitoPassword = intent.getStringExtra("cognito_password");
            this.organization = intent.getStringExtra("organization");
        }

        private boolean hasXmppCredential() {
            return !Strings.isNullOrEmpty(xmppToken);
        }

        private static RestoreCredentials fromIntent(final Intent intent) {
            final RestoreCredentials credentials = new RestoreCredentials(intent);
            return credentials.hasXmppCredential() ? credentials : null;
        }
    }

    /**
     * Enables the freshly restored account with the credentials the restore signed in with.
     *
     * The backup carries no password and no FAST token and is written with
     * OPTION_DISABLED set, so a restored account cannot connect on its own. The restore dialog
     * collects the account password and authenticates before the import starts, so those
     * credentials are in hand here -- nothing sensitive is read out of the backup file.
     *
     * This runs in the service rather than the activity on purpose. ImportBackupActivity drops its
     * OnBackupProcessed listener in onStop(), so an import that finishes while the app is
     * backgrounded (or merely with the screen off) never calls back into the activity. Doing the
     * work here means the restore completes either way instead of leaving a disabled, credential-less
     * account behind a "restored" notification.
     */
    private void applyRestoreCredentials(final Jid jid, final RestoreCredentials credentials) {
        if (credentials == null) {
            return;
        }
        Account account = null;
        for (final Account candidate : mDatabaseBackend.getAccounts()) {
            if (candidate.getJid().asBareJid().equals(jid.asBareJid())) {
                account = candidate;
                break;
            }
        }
        if (account == null) {
            Log.w(Config.LOGTAG, "restored account row not found; leaving it to a manual login");
            return;
        }
        // fromIntent() only hands over credentials that carry a token; the backend is JWT-only.
        account.setToken(credentials.xmppToken);
        if (!Strings.isNullOrEmpty(credentials.organization)) {
            account.setOrganization(credentials.organization);
        }
        account.setFirstName(Strings.nullToEmpty(credentials.firstName));
        account.setLastName(Strings.nullToEmpty(credentials.lastName));
        account.setAdd_to_core_vpn(credentials.addToCoreVpn);
        account.setCanPurchaseNumbers(credentials.addUserToPurchaseNumbers);
        account.setSMSEnabled(credentials.smsEnabled);
        account.setCoreRegion(credentials.coreRegion);
        account.setOption(Account.OPTION_DISABLED, false);
        account.setOption(Account.OPTION_SOFT_DISABLED, false);
        mDatabaseBackend.updateAccount(account);
        // cognito_account is not in the backup, so the row has to be rebuilt from this session or
        // the restored account has no Cognito identity behind it.
        if (mDatabaseBackend.getCognitoAccount(account) == null) {
            mDatabaseBackend.createCognitoAccount(
                    new CognitoAccount(
                            credentials.cognitoUsername,
                            credentials.cognitoPassword,
                            credentials.organization,
                            account.getUuid(),
                            getApplicationContext()));
        }
        CognitoClientMigrationManager.markMigrationReauthV1Completed(getApplicationContext());
    }

    private void markRestoreCompleted() {
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .edit()
                .putBoolean(PREF_RESTORE_COMPLETED, true)
                .apply();
    }

    private void notifySuccess() {
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(getBaseContext(), "backup");
        mBuilder.setContentTitle(getString(R.string.notification_restored_backup_title))
                .setContentText(getString(R.string.notification_restored_backup_subtitle))
                .setAutoCancel(true)
                .setContentIntent(
                        PendingIntent.getActivity(
                                this,
                                145,
                                // Same flags onBackupRestored() uses: land on the chat list with
                                // nothing behind it. Without CLEAR_TASK the notification stacks
                                // chats on top of the restore/login screens still in the task, and
                                // back walks the user straight into login again.
                                new Intent(this, ConversationActivity.class)
                                        .addFlags(
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                                s()
                                        ? PendingIntent.FLAG_IMMUTABLE
                                                | PendingIntent.FLAG_UPDATE_CURRENT
                                        : PendingIntent.FLAG_UPDATE_CURRENT))
                .setSmallIcon(R.drawable.ic_unarchive_24dp);
        notificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    /**
     * Tells XmppConnectionService the database changed underneath it.
     *
     * This used to stopService() it, on the assumption the next bind would build a fresh instance
     * that reads the restored rows. It is a sticky foreground service the app re-starts on its own,
     * and stopService() is asynchronous, so the stop routinely lost the race against the
     * activity's rebind: the surviving instance still reported no accounts, and
     * ConversationsActivity -- which decides onboarding from that in-memory list -- sent the user
     * to "Get started" and on to the login screen despite the restore succeeding. Ask for an
     * explicit reload instead, which is correct whether or not the service was already running.
     */
    private void notifyServiceOfRestore() {
        startService(
                new Intent(this, XmppConnectionService.class)
                        .setAction(XmppConnectionService.ACTION_RESTORED_BACKUP));
    }

    public void removeOnBackupProcessedListener(OnBackupProcessed listener) {
        synchronized (mOnBackupProcessedListeners) {
            mOnBackupProcessedListeners.remove(listener);
        }
    }

    public void addOnBackupProcessedListener(OnBackupProcessed listener) {
        synchronized (mOnBackupProcessedListeners) {
            mOnBackupProcessedListeners.add(listener);
        }
    }

    public static ListenableFuture<BackupFile> read(final Context context, final Uri uri) {
        return Futures.submit(() -> BackupFile.read(context, uri), BACKUP_FILE_READER_EXECUTOR);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return this.binder;
    }

    public interface OnBackupFilesLoaded {
        void onBackupFilesLoaded(List<BackupFile> files);
    }

    /** Carries the first refused candidate alongside the usable ones, so neither goes unsaid. */
    public interface OnBackupFolderLoaded {
        void onBackupFolderLoaded(List<BackupFile> files, Throwable rejection);
    }

    public interface OnBackupProcessed {
        void onBackupRestored();

        void onBackupDecryptionFailed();

        void onBackupRestoreFailed(RestoreFailure failure);

        void onAccountAlreadySetup();
    }

    /**
     * Why a restore failed, so the user gets something more actionable than "could not restore
     * backup" and a support log can be read without the stack trace.
     *
     * Decryption failures are NOT in here: they keep their own callback, because the passphrase
     * is the one thing the user can fix by retyping it.
     */
    public enum RestoreFailure {
        /** The backup file could not be opened or read at all. */
        FILE_UNREADABLE,
        /** The file is not a backup, or its contents are truncated/malformed. */
        NOT_A_BACKUP,
        /** The file is a backup, but from a format this build does not understand. */
        INCOMPATIBLE_FORMAT,
        /** The rows decoded fine but this device's database would not take them. */
        DATABASE_REJECTED,
        /** Read to the end without an account row, so there would be nothing to show. */
        NO_ACCOUNT_IN_BACKUP,
        /** Anything we could not attribute. */
        UNKNOWN
    }

    public static class BackupFile {
        private final Uri uri;
        private Uri mediaZipUri;
        private final BackupFileHeader header;

        private BackupFile(Uri uri, BackupFileHeader header) {
            this.uri = uri;
            this.header = header;
        }

        private BackupFile(final Uri uri, final Uri mediaZipUri, final BackupFileHeader header) {
            this.uri = uri;
            this.mediaZipUri = mediaZipUri;
            this.header = header;
        }

        /**
         * The media archive found in the same directory, if the user granted us that directory.
         *
         * Pairing happens at discovery rather than at restore time because the directory is the
         * only thing that ties an archive to a backup -- once we are down to a single document
         * uri there is nothing left to look around in.
         */
        private BackupFile withMediaZip(final Uri mediaZip) {
            return mediaZip == null ? this : new BackupFile(this.uri, mediaZip, this.header);
        }

        private static BackupFile read(File file) throws IOException {
            final FileInputStream fileInputStream = new FileInputStream(file);
            final DataInputStream dataInputStream = new DataInputStream(fileInputStream);
            BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
            fileInputStream.close();
            return new BackupFile(Uri.fromFile(file), backupFileHeader);
        }

        public static BackupFile read(final Context context, final Uri uri) throws IOException {
            // ImportBackupActivity is exported and takes a VIEW intent, so this Uri can come
            // straight from another app. Resolving it would run as us.
            if (!ContentUriValidator.isSafeToResolve(context, uri)) {
                throw new SecurityException("refusing to read a backup from an untrusted uri");
            }
            final InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                throw new FileNotFoundException();
            }
            try (final DataInputStream dataInputStream = new DataInputStream(inputStream)) {
                return new BackupFile(uri, BackupFileHeader.read(dataInputStream));
            }
        }

        public BackupFileHeader getHeader() {
            return header;
        }

        public Uri getUri() {
            return uri;
        }

        public Uri getMediaZipUri() {
            return mediaZipUri;
        }
    }

    public class ImportBackupServiceBinder extends Binder {
        public ImportBackupService getService() {
            return ImportBackupService.this;
        }
    }
}
