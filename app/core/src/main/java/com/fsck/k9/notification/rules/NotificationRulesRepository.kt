package com.fsck.k9.notification.rules

import androidx.core.database.getStringOrNull
import com.fsck.k9.Account
import com.fsck.k9.helper.map
import com.fsck.k9.mailstore.LocalStoreProvider

class NotificationRulesRepository(
    private val localStoreProvider: LocalStoreProvider,
    private val account: Account
) {
    fun getRules(): NotificationRuleList {
        val database = localStoreProvider.getInstance(account).database
        return NotificationRuleList(
            database.execute(false) { db ->
                db.query(
                    "notification_rules",
                    arrayOf(
                        "id",
                        "description",
                        "enabled",
                        "action",
                        "action_extra"
                    ),
                    null,
                    null,
                    null,
                    null,
                    null
                ).use { cursor ->
                    cursor.map {
                        val id = cursor.getLong(0)
                        val description = cursor.getString(1)
                        val enabled = cursor.getLong(2)
                        val action = cursor.getString(3)
                        val actionExtra = cursor.getStringOrNull(4)
                        val clauses = database.execute(false) { db ->
                            db.query(
                                "notification_rule_clauses",
                                arrayOf(
                                    "id",
                                    "property",
                                    "property_extra",
                                    "action",
                                    "action_extra"
                                ),
                                "notification_rule_id = " + id,
                                null,
                                null,
                                null,
                                null
                            ).use { cursor ->
                                cursor.map {
                                    val clauseId = cursor.getLong(0)
                                    val property = cursor.getString(1)
                                    val propertyExtra = cursor.getStringOrNull(2)
                                    val match = cursor.getString(3)
                                    val matchExtra = cursor.getString(4)
                                    NotificationRuleClause.create(clauseId, property, propertyExtra, match, matchExtra)
                                }
                            }
                        }
                        NotificationRule.create(id, description, enabled, action, actionExtra, clauses)
                    }
                }
            }
        )
    }
}
