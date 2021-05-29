package com.fsck.k9.notification

import android.content.Context
import com.fsck.k9.R

class K9NotificationResourceProvider(private val context: Context) : NotificationResourceProvider {
    override val iconWarning: Int = R.drawable.notification_icon_warning
    override val iconMarkAsRead: Int = R.drawable.notification_action_mark_as_read
    override val iconDelete: Int = R.drawable.notification_action_delete
    override val iconReply: Int = R.drawable.notification_action_reply
    override val iconMuteSender: Int = R.drawable.notification_action_mute_sender
    override val iconNewMail: Int = R.drawable.notification_icon_new_mail
    override val iconSendingMail: Int = R.drawable.notification_icon_check_mail
    override val iconCheckingMail: Int = R.drawable.notification_icon_check_mail
    override val wearIconMarkAsRead: Int = R.drawable.notification_action_mark_as_read
    override val wearIconDelete: Int = R.drawable.notification_action_delete
    override val wearIconArchive: Int = R.drawable.notification_action_archive
    override val wearIconReplyAll: Int = R.drawable.notification_action_reply
    override val wearIconMarkAsSpam: Int = R.drawable.notification_action_mark_as_spam
    override val wearIconMuteSender: Int = R.drawable.notification_action_mute_sender

    override val messagesChannelName: String
        get() = context.getString(R.string.notification_channel_messages_title)
    override val messagesChannelDescription: String
        get() = context.getString(R.string.notification_channel_messages_description)
    override val miscellaneousChannelName: String
        get() = context.getString(R.string.notification_channel_miscellaneous_title)
    override val miscellaneousChannelDescription: String
        get() = context.getString(R.string.notification_channel_miscellaneous_description)

    override fun authenticationErrorTitle(): String =
        context.getString(R.string.notification_authentication_error_title)

    override fun authenticationErrorBody(accountName: String): String =
        context.getString(R.string.notification_authentication_error_text, accountName)

    override fun certificateErrorTitle(accountName: String): String =
        context.getString(R.string.notification_certificate_error_title, accountName)

    override fun certificateErrorBody(): String = context.getString(R.string.notification_certificate_error_text)

    override fun newMailTitle(): String = context.getString(R.string.notification_new_title)

    override fun newMailUnreadMessageCount(unreadMessageCount: Int, accountName: String): String =
        context.getString(R.string.notification_new_one_account_fmt, unreadMessageCount, accountName)

    override fun newMessagesTitle(newMessagesCount: Int): String =
        context.resources.getQuantityString(
            R.plurals.notification_new_messages_title,
            newMessagesCount, newMessagesCount
        )

    override fun additionalMessages(overflowMessagesCount: Int, accountName: String): String =
        context.getString(R.string.notification_additional_messages, overflowMessagesCount, accountName)

    override fun previewEncrypted(): String = context.getString(R.string.preview_encrypted)

    override fun noSubject(): String = context.getString(R.string.general_no_subject)

    override fun recipientDisplayName(recipientDisplayName: String): String =
        context.getString(R.string.message_to_fmt, recipientDisplayName)

    override fun noSender(): String = context.getString(R.string.general_no_sender)

    override fun sendFailedTitle(): String = context.getString(R.string.send_failure_subject)

    override fun sendingMailTitle(): String = context.getString(R.string.notification_bg_send_title)

    override fun sendingMailBody(accountName: String): String =
        context.getString(R.string.notification_bg_send_ticker, accountName)

    override fun checkingMailTicker(accountName: String, folderName: String): String =
        context.getString(R.string.notification_bg_sync_ticker, accountName, folderName)

    override fun checkingMailTitle(): String =
        context.getString(R.string.notification_bg_sync_title)

    override fun checkingMailSeparator(): String =
        context.getString(R.string.notification_bg_title_separator)

    override fun actionMarkAsRead(): String = context.getString(R.string.notification_action_mark_as_read)

    override fun actionMuteSender(): String = context.getString(R.string.notification_action_mute_sender)

    override fun actionMarkAllAsRead(): String = context.getString(R.string.notification_action_mark_all_as_read)

    override fun actionDelete(): String = context.getString(R.string.notification_action_delete)

    override fun actionDeleteAll(): String = context.getString(R.string.notification_action_delete_all)

    override fun actionReply(): String = context.getString(R.string.notification_action_reply)

    override fun actionArchive(): String = context.getString(R.string.notification_action_archive)

    override fun actionArchiveAll(): String = context.getString(R.string.notification_action_archive_all)

    override fun actionMarkAsSpam(): String = context.getString(R.string.notification_action_spam)
}
