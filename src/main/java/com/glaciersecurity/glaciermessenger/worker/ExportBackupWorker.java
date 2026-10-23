package com.glaciersecurity.glaciermessenger.worker;

import static com.glaciersecurity.glaciermessenger.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.widget.Toast;
import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.stream.JsonWriter;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.crypto.axolotl.SQLiteAxolotlStore;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Conversation;
import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.utils.BackupFileHeader;
import com.glaciersecurity.glaciermessenger.utils.KeystoreCredentialStore;
import com.glaciersecurity.glaciermessenger.utils.WakeLockHelper;
import com.glaciersecurity.glaciermessenger.utils.Compatibility;

import android.os.Environment;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class ExportBackupWorker extends Worker {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US);

    public static final String KEYTYPE = "AES";
    public static final String CIPHERMODE = "AES/GCM/NoPadding";
    public static final String PROVIDER = "BC";

    public static final String MIME_TYPE = "application/vnd.glacier.backup";

    private static final String BACKUP_KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int BACKUP_KDF_ITERATIONS = 310_000;
    private static final int BACKUP_KEY_LENGTH_BITS = 256;

    private static final String LEGACY_BACKUP_KDF_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final int LEGACY_BACKUP_KDF_ITERATIONS = 1_024;
    private static final int LEGACY_BACKUP_KEY_LENGTH_BITS = 128;

    private static final int NOTIFICATION_ID = 19;

    private final String passphrase;
    private final boolean exportMedia;
    private final WakeLock wakeLock;

    public ExportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.passphrase = inputData.getString("passphrase");
        this.exportMedia = inputData.getBoolean("export_media", false);
        final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, ExportBackupWorker.class.getName()) : null;
    }

    @NonNull
    @Override
    public Result doWork() {
        WakeLockHelper.acquire(wakeLock);
        try {
            setForegroundAsync(getForegroundInfo());
            final List<File> files;
            try {
                files = export();
            } catch (final IOException
                           | InvalidKeySpecException
                           | InvalidAlgorithmParameterException
                           | InvalidKeyException
                           | NoSuchPaddingException
                           | NoSuchAlgorithmException
                           | NoSuchProviderException e) {
                return Result.failure();
            } finally {
                getApplicationContext()
                        .getSystemService(NotificationManager.class)
                        .cancel(NOTIFICATION_ID);
            }
            if (files.isEmpty()) {
                return Result.success();
            }
            notifySuccess();
            return Result.success();
        } finally {
            WakeLockHelper.release(wakeLock);
        }
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        final NotificationCompat.Builder notification = getNotification();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification.build());
        }
    }

    private List<File> export()
            throws IOException,
            InvalidKeySpecException,
            InvalidAlgorithmParameterException,
            InvalidKeyException,
            NoSuchPaddingException,
            NoSuchAlgorithmException,
            NoSuchProviderException {
        final Context context = getApplicationContext();
        final var database = DatabaseBackend.getInstance(context);
        final var accounts = database.getAccounts();

        int count = 0;
        final int max = accounts.size();
        final ImmutableList.Builder<File> files = new ImmutableList.Builder<>();
        for (final Account account : accounts) {
            if (isStopped()) {
                return files.build();
            }
            final String password = passphrase;
            if (Strings.nullToEmpty(passphrase).trim().isEmpty()) {
                count++;
                continue;
            }
            final String dateStr = DATE_FORMAT.format(new Date());
            final String dirName = String.format(Locale.US, "%s_%s", account.getUsername(), dateStr);
            final File exportDir = new File(FileBackend.getBackupDirectoryCustom(), dirName);
            final String filename =
                    String.format(
                            Locale.US,
                            "%s_%s_%s.ceb",
                            account.getUsername(),
                            getBackupDiscriminator(account),
                            dateStr);
            final File file = new File(exportDir, filename);
            try {
                export(database, account, password, file, max, count);
            } catch (final WorkStoppedException e) {
                if (file.delete()) {
                }
                return files.build();
            }
            files.add(file);
            if (exportMedia) {
                exportMediaAsZip(account.getUsername(), dateStr, exportDir);
            }
            count++;
        }
        return files.build();
    }

    private void export(
            final DatabaseBackend database,
            final Account account,
            final String password,
            final File file,
            final int max,
            final int count)
            throws IOException,
            InvalidKeySpecException,
            InvalidAlgorithmParameterException,
            InvalidKeyException,
            NoSuchPaddingException,
            NoSuchAlgorithmException,
            NoSuchProviderException,
            WorkStoppedException {
        final var context = getApplicationContext();
        final SecureRandom secureRandom = new SecureRandom();
        final byte[] IV = new byte[12];
        final byte[] salt = new byte[16];
        secureRandom.nextBytes(IV);
        secureRandom.nextBytes(salt);
        final BackupFileHeader backupFileHeader =
                new BackupFileHeader(
                        context.getString(R.string.app_name),
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt);
        final var notification = getNotification();
        final var cancelPendingIntent =
                WorkManager.getInstance(context).createCancelPendingIntent(getId());
        notification.addAction(
                new NotificationCompat.Action.Builder(
                        R.drawable.ic_cancel_24dp,
                        context.getString(R.string.cancel),
                        cancelPendingIntent)
                        .build());
        final Progress progress = new Progress(notification, max, count);
        final File directory = file.getParentFile();
        if (directory != null && directory.mkdirs()) {
        }
        final FileOutputStream fileOutputStream = new FileOutputStream(file);
        final DataOutputStream dataOutputStream = new DataOutputStream(fileOutputStream);
        try {
            backupFileHeader.write(dataOutputStream);
            dataOutputStream.flush();

            final Cipher cipher =
                    Compatibility.twentyEight()
                            ? Cipher.getInstance(CIPHERMODE)
                            : Cipher.getInstance(CIPHERMODE, PROVIDER);
            final byte[] key = getKey(password, salt);
            SecretKeySpec keySpec = new SecretKeySpec(key, KEYTYPE);
            IvParameterSpec ivSpec = new IvParameterSpec(IV);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            // jsonWriter.close() closes the whole chain, so the cipher stream is closed exactly
            // once on the normal path -- closing it twice would write a second GCM tag.
            final CipherOutputStream cipherOutputStream =
                    new CipherOutputStream(fileOutputStream, cipher);
            final JsonWriter jsonWriter;
            try {
                jsonWriter =
                        new JsonWriter(
                                new OutputStreamWriter(
                                        new GZIPOutputStream(cipherOutputStream),
                                        StandardCharsets.UTF_8));
            } catch (final IOException e) {
                // That constructor writes the gzip header, so a failure here leaves the cipher
                // stream open with nothing wrapping it to close it later.
                FileBackend.close(cipherOutputStream);
                throw e;
            }
            jsonWriter.beginArray();
            final SQLiteDatabase db = database.getReadableDatabase();
            final String uuid = account.getUuid();
            accountExport(db, uuid, jsonWriter);
            simpleExport(db, Conversation.TABLENAME, Conversation.ACCOUNT, uuid, jsonWriter);
            messageExport(db, uuid, jsonWriter, progress);
            for (final String table :
                    Arrays.asList(
                            SQLiteAxolotlStore.PREKEY_TABLENAME,
                            SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                            SQLiteAxolotlStore.SESSION_TABLENAME)) {
                throwIfWorkStopped();
                simpleExport(db, table, SQLiteAxolotlStore.ACCOUNT, uuid, jsonWriter);
            }
            throwIfWorkStopped();
            identitiesExport(db, uuid, jsonWriter);
            simpleExport(db, GlacierOrgEntity.TABLENAME, jsonWriter);
            jsonWriter.endArray();
            jsonWriter.flush();
            jsonWriter.close();
        } finally {
            // Wraps the file stream and is the one thing here that jsonWriter.close() does not reach.
            FileBackend.close(dataOutputStream);
        }
    }

    private NotificationCompat.Builder getNotification() {
        final var context = getApplicationContext();
        final NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, "backup");
        notification
                .setContentTitle(context.getString(R.string.notification_create_backup_title))
                .setSmallIcon(R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false);
        notification.setOngoing(true);
        notification.setLocalOnly(true);
        return notification;
    }

    private void throwIfWorkStopped() throws WorkStoppedException {
        if (isStopped()) {
            throw new WorkStoppedException();
        }
    }

    private String getBackupDiscriminator(final Account account) {
        final String organization = Strings.nullToEmpty(account.getOrganization()).trim();
        final String rawDiscriminator = organization.isEmpty() ? account.getServer() : organization;
        final String sanitized = rawDiscriminator.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!sanitized.isEmpty()) {
            return sanitized;
        }
        return account.getServer().replaceAll("[^A-Za-z0-9._-]", "_");
    }


    private static void accountExport(
            final SQLiteDatabase db, final String uuid, final JsonWriter writer)
            throws IOException {
        try (final Cursor accountCursor =
                     db.query(
                             Account.TABLENAME,
                             null,
                             Account.UUID + "=?",
                             new String[] {uuid},
                             null,
                             null,
                             null)) {
            while (accountCursor != null && accountCursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(Account.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                    final String name = accountCursor.getColumnName(i);
                    // Never export reusable authentication material. The values live in
                    // KeystoreCredentialStore, not in these columns, and a restored FAST token
                    // would be a replayable credential on the importing device.
                    if (Account.PASSWORD.equals(name) || Account.FAST_TOKEN.equals(name)) {
                        continue;
                    }
                    writer.name(name);
                    final String value = accountCursor.getString(i);
                    if (value == null
                            || Account.ROSTERVERSION.equals(accountCursor.getColumnName(i))) {
                        writer.nullValue();
                    } else if (Account.OPTIONS.equals(accountCursor.getColumnName(i))
                            && value.matches("\\d+")) {
                        // The \\d+ guard above does not rule out a value too large for an int.
                        try {
                            int intValue = Integer.parseInt(value);
                            intValue |= 1 << Account.OPTION_DISABLED;
                            writer.value(intValue);
                        } catch (final NumberFormatException e) {
                            writer.value(value);
                        }
                    } else {
                        writer.value(value);
                    }
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private static void simpleExport(
            final SQLiteDatabase db,
            final String table,
            final String column,
            final String uuid,
            final JsonWriter writer)
            throws IOException {
        try (final Cursor cursor =
                     db.query(table, null, column + "=?", new String[] {uuid}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(table);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < cursor.getColumnCount(); ++i) {
                    final String name = cursor.getColumnName(i);
                    writer.name(name);
                    final String value = cursor.getString(i);
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private static void simpleExport(
            final SQLiteDatabase db, final String table, final JsonWriter writer)
            throws IOException {
        try (final Cursor cursor =
                     db.query(table, null, null, null, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(table);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < cursor.getColumnCount(); ++i) {
                    final String name = cursor.getColumnName(i);
                    writer.name(name);
                    final String value = cursor.getString(i);
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private static void identitiesExport(
            final SQLiteDatabase db, final String uuid, final JsonWriter writer)
            throws IOException {
        try (final Cursor cursor =
                     db.query(
                             SQLiteAxolotlStore.IDENTITIES_TABLENAME,
                             null,
                             SQLiteAxolotlStore.ACCOUNT + "=?",
                             new String[] {uuid},
                             null,
                             null,
                             null)) {
            final int keyIndex = cursor != null ? cursor.getColumnIndex(SQLiteAxolotlStore.KEY) : -1;
            final int ownIndex = cursor != null ? cursor.getColumnIndex(SQLiteAxolotlStore.OWN) : -1;
            while (cursor != null && cursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(SQLiteAxolotlStore.IDENTITIES_TABLENAME);
                writer.name("values");
                writer.beginObject();
                final boolean own = ownIndex >= 0 && cursor.getInt(ownIndex) != 0;
                for (int i = 0; i < cursor.getColumnCount(); ++i) {
                    final String name = cursor.getColumnName(i);
                    writer.name(name);
                    String value = cursor.getString(i);
                    if (own && i == keyIndex && value != null) {
                        // the live DB holds the own identity key Keystore-wrapped; the
                        // backup stream is protected by the export passphrase instead, so
                        // decrypt here for portability across devices
                        final String decrypted =
                                KeystoreCredentialStore.decryptString(
                                        DatabaseBackend.omemoIdentityCredentialId(uuid), value);
                        value = decrypted != null ? decrypted : value;
                    }
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private void messageExport(
            final SQLiteDatabase db,
            final String uuid,
            final JsonWriter writer,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        try (final Cursor cursor =
                     db.rawQuery(
                             "select messages.* from messages join conversations on conversations.uuid=messages.conversationUuid where conversations.accountUuid=?",
                             new String[] {uuid})) {
            final int size = cursor != null ? cursor.getCount() : 0;
            long lastUpdate = 0;
            int i = 0;
            int p = Integer.MIN_VALUE;
            while (cursor != null && cursor.moveToNext()) {
                throwIfWorkStopped();
                writer.beginObject();
                writer.name("table");
                writer.value(Message.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int j = 0; j < cursor.getColumnCount(); ++j) {
                    final String name = cursor.getColumnName(j);
                    writer.name(name);
                    final String value = cursor.getString(j);
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
                final int percentage = i * 100 / size;
                if (p < percentage && (SystemClock.elapsedRealtime() - lastUpdate) > 2_000) {
                    p = percentage;
                    lastUpdate = SystemClock.elapsedRealtime();
                    notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                }
                i++;
            }
        }
    }

    public static byte[] getKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        return getKey(
                password,
                salt,
                BACKUP_KDF_ALGORITHM,
                BACKUP_KDF_ITERATIONS,
                BACKUP_KEY_LENGTH_BITS);
    }

    public static byte[] getLegacyKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        return getKey(
                password,
                salt,
                LEGACY_BACKUP_KDF_ALGORITHM,
                LEGACY_BACKUP_KDF_ITERATIONS,
                LEGACY_BACKUP_KEY_LENGTH_BITS);
    }

    private static byte[] getKey(
            final String password,
            final byte[] salt,
            final String algorithm,
            final int iterations,
            final int keyLength)
            throws InvalidKeySpecException {
        final SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return factory.generateSecret(
                        new PBEKeySpec(password.toCharArray(), salt, iterations, keyLength))
                .getEncoded();
    }

    private void exportMediaAsZip(final String username, final String dateStr, final File exportDir) {
        final Context context = getApplicationContext();

        final List<String[]> mediaDirs = new ArrayList<>();
        mediaDirs.add(new String[]{Environment.DIRECTORY_PICTURES, "Pictures"});
        mediaDirs.add(new String[]{Environment.DIRECTORY_MOVIES, "Movies"});
        mediaDirs.add(new String[]{Environment.DIRECTORY_DOCUMENTS, "Documents"});
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            mediaDirs.add(new String[]{Environment.DIRECTORY_RECORDINGS, "Recordings"});
        }

        final String zipFilename = String.format(Locale.US, "%s_%s.zip", username, dateStr);
        final File zipFile = new File(exportDir, zipFilename);
        if (exportDir.mkdirs()) {
        }

        final SecureRandom secureRandom = new SecureRandom();
        final byte[] iv = new byte[12];
        final byte[] salt = new byte[16];
        secureRandom.nextBytes(iv);
        secureRandom.nextBytes(salt);

        boolean hasContent = false;
        try (final FileOutputStream fos = new FileOutputStream(zipFile)) {
            // file layout: [IV (12 bytes)][Salt (16 bytes)][AES/GCM encrypted ZIP data]
            fos.write(iv);
            fos.write(salt);
            fos.flush();

            final byte[] key = getKey(passphrase, salt);
            final Cipher cipher = Compatibility.twentyEight()
                    ? Cipher.getInstance(CIPHERMODE)
                    : Cipher.getInstance(CIPHERMODE, PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEYTYPE), new IvParameterSpec(iv));

            try (final CipherOutputStream cos = new CipherOutputStream(fos, cipher);
                 final ZipOutputStream zos = new ZipOutputStream(cos)) {
                for (final String[] dirInfo : mediaDirs) {
                    final File mediaSource =
                            FileBackend.getConversationsMediaDirectory(context, dirInfo[0]);
                    if (mediaSource.exists() && mediaSource.isDirectory()) {
                        addDirectoryToZip(zos, mediaSource, dirInfo[1]);
                        hasContent = true;
                    }
                }
            }
        } catch (final IOException
                       | InvalidKeySpecException
                       | NoSuchAlgorithmException
                       | NoSuchProviderException
                       | NoSuchPaddingException
                       | InvalidKeyException
                       | InvalidAlgorithmParameterException e) {
            Log.w(Config.LOGTAG, "failed to export media as zip", e);
            if (!zipFile.delete()) {
                Log.w(Config.LOGTAG, "failed to delete incomplete zip file");
            }
            return;
        }
        if (!hasContent) {
            zipFile.delete();
        }
    }

    private void addDirectoryToZip(final ZipOutputStream zos, final File dir, final String parentPath) throws IOException {
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (final File file : files) {
            final String entryName = parentPath.isEmpty() ? file.getName() : parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                addDirectoryToZip(zos, file, entryName);
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                try (final FileInputStream fis = new FileInputStream(file)) {
                    final byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private void notifySuccess() {
        final var context = getApplicationContext();

        new Handler(Looper.getMainLooper())
                .post(
                        () ->
                                Toast.makeText(
                                                context,
                                                context.getString(
                                                        R.string.local_backup_success_toast),
                                                Toast.LENGTH_LONG)
                                        .show());
    }


    private static class Progress {
        private final NotificationCompat.Builder notification;
        private final int max;
        private final int count;

        private Progress(
                final NotificationCompat.Builder notification, final int max, final int count) {
            this.notification = notification;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            notification.setProgress(max * 100, count * 100 + percentage, false);
            return notification.build();
        }
    }

    private static class WorkStoppedException extends Exception {}
}
