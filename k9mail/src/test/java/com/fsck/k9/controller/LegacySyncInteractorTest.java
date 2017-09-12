package com.fsck.k9.controller;


import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.fsck.k9.Account;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.RemoteStore;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.notification.NotificationController;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static junit.framework.TestCase.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class LegacySyncInteractorTest {

    private static final String FOLDER_NAME = "Folder";

    private LegacySyncInteractor syncInteractor;

    @Mock
    private Account account;
    @Mock
    private LocalFolder localFolder;
    @Mock
    private Folder remoteFolder;
    @Mock
    private MessagingListener listener;
    @Mock
    private MessagingController controller;
    @Mock
    private MessageDownloader messageDownloader;
    @Mock
    private NotificationController notificationController;
    @Captor
    private ArgumentCaptor<List<Message>> messageListCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        syncInteractor = new LegacySyncInteractor(controller, messageDownloader, notificationController);

        configureLocalStoreWithFolder();
        configureRemoteStoreWithFolder();
        when(controller.getListeners(listener)).thenReturn(singleton(listener));
    }

    @Test
    public void performSync_withOneMessageInRemoteFolder_shouldFinishWithoutError() throws Exception {
        messageCountInRemoteFolder(1);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFinished(account, FOLDER_NAME, 1, 0);
    }

    @Test
    public void performSync_withEmptyRemoteFolder_shouldFinishWithoutError() throws Exception {
        messageCountInRemoteFolder(0);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFinished(account, FOLDER_NAME, 0, 0);
    }

    @Test
    public void performSync_withNegativeMessageCountInRemoteFolder_shouldFinishWithError() throws Exception {
        messageCountInRemoteFolder(-1);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFailed(account, FOLDER_NAME,
                "Exception: Message count -1 for folder Folder");
    }

    @Test
    public void performSync_shouldOpenRemoteFolderFromStore() throws Exception {
        messageCountInRemoteFolder(1);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(remoteFolder).open(Folder.OPEN_MODE_RW);
    }

    @Test
    public void performSync_withAccountPolicySetToExpungeOnPoll_shouldExpungeRemoteFolder() throws Exception {
        messageCountInRemoteFolder(1);
        when(account.getExpungePolicy()).thenReturn(Account.Expunge.EXPUNGE_ON_POLL);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(remoteFolder).expunge();
    }

    @Test
    public void performSync_withAccountPolicySetToExpungeManually_shouldNotExpungeRemoteFolder() throws Exception {
        messageCountInRemoteFolder(1);
        when(account.getExpungePolicy()).thenReturn(Account.Expunge.EXPUNGE_MANUALLY);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(remoteFolder, never()).expunge();
    }

    @Test
    public void performSync_withAccountSetToSyncRemoteDeletions_shouldDeleteLocalCopiesOfDeletedMessages() throws Exception {
        messageCountInRemoteFolder(0);
        LocalMessage localCopyOfRemoteDeletedMessage = mock(LocalMessage.class);
        when(account.syncRemoteDeletions()).thenReturn(true);
        when(localFolder.getAllMessagesAndEffectiveDates()).thenReturn(singletonMap("1", 0L));
        when(localFolder.getMessagesByUids(any(List.class)))
                .thenReturn(Collections.singletonList(localCopyOfRemoteDeletedMessage));

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).destroyMessages(messageListCaptor.capture());
        assertEquals(localCopyOfRemoteDeletedMessage, messageListCaptor.getValue().get(0));
    }

    @Test
    public void performSync_withAccountSetToSyncRemoteDeletions_shouldNotDeleteLocalCopiesOfExistingMessagesAfterEarliestPollDate()
            throws Exception {
        messageCountInRemoteFolder(1);
        Date dateOfEarliestPoll = new Date();
        LocalMessage localMessage = localMessageWithCopyOnServer();
        when(account.syncRemoteDeletions()).thenReturn(true);
        when(account.getEarliestPollDate()).thenReturn(dateOfEarliestPoll);
        when(localMessage.olderThan(dateOfEarliestPoll)).thenReturn(false);
        when(localFolder.getMessages(null)).thenReturn(Collections.singletonList(localMessage));

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder, never()).destroyMessages(messageListCaptor.capture());
    }

    @Test
    public void performSync_withAccountSetToSyncRemoteDeletions_shouldDeleteLocalCopiesOfExistingMessagesBeforeEarliestPollDate()
            throws Exception {
        messageCountInRemoteFolder(1);
        LocalMessage localMessage = localMessageWithCopyOnServer();
        Date dateOfEarliestPoll = new Date();
        when(account.syncRemoteDeletions()).thenReturn(true);
        when(account.getEarliestPollDate()).thenReturn(dateOfEarliestPoll);
        when(localMessage.olderThan(dateOfEarliestPoll)).thenReturn(true);
        when(localFolder.getAllMessagesAndEffectiveDates()).thenReturn(singletonMap("1", 0L));
        when(localFolder.getMessagesByUids(any(List.class))).thenReturn(Collections.singletonList(localMessage));

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).destroyMessages(messageListCaptor.capture());
        assertEquals(localMessage, messageListCaptor.getValue().get(0));
    }

    @Test
    public void performSync_withAccountSetNotToSyncRemoteDeletions_shouldNotDeleteLocalCopiesOfMessages()
            throws Exception {
        messageCountInRemoteFolder(0);
        LocalMessage remoteDeletedMessage = mock(LocalMessage.class);
        when(account.syncRemoteDeletions()).thenReturn(false);
        when(localFolder.getMessages(null)).thenReturn(Collections.singletonList(remoteDeletedMessage));

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder, never()).destroyMessages(messageListCaptor.capture());
    }

    @Test
    public void performSync_shouldDownloadUnsyncedMessagesAndSyncLocalMessageFlags() throws Exception {
        messageCountInRemoteFolder(1);
        localMessageWithCopyOnServer();

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(messageDownloader).downloadMessages(eq(account), eq(remoteFolder), eq(localFolder),
                messageListCaptor.capture(), eq(true), eq(true));
        assertEquals(messageListCaptor.getValue().size(), 1);
    }

    private void configureLocalStoreWithFolder() throws MessagingException {
        LocalStore localStore = mock(LocalStore.class);
        when(account.getLocalStore()).thenReturn(localStore);
        when(localStore.getFolder(FOLDER_NAME)).thenReturn(localFolder);
        when(localFolder.getName()).thenReturn(FOLDER_NAME);
    }

    private void configureRemoteStoreWithFolder() throws MessagingException {
        RemoteStore remoteStore = mock(RemoteStore.class);
        when(account.getRemoteStore()).thenReturn(remoteStore);
        when(remoteStore.getFolder(FOLDER_NAME)).thenReturn(remoteFolder);
        when(remoteFolder.getName()).thenReturn(FOLDER_NAME);
    }

    private void messageCountInRemoteFolder(int value) throws MessagingException {
        when(remoteFolder.getMessageCount()).thenReturn(value);
    }

    private LocalMessage localMessageWithCopyOnServer() throws MessagingException {
        String messageUid = "UID";
        Message remoteMessage = mock(Message.class);
        LocalMessage localMessage = mock(LocalMessage.class);

        when(remoteMessage.getUid()).thenReturn(messageUid);
        when(localMessage.getUid()).thenReturn(messageUid);
        when(remoteFolder.getMessages(anyInt(), anyInt(), any(Date.class), any(MessageRetrievalListener.class)))
                .thenReturn(Collections.singletonList(remoteMessage));
        return localMessage;
    }
}
