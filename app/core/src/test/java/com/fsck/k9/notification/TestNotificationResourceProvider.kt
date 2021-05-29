package com.fsck.k9.notification

class TestNotificationResourceProvider : NotificationResourceProvider {
    override val iconWarning: Int = 1
    override val iconMarkAsRead: Int = 2
    override val iconDelete: Int = 3
    override val iconReply: Int = 4
    override val iconMuteSender: Int = 5
    override val iconNewMail: Int = 6
    override val iconSendingMail: Int = 7
    override val iconCheckingMail: Int = 8
    override val wearIconMarkAsRead: Int = 9
    override val wearIconDelete: Int = 10
    override val wearIconArchive: Int = 11
    override val wearIconReplyAll: Int = 12
    override val wearIconMarkAsSpam: Int = 13
    override val wearIconMuteSender: Int = 14

    override val messagesChannelName = "Messages"
    override val messagesChannelDescription = "Notifications related to messages"
    override val miscellaneousChannelName = "Miscellaneous"
    override val miscellaneousChannelDescription = "Miscellaneous notifications like errors etc."

    override fun authenticationErrorTitle(): String = "Authentication failed"

    override fun authenticationErrorBody(accountName: String): String =
        "Authentication failed for $accountName. Update your server settings."

    override fun certificateErrorTitle(accountName: String): String = "Certificate error for $accountName"

    override fun certificateErrorBody(): String = "Check your server settings"

    override fun newMailTitle(): String = "New mail"

    override fun newMailUnreadMessageCount(unreadMessageCount: Int, accountName: String): String =
        "$unreadMessageCount Unread ($accountName)"

    override fun newMessagesTitle(newMessagesCount: Int): String = when (newMessagesCount) {
        1 -> "1 new message"
        else -> "$newMessagesCount new messages"
    }

    override fun additionalMessages(overflowMessagesCount: Int, accountName: String): String =
        "+ $overflowMessagesCount more on $accountName"

    override fun previewEncrypted(): String = "*Encrypted*"

    override fun noSubject(): String = "(No subject)"

    override fun recipientDisplayName(recipientDisplayName: String): String = "To:$recipientDisplayName"

    override fun noSender(): String = "No sender"

    override fun sendFailedTitle(): String = "Failed to send some messages"

    override fun sendingMailTitle(): String = "Sending mail"

    override fun sendingMailBody(accountName: String): String = "Sending mail: $accountName"

    override fun checkingMailTicker(accountName: String, folderName: String): String =
        "Checking mail: $accountName:$folderName"

    override fun checkingMailTitle(): String = "Checking mail"

    override fun checkingMailSeparator(): String = ":"

    override fun actionMarkAsRead(): String = "Mark Read"

    override fun actionMarkAllAsRead(): String = "Mark All Read"

    override fun actionDelete(): String = "Delete"

    override fun actionDeleteAll(): String = "Delete All"

    override fun actionReply(): String = "Reply"

    override fun actionArchive(): String = "Archive"

    override fun actionArchiveAll(): String = "Archive All"

    override fun actionMarkAsSpam(): String = "Spam"

    override fun actionMuteSender(): String = "Mute Sender"
}
