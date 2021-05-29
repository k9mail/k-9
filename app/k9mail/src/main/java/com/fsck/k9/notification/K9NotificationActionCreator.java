package com.fsck.k9.notification;


import java.util.List;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.fsck.k9.Account;
import com.fsck.k9.DI;
import com.fsck.k9.K9;
import com.fsck.k9.R;
import com.fsck.k9.activity.MessageList;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.ui.notification.DeleteConfirmationActivity;
import com.fsck.k9.activity.compose.MessageActions;
import com.fsck.k9.activity.setup.AccountSetupIncoming;
import com.fsck.k9.activity.setup.AccountSetupOutgoing;
import com.fsck.k9.search.AccountSearchConditions;
import com.fsck.k9.search.LocalSearch;
import com.fsck.k9.ui.messagelist.DefaultFolderProvider;


/**
 * This class contains methods to create the {@link PendingIntent}s for the actions of our notifications.
 * <p/>
 * <strong>Note:</strong>
 * We need to take special care to ensure the {@code PendingIntent}s are unique as defined in the documentation of
 * {@link PendingIntent}. Otherwise selecting a notification action might perform the action on the wrong message.
 * <p/>
 * We use the notification ID as {@code requestCode} argument to ensure each notification/action pair gets a unique
 * {@code PendingIntent}.
 */
class K9NotificationActionCreator implements NotificationActionCreator {
    private final Context context;
    private final AccountSearchConditions accountSearchConditions = DI.get(AccountSearchConditions.class);
    private final DefaultFolderProvider defaultFolderProvider = DI.get(DefaultFolderProvider.class);


    public K9NotificationActionCreator(Context context) {
        this.context = context;
    }

    @Override
    public PendingIntent createViewMessagePendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = createMessageViewIntent(messageReference);
        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createViewFolderPendingIntent(Account account, long folderId, int notificationId) {
        Intent intent = createMessageListIntent(account, folderId);
        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createViewMessagesPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId) {

        Intent intent;
        if (account.isGoToUnreadMessageSearch()) {
            intent = createUnreadIntent(account);
        } else {
            Long folderServerId = getFolderIdOfAllMessages(messageReferences);

            if (folderServerId == null) {
                intent = createMessageListIntent(account);
            } else {
                intent = createMessageListIntent(account, folderServerId);
            }
        }

        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createViewFolderListPendingIntent(Account account, int notificationId) {
        Intent intent = createMessageListIntent(account);
        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createDismissAllMessagesPendingIntent(Account account, int notificationId) {
        Intent intent = NotificationActionService.createDismissAllMessagesIntent(context, account);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createDismissMessagePendingIntent(Context context, MessageReference messageReference,
            int notificationId) {

        Intent intent = NotificationActionService.createDismissMessageIntent(context, messageReference);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createReplyPendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = MessageActions.getActionReplyIntent(context, messageReference);

        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createMarkMessageAsReadPendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = NotificationActionService.createMarkMessageAsReadIntent(context, messageReference);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createMarkAllAsReadPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId) {
        String accountUuid = account.getUuid();
        Intent intent = NotificationActionService.createMarkAllAsReadIntent(context, accountUuid, messageReferences);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createMuteSenderPendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = NotificationActionService.createMuteSenderIntent(context, messageReference);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent getEditIncomingServerSettingsIntent(Account account) {
        Intent intent = AccountSetupIncoming.intentActionEditIncomingSettings(context, account);

        return PendingIntent.getActivity(context, account.getAccountNumber(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent getEditOutgoingServerSettingsIntent(Account account) {
        Intent intent = AccountSetupOutgoing.intentActionEditOutgoingSettings(context, account);

        return PendingIntent.getActivity(context, account.getAccountNumber(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createDeleteMessagePendingIntent(MessageReference messageReference, int notificationId) {
        if (K9.isConfirmDeleteFromNotification()) {
            return createDeleteConfirmationPendingIntent(messageReference, notificationId);
        } else {
            return createDeleteServicePendingIntent(messageReference, notificationId);
        }
    }

    private PendingIntent createDeleteServicePendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = NotificationActionService.createDeleteMessageIntent(context, messageReference);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createDeleteConfirmationPendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = DeleteConfirmationActivity.getIntent(context, messageReference);

        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createDeleteAllPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId) {
        if (K9.isConfirmDeleteFromNotification()) {
            return getDeleteAllConfirmationPendingIntent(messageReferences, notificationId);
        } else {
            return getDeleteAllServicePendingIntent(account, messageReferences, notificationId);
        }
    }

    private PendingIntent getDeleteAllConfirmationPendingIntent(List<MessageReference> messageReferences,
            int notificationId) {
        Intent intent = DeleteConfirmationActivity.getIntent(context, messageReferences);

        return PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private PendingIntent getDeleteAllServicePendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId) {
        String accountUuid = account.getUuid();
        Intent intent = NotificationActionService.createDeleteAllMessagesIntent(
                context, accountUuid, messageReferences);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createArchiveMessagePendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = NotificationActionService.createArchiveMessageIntent(context, messageReference);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createArchiveAllPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId) {
        Intent intent = NotificationActionService.createArchiveAllIntent(context, account, messageReferences);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public PendingIntent createMarkMessageAsSpamPendingIntent(MessageReference messageReference, int notificationId) {
        Intent intent = NotificationActionService.createMarkMessageAsSpamIntent(context, messageReference);

        return PendingIntent.getService(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Intent createUnreadIntent(final Account account) {
        String searchTitle = context.getString(R.string.search_title, account.getDescription(), context.getString(R.string.unread_modifier));
        LocalSearch search = accountSearchConditions.createUnreadSearch(account, searchTitle);
        return MessageList.intentDisplaySearch(context, search, true, false, false);
    }

    private Intent createMessageListIntent(Account account) {
        long folderId = defaultFolderProvider.getDefaultFolder(account);
        LocalSearch search = new LocalSearch();
        search.addAllowedFolder(folderId);
        search.addAccountUuid(account.getUuid());
        return MessageList.intentDisplaySearch(context, search, false, true, true);
    }

    private Intent createMessageListIntent(Account account, long folderId) {
        LocalSearch search = new LocalSearch();
        search.addAllowedFolder(folderId);
        search.addAccountUuid(account.getUuid());
        return MessageList.intentDisplaySearch(context, search, false, true, true);
    }

    private Intent createMessageViewIntent(MessageReference message) {
        return MessageList.actionDisplayMessageIntent(context, message);
    }

    private Long getFolderIdOfAllMessages(List<MessageReference> messageReferences) {
        MessageReference firstMessage = messageReferences.get(0);
        long folderId = firstMessage.getFolderId();

        for (MessageReference messageReference : messageReferences) {
            if (folderId != messageReference.getFolderId()) {
                return null;
            }
        }

        return folderId;
    }
}
