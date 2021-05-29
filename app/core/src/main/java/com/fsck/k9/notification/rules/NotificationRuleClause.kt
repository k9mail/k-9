package com.fsck.k9.notification.rules

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.MimeMessage

class NotificationRuleClause(val id: Long, val property: PropertyType, val propertyExtra: String?, val match: MatchType, val matchExtra: String) {
    fun matches(message: MimeMessage): Boolean {
        when (property) {
            PropertyType.SENDER -> {
                for (from in message.from) {
                    if (matches(from.address)) {
                        return true
                    }
                }
                for (sender in message.sender) {
                    if (matches(sender.address)) {
                        return true
                    }
                }
            }
            PropertyType.RECIPIENT -> {
                for (recipient in message.getRecipients(Message.RecipientType.TO)) {
                    if (matches(recipient.address)) {
                        return true
                    }
                }
            }
            PropertyType.HEADER -> {
                for (header in message.getHeader(propertyExtra)) {
                    if (matches(header)) {
                        return true
                    }
                }
            }
            PropertyType.SUBJECT -> {
                if (matches(message.subject)) {
                    return true
                }
            }
            PropertyType.BODY -> {
                if (matches(message.body.toString())) {
                    return true
                }
            }
        }
        return false
    }

    fun matches(string: String): Boolean {
        when (match) {
            MatchType.EQUALS -> return string.equals(matchExtra)
            MatchType.EQUALS_IGNORE_CASE -> return string.equals(matchExtra, ignoreCase = true)
            MatchType.CONTAINS -> return string.indexOf(matchExtra) >= 0
            MatchType.STARTS_WITH -> return string.startsWith(matchExtra)
        }
    }

    enum class PropertyType {
        SENDER,
        RECIPIENT,
        HEADER,
        SUBJECT,
        BODY
    }

    enum class MatchType {
        EQUALS,
        EQUALS_IGNORE_CASE,
        CONTAINS,
        STARTS_WITH
    }

    companion object {
        @JvmStatic
        fun create(id: Long, property: String, propertyExtra: String?, match: String, matchExtra: String): NotificationRuleClause {
            return NotificationRuleClause(id, PropertyType.valueOf(property), propertyExtra, MatchType.valueOf(match), matchExtra)
        }
    }
}
