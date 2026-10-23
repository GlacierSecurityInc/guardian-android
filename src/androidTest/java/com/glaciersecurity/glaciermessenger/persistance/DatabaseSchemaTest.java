package com.glaciersecurity.glaciermessenger.persistance;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What {@code DatabaseBackend.onCreate} actually produces on a device.
 *
 * <p>This is not a restatement of the CREATE statements. The JVM tier cannot run any of it --
 * {@code android.database.sqlite} on the mockable android.jar is stubs, and Robolectric's SQLite
 * is a different build with different FTS support -- so the only place the schema is ever
 * executed against the SQLite that ships with Android is here.
 *
 * <p>The specific things worth pinning: {@code messages_index} is an FTS4 <em>external content</em>
 * table, which is only consistent because three triggers keep it in step with {@code messages};
 * {@code onConfigure} turns on {@code foreign_keys}, without which the {@code ON DELETE CASCADE}
 * on conversations and messages silently does nothing; and a freshly created database must report
 * exactly the version this build claims, or the downgrade guard has nothing to compare against.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseSchemaTest {

    /** Every table {@code onCreate} is expected to leave behind. */
    private static final List<String> TABLES =
            Arrays.asList(
                    "accounts",
                    "conversations",
                    "messages",
                    "contacts",
                    "discovery_results",
                    "sessions",
                    "prekeys",
                    "signed_prekeys",
                    "identities",
                    "presence_templates",
                    "resolver_results",
                    "messages_index",
                    "cognito_account",
                    "glacier_organization",
                    "latest_versions",
                    "sms_profile",
                    "call_history",
                    "voicemail_records",
                    "wireguard_config");

    private static final List<String> INDICES =
            Arrays.asList(
                    "message_time_index",
                    "message_conversation_index",
                    "message_deleted_index",
                    "message_file_path_index",
                    "message_type_index");

    private static final List<String> TRIGGERS =
            Arrays.asList(
                    "after_message_insert", "after_message_update", "before_message_delete");

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    private SQLiteDatabase db() {
        return DatabaseBackend.getInstance(context).getReadableDatabase();
    }

    private List<String> namesOfType(final String type) {
        final List<String> names = new ArrayList<>();
        try (Cursor cursor =
                db().rawQuery("SELECT name FROM sqlite_master WHERE type=?", new String[] {type})) {
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        }
        return names;
    }

    @Test
    public void aFreshDatabaseIsAtTheVersionThisBuildClaims() {
        assertThat(db().getVersion()).isEqualTo(DatabaseBackend.getAppDatabaseVersion());
    }

    @Test
    public void everyTableOnCreateDeclaresExists() {
        assertThat(namesOfType("table")).containsAtLeastElementsIn(TABLES);
    }

    @Test
    public void theMessageIndicesExist() {
        // Dropping one of these does not break a query, it just makes conversation loading scan
        // the whole messages table -- invisible in a test that only checks results.
        assertThat(namesOfType("index")).containsAtLeastElementsIn(INDICES);
    }

    @Test
    public void allThreeFtsTriggersExist() {
        assertThat(namesOfType("trigger")).containsAtLeastElementsIn(TRIGGERS);
    }

    @Test
    public void theSearchIndexIsAnFts4ExternalContentTable() {
        try (Cursor cursor =
                db().rawQuery(
                                "SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_index'",
                                null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            final String sql = cursor.getString(0);
            assertThat(sql).contains("fts4");
            // content="messages" is what makes it an external-content index: the body text is not
            // duplicated into the index table, which is also why the triggers are load-bearing.
            assertThat(sql).contains("content=\"messages\"");
            assertThat(sql).contains("unicode61");
        }
    }

    @Test
    public void foreignKeysAreEnforced() {
        try (Cursor cursor = db().rawQuery("PRAGMA foreign_keys", null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            assertWithMessage("PRAGMA foreign_keys, set by DatabaseBackend.onConfigure")
                    .that(cursor.getInt(0))
                    .isEqualTo(1);
        }
    }

    @Test
    public void theMessagesTableCarriesTheColumnsTheEntityReadsBack() {
        final List<String> columns = new ArrayList<>();
        try (Cursor cursor = db().rawQuery("PRAGMA table_info(messages)", null)) {
            final int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        }
        // The ones added by later migrations, which are the ones an incomplete onCreate would
        // leave out -- a bug that only shows up on a clean install, never on an upgrade.
        assertThat(columns)
                .containsAtLeast(
                        "uuid",
                        "conversationUuid",
                        "body",
                        "deleted",
                        "readByMarkers",
                        "occupantId",
                        "reactions",
                        "bodyLanguage");
    }
}
