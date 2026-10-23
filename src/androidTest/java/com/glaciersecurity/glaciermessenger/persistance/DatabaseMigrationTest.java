package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.database.Cursor;
// SQLCipher throws the platform SQLiteException, not one of its own, so this import stays.
import android.database.sqlite.SQLiteException;

// The fixture is a SQLCipher in-memory database because onUpgrade now takes one. Unkeyed,
// so it is ordinary SQLite -- what changed is which engine runs the migrations, and running them
// on the engine that will actually run them in production is the point.
import net.zetetic.database.sqlcipher.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * The schema migrations in {@code onUpgrade}, run against real SQLite.
 *
 * <p>Upgrades are the one database path that only executes on a user's device, with their data,
 * once -- and a mistake is unrecoverable, because by the time anyone notices, the old rows are
 * gone. The v73 step is a table rebuild (SQLite has no usable DROP COLUMN for this case), so it
 * copies rows between two tables and swaps them; the failure mode is not an exception but a
 * silently emptied {@code glacier_organization}.
 *
 * <p>{@code onUpgrade} takes the database as an argument, so these tests hand it a scratch
 * database seeded to look like the old version rather than going anywhere near the app's real
 * {@code history} file. That also means the assertions are about the migration alone, with no
 * interference from whatever {@code GlacierGuardian.onCreate} already did at startup.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseMigrationTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private SQLiteDatabase db;
    private DatabaseBackend backend;

    @Before
    public void createScratchDatabase() {
        backend = DatabaseBackend.getInstance(context);
        // In-memory: no file to clean up, and nothing that can be confused with the real one.
        db = SQLiteDatabase.create(null);
    }

    @After
    public void closeScratchDatabase() {
        if (db != null) {
            db.close();
        }
    }

    /** {@code glacier_organization} as it stood at v72, i.e. still carrying the retention column. */
    private void createOrganizationTableAtV72() {
        db.execSQL(
                "CREATE TABLE " + GlacierOrgEntity.TABLENAME + " ("
                        + GlacierOrgEntity.DNS_ENABLED + " INTEGER, "
                        + GlacierOrgEntity.DNS_ID + " TEXT, "
                        + GlacierOrgEntity.FORCE_TRACKING + " INTEGER, "
                        + GlacierOrgEntity.ORGANIZATION + " TEXT PRIMARY KEY, "
                        + "retention INTEGER, "
                        + GlacierOrgEntity.SECURITYHUB_DATA_ENABLED + " INTEGER, "
                        + GlacierOrgEntity.SMS_ENABLED + " INTEGER, "
                        + GlacierOrgEntity.TRACKING_ENABLED + " INTEGER, "
                        + GlacierOrgEntity.TRACKING_FREQUENCY + " TEXT, "
                        + GlacierOrgEntity.UPLOAD_ENABLED + " INTEGER, "
                        + GlacierOrgEntity.VOICEMAIL_ENABLED + " INTEGER, "
                        + "UNIQUE(" + GlacierOrgEntity.ORGANIZATION + ") ON CONFLICT REPLACE);");
    }

    private void insertOrganization(final String name, final String dnsId, final int retention) {
        db.execSQL(
                "INSERT INTO " + GlacierOrgEntity.TABLENAME
                        + " (organization, dns_id, dns_enabled, retention, tracking_frequency)"
                        + " VALUES (?, ?, 1, ?, '15m')",
                new Object[] {name, dnsId, retention});
    }

    private List<String> columnsOf(final String table) {
        final List<String> columns = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            final int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        }
        return columns;
    }

    private int countOf(final String table) {
        try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + table, null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    /** messages plus its FTS index as they stood at v73 -- delete trigger still AFTER DELETE. */
    private void createMessagesAtV73() {
        db.execSQL("CREATE TABLE messages (uuid TEXT PRIMARY KEY, body TEXT)");
        db.execSQL(
                "CREATE VIRTUAL TABLE messages_index USING fts4 (uuid,body,notindexed=\"uuid\","
                        + "content=\"messages\",tokenize='unicode61')");
        db.execSQL(
                "CREATE TRIGGER after_message_insert AFTER INSERT ON messages BEGIN INSERT INTO"
                        + " messages_index(rowid,uuid,body) VALUES(NEW.rowid,NEW.uuid,NEW.body);"
                        + " END;");
        db.execSQL(
                "CREATE TRIGGER after_message_delete AFTER DELETE ON messages BEGIN DELETE FROM"
                        + " messages_index WHERE rowid=OLD.rowid; END;");
    }

    private String triggerSql(final String name) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?",
                        new String[] {name})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private void assertSearchIndexIsIntact() {
        try {
            db.execSQL("INSERT INTO messages_index(messages_index) VALUES('integrity-check')");
        } catch (final SQLiteException e) {
            throw new AssertionError("messages_index still holds stale postings", e);
        }
    }

    // ---- v74: the message-index delete trigger -------------------------------------------

    @Test
    public void upgradingTo74SwapsTheDeleteTriggerToBeforeDelete() {
        createMessagesAtV73();
        assertThat(triggerSql("after_message_delete")).contains("AFTER DELETE");

        backend.onUpgrade(db, 73, 74);

        assertThat(triggerSql("after_message_delete")).isNull();
        assertThat(triggerSql("before_message_delete")).contains("BEFORE DELETE");
    }

    @Test
    public void upgradingTo74RepairsAnIndexTheOldTriggerCorrupted() {
        createMessagesAtV73();
        db.execSQL("INSERT INTO messages VALUES ('m1','the quick brown fox')");
        // The AFTER DELETE trigger cannot re-tokenise a row that is already gone, so this leaves
        // the posting behind -- the state every upgrading device is in.
        db.execSQL("DELETE FROM messages WHERE uuid='m1'");

        backend.onUpgrade(db, 73, 74);

        // The repair has to happen inside the migration. onUpgrade shares a transaction with the
        // version bump, so anything deferred past it is lost if the process dies and no later
        // launch re-enters the block.
        assertSearchIndexIsIntact();
        db.execSQL("INSERT INTO messages VALUES ('m2','a slow green turtle')");
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT m.body FROM messages m JOIN messages_index ix ON ix.rowid=m.rowid"
                                + " WHERE ix.body MATCH 'brown*'",
                        null)) {
            assertWithMessage("a stale posting matched a message inserted after the upgrade")
                    .that(cursor.getCount())
                    .isEqualTo(0);
        }
    }

    @Test
    public void upgradingTo74KeepsMessagesSearchable() {
        createMessagesAtV73();
        db.execSQL("INSERT INTO messages VALUES ('m1','the quick brown fox')");

        backend.onUpgrade(db, 73, 74);

        // The rebuild discards the index and re-derives it, so a migration that ran it wrongly
        // would leave every existing message unsearchable rather than throwing.
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT m.body FROM messages m JOIN messages_index ix ON ix.rowid=m.rowid"
                                + " WHERE ix.body MATCH 'brown*'",
                        null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }
    }

    // ---- v49: the index is dropped and recreated ----------------------------------------

    @Test
    public void upgradingTo49RepopulatesTheIndexItDrops() {
        // v49 drops messages_index and builds it again from scratch, so every existing message
        // is missing from it until something repopulates it. The failure mode is not an
        // exception -- search simply returns nothing for messages sent before the upgrade.
        createMessagesAtV73();
        db.execSQL("INSERT INTO messages VALUES ('m1','the quick brown fox')");

        // 48 -> 49 exactly: later steps ALTER tables this scratch database does not have.
        backend.onUpgrade(db, 48, 49);

        assertSearchIndexIsIntact();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT m.body FROM messages m JOIN messages_index ix ON ix.rowid=m.rowid"
                                + " WHERE ix.body MATCH 'brown*'",
                        null)) {
            assertWithMessage("messages sent before the upgrade fell out of the search index")
                    .that(cursor.getCount())
                    .isEqualTo(1);
        }
    }

    // ---- v73: drop glacier_organization.retention --------------------------------------

    @Test
    public void upgradingTo73DropsTheRetentionColumn() {
        createOrganizationTableAtV72();
        assertThat(columnsOf(GlacierOrgEntity.TABLENAME)).contains("retention");

        backend.onUpgrade(db, 72, 73);

        assertThat(columnsOf(GlacierOrgEntity.TABLENAME)).doesNotContain("retention");
    }

    @Test
    public void upgradingTo73KeepsEveryOrganisationRow() {
        createOrganizationTableAtV72();
        insertOrganization("example-org", "dns-one", 1);
        insertOrganization("another-org", "dns-two", 0);

        backend.onUpgrade(db, 72, 73);

        assertWithMessage("rows lost in the glacier_organization table rebuild")
                .that(countOf(GlacierOrgEntity.TABLENAME))
                .isEqualTo(2);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT dns_id, dns_enabled, tracking_frequency FROM "
                                + GlacierOrgEntity.TABLENAME
                                + " WHERE organization=?",
                        new String[] {"example-org"})) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertThat(cursor.getString(0)).isEqualTo("dns-one");
            assertThat(cursor.getInt(1)).isEqualTo(1);
            assertThat(cursor.getString(2)).isEqualTo("15m");
        }
    }

    @Test
    public void upgradingTo73LeavesTheTableUsableAfterwards() {
        createOrganizationTableAtV72();
        insertOrganization("example-org", "dns-one", 1);

        backend.onUpgrade(db, 72, 73);

        // The rebuild renames glacier_organization_new over the original. If the UNIQUE..ON
        // CONFLICT REPLACE clause were dropped in the process, this second insert would throw
        // instead of replacing -- and every later org sync would fail.
        db.execSQL(
                "INSERT INTO " + GlacierOrgEntity.TABLENAME
                        + " (organization, dns_id) VALUES ('example-org', 'dns-three')");
        assertThat(countOf(GlacierOrgEntity.TABLENAME)).isEqualTo(1);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT dns_id FROM " + GlacierOrgEntity.TABLENAME, null)) {
            cursor.moveToFirst();
            assertThat(cursor.getString(0)).isEqualTo("dns-three");
        }
    }

    @Test
    public void theScratchTableIsNotLeftBehind() {
        createOrganizationTableAtV72();

        backend.onUpgrade(db, 72, 73);

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE name='glacier_organization_new'",
                        null)) {
            assertWithMessage("glacier_organization_new should have been renamed away")
                    .that(cursor.getCount())
                    .isEqualTo(0);
        }
    }

    // ---- v72: drop location_history ----------------------------------------------------

    @Test
    public void upgradingTo72DropsTheLocationHistoryTable() {
        db.execSQL("CREATE TABLE location_history (uuid TEXT PRIMARY KEY, time NUMBER)");
        db.execSQL("INSERT INTO location_history VALUES ('one', 1000)");
        createOrganizationTableAtV72();

        backend.onUpgrade(db, 71, 73);

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE name='location_history'", null)) {
            assertWithMessage("30 days of the device's own location fixes must not survive")
                    .that(cursor.getCount())
                    .isEqualTo(0);
        }
    }

    @Test
    public void upgradingTo72WithNoLocationHistoryTableIsNotAnError() {
        // A device that installed after the table was already gone, or a clean install that then
        // upgraded. DROP TABLE IF EXISTS has to carry this, because a throw here rolls back the
        // whole upgrade and the device never records the new version.
        createOrganizationTableAtV72();

        backend.onUpgrade(db, 71, 73);

        assertThat(columnsOf(GlacierOrgEntity.TABLENAME)).doesNotContain("retention");
    }

    @Test
    public void anUpgradeThatStartsAboveAStepDoesNotRunThatStep() {
        // Already at 73: the v73 block is guarded by oldVersion < 73, so a table without the
        // retention column must be left exactly as it is rather than rebuilt again.
        db.execSQL(
                "CREATE TABLE " + GlacierOrgEntity.TABLENAME
                        + " (organization TEXT PRIMARY KEY, dns_id TEXT)");
        db.execSQL("INSERT INTO " + GlacierOrgEntity.TABLENAME + " VALUES ('example-org', 'x')");

        backend.onUpgrade(db, 73, 73);

        assertThat(countOf(GlacierOrgEntity.TABLENAME)).isEqualTo(1);
        assertThat(columnsOf(GlacierOrgEntity.TABLENAME)).containsExactly("organization", "dns_id");
    }
}
