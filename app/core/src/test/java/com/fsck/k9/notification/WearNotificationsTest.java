package com.fsck.k9.notification;


import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import androidx.core.app.NotificationCompat.Action;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationCompat.Extender;
import androidx.core.app.NotificationCompat.WearableExtender;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.K9.NotificationQuickDelete;
import com.fsck.k9.testing.MockHelper;
import com.fsck.k9.RobolectricTest;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.controller.MessagingController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class WearNotificationsTest extends RobolectricTest {
    private static final int ACCOUNT_NUMBER = 42;
    private static final String ACCOUNT_NAME = "accountName";

    private NotificationResourceProvider resourceProvider = new TestNotificationResourceProvider();
    private Account account;
    private Builder builder;
    private NotificationActionCreator actionCreator;
    private TestWearNotifications wearNotifications;
    private Notification notification;

    @Before
    public void setUp() throws Exception {
        account = createAccount();
        notification = createNotification();
        builder = createNotificationBuilder(notification);
        actionCreator = createNotificationActionCreator();
        NotificationHelper notificationHelper = createNotificationHelper(RuntimeEnvironment.application, builder);
        MessagingController messagingController = createMessagingController();

        wearNotifications = new TestWearNotifications(notificationHelper, actionCreator, messagingController,
                resourceProvider);
    }

    @Test
    public void testBuildStackedNotification() throws Exception {
        disableOptionalActions();
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        MessageReference messageReference = createMessageReference(1);
        NotificationContent content = createNotificationContent(messageReference);
        NotificationHolder holder = createNotificationHolder(notificationId, content);
        PendingIntent replyPendingIntent = createFakePendingIntent(1);
        when(actionCreator.createReplyPendingIntent(messageReference, notificationId)).thenReturn(replyPendingIntent);
        PendingIntent markAsReadPendingIntent = createFakePendingIntent(2);
        when(actionCreator.createMarkMessageAsReadPendingIntent(messageReference, notificationId))
                .thenReturn(markAsReadPendingIntent);
        PendingIntent muteSenderPendingIntent = createFakePendingIntent(3);
        when(actionCreator.createMuteSenderPendingIntent(messageReference, notificationId))
                .thenReturn(muteSenderPendingIntent);

        Notification result = wearNotifications.buildStackedNotification(account, holder);

        assertEquals(notification, result);
        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconReplyAll(), "Reply", replyPendingIntent);
        verifyAddAction(resourceProvider.getWearIconMarkAsRead(), "Mark Read", markAsReadPendingIntent);
        verifyAddAction(resourceProvider.getWearIconMuteSender(), "Mute Sender", muteSenderPendingIntent);
        verifyNumberOfActions(3);
    }

    @Test
    public void testBuildStackedNotificationWithDeleteActionEnabled() throws Exception {
        enableDeleteAction();
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        MessageReference messageReference = createMessageReference(1);
        NotificationContent content = createNotificationContent(messageReference);
        NotificationHolder holder = createNotificationHolder(notificationId, content);
        PendingIntent deletePendingIntent = createFakePendingIntent(1);
        when(actionCreator.createDeleteMessagePendingIntent(messageReference, notificationId))
                .thenReturn(deletePendingIntent);

        Notification result = wearNotifications.buildStackedNotification(account, holder);

        assertEquals(notification, result);
        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconDelete(), "Delete", deletePendingIntent);
    }

    @Test
    public void testBuildStackedNotificationWithArchiveActionEnabled() throws Exception {
        enableArchiveAction();
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        MessageReference messageReference = createMessageReference(1);
        NotificationContent content = createNotificationContent(messageReference);
        NotificationHolder holder = createNotificationHolder(notificationId, content);
        PendingIntent archivePendingIntent = createFakePendingIntent(1);
        when(actionCreator.createArchiveMessagePendingIntent(messageReference, notificationId))
                .thenReturn(archivePendingIntent);

        Notification result = wearNotifications.buildStackedNotification(account, holder);

        assertEquals(notification, result);
        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconArchive(), "Archive", archivePendingIntent);
    }

    @Test
    public void testBuildStackedNotificationWithMarkAsSpamActionEnabled() throws Exception {
        enableSpamAction();
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        MessageReference messageReference = createMessageReference(1);
        NotificationContent content = createNotificationContent(messageReference);
        NotificationHolder holder = createNotificationHolder(notificationId, content);
        PendingIntent markAsSpamPendingIntent = createFakePendingIntent(1);
        when(actionCreator.createMarkMessageAsSpamPendingIntent(messageReference, notificationId))
                .thenReturn(markAsSpamPendingIntent);

        Notification result = wearNotifications.buildStackedNotification(account, holder);

        assertEquals(notification, result);
        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconMarkAsSpam(), "Spam", markAsSpamPendingIntent);
    }

    @Test
    public void testAddSummaryActions() throws Exception {
        disableOptionalSummaryActions();
        int notificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        ArrayList<MessageReference> messageReferences = createMessageReferenceList();
        NotificationData notificationData = createNotificationData(messageReferences);
        PendingIntent markAllAsReadPendingIntent = createFakePendingIntent(1);
        when(actionCreator.createMarkAllAsReadPendingIntent(account, messageReferences, notificationId))
                .thenReturn(markAllAsReadPendingIntent);

        wearNotifications.addSummaryActions(builder, notificationData);

        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconMarkAsRead(), "Mark All Read", markAllAsReadPendingIntent);
        verifyNumberOfActions(1);
    }

    @Test
    public void testAddSummaryActionsWithDeleteAllActionEnabled() throws Exception {
        enableDeleteAction();
        int notificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        ArrayList<MessageReference> messageReferences = createMessageReferenceList();
        NotificationData notificationData = createNotificationData(messageReferences);
        PendingIntent deletePendingIntent = createFakePendingIntent(1);
        when(actionCreator.createDeleteAllPendingIntent(account, messageReferences, notificationId))
                .thenReturn(deletePendingIntent);

        wearNotifications.addSummaryActions(builder, notificationData);

        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconDelete(), "Delete All", deletePendingIntent);
    }

    @Test
    public void testAddSummaryActionsWithArchiveAllActionEnabled() throws Exception {
        enableArchiveAction();
        int notificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        ArrayList<MessageReference> messageReferences = createMessageReferenceList();
        NotificationData notificationData = createNotificationData(messageReferences);
        PendingIntent archivePendingIntent = createFakePendingIntent(1);
        when(actionCreator.createArchiveAllPendingIntent(account, messageReferences, notificationId))
                .thenReturn(archivePendingIntent);

        wearNotifications.addSummaryActions(builder, notificationData);

        verifyExtendWasOnlyCalledOnce();
        verifyAddAction(resourceProvider.getWearIconArchive(), "Archive All", archivePendingIntent);
    }

    private void disableOptionalActions() {
        disableDeleteAction();
        disableArchiveAction();
        disableSpamAction();
    }

    private void disableDeleteAction() {
        K9.setNotificationQuickDeleteBehaviour(NotificationQuickDelete.NEVER);
    }

    private void disableArchiveAction() {
        when(account.getArchiveFolderId()).thenReturn(null);
    }

    private void disableSpamAction() {
        when(account.getSpamFolderId()).thenReturn(null);
    }

    private void enableDeleteAction() {
        K9.setNotificationQuickDeleteBehaviour(NotificationQuickDelete.ALWAYS);
        K9.setConfirmDeleteFromNotification(false);
    }

    private void enableArchiveAction() {
        when(account.getArchiveFolderId()).thenReturn(22L);
    }

    private void enableSpamAction() {
        when(account.getSpamFolderId()).thenReturn(11L);
    }

    private void disableOptionalSummaryActions() {
        disableDeleteAction();
        disableArchiveAction();
    }

    private Builder createNotificationBuilder(Notification notification) {
        Builder builder = MockHelper.mockBuilder(Builder.class);
        when(builder.build()).thenReturn(notification);
        return builder;
    }

    private NotificationHelper createNotificationHelper(Context context, Builder builder) {
        NotificationHelper notificationHelper = mock(NotificationHelper.class);
        when(notificationHelper.createNotificationBuilder(any(Account.class), any(NotificationChannelManager
                .ChannelType.class))).thenReturn(builder);
        when(notificationHelper.getAccountName(account)).thenReturn(ACCOUNT_NAME);
        when(notificationHelper.getContext()).thenReturn(context);
        return notificationHelper;
    }

    private NotificationActionCreator createNotificationActionCreator() {
        return mock(NotificationActionCreator.class);
    }

    private Account createAccount() {
        Account account = mock(Account.class);
        when(account.getAccountNumber()).thenReturn(ACCOUNT_NUMBER);
        return account;
    }

    private MessagingController createMessagingController() {
        MessagingController messagingController = mock(MessagingController.class);
        when(messagingController.isMoveCapable(account)).thenReturn(true);
        return messagingController;
    }

    private NotificationContent createNotificationContent(MessageReference messageReference) {
        return new NotificationContent(messageReference, null, null, null, null, false);
    }

    private NotificationHolder createNotificationHolder(int notificationId, NotificationContent content) {
        return new NotificationHolder(notificationId, content);
    }

    private Notification createNotification() {
        return mock(Notification.class);
    }

    private MessageReference createMessageReference(int number) {
        return new MessageReference("account", 1, String.valueOf(number), null);
    }

    private PendingIntent createFakePendingIntent(int requestCode) {
        return PendingIntent.getActivity(RuntimeEnvironment.application, requestCode, null, 0);
    }

    private ArrayList<MessageReference> createMessageReferenceList() {
        ArrayList<MessageReference> messageReferences = new ArrayList<>();
        messageReferences.add(createMessageReference(1));
        messageReferences.add(createMessageReference(2));

        return messageReferences;
    }

    private NotificationData createNotificationData(ArrayList<MessageReference> messageReferences) {
        NotificationData notificationData = mock(NotificationData.class);
        when(notificationData.getAccount()).thenReturn(account);
        when(notificationData.getAllMessageReferences()).thenReturn(messageReferences);
        return notificationData;
    }

    private Builder verifyExtendWasOnlyCalledOnce() {
        return verify(builder, times(1)).extend(any(Extender.class));
    }

    private void verifyAddAction(int icon, String title, PendingIntent pendingIntent) {
        verify(builder).extend(action(icon, title, pendingIntent));
    }

    private Builder verifyNumberOfActions(int expectedNumberOfActions) {
        return verify(builder).extend(numberOfActions(expectedNumberOfActions));
    }

    private WearableExtender action(int icon, String title, PendingIntent pendingIntent) {
        return argThat(new ActionMatcher(icon, title, pendingIntent));
    }

    private WearableExtender numberOfActions(int expectedNumberOfActions) {
        return argThat(new NumberOfActionsMatcher(expectedNumberOfActions));
    }


    static class ActionMatcher implements ArgumentMatcher<WearableExtender> {
        private int icon;
        private String title;
        private PendingIntent pendingIntent;

        public ActionMatcher(int icon, String title, PendingIntent pendingIntent) {
            this.icon = icon;
            this.title = title;
            this.pendingIntent = pendingIntent;
        }

        @Override
        public boolean matches(WearableExtender argument) {
            for (Action action : argument.getActions()) {
                if (action.icon == icon && action.title.equals(title) && action.actionIntent == pendingIntent) {
                    return true;
                }
            }

            return false;
        }
    }

    static class NumberOfActionsMatcher implements ArgumentMatcher<WearableExtender> {
        private final int expectedNumberOfActions;

        public NumberOfActionsMatcher(int expectedNumberOfActions) {
            this.expectedNumberOfActions = expectedNumberOfActions;
        }

        @Override
        public boolean matches(WearableExtender argument) {
            return argument.getActions().size() == expectedNumberOfActions;
        }
    }

    static class TestWearNotifications extends WearNotifications {
        private final MessagingController messagingController;

        public TestWearNotifications(NotificationHelper notificationHelper, NotificationActionCreator actionCreator,
                                     MessagingController messagingController, NotificationResourceProvider resourceProvider) {
            super(notificationHelper, actionCreator, resourceProvider);
            this.messagingController = messagingController;
        }

        @Override
        MessagingController createMessagingController() {
            return messagingController;
        }
    }
}
