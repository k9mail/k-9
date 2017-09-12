package com.fsck.k9.controller;


import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fsck.k9.Account;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.ImapMessage;
import com.fsck.k9.mail.store.imap.QresyncParamResponse;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.annotation.Config;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollection;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class QresyncExtensionHandlerTest {

    private static final String FOLDER_NAME = "Folder";
    private static final List<String> VANISHED_EARLIER_UIDS = new ArrayList<>(0);
    private static final List<ImapMessage> MODIFIED_MESSAGES = new ArrayList<>(0);
    private static final List<String> EXPUNGED_UIDS = new ArrayList<>(0);
    private static final long SMALLEST_LOCAL_UID = 10L;

    private QresyncExtensionHandler extensionHandler;

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
    private QresyncParamResponse qresyncParamResponse;
    @Mock
    private List<String> expungedUids;
    @Mock
    private MessageDownloader messageDownloader;
    @Mock
    private FlagSyncHelper flagSyncHelper;
    @Mock
    private SyncHelper syncHelper;
    @Captor
    private ArgumentCaptor<List> downloadedMessagesCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        extensionHandler = new QresyncExtensionHandler(syncHelper, flagSyncHelper, controller, messageDownloader);

        when(localFolder.getName()).thenReturn(FOLDER_NAME);
        when(localFolder.getSmallestMessageUid()).thenReturn(SMALLEST_LOCAL_UID);
        when(imapFolder.getName()).thenReturn(FOLDER_NAME);
        when(controller.getListeners()).thenReturn(singleton(listener));
        when(controller.getListeners(listener)).thenReturn(singleton(listener));
        when(syncHelper.getRemoteStart(localFolder, imapFolder)).thenReturn(1);

        when(qresyncParamResponse.getExpungedUids()).thenReturn(VANISHED_EARLIER_UIDS);
        when(qresyncParamResponse.getModifiedMessages()).thenReturn(MODIFIED_MESSAGES);
    }

    @Test
    public void continueSync_withSyncRemoteDeletionsSetToTrue_shouldDeleteLocalCopiesOfDeletedMessages() throws Exception {
        String vanishedUid = "1", expungedUid = "2";
        VANISHED_EARLIER_UIDS.add(vanishedUid);
        EXPUNGED_UIDS.add(expungedUid);
        when(account.syncRemoteDeletions()).thenReturn(true);
        ArgumentCaptor<List> deletedUidsCaptor = ArgumentCaptor.forClass(List.class);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(syncHelper).deleteLocalMessages(deletedUidsCaptor.capture(), eq(account), eq(localFolder),
                eq(controller), eq(listener));
        List<String> deletedUids = deletedUidsCaptor.getValue();
        assertTrue(deletedUids.contains(vanishedUid));
        assertTrue(deletedUids.contains(expungedUid));
    }

    @Test
    public void continueSync_withSyncRemoteDeletionsSetToFalse_shouldNotDeleteLocalCopiesOfDeletedMessages()
            throws Exception {
        EXPUNGED_UIDS.add("1");
        when(account.syncRemoteDeletions()).thenReturn(false);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(syncHelper, never()).deleteLocalMessages(anyCollection(), eq(account), eq(localFolder), eq(controller),
                eq(listener));
    }

    @Test
    public void continueSync_withModifiedMessage_shouldProcessFlags() throws Exception {
        ImapMessage modifiedMessage = addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 1, false);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(flagSyncHelper).processDownloadedFlags(account, localFolder, modifiedMessage);
    }

    @Test
    public void continueSync_withChangesToOutdatedMessage_shouldNotProcessFlags() throws Exception {
        ImapMessage modifiedMessage = addModifiedMessageToResponse(SMALLEST_LOCAL_UID - 1, false);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(flagSyncHelper, never()).processDownloadedFlags(account, localFolder, modifiedMessage);
    }

    @Test
    public void continueSync_withNewMessage_shouldDownloadNewMessage() throws Exception {
        ImapMessage modifiedMessage = addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 1, true);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(messageDownloader).downloadMessages(eq(account), eq(imapFolder), eq(localFolder),
                downloadedMessagesCaptor.capture(), eq(true), eq(false));
        assertTrue(downloadedMessagesCaptor.getValue().contains(modifiedMessage));
    }

    @Test
    public void continueSync_withVisibleLimitNotReached_shouldDownloadOldMessages() throws Exception {
        when(localFolder.getVisibleLimit()).thenReturn(2);
        when(localFolder.getMessageCount()).thenReturn(0);
        when(imapFolder.getMessageCount()).thenReturn(3);
        when(syncHelper.getRemoteStart(localFolder, imapFolder)).thenReturn(2);
        addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 1, true);
        ImapMessage oldMessage = mock(ImapMessage.class);
        when(imapFolder.getMessages(eq(2), eq(2), any(Date.class), any(MessageRetrievalListener.class)))
                .thenReturn(singletonList(oldMessage));

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(messageDownloader).downloadMessages(eq(account), eq(imapFolder), eq(localFolder),
                downloadedMessagesCaptor.capture(), eq(true), eq(true));
        assertTrue(downloadedMessagesCaptor.getValue().contains(oldMessage));
    }

    @Test
    public void continueSync_withVisibleLimitReached_shouldNotDownloadOldMessages() throws Exception {
        when(localFolder.getVisibleLimit()).thenReturn(2);
        when(localFolder.getMessageCount()).thenReturn(2);
        when(imapFolder.getMessageCount()).thenReturn(3);
        ImapMessage newMessage = addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 1, true);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(messageDownloader).downloadMessages(eq(account), eq(imapFolder), eq(localFolder),
                downloadedMessagesCaptor.capture(), eq(true), eq(false));
        List<ImapMessage> downloadedMessages = downloadedMessagesCaptor.getValue();
        assertEquals(downloadedMessages.size(), 1);
        assertEquals(downloadedMessages.get(0), newMessage);
    }

    @Test
    public void continueSync_withNoMoreMessagesInRemoteMailbox_shouldNotDownloadOldMessages() throws Exception {
        when(localFolder.getVisibleLimit()).thenReturn(4);
        when(localFolder.getMessageCount()).thenReturn(3);
        when(imapFolder.getMessageCount()).thenReturn(3);
        ImapMessage newMessage = addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 1, true);

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(messageDownloader).downloadMessages(eq(account), eq(imapFolder), eq(localFolder),
                downloadedMessagesCaptor.capture(), eq(true), eq(false));
        List<ImapMessage> downloadedMessages = downloadedMessagesCaptor.getValue();
        assertEquals(downloadedMessages.size(), 1);
        assertEquals(downloadedMessages.get(0), newMessage);
    }

    @Test
    public void continueSync_withModifiedMessages_shouldNotifyListenersOfHeaderSynchronization() throws Exception {
        addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 2, false);
        addModifiedMessageToResponse(SMALLEST_LOCAL_UID + 3, true);
        when(localFolder.getVisibleLimit()).thenReturn(3);
        when(localFolder.getMessageCount()).thenReturn(1);
        when(imapFolder.getMessageCount()).thenReturn(3);
        when(imapFolder.getMessages(eq(1), eq(1), any(Date.class), any(MessageRetrievalListener.class)))
                .thenReturn(singletonList(mock(ImapMessage.class)));

        extensionHandler.continueSync(account, localFolder, imapFolder, qresyncParamResponse, EXPUNGED_UIDS, listener);

        verify(listener).synchronizeMailboxHeadersStarted(account, FOLDER_NAME);
        verify(listener).synchronizeMailboxHeadersProgress(account, FOLDER_NAME, 1, 1);
        verify(listener).synchronizeMailboxHeadersFinished(account, FOLDER_NAME, 3, 2);
    }

    private ImapMessage addModifiedMessageToResponse(Long uid, final boolean newMessage) throws MessagingException {
        final ImapMessage modifiedMessage = mock(ImapMessage.class);
        when(modifiedMessage.getUid()).thenReturn(Long.toString(uid));

        LocalMessage localMessage = null;
        if (!newMessage) {
            localMessage = mock(LocalMessage.class);
            when(localMessage.isSet(Flag.X_DOWNLOADED_FULL)).thenReturn(true);
        }
        when(localFolder.getMessage(Long.toString(uid))).thenReturn(localMessage);

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                int argIndex = newMessage ? 5 : 6;
                List<Message> messageList = (List) args[argIndex];
                messageList.add(modifiedMessage);
                return null;
            }
        }).when(syncHelper).evaluateMessageForDownload(eq(modifiedMessage), eq(FOLDER_NAME), eq(localFolder),
                eq(imapFolder), eq(account), any(List.class), any(List.class), eq(controller));

        MODIFIED_MESSAGES.add(modifiedMessage);
        return modifiedMessage;
    }
}
