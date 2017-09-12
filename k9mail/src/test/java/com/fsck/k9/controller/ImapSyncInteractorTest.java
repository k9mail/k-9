package com.fsck.k9.controller;


import java.util.List;

import com.fsck.k9.Account;
import com.fsck.k9.Account.Expunge;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.RemoteStore;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.QresyncParamResponse;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.notification.NotificationController;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RW;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.mockito.Matchers.anyCollection;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class ImapSyncInteractorTest {

    private static final String FOLDER_NAME = "Folder";
    private static final long CACHED_UID_VALIDITY = 1L;
    private static final long CURRENT_UID_VALIDITY = 2L;
    private static final long CACHED_HIGHEST_MOD_SEQ = 3L;
    private static final long CURRENT_HIGHEST_MOD_SEQ = 4L;
    private static final List<String> LOCAL_MESSAGE_UIDS = singletonList("1");

    private ImapSyncInteractor syncInteractor;

    @Mock
    private Account account;
    @Mock
    private LocalFolder localFolder;
    @Mock
    private ImapFolder imapFolder;
    @Mock
    private MessagingListener listener;
    @Mock
    private MessagingController controller;
    @Mock
    private FlagSyncHelper flagSyncHelper;
    @Mock
    private MessageDownloader messageDownloader;
    @Mock
    private NotificationController notificationController;
    @Mock
    private SyncHelper syncHelper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        syncInteractor = new ImapSyncInteractor(syncHelper, flagSyncHelper, controller, messageDownloader,
                notificationController);

        configureLocalStoreWithFolder();
        configureLocalFolder();
        configureImapStoreWithFolder(imapFolder);
        configureImapFolder();
        when(syncHelper.verifyOrCreateRemoteSpecialFolder(account, FOLDER_NAME, imapFolder, listener, controller))
                .thenReturn(true);
        when(controller.getListeners(listener)).thenReturn(singleton(listener));
    }

    @Test
    public void performSync_withProcessPendingCommandsSynchronousThrowingException_shouldFinishWithError()
            throws Exception {
        doThrow(MessagingException.class).when(controller).processPendingCommandsSynchronous(account);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFailed(eq(account), eq(FOLDER_NAME), anyString());
    }
    @Test
    public void performSync_withImapFolder_shouldFinishWithoutError() throws Exception {
        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFinished(eq(account), eq(FOLDER_NAME), anyInt(), anyInt());
    }

    @Test
    public void performSync_withNonImapFolder_shouldFinishWithError() throws Exception {
        configureImapStoreWithFolder(mock(Folder.class));

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFailed(eq(account), eq(FOLDER_NAME), anyString());
    }

    @Test
    public void performSync_withRemoteFolderCreationFailed_shouldExitWithoutOpeningRemoteFolder() throws Exception {
        when(syncHelper.verifyOrCreateRemoteSpecialFolder(account, FOLDER_NAME, imapFolder, listener, controller))
                .thenReturn(false);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder, never()).openUsingQresyncParam(anyInt(), anyLong(), anyLong());
    }

    @Test
    public void performSync_withValidCachedUidValidityAndHighestModSeq_shouldOpenFolderUsingQresyncParams()
            throws Exception {
        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder).openUsingQresyncParam(OPEN_MODE_RW, CACHED_UID_VALIDITY, CACHED_HIGHEST_MOD_SEQ);
    }
    @Test
    public void performSync_withoutCachedUidValidity_shouldOpenImapFolderNormally() throws Exception {
        when(localFolder.hasCachedUidValidity()).thenReturn(false);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder).open(OPEN_MODE_RW);
    }

    @Test
    public void performSync_withInvalidCachedHighestModSeq_shouldOpenImapFolderNormally() throws Exception {
        when(localFolder.isCachedHighestModSeqValid()).thenReturn(false);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder).open(OPEN_MODE_RW);
    }

    @Test
    public void performSync_withQresyncEnabledAndAccountSetToExpungeOnPoll_shouldExpungeRemoteFolder()
            throws Exception {
        when(imapFolder.openUsingQresyncParam(OPEN_MODE_RW, CACHED_UID_VALIDITY, CACHED_HIGHEST_MOD_SEQ))
                .thenReturn(mock(QresyncParamResponse.class));
        when(account.getExpungePolicy()).thenReturn(Expunge.EXPUNGE_ON_POLL);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder).expungeUsingQresync();
    }

    @Test
    public void performSync_withoutQresyncEnabledAndAccountSetToExpungeOnPoll_shouldExpungeRemoteFolder()
            throws Exception {
        when(imapFolder.openUsingQresyncParam(OPEN_MODE_RW, CACHED_UID_VALIDITY, CACHED_HIGHEST_MOD_SEQ))
                .thenReturn(null);
        when(account.getExpungePolicy()).thenReturn(Expunge.EXPUNGE_ON_POLL);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder).expunge();
    }

    @Test
    public void performSync_withAccountNotSetToExpungeOnPoll_shouldNotExpungeRemoteFolder() throws Exception {
        when(account.getExpungePolicy()).thenReturn(Expunge.EXPUNGE_MANUALLY);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(imapFolder, never()).expunge();
        verify(imapFolder, never()).expungeUsingQresync();
    }

    @Test
    public void performSync_withNegativeMessageCount_shouldFinishWithError() throws Exception {
        when(imapFolder.getMessageCount()).thenReturn(-1);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxFailed(eq(account), eq(FOLDER_NAME), anyString());
    }

    @Test
    public void performSync_withDifferentUidValidity_shouldDeleteAllLocallyStoredMessages() throws Exception {
        when(imapFolder.getUidValidity()).thenReturn(CURRENT_UID_VALIDITY);
        List<LocalMessage> localMessages = configureLocalFolderWithSingleMessage();

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).destroyMessages(localMessages);
    }

    @Test
    public void performSync_withDifferentUidValidity_shouldNotifyListenersOfDeletion() throws Exception {
        when(imapFolder.getUidValidity()).thenReturn(CURRENT_UID_VALIDITY);
        List<LocalMessage> localMessages = configureLocalFolderWithSingleMessage();

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(listener).synchronizeMailboxRemovedMessage(account, FOLDER_NAME, localMessages.get(0));
    }

    @Test
    public void performSync_withDifferentUidValidity_shouldClearHighestModSeq() throws Exception {
        when(imapFolder.getUidValidity()).thenReturn(CURRENT_UID_VALIDITY);
        configureLocalFolderWithSingleMessage();

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder, atLeastOnce()).invalidateHighestModSeq();
    }

    @Test
    public void performSync_withEqualUidValidity_shouldNotDeleteAllLocallyStoredMessages() throws Exception {
        when(imapFolder.getUidValidity()).thenReturn(CACHED_UID_VALIDITY);
        List<LocalMessage> localMessages = configureLocalFolderWithSingleMessage();

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder, never()).destroyMessages(localMessages);
    }

    @Test
    public void performSync_withoutCachedUidValidity_shouldNotDeleteAllLocallyStoredMessages() throws Exception {
        when(localFolder.hasCachedUidValidity()).thenReturn(false);
        when(imapFolder.getUidValidity()).thenReturn(CURRENT_UID_VALIDITY);
        List<LocalMessage> localMessages = configureLocalFolderWithSingleMessage();

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder, never()).destroyMessages(localMessages);
    }

    @Test
    public void performSync_shouldUpdateCachedUidValidity() throws Exception {
        when(imapFolder.getUidValidity()).thenReturn(CURRENT_UID_VALIDITY);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).setUidValidity(CURRENT_UID_VALIDITY);
    }

    @Test
    public void performSync_withFolderSupportingModSeqs_shouldUpdateCachedHighestModSeq() throws Exception {
        when(imapFolder.supportsModSeq()).thenReturn(true);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).setHighestModSeq(CURRENT_HIGHEST_MOD_SEQ);
    }

    @Test
    public void performSync_withFolderNotSupportingModSeqs_shouldInvalidateCachedHighestModSeq() throws Exception {
        when(imapFolder.supportsModSeq()).thenReturn(false);

        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).invalidateHighestModSeq();
    }

    @Test
    public void performSync_afterCompletion_shouldCloseFolders() throws Exception {
        syncInteractor.performSync(account, FOLDER_NAME, listener);

        verify(localFolder).close();
        verify(imapFolder).close();
    }

    private void configureLocalStoreWithFolder() throws MessagingException {
        LocalStore localStore = mock(LocalStore.class);
        when(account.getLocalStore()).thenReturn(localStore);
        when(localStore.getFolder(FOLDER_NAME)).thenReturn(localFolder);
    }

    private void configureLocalFolder() throws Exception {
        when(localFolder.getName()).thenReturn(FOLDER_NAME);
        when(localFolder.hasCachedUidValidity()).thenReturn(true);
        when(localFolder.getUidValidity()).thenReturn(CACHED_UID_VALIDITY);
        when(localFolder.isCachedHighestModSeqValid()).thenReturn(true);
        when(localFolder.getHighestModSeq()).thenReturn(CACHED_HIGHEST_MOD_SEQ);
    }

    private void configureImapStoreWithFolder(Folder folder) throws MessagingException {
        RemoteStore remoteStore = mock(RemoteStore.class);
        when(account.getRemoteStore()).thenReturn(remoteStore);
        when(remoteStore.getFolder(FOLDER_NAME)).thenReturn(folder);
    }

    private void configureImapFolder() throws Exception {
        when(imapFolder.getName()).thenReturn(FOLDER_NAME);
        when(imapFolder.getUidValidity()).thenReturn(CACHED_UID_VALIDITY);
        when(imapFolder.getHighestModSeq()).thenReturn(CURRENT_HIGHEST_MOD_SEQ);
    }

    private List<LocalMessage> configureLocalFolderWithSingleMessage() throws Exception {
        when(localFolder.getAllMessagesAndEffectiveDates()).thenReturn(singletonMap(LOCAL_MESSAGE_UIDS.get(0), 1L));
        List<LocalMessage> localMessages = singletonList(mock(LocalMessage.class));
        when(localFolder.getMessagesByUids(anyCollection())).thenReturn(localMessages);
        return localMessages;
    }
}
