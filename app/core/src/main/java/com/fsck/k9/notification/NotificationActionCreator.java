package com.fsck.k9.notification;


import java.util.List;

import android.app.PendingIntent;
import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.controller.MessageReference;


public interface NotificationActionCreator {
    PendingIntent createViewMessagePendingIntent(MessageReference messageReference, int notificationId);

    PendingIntent createViewFolderPendingIntent(Account account, long folderId, int notificationId);

    PendingIntent createViewMessagesPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId);

    PendingIntent createViewFolderListPendingIntent(Account account, int notificationId);

    PendingIntent createDismissAllMessagesPendingIntent(Account account, int notificationId);

    PendingIntent createDismissMessagePendingIntent(Context context, MessageReference messageReference,
            int notificationId);

    PendingIntent createReplyPendingIntent(MessageReference messageReference, int notificationId);

    PendingIntent createMarkMessageAsReadPendingIntent(MessageReference messageReference, int notificationId);

    PendingIntent createMarkAllAsReadPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId);

    PendingIntent createMuteSenderPendingIntent(MessageReference messageReference, int notificationId);

    PendingIntent getEditIncomingServerSettingsIntent(Account account);

    PendingIntent getEditOutgoingServerSettingsIntent(Account account);

    PendingIntent createDeleteMessagePendingIntent(MessageReference messageReference, int notificationId);

    PendingIntent createDeleteAllPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId);

    PendingIntent createArchiveMessagePendingIntent(MessageReference messageReference, int notificationId);

    PendingIntent createArchiveAllPendingIntent(Account account, List<MessageReference> messageReferences,
            int notificationId);

    PendingIntent createMarkMessageAsSpamPendingIntent(MessageReference messageReference, int notificationId);
}
