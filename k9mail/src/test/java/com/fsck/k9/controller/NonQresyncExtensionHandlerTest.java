package com.fsck.k9.controller;


import java.util.Date;
import java.util.List;

import com.fsck.k9.Account;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.ImapMessage;
import com.fsck.k9.mailstore.LocalFolder;
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

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class NonQresyncExtensionHandlerTest {

    private static final String FOLDER_NAME = "Folder";
    private static final String NEW_MESSAGE_UID = "2";
    private static final String OLD_MESSAGE_UID = "1";

    private NonQresyncExtensionHandler extensionHandler;

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
    private ImapMessage remoteNewMessage;
    @Mock
    private ImapMessage remoteOldMessage;
    @Mock
    private MessageDownloader messageDownloader;
    @Mock
    private FlagSyncHelper flagSyncHelper;
    @Mock
    private SyncHelper syncHelper;
    @Captor
    private ArgumentCaptor<List<String>> deletedUidsCaptor;
    @Captor
    private ArgumentCaptor<List<ImapMessage>> condstoreFlagSyncCaptor;
    @Captor
    private ArgumentCaptor<List<Message>> syncFlagCaptor;
    @Captor
    private ArgumentCaptor<List<ImapMessage>> downloadMessagesCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        extensionHandler = new NonQresyncExtensionHandler(syncHelper, flagSyncHelper, controller, messageDownloader);

        configureLocalFolder();
        configureImapFolder();
        configureRemainingMocks();
    }

    @Test
    public void continueSync_shouldNotifyListenersOfHeaderSynchronization() throws Exception {
        when(account.syncRemoteDeletions()).thenReturn(true);

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(listener).synchronizeMailboxHeadersStarted(account, FOLDER_NAME);
        verify(listener).synchronizeMailboxHeadersProgress(account, FOLDER_NAME, 1, 2);
        verify(listener).synchronizeMailboxHeadersProgress(account, FOLDER_NAME, 2, 2);
        verify(listener).synchronizeMailboxHeadersFinished(account, FOLDER_NAME, 2, 2);
    }

    @Test
    public void continueSync_withSyncRemoteDeletionsSetToTrue_shouldDeleteLocalCopiesOfDeletedMessages()
            throws Exception {
        when(account.syncRemoteDeletions()).thenReturn(true);
        when(imapFolder.getMessages(eq(1), eq(2), any(Date.class), any(MessageRetrievalListener.class)))
                .thenReturn(singletonList(remoteNewMessage));

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(syncHelper).deleteLocalMessages(deletedUidsCaptor.capture(), eq(account), eq(localFolder),
                eq(controller), eq(listener));
        assertEquals(deletedUidsCaptor.getValue(), singletonList(OLD_MESSAGE_UID));
    }

    @Test
    public void continueSync_withSyncRemoteDeletionsSetToFalse_shouldNotDeleteLocalCopiesOfDeletedMessages()
            throws Exception {
        when(account.syncRemoteDeletions()).thenReturn(false);
        when(imapFolder.getMessages(eq(1), eq(2), any(Date.class), any(MessageRetrievalListener.class)))
                .thenReturn(singletonList(remoteNewMessage));

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(syncHelper, never()).deleteLocalMessages(anyList(), eq(account), eq(localFolder), eq(controller),
                eq(listener));
    }

    @Test
    public void continueSync_withCondstoreEnabledAndHighestModSeqChanged_shouldFetchChangedMessageFlagsUsingCondstore()
            throws Exception {
        when(localFolder.isCachedHighestModSeqValid()).thenReturn(true);
        when(imapFolder.supportsModSeq()).thenReturn(true);
        when(localFolder.getHighestModSeq()).thenReturn(1L);
        when(imapFolder.getHighestModSeq()).thenReturn(2L);

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(imapFolder).fetchChangedMessageFlagsUsingCondstore(condstoreFlagSyncCaptor.capture(), anyLong(),
                any(MessageRetrievalListener.class));
        assertEquals(condstoreFlagSyncCaptor.getValue(), singletonList(remoteOldMessage));
    }

    @Test
    public void continueSync_withCondstoreEnabledAndHighestModSeqNotChanged_shouldNotFetchChangedMessageFlags()
            throws Exception {
        when(localFolder.isCachedHighestModSeqValid()).thenReturn(true);
        when(imapFolder.supportsModSeq()).thenReturn(true);
        when(localFolder.getHighestModSeq()).thenReturn(1L);
        when(imapFolder.getHighestModSeq()).thenReturn(1L);

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(imapFolder, never()).fetchChangedMessageFlagsUsingCondstore(anyList(), anyLong(),
                any(MessageRetrievalListener.class));
    }

    @Test
    public void continueSync_withoutCondstore_shouldFetchFlagsForAllMessages() throws Exception {
        when(localFolder.isCachedHighestModSeqValid()).thenReturn(false);

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(flagSyncHelper).refreshLocalMessageFlags(eq(account), eq(imapFolder), eq(localFolder),
                syncFlagCaptor.capture());
        assertEquals(syncFlagCaptor.getValue(), singletonList(remoteOldMessage));
    }

    @Test
    public void continueSync_withFolderNotSupportingModSeqs_shouldFetchFlagsForAllMessages() throws Exception {
        when(localFolder.isCachedHighestModSeqValid()).thenReturn(true);
        when(imapFolder.supportsModSeq()).thenReturn(false);

        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(flagSyncHelper).refreshLocalMessageFlags(eq(account), eq(imapFolder), eq(localFolder),
                syncFlagCaptor.capture());
        assertEquals(syncFlagCaptor.getValue(), singletonList(remoteOldMessage));
    }

    @Test
    public void continueSync_shouldDownloadNewMessages() throws Exception {
        extensionHandler.continueSync(account, localFolder, imapFolder, listener);

        verify(messageDownloader).downloadMessages(eq(account), eq(imapFolder), eq(localFolder),
                downloadMessagesCaptor.capture(), eq(true), eq(true));
        assertEquals(downloadMessagesCaptor.getValue(), singletonList(remoteNewMessage));
    }

    private void configureLocalFolder() throws MessagingException {
        when(localFolder.getName()).thenReturn(FOLDER_NAME);
        when(localFolder.getMessage(NEW_MESSAGE_UID)).thenReturn(null);
        when(localFolder.getAllMessagesAndEffectiveDates()).thenReturn(singletonMap(OLD_MESSAGE_UID, 0L));
    }

    private void configureImapFolder() throws MessagingException {
        when(imapFolder.getName()).thenReturn(FOLDER_NAME);
        when(imapFolder.getMessageCount()).thenReturn(2);
        when(imapFolder.getMessages(eq(1), eq(2), any(Date.class), any(MessageRetrievalListener.class)))
                .thenReturn(asList(remoteNewMessage, remoteOldMessage));
    }

    private void configureRemainingMocks() throws MessagingException {
        when(remoteNewMessage.getUid()).thenReturn(NEW_MESSAGE_UID);
        when(remoteOldMessage.getUid()).thenReturn(OLD_MESSAGE_UID);

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                List<Message> unsyncedMessages = (List) args[5];
                unsyncedMessages.add(remoteNewMessage);
                return null;
            }
        }).when(syncHelper).evaluateMessageForDownload(eq(remoteNewMessage), eq(FOLDER_NAME), eq(localFolder),
                eq(imapFolder), eq(account), any(List.class), any(List.class), eq(controller));

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                List<Message> flagSyncMessages = (List) args[6];
                flagSyncMessages.add(remoteOldMessage);
                return null;
            }
        }).when(syncHelper).evaluateMessageForDownload(eq(remoteOldMessage), eq(FOLDER_NAME), eq(localFolder),
                eq(imapFolder), eq(account), any(List.class), any(List.class), eq(controller));

        when(controller.getListeners(listener)).thenReturn(singleton(listener));
        when(syncHelper.getRemoteStart(localFolder, imapFolder)).thenReturn(1);
        when(account.getDisplayCount()).thenReturn(2);
    }
}
