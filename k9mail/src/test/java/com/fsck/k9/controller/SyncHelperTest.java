package com.fsck.k9.controller;


import java.util.ArrayList;
import java.util.List;

import com.fsck.k9.Account;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Folder.FolderType;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.imap.ImapMessage;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static junit.framework.TestCase.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class SyncHelperTest {

    private static final String FOLDER_NAME = "Folder";

    private SyncHelper syncHelper;

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
    private ImapMessage remoteNewMessage;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        syncHelper = SyncHelper.getInstance();

        when(localFolder.getName()).thenReturn(FOLDER_NAME);
        when(remoteFolder.getName()).thenReturn(FOLDER_NAME);
        when(controller.getListeners(listener)).thenReturn(singleton(listener));
    }

    @Test
    public void verifyOrCreateRemoteSpecialFolder_withUnavailableRemoteFolder_shouldTryToCreateRemoteFolder()
            throws Exception {
        when(account.getTrashFolderName()).thenReturn("Trash");
        when(remoteFolder.exists()).thenReturn(false);

        syncHelper.verifyOrCreateRemoteSpecialFolder(account, "Trash", remoteFolder, listener, controller);

        verify(remoteFolder).create(FolderType.HOLDS_MESSAGES);
    }

    @Test
    public void verifyOrCreateRemoteSpecialFolder_withFolderCreationFailed_shouldNotifyListeners() throws Exception {
        String folderName = "Trash";
        when(account.getTrashFolderName()).thenReturn(folderName);
        when(remoteFolder.exists()).thenReturn(false);
        when(remoteFolder.create(FolderType.HOLDS_MESSAGES)).thenReturn(false);

        syncHelper.verifyOrCreateRemoteSpecialFolder(account, folderName, remoteFolder, listener, controller);

        verify(listener).synchronizeMailboxFinished(account, folderName, 0, 0);
    }

    @Test
    public void getRemoteStart() throws Exception {
        when(remoteFolder.getMessageCount()).thenReturn(152);
        when(localFolder.getVisibleLimit()).thenReturn(100);

        int remoteStart = syncHelper.getRemoteStart(localFolder, remoteFolder);

        assertEquals(remoteStart, 53);
    }

    @Test
    public void deleteLocalMessages_withDeletedMessages_shouldNotifyListeners() throws Exception {
        LocalMessage destroyedMessage = mock(LocalMessage.class);
        when(localFolder.getMessagesByUids(singleton("1"))).thenReturn(singletonList(destroyedMessage));

        syncHelper.deleteLocalMessages(singleton("1"), account, localFolder, controller, listener);

        verify(listener).synchronizeMailboxRemovedMessage(account, FOLDER_NAME, destroyedMessage);
    }

    @Test
    public void evaluateMessageForDownload_withoutLocalCopy_shouldAddMessageToUnsyncedMessagesList() throws Exception {
        Message message = createRemoteMessage(false);
        List<Message> unsyncedMessages = new ArrayList<>(0);
        List<Message> syncFlagMessages = new ArrayList<>(0);

        syncHelper.evaluateMessageForDownload(message, FOLDER_NAME, localFolder, remoteFolder, account, unsyncedMessages,
                syncFlagMessages, controller);

        assertEquals(singletonList(message), unsyncedMessages);
    }

    @Test
    public void evaluateMessageForDownload_withLocalCopy_shouldAddMessageToSyncFlagMessagesList() throws Exception {
        Message message = createRemoteMessage(true);
        List<Message> unsyncedMessages = new ArrayList<>(0);
        List<Message> syncFlagMessages = new ArrayList<>(0);

        syncHelper.evaluateMessageForDownload(message, FOLDER_NAME, localFolder, remoteFolder, account, unsyncedMessages,
                syncFlagMessages, controller);

        assertEquals(singletonList(message), syncFlagMessages);
    }

    private Message createRemoteMessage(boolean availableLocally) throws MessagingException {
        String uid = "1";
        Message remoteMessage = mock(Message.class);
        when(remoteMessage.getUid()).thenReturn(uid);
        if (availableLocally) {
            LocalMessage localMessage = mock(LocalMessage.class);
            when(localMessage.isSet(Flag.X_DOWNLOADED_FULL)).thenReturn(true);
            when(localFolder.getMessageUidAndFlags(uid)).thenReturn(localMessage);
        } else {
            when(localFolder.getMessage(uid)).thenReturn(null);
        }
        return remoteMessage;
    }
}
