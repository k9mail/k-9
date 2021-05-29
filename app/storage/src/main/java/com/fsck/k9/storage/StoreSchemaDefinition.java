package com.fsck.k9.storage;


import android.database.sqlite.SQLiteDatabase;

import com.fsck.k9.K9;
import com.fsck.k9.mail.FolderClass;
import com.fsck.k9.mailstore.LockableDatabase.SchemaDefinition;
import com.fsck.k9.mailstore.MigrationsHelper;
import com.fsck.k9.storage.migrations.Migrations;
import timber.log.Timber;


class StoreSchemaDefinition implements SchemaDefinition {
    static final int DB_VERSION = 79;

    private final MigrationsHelper migrationsHelper;


    StoreSchemaDefinition(MigrationsHelper migrationsHelper) {
        this.migrationsHelper = migrationsHelper;
    }

    @Override
    public int getVersion() {
        return DB_VERSION;
    }

    @Override
    public void doDbUpgrade(final SQLiteDatabase db) {
        try {
            upgradeDatabase(db);
        } catch (Exception e) {
            if (K9.DEVELOPER_MODE) {
                throw new Error("Exception while upgrading database", e);
            }

            Timber.e(e, "Exception while upgrading database. Resetting the DB to v0");
            db.setVersion(0);
            upgradeDatabase(db);
        }
    }

    private void upgradeDatabase(final SQLiteDatabase db) {
        Timber.i("Upgrading database from version %d to version %d", db.getVersion(), DB_VERSION);

        db.beginTransaction();
        try {
            if (db.getVersion() > DB_VERSION) {
                String accountUuid = migrationsHelper.getAccount().getUuid();
                throw new AssertionError("Database downgrades are not supported. " +
                        "Please fix the account database '" + accountUuid + "' manually or " +
                        "clear app data.");
            }

            // We only support upgrades from K-9 Mail 5.301. For upgrades from earlier versions we start from scratch.
            if (db.getVersion() < 61) {
                dbCreateDatabaseFromScratch(db);
            } else {
                Migrations.upgradeDatabase(db, migrationsHelper);
            }

            db.setVersion(DB_VERSION);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (db.getVersion() != DB_VERSION) {
            throw new RuntimeException("Database upgrade failed!");
        }
    }

    private static void dbCreateDatabaseFromScratch(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS account_extra_values");
        db.execSQL("CREATE TABLE account_extra_values (" +
                "name TEXT NOT NULL PRIMARY KEY, " +
                "value_text TEXT, " +
                "value_integer INTEGER " +
                ")");

        db.execSQL("DROP TABLE IF EXISTS folders");
        db.execSQL("CREATE TABLE folders (" +
                "id INTEGER PRIMARY KEY," +
                "name TEXT, " +
                "last_updated INTEGER, " +
                "unread_count INTEGER, " +
                "visible_limit INTEGER, " +
                "status TEXT, " +
                "flagged_count INTEGER default 0, " +
                "integrate INTEGER, " +
                "top_group INTEGER, " +
                "poll_class TEXT, " +
                "push_class TEXT, " +
                "display_class TEXT, " +
                "notify_class TEXT default '"+ FolderClass.INHERITED.name() + "', " +
                "more_messages TEXT default \"unknown\", " +
                "server_id TEXT, " +
                "local_only INTEGER, " +
                "type TEXT DEFAULT \"regular\"" +
                ")");

        db.execSQL("DROP INDEX IF EXISTS folder_server_id");
        db.execSQL("CREATE INDEX folder_server_id ON folders (server_id)");

        db.execSQL("DROP TABLE IF EXISTS folder_extra_values");
        db.execSQL("CREATE TABLE folder_extra_values (" +
                "folder_id INTEGER NOT NULL, " +
                "name TEXT NOT NULL, " +
                "value_text TEXT, " +
                "value_integer INTEGER, " +
                "PRIMARY KEY (folder_id, name)" +
                ")");

        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY, " +
                "deleted INTEGER default 0, " +
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
                "preview_type TEXT default \"none\", " +
                "preview TEXT, " +
                "mime_type TEXT, "+
                "normalized_subject_hash INTEGER, " +
                "empty INTEGER default 0, " +
                "read INTEGER default 0, " +
                "flagged INTEGER default 0, " +
                "answered INTEGER default 0, " +
                "forwarded INTEGER default 0, " +
                "message_part_id INTEGER," +
                "encryption_type TEXT" +
                ")");

        db.execSQL("DROP TABLE IF EXISTS message_parts");
        db.execSQL("CREATE TABLE message_parts (" +
                "id INTEGER PRIMARY KEY, " +
                "type INTEGER NOT NULL, " +
                "root INTEGER, " +
                "parent INTEGER NOT NULL, " +
                "seq INTEGER NOT NULL, " +
                "mime_type TEXT, " +
                "decoded_body_size INTEGER, " +
                "display_name TEXT, " +
                "header TEXT, " +
                "encoding TEXT, " +
                "charset TEXT, " +
                "data_location INTEGER NOT NULL, " +
                "data BLOB, " +
                "preamble TEXT, " +
                "epilogue TEXT, " +
                "boundary TEXT, " +
                "content_id TEXT, " +
                "server_extra TEXT" +
                ")");

        db.execSQL("CREATE TRIGGER set_message_part_root " +
                "AFTER INSERT ON message_parts " +
                "BEGIN " +
                "UPDATE message_parts SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");

        db.execSQL("CREATE INDEX IF NOT EXISTS msg_uid ON messages (uid, folder_id)");
        db.execSQL("DROP INDEX IF EXISTS msg_folder_id");
        db.execSQL("DROP INDEX IF EXISTS msg_folder_id_date");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_folder_id_deleted_date ON messages (folder_id,deleted,internal_date)");

        db.execSQL("DROP INDEX IF EXISTS msg_empty");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_empty ON messages (empty)");

        db.execSQL("DROP INDEX IF EXISTS msg_read");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_read ON messages (read)");

        db.execSQL("DROP INDEX IF EXISTS msg_flagged");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_flagged ON messages (flagged)");

        db.execSQL("DROP INDEX IF EXISTS msg_composite");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_composite ON messages (deleted, empty,folder_id,flagged,read)");

        db.execSQL("DROP INDEX IF EXISTS message_parts_root");
        db.execSQL("CREATE INDEX IF NOT EXISTS message_parts_root ON message_parts (root)");

        db.execSQL("DROP TABLE IF EXISTS threads");
        db.execSQL("CREATE TABLE threads (" +
                "id INTEGER PRIMARY KEY, " +
                "message_id INTEGER, " +
                "root INTEGER, " +
                "parent INTEGER" +
                ")");

        db.execSQL("DROP INDEX IF EXISTS threads_message_id");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_message_id ON threads (message_id)");

        db.execSQL("DROP INDEX IF EXISTS threads_root");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_root ON threads (root)");

        db.execSQL("DROP INDEX IF EXISTS threads_parent");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_parent ON threads (parent)");

        db.execSQL("DROP TRIGGER IF EXISTS set_thread_root");
        db.execSQL("CREATE TRIGGER set_thread_root " +
                "AFTER INSERT ON threads " +
                "BEGIN " +
                "UPDATE threads SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");

        db.execSQL("DROP TABLE IF EXISTS outbox_state");
        db.execSQL("CREATE TABLE outbox_state (" +
                "message_id INTEGER PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE," +
                "send_state TEXT," +
                "number_of_send_attempts INTEGER DEFAULT 0," +
                "error_timestamp INTEGER DEFAULT 0," +
                "error TEXT)");

        db.execSQL("DROP TABLE IF EXISTS pending_commands");
        db.execSQL("CREATE TABLE pending_commands " +
                "(id INTEGER PRIMARY KEY, command TEXT, data TEXT)");

        db.execSQL("DROP TABLE IF EXISTS notification_rule_clauses");
        db.execSQL("CREATE TABLE notification_rule_clauses (" +
                "id INTEGER PRIMARY KEY, " +
                "notification_rule_id INTEGER NOT NULL, " +
                "property TEXT NOT NULL, " +
                "property_extra TEXT, " +
                "match TEXT NOT NULL, " +
                "match_extra TEXT " +
                ")");

        db.execSQL("DROP TABLE IF EXISTS notification_rules");
        db.execSQL("CREATE TABLE notification_rules (" +
                "id INTEGER PRIMARY KEY, " +
                "description TEXT NOT NULL, " +
                "enabled INTEGER DEFAULT 1," +
                "action TEXT NOT NULL, " +
                "action_extra TEXT NOT NULL " +
                ")");

        db.execSQL("DROP TRIGGER IF EXISTS delete_folder");
        db.execSQL("CREATE TRIGGER delete_folder BEFORE DELETE ON folders BEGIN DELETE FROM messages WHERE old.id = folder_id; END;");

        db.execSQL("DROP TRIGGER IF EXISTS delete_folder_extra_values");
        db.execSQL("CREATE TRIGGER delete_folder_extra_values " +
                "BEFORE DELETE ON folders " +
                "BEGIN " +
                "DELETE FROM folder_extra_values WHERE old.id = folder_id; " +
                "END;");

        db.execSQL("DROP TRIGGER IF EXISTS delete_message");
        db.execSQL("CREATE TRIGGER delete_message " +
                "BEFORE DELETE ON messages " +
                "BEGIN " +
                "DELETE FROM message_parts WHERE root = OLD.message_part_id; " +
                "DELETE FROM messages_fulltext WHERE docid = OLD.id; " +
                "DELETE FROM threads WHERE message_id = OLD.id; " +
                "END");

        db.execSQL("DROP TRIGGER IF EXISTS delete_notification_rule_clauses");
        db.execSQL("CREATE TRIGGER delete_notification_rule_clauses " +
                "BEFORE DELETE ON notification_rules " +
                "BEGIN " +
                "DELETE FROM notification_rule_clauses WHERE old.id = notification_rule_id; " +
                "END");

        db.execSQL("DROP TABLE IF EXISTS messages_fulltext");
        db.execSQL("CREATE VIRTUAL TABLE messages_fulltext USING fts4 (fulltext)");
    }
}
