package com.fsck.k9.notification

interface NotificationResourceProvider {
    val iconWarning: Int
    val iconMarkAsRead: Int
    val iconDelete: Int
    val iconReply: Int
    val iconMuteSender: Int
    val iconNewMail: Int
    val iconSendingMail: Int
    val iconCheckingMail: Int
    val wearIconMarkAsRead: Int
    val wearIconDelete: Int
    val wearIconArchive: Int
    val wearIconReplyAll: Int
    val wearIconMarkAsSpam: Int
    val wearIconMuteSender: Int

    val messagesChannelName: String
    val messagesChannelDescription: String
    val miscellaneousChannelName: String
    val miscellaneousChannelDescription: String

    fun authenticationErrorTitle(): String
    fun authenticationErrorBody(accountName: String): String

    fun certificateErrorTitle(accountName: String): String
    fun certificateErrorBody(): String

    fun newMailTitle(): String
    fun newMailUnreadMessageCount(unreadMessageCount: Int, accountName: String): String
    fun newMessagesTitle(newMessagesCount: Int): String
    fun additionalMessages(overflowMessagesCount: Int, accountName: String): String
    fun previewEncrypted(): String
    fun noSubject(): String
    fun recipientDisplayName(recipientDisplayName: String): String
    fun noSender(): String

    fun sendFailedTitle(): String
    fun sendingMailTitle(): String
    fun sendingMailBody(accountName: String): String

    fun checkingMailTicker(accountName: String, folderName: String): String
    fun checkingMailTitle(): String
    fun checkingMailSeparator(): String

    fun actionMarkAsRead(): String
    fun actionMarkAllAsRead(): String
    fun actionDelete(): String
    fun actionDeleteAll(): String
    fun actionReply(): String
    fun actionArchive(): String
    fun actionArchiveAll(): String
    fun actionMarkAsSpam(): String
    fun actionMuteSender(): String
}
