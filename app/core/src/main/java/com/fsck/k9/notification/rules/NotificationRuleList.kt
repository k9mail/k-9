package com.fsck.k9.notification.rules

import com.fsck.k9.mail.internet.MimeMessage

class NotificationRuleList(val rules: List<NotificationRule>) {
    fun isMuted(message: MimeMessage): Boolean {
        for (rule in rules) {
            if (rule.isMuted(message)) {
                return true
            }
        }
        return false
    }
}
