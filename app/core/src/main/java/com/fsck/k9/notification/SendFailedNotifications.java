package com.fsck.k9.notification;


import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.BigTextStyle;
import androidx.core.app.NotificationManagerCompat;

import com.fsck.k9.Account;
import com.fsck.k9.helper.ExceptionHelper;

import static com.fsck.k9.notification.NotificationHelper.NOTIFICATION_LED_BLINK_FAST;
import static com.fsck.k9.notification.NotificationHelper.NOTIFICATION_LED_FAILURE_COLOR;


class SendFailedNotifications {
    private final NotificationHelper notificationHelper;
    private final NotificationActionCreator actionBuilder;
    private final NotificationResourceProvider resourceProvider;


    public SendFailedNotifications(NotificationHelper notificationHelper, NotificationActionCreator actionBuilder,
            NotificationResourceProvider resourceProvider) {
        this.notificationHelper = notificationHelper;
        this.actionBuilder = actionBuilder;
        this.resourceProvider = resourceProvider;
    }

    public void showSendFailedNotification(Account account, Exception exception) {
        String title = resourceProvider.sendFailedTitle();
        String text = ExceptionHelper.getRootCauseMessage(exception);

        int notificationId = NotificationIds.getSendFailedNotificationId(account);
        PendingIntent folderListPendingIntent = actionBuilder.createViewFolderListPendingIntent(
                account, notificationId);

        NotificationCompat.Builder builder = notificationHelper
                .createNotificationBuilder(account, NotificationChannelManager.ChannelType.MISCELLANEOUS)
                .setSmallIcon(resourceProvider.getIconWarning())
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(folderListPendingIntent)
                .setStyle(new BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_ERROR);

        notificationHelper.configureNotification(builder, null, null, NOTIFICATION_LED_FAILURE_COLOR,
                NOTIFICATION_LED_BLINK_FAST, true);

        getNotificationManager().notify(notificationId, builder.build());
    }

    public void clearSendFailedNotification(Account account) {
        int notificationId = NotificationIds.getSendFailedNotificationId(account);
        getNotificationManager().cancel(notificationId);
    }

    private NotificationManagerCompat getNotificationManager() {
        return notificationHelper.getNotificationManager();
    }
}
