package com.fsck.k9.storage.migrations

import android.database.sqlite.SQLiteDatabase

internal class MigrationTo80(private val db: SQLiteDatabase) {
    fun addNotificationRuleTables() {
        db.execSQL(
            "CREATE TABLE notification_rule_clauses (" +
                "id INTEGER PRIMARY KEY, " +
                "notification_rule_id INTEGER NOT NULL, " +
                "property TEXT NOT NULL, " +
                "property_extra TEXT, " +
                "match TEXT NOT NULL, " +
                "match_extra TEXT " +
                ")"
        )

        db.execSQL(
            "CREATE TABLE notification_rules (" +
                "id INTEGER PRIMARY KEY, " +
                "description TEXT NOT NULL, " +
                "enabled INTEGER DEFAULT 1," +
                "action TEXT NOT NULL, " +
                "action_extra TEXT NOT NULL " +
                ")"
        )

        db.execSQL(
            "CREATE TRIGGER delete_notification_rule_clauses " +
                "BEFORE DELETE ON notification_rules " +
                "BEGIN " +
                "DELETE FROM notification_rule_clauses WHERE old.id = notification_rule_id; " +
                "END"
        )
    }
}
