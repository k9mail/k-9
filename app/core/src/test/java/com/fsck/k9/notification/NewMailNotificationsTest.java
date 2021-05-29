package com.fsck.k9.notification;


import java.io.ByteArrayInputStream;

import android.app.Notification;
import androidx.core.app.NotificationManagerCompat;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.K9.NotificationHideSubject;
import com.fsck.k9.K9RobolectricTest;
import com.fsck.k9.controller.MessageReference;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mailstore.LocalMessage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class NewMailNotificationsTest extends K9RobolectricTest {
    private static final int ACCOUNT_NUMBER = 23;

    private Account account;
    private TestNewMailNotifications newMailNotifications;
    private NotificationManagerCompat notificationManager;
    private NotificationContentCreator contentCreator;
    private DeviceNotifications deviceNotifications;
    private WearNotifications wearNotifications;


    @Before
    public void setUp() throws Exception {
        account = createAccount();

        notificationManager = createNotificationManager();
        NotificationHelper notificationHelper = createNotificationHelper(notificationManager);
        contentCreator = createNotificationContentCreator();
        deviceNotifications = createDeviceNotifications();
        wearNotifications = createWearNotifications();

        newMailNotifications = new TestNewMailNotifications(notificationHelper, contentCreator, deviceNotifications,
                wearNotifications);
    }

    @Test
    public void testAddNewMailNotification() throws Exception {
        int notificationIndex = 0;
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        whenAddingContentReturn(content, AddNotificationResult.newNotification(holder));
        Notification wearNotification = createNotification();
        Notification summaryNotification = createNotification();
        addToWearNotifications(holder, wearNotification);
        addToDeviceNotifications(summaryNotification);

        newMailNotifications.addNewMailNotification(account, message, 42);

        int wearNotificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager).notify(wearNotificationId, wearNotification);
        verify(notificationManager).notify(summaryNotificationId, summaryNotification);
    }

    @Test
    public void testAddNewMailNotificationWithCancelingExistingNotification() throws Exception {
        int notificationIndex = 0;
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        whenAddingContentReturn(content, AddNotificationResult.replaceNotification(holder));
        Notification wearNotification = createNotification();
        Notification summaryNotification = createNotification();
        addToWearNotifications(holder, wearNotification);
        addToDeviceNotifications(summaryNotification);

        newMailNotifications.addNewMailNotification(account, message, 42);

        int wearNotificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager).notify(wearNotificationId, wearNotification);
        verify(notificationManager).cancel(wearNotificationId);
        verify(notificationManager).notify(summaryNotificationId, summaryNotification);
    }

    @Test
    public void testAddNewMailNotificationWithPrivacyModeEnabled() throws Exception {
        enablePrivacyMode();
        int notificationIndex = 0;
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        whenAddingContentReturn(content, AddNotificationResult.newNotification(holder));
        Notification wearNotification = createNotification();
        addToDeviceNotifications(wearNotification);

        newMailNotifications.addNewMailNotification(account, message, 42);

        int wearNotificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager, never()).notify(eq(wearNotificationId), any(Notification.class));
        verify(notificationManager).notify(summaryNotificationId, wearNotification);
    }

    @Test
    public void testAddNewMailNotificationTwice() throws Exception {
        int notificationIndexOne = 0;
        int notificationIndexTwo = 1;
        LocalMessage messageOne = createLocalMessage();
        LocalMessage messageTwo = createLocalMessage();
        NotificationContent contentOne = createNotificationContent();
        NotificationContent contentTwo = createNotificationContent();
        NotificationHolder holderOne = createNotificationHolder(contentOne, notificationIndexOne);
        NotificationHolder holderTwo = createNotificationHolder(contentTwo, notificationIndexTwo);
        addToNotificationContentCreator(messageOne, contentOne);
        addToNotificationContentCreator(messageTwo, contentTwo);
        whenAddingContentReturn(contentOne, AddNotificationResult.newNotification(holderOne));
        whenAddingContentReturn(contentTwo, AddNotificationResult.newNotification(holderTwo));
        Notification wearNotificationOne = createNotification();
        Notification wearNotificationTwo = createNotification();
        Notification summaryNotification = createNotification();
        addToWearNotifications(holderOne, wearNotificationOne);
        addToWearNotifications(holderTwo, wearNotificationTwo);
        addToDeviceNotifications(summaryNotification);

        newMailNotifications.addNewMailNotification(account, messageOne, 42);
        newMailNotifications.addNewMailNotification(account, messageTwo, 42);

        int wearNotificationIdOne = NotificationIds.getNewMailStackedNotificationId(account, notificationIndexOne);
        int wearNotificationIdTwo = NotificationIds.getNewMailStackedNotificationId(account, notificationIndexTwo);
        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager).notify(wearNotificationIdOne, wearNotificationOne);
        verify(notificationManager).notify(wearNotificationIdTwo, wearNotificationTwo);
        verify(notificationManager, times(2)).notify(summaryNotificationId, summaryNotification);
    }

    @Test
    public void testRemoveNewMailNotificationWithoutNotificationData() throws Exception {
        MessageReference messageReference = createMessageReference(1);

        newMailNotifications.removeNewMailNotification(account, messageReference);

        verify(notificationManager, never()).cancel(anyInt());
    }

    @Test
    public void testRemoveNewMailNotificationWithUnknownMessageReference() throws Exception {
        enablePrivacyMode();
        MessageReference messageReference = createMessageReference(1);
        int notificationIndex = 0;
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        whenAddingContentReturn(content, AddNotificationResult.newNotification(holder));
        Notification summaryNotification = createNotification();
        addToDeviceNotifications(summaryNotification);
        newMailNotifications.addNewMailNotification(account, message, 23);
        whenRemovingContentReturn(messageReference, RemoveNotificationResult.unknownNotification());

        newMailNotifications.removeNewMailNotification(account, messageReference);

        verify(notificationManager, never()).cancel(anyInt());
    }

    @Test
    public void testRemoveNewMailNotification() throws Exception {
        enablePrivacyMode();
        MessageReference messageReference = createMessageReference(1);
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        whenAddingContentReturn(content, AddNotificationResult.newNotification(holder));
        Notification summaryNotification = createNotification();
        addToDeviceNotifications(summaryNotification);
        newMailNotifications.addNewMailNotification(account, message, 23);
        whenRemovingContentReturn(messageReference, RemoveNotificationResult.cancelNotification(notificationId));

        newMailNotifications.removeNewMailNotification(account, messageReference);

        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager).cancel(notificationId);
        verify(notificationManager, times(2)).notify(summaryNotificationId, summaryNotification);
    }

    @Test
    public void testRemoveNewMailNotificationClearingAllNotifications() throws Exception {
        MessageReference messageReference = createMessageReference(1);
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        whenAddingContentReturn(content, AddNotificationResult.newNotification(holder));
        Notification summaryNotification = createNotification();
        addToDeviceNotifications(summaryNotification);
        newMailNotifications.addNewMailNotification(account, message, 23);
        whenRemovingContentReturn(messageReference, RemoveNotificationResult.cancelNotification(notificationId));
        when(newMailNotifications.notificationData.getNewMessagesCount()).thenReturn(0);
        setActiveNotificationIds();

        newMailNotifications.removeNewMailNotification(account, messageReference);

        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager).cancel(notificationId);
        verify(notificationManager).cancel(summaryNotificationId);
    }

    @Test
    public void testRemoveNewMailNotificationWithCreateNotification() throws Exception {
        MessageReference messageReference = createMessageReference(1);
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        LocalMessage message = createLocalMessage();
        NotificationContent contentOne = createNotificationContent();
        NotificationContent contentTwo = createNotificationContent();
        NotificationHolder holderOne = createNotificationHolder(contentOne, notificationIndex);
        NotificationHolder holderTwo = createNotificationHolder(contentTwo, notificationIndex);
        addToNotificationContentCreator(message, contentOne);
        whenAddingContentReturn(contentOne, AddNotificationResult.newNotification(holderOne));
        Notification summaryNotification = createNotification();
        addToDeviceNotifications(summaryNotification);
        Notification wearNotificationOne = createNotification();
        Notification wearNotificationTwo = createNotification();
        addToWearNotifications(holderOne, wearNotificationOne);
        addToWearNotifications(holderTwo, wearNotificationTwo);
        newMailNotifications.addNewMailNotification(account, message, 23);
        whenRemovingContentReturn(messageReference, RemoveNotificationResult.createNotification(holderTwo));

        newMailNotifications.removeNewMailNotification(account, messageReference);

        int summaryNotificationId = NotificationIds.getNewMailSummaryNotificationId(account);
        verify(notificationManager).cancel(notificationId);
        verify(notificationManager).notify(notificationId, wearNotificationTwo);
        verify(notificationManager, times(2)).notify(summaryNotificationId, summaryNotification);
    }

    @Test
    public void testClearNewMailNotificationsWithoutNotificationData() throws Exception {
        newMailNotifications.clearNewMailNotifications(account);

        verify(notificationManager, never()).cancel(anyInt());
    }

    @Test
    public void testClearNewMailNotifications() throws Exception {
        int notificationIndex = 0;
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, notificationIndex);
        LocalMessage message = createLocalMessage();
        NotificationContent content = createNotificationContent();
        NotificationHolder holder = createNotificationHolder(content, notificationIndex);
        addToNotificationContentCreator(message, content);
        setActiveNotificationIds(notificationId);
        whenAddingContentReturn(content, AddNotificationResult.newNotification(holder));
        newMailNotifications.addNewMailNotification(account, message, 3);

        newMailNotifications.clearNewMailNotifications(account);

        verify(notificationManager).cancel(notificationId);
        verify(notificationManager).cancel(NotificationIds.getNewMailSummaryNotificationId(account));
    }

    @Test
    public void testMuteMailingLists() throws Exception {
        final String MESSAGE = "From: lena@example.com\n"
                + "List-Id: \"Lena's Personal Joke List\" <lenas-jokes.localhost>\n"
                + "Date: Apr 1st Wed Apr  1 23:44:53 CEST 2020\n"
                + "\n"
                + "Hi!\n";

        MimeMessage message = new MimeMessage();
        message.parse(new ByteArrayInputStream(MESSAGE.getBytes()));

        final Account account = new Account("uuid");
        account.setMuteMailingLists(true);

        assertTrue(account.isNotificationSuppressed(message));
    }

    @Test
    public void testMutedSender() throws Exception {
        final String BOB_ADDRESS = "bob@example.com";

        LocalMessage message = createLocalMessage();
        when(message.getSender()).thenReturn(new Address[] { new Address(BOB_ADDRESS) });

        final Account account = new Account("uuid");
        account.setMutedSenders(BOB_ADDRESS);

        assertTrue(account.isNotificationSuppressed(message));
    }

    @Test
    public void testMuteIfSentTo() throws Exception {
        final String ALICE_ADDRESS = "alice@example.com";
        final String LIST_ADDRESS = "list@example.com";
        final String ME_ADDRESS = "me@example.com";
        final String MESSAGE = "From: Alice <" + ALICE_ADDRESS + ">\n"
                + "To: Myself <" + ME_ADDRESS + ">\n"
                + "Cc: Somebody Else <someone@example.com>, The List <" + LIST_ADDRESS + ">\n"
                + "Date: Apr 1st Wed Apr  1 23:44:53 CEST 2020\n"
                + "\n"
                + "Hi!\n";

        MimeMessage message = new MimeMessage();
        message.parse(new ByteArrayInputStream(MESSAGE.getBytes()));

        final Account account = new Account("uuid");

        account.setMuteIfSentTo(LIST_ADDRESS + ";" + ME_ADDRESS);
        assertTrue(account.isNotificationSuppressed(message));

        account.setMuteIfSentTo(ME_ADDRESS);
        assertTrue(account.isNotificationSuppressed(message));

        account.setMuteIfSentTo(LIST_ADDRESS);
        assertTrue(account.isNotificationSuppressed(message));

        account.setMuteIfSentTo(ALICE_ADDRESS);
        assertFalse(account.isNotificationSuppressed(message));
    }

    private Account createAccount() {
        Account account = mock(Account.class);
        when(account.getAccountNumber()).thenReturn(ACCOUNT_NUMBER);
        return account;
    }

    private LocalMessage createLocalMessage() {
        return mock(LocalMessage.class);
    }

    private NotificationContent createNotificationContent() {
        return new NotificationContent(null, null, null, null, null, false);
    }

    private NotificationHolder createNotificationHolder(NotificationContent content, int index) {
        int notificationId = NotificationIds.getNewMailStackedNotificationId(account, index);
        return new NotificationHolder(notificationId, content);
    }

    private NotificationManagerCompat createNotificationManager() {
        return mock(NotificationManagerCompat.class);
    }

    private NotificationHelper createNotificationHelper(NotificationManagerCompat notificationManager) {
        NotificationHelper notificationHelper = mock(NotificationHelper.class);
        when(notificationHelper.getNotificationManager()).thenReturn(notificationManager);
        return notificationHelper;
    }

    private NotificationContentCreator createNotificationContentCreator() {
        return mock(NotificationContentCreator.class);
    }

    private void addToNotificationContentCreator(LocalMessage message, NotificationContent content) {
        when(contentCreator.createFromMessage(account, message)).thenReturn(content);
    }

    private DeviceNotifications createDeviceNotifications() {
        return mock(DeviceNotifications.class);
    }

    private void addToDeviceNotifications(Notification notificationToReturn) {
        when(deviceNotifications.buildSummaryNotification(
                        eq(account), eq(newMailNotifications.notificationData), anyBoolean())
        ).thenReturn(notificationToReturn);
    }

    private Notification createNotification() {
        return mock(Notification.class);
    }

    private WearNotifications createWearNotifications() {
        return mock(WearNotifications.class);
    }

    private MessageReference createMessageReference(int number) {
        return new MessageReference("account", 1, String.valueOf(number), null);
    }

    private void addToWearNotifications(NotificationHolder notificationHolder, Notification notificationToReturn) {
        when(wearNotifications.buildStackedNotification(account, notificationHolder)).thenReturn(notificationToReturn);
    }

    private void whenAddingContentReturn(NotificationContent content, AddNotificationResult result) {
        NotificationData notificationData = newMailNotifications.notificationData;
        when(notificationData.addNotificationContent(content)).thenReturn(result);

        int newCount = notificationData.getNewMessagesCount() + 1;
        when(notificationData.getNewMessagesCount()).thenReturn(newCount);
    }

    private void whenRemovingContentReturn(MessageReference messageReference, RemoveNotificationResult result) {
        NotificationData notificationData = newMailNotifications.notificationData;
        when(notificationData.removeNotificationForMessage(messageReference)).thenReturn(result);
    }

    private void setActiveNotificationIds(int... notificationIds) {
        NotificationData notificationData = newMailNotifications.notificationData;
        when(notificationData.getActiveNotificationIds()).thenReturn(notificationIds);
    }

    private void enablePrivacyMode() {
        K9.setNotificationHideSubject(NotificationHideSubject.ALWAYS);
    }

    static class TestNewMailNotifications extends NewMailNotifications {

        public final NotificationData notificationData;

        TestNewMailNotifications(NotificationHelper notificationHelper, NotificationContentCreator contentCreator,
                DeviceNotifications deviceNotifications, WearNotifications wearNotifications) {
            super(notificationHelper, contentCreator, deviceNotifications, wearNotifications);
            notificationData = mock(NotificationData.class);
        }

        @Override
        NotificationData createNotificationData(Account account, int unreadMessageCount) {
            return notificationData;
        }
    }
}
