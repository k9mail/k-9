package com.fsck.k9.mailstore;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import com.fsck.k9.Account;
import com.fsck.k9.BuildConfig;
import com.fsck.k9.GlobalsHelper;
import com.fsck.k9.K9;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.R;
import com.fsck.k9.mail.MessagingException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@RunWith(K9RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class StoreSchemaDefinitionTest {
    private StoreSchemaDefinition storeSchemaDefinition;


    @Before
    public void setUp() throws MessagingException {
        ShadowLog.stream = System.out;

        Application application = RuntimeEnvironment.application;
        K9.app = application;
        GlobalsHelper.setContext(application);
        StorageManager.getInstance(application);

        storeSchemaDefinition = createStoreSchemaDefinition();
    }

    @Test
    public void getVersion_shouldReturnCurrentDatabaseVersion() {
        int version = storeSchemaDefinition.getVersion();

        assertEquals(LocalStore.DB_VERSION, version);
    }

    @Test
    public void doDbUpgrade_withEmptyDatabase_shouldSetsDatabaseVersion() {
        SQLiteDatabase database = SQLiteDatabase.create(null);

        storeSchemaDefinition.doDbUpgrade(database);

        assertEquals(LocalStore.DB_VERSION, database.getVersion());
    }

    @Test
    public void doDbUpgrade_withBadDatabase_shouldThrowInDebugBuild() {
        if (BuildConfig.DEBUG) {
            SQLiteDatabase database = SQLiteDatabase.create(null);
            database.setVersion(29);

            try {
                storeSchemaDefinition.doDbUpgrade(database);
                fail("Expected Error");
            } catch (Error e) {
                assertEquals("Exception while upgrading database", e.getMessage());
            }
        }
    }

    @Test
    public void doDbUpgrade_withV29_shouldUpgradeDatabaseToLatestVersion() {
        SQLiteDatabase database = createV29Database();

        storeSchemaDefinition.doDbUpgrade(database);

        assertEquals(LocalStore.DB_VERSION, database.getVersion());
    }

    @Test
    public void doDbUpgrade_withV29() {
        SQLiteDatabase database = createV29Database();
        insertMessageWithSubject(database, "Test Email");

        storeSchemaDefinition.doDbUpgrade(database);

        assertMessageWithSubjectExists(database, "Test Email");
    }

    @Test
    public void doDbUpgrade_fromV29_shouldResultInSameTables() {
        SQLiteDatabase newDatabase = createNewDatabase();
        SQLiteDatabase upgradedDatabase = createV29Database();

        storeSchemaDefinition.doDbUpgrade(upgradedDatabase);

        assertDatabaseTablesEquals(newDatabase, upgradedDatabase);
    }

    @Test
    public void doDbUpgrade_fromV29_shouldResultInSameTriggers() {
        SQLiteDatabase newDatabase = createNewDatabase();
        SQLiteDatabase upgradedDatabase = createV29Database();

        storeSchemaDefinition.doDbUpgrade(upgradedDatabase);

        assertDatabaseTriggersEquals(newDatabase, upgradedDatabase);
    }

    @Test
    public void doDbUpgrade_fromV29_shouldResultInSameIndexes() {
        SQLiteDatabase newDatabase = createNewDatabase();
        SQLiteDatabase upgradedDatabase = createV29Database();

        storeSchemaDefinition.doDbUpgrade(upgradedDatabase);

        assertDatabaseIndexesEquals(newDatabase, upgradedDatabase);
    }


    private SQLiteDatabase createV29Database() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        initV29Database(database);
        return database;
    }

    private void initV29Database(SQLiteDatabase db) {
        /*
         * There is no precise definition of a v29 database. This function approximates it by creating a database
         * that could be upgraded to the latest database as of v58
         */

        db.beginTransaction();

        db.execSQL("CREATE TABLE folders (" +
                "id INTEGER PRIMARY KEY," +
                "name TEXT, " +
                "last_updated INTEGER, " +
                "unread_count INTEGER, " +
                "visible_limit INTEGER, " +
                "status TEXT, " +
                "push_state TEXT, " +
                "last_pushed INTEGER " +
                ")");

        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY, " +
                "folder_id INTEGER, " +
                "uid TEXT, " +
                "subject TEXT, " +
                "date INTEGER, " +
                "flags TEXT, " +
                "sender_list TEXT, " +
                "to_list TEXT, " +
                "cc_list TEXT, " +
                "bcc_list TEXT, " +
                "reply_to_list TEXT, " +
                "attachment_count INTEGER, " +
                "internal_date INTEGER, " +
                "message_id TEXT, " +
                "html_content TEXT, " +
                "text_content TEXT, " +
                "preview_type TEXT default \"none\", " +
                "message_part_id INTEGER" +
                ")");

        db.execSQL("CREATE TABLE attachments (" +
                "id INTEGER PRIMARY KEY, " +
                "size INTEGER, " +
                "name TEXT, " +
                "mime_type TEXT, " +
                "store_data TEXT, " +
                "content_uri TEXT, " +
                "message_id INTEGER" +
                ")");

        db.execSQL("CREATE TABLE headers (" +
                "id INTEGER PRIMARY KEY, " +
                "name TEXT, " +
                "value TEXT, " +
                "message_id INTEGER" +
                ")");

        db.execSQL("CREATE TABLE threads (" +
                "id INTEGER PRIMARY KEY, " +
                "message_id INTEGER, " +
                "root INTEGER, " +
                "parent INTEGER" +
                ")");

        db.execSQL("CREATE TABLE pending_commands (" +
                "id INTEGER PRIMARY KEY, " +
                "command TEXT, " +
                "arguments TEXT" +
                ")");

        db.execSQL("CREATE INDEX msg_uid ON messages (uid, folder_id)");
        db.execSQL("CREATE INDEX folder_name ON folders (name)");
        db.execSQL("CREATE INDEX threads_message_id ON threads (message_id)");
        db.execSQL("CREATE INDEX threads_root ON threads (root)");
        db.execSQL("CREATE INDEX threads_parent ON threads (parent)");

        db.execSQL("CREATE TRIGGER set_thread_root " +
                "AFTER INSERT ON threads " +
                "BEGIN " +
                "UPDATE threads SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");

        db.execSQL("CREATE TRIGGER delete_folder " +
                "BEFORE DELETE ON folders " +
                "BEGIN " +
                "DELETE FROM messages WHERE old.id = folder_id; " +
                "END;");

        db.execSQL("CREATE TRIGGER delete_message " +
                "BEFORE DELETE ON messages " +
                "BEGIN " +
                "DELETE FROM message_parts WHERE root = OLD.message_part_id; " +
                "DELETE FROM messages_fulltext WHERE docid = OLD.id; " +
                "END");

        db.setVersion(29);

        db.setTransactionSuccessful();
        db.endTransaction();
    }

    private void assertMessageWithSubjectExists(SQLiteDatabase database, String subject) {
        Cursor cursor = database.query("messages", new String[] { "subject" }, null, null, null, null, null);
        try {
            assertTrue(cursor.moveToFirst());
            assertEquals(subject, cursor.getString(0));
        } finally {
            cursor.close();
        }
    }

    private void assertDatabaseTablesEquals(SQLiteDatabase expected, SQLiteDatabase actual) {
        List<String> tablesInNewDatabase = tablesInDatabase(expected);
        Collections.sort(tablesInNewDatabase);

        List<String> tablesInUpgradedDatabase = tablesInDatabase(actual);
        Collections.sort(tablesInUpgradedDatabase);

        assertEquals(tablesInNewDatabase, tablesInUpgradedDatabase);
    }

    private void assertDatabaseTriggersEquals(SQLiteDatabase expected, SQLiteDatabase actual) {
        List<String> triggersInNewDatabase = triggersInDatabase(expected);
        Collections.sort(triggersInNewDatabase);

        List<String> triggersInUpgradedDatabase = triggersInDatabase(actual);
        Collections.sort(triggersInUpgradedDatabase);

        assertEquals(triggersInNewDatabase, triggersInUpgradedDatabase);
    }

    private void assertDatabaseIndexesEquals(SQLiteDatabase expected, SQLiteDatabase actual) {
        List<String> indexesInNewDatabase = indexesInDatabase(expected);
        Collections.sort(indexesInNewDatabase);

        List<String> indexesInUpgradedDatabase = indexesInDatabase(actual);
        Collections.sort(indexesInUpgradedDatabase);

        assertEquals(indexesInNewDatabase, indexesInUpgradedDatabase);
    }

    private List<String> tablesInDatabase(SQLiteDatabase db) {
        return objectsInDatabase(db, "table");
    }

    private List<String> triggersInDatabase(SQLiteDatabase db) {
        return objectsInDatabase(db, "trigger");
    }

    private List<String> indexesInDatabase(SQLiteDatabase db) {
        return objectsInDatabase(db, "index");
    }

    private List<String> objectsInDatabase(SQLiteDatabase db, String type) {
        List<String> databaseObjects = new ArrayList<>();
        Cursor cursor = db.rawQuery("SELECT sql FROM sqlite_master WHERE type = ? AND sql IS NOT NULL",
                new String[] { type });
        try {
            while (cursor.moveToNext()) {
                String sql = cursor.getString(cursor.getColumnIndex("sql"));
                String resortedSql = "table".equals(type) ? sortTableColumns(sql) : sql;
                databaseObjects.add(resortedSql);
            }
        } finally {
            cursor.close();
        }

        return databaseObjects;
    }

    private String sortTableColumns(String sql) {
        int positionOfColumnDefinitions = sql.indexOf('(');
        String columnDefinitionsSql = sql.substring(positionOfColumnDefinitions + 1, sql.length() - 1);
        String[] columnDefinitions = columnDefinitionsSql.split(" *, *(?![^(]*\\))");
        Arrays.sort(columnDefinitions);

        String sqlPrefix = sql.substring(0, positionOfColumnDefinitions + 1);
        String sortedColumnDefinitionsSql = TextUtils.join(", ", columnDefinitions);
        return sqlPrefix + sortedColumnDefinitionsSql + ")";
    }

    private void insertMessageWithSubject(SQLiteDatabase database, String subject) {
        ContentValues data = new ContentValues();
        data.put("subject", subject);
        long rowId = database.insert("messages", null, data);
        assertNotEquals(-1, rowId);
    }

    private StoreSchemaDefinition createStoreSchemaDefinition() throws MessagingException {
        Context context = createContext();
        Account account = createAccount();
        LockableDatabase lockableDatabase = createLockableDatabase();

        LocalStore localStore = mock(LocalStore.class);
        localStore.database = lockableDatabase;
        when(localStore.getContext()).thenReturn(context);
        when(localStore.getAccount()).thenReturn(account);
        when(localStore.getDatabase()).thenReturn(lockableDatabase);

        return new StoreSchemaDefinition(localStore);
    }

    private Context createContext() {
        Context context = mock(Context.class);
        when(context.getString(R.string.special_mailbox_name_outbox)).thenReturn("Outbox");
        return context;
    }

    private LockableDatabase createLockableDatabase() throws MessagingException {
        LockableDatabase lockableDatabase = mock(LockableDatabase.class);
        when(lockableDatabase.execute(anyBoolean(), any(LockableDatabase.DbCallback.class))).thenReturn(false);
        return lockableDatabase;
    }

    private Account createAccount() {
        Account account = mock(Account.class);
        when(account.getInboxFolderName()).thenReturn("Inbox");
        when(account.getLocalStorageProviderId()).thenReturn(StorageManager.InternalStorageProvider.ID);
        return account;
    }

    private SQLiteDatabase createNewDatabase() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        storeSchemaDefinition.doDbUpgrade(database);
        return database;
    }
}
