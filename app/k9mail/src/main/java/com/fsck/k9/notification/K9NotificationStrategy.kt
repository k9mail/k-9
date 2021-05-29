package com.fsck.k9.notification

import com.fsck.k9.Account
import com.fsck.k9.K9
import com.fsck.k9.helper.Contacts
import com.fsck.k9.mail.Flag
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalFolder.isModeMismatch
import com.fsck.k9.mailstore.LocalMessage
import timber.log.Timber

class K9NotificationStrategy(private val contacts: Contacts) : NotificationStrategy {

    override fun shouldNotifyForMessage(
        account: Account,
        localFolder: LocalFolder,
        message: LocalMessage,
        isOldMessage: Boolean
    ): Boolean {

        // If we don't even have an account name, don't show the notification.
        // (This happens during initial account setup)
        if (account.name == null) {
            Timber.v("No notification: Missing account name")
            return false
        }

        if (!K9.isNotificationDuringQuietTimeEnabled && K9.isQuietTime) {
            Timber.v("No notification: Quiet time is active")
            return false
        }

        if (!account.isNotifyNewMail) {
            Timber.v("No notification: Notifications are disabled")
            return false
        }

        val folder = message.folder
        if (folder != null) {
            when (folder.databaseId) {
                account.inboxFolderId -> {
                    // Don't skip notifications if the Inbox folder is also configured as another special folder
                }
                account.trashFolderId -> {
                    Timber.v("No notification: Message is in Trash folder")
                    return false
                }
                account.draftsFolderId -> {
                    Timber.v("No notification: Message is in Drafts folder")
                    return false
                }
                account.spamFolderId -> {
                    Timber.v("No notification: Message is in Spam folder")
                    return false
                }
                account.sentFolderId -> {
                    Timber.v("No notification: Message is in Sent folder")
                    return false
                }
            }
        }

        if (isModeMismatch(account.folderDisplayMode, localFolder.displayClass)) {
            Timber.v("No notification: Message is in folder not being displayed")
            return false
        }

        if (isModeMismatch(account.folderNotifyNewMailMode, localFolder.notifyClass)) {
            Timber.v("No notification: Notifications are disabled for this folder class")
            return false
        }

        if (isOldMessage) {
            Timber.v("No notification: Message is old")
            return false
        }

        if (message.isSet(Flag.SEEN)) {
            Timber.v("No notification: Message is marked as read")
            return false
        }

        if (!account.isNotifySelfNewMail && account.isAnIdentity(message.from)) {
            Timber.v("No notification: Notifications for messages from yourself are disabled")
            return false
        }

        if (account.isNotifyContactsMailOnly && !contacts.isAnyInContacts(message.from)) {
            Timber.v("No notification: Message is not from a known contact")
            return false
        }

        if (account.isNotificationSuppressed(message)) {
            Timber.v("No notification: Message matches suppression pattern")
            return false
        }

        return true
    }
}
