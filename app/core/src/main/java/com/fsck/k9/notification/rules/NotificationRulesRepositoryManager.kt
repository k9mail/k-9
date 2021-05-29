package com.fsck.k9.notification.rules

import com.fsck.k9.Account
import com.fsck.k9.mailstore.LocalStoreProvider

class NotificationRulesRepositoryManager(private val localStoreProvider: LocalStoreProvider) {
    fun getNotificationRulesRepository(account: Account) = NotificationRulesRepository(localStoreProvider, account)
}
