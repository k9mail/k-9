package com.fsck.k9.controller;


import java.util.Date;
import java.util.List;

import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.AccountStats;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
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
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLog;

import static java.util.Collections.singletonList;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class MessageDownloaderTest {

    private static final String FOLDER_NAME = "Folder";
    private static final int MAXIMUM_SMALL_MESSAGE_SIZE = 1000;

    private MessageDownloader messageDownloader;

    @Mock
    private Account account;
    @Mock
    private Folder remoteFolder;
    @Mock
    private LocalFolder localFolder;
    @Mock
    private MessagingListener listener;
    @Mock
    private MessagingController controller;
    @Mock
    private SyncHelper syncHelper;
    @Mock
    private Message unsyncedRemoteMessage;
    @Captor
    private ArgumentCaptor<FetchProfile> fetchProfileCaptor;

    @Before
    public void setUp() throws MessagingException {
        ShadowLog.stream = System.out;
        MockitoAnnotations.initMocks(this);
        Context context = ShadowApplication.getInstance().getApplicationContext();

        messageDownloader = MessageDownloader.newInstance(context, controller, syncHelper);

        when(account.getStats(any(Context.class))).thenReturn(mock(AccountStats.class));
        when(account.getMaximumAutoDownloadMessageSize()).thenReturn(MAXIMUM_SMALL_MESSAGE_SIZE);
        when(localFolder.getName()).thenReturn(FOLDER_NAME);
        when(remoteFolder.getName()).thenReturn(FOLDER_NAME);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Message remoteMessage = invocation.getArgumentAt(0, Message.class);
                List<Message> unsyncedMessages = invocation.getArgumentAt(5, List.class);
                unsyncedMessages.add(remoteMessage);
                return null;
            }
        }).when(syncHelper).evaluateMessageForDownload(any(Message.class), eq(FOLDER_NAME), eq(localFolder),
                eq(remoteFolder), eq(account), any(List.class), any(List.class), eq(controller));
    }

    @Test
    public void downloadMessages_withAccountSupportingFetchingFlags_shouldFetchUnsychronizedMessagesEnvelopeAndFlags()
            throws Exception {
        when(remoteFolder.supportsFetchingFlags()).thenReturn(true);

        messageDownloader.downloadMessages(account, remoteFolder, localFolder, singletonList(unsyncedRemoteMessage), true,
                true);

        verify(remoteFolder, atLeastOnce()).fetch(anyList(), fetchProfileCaptor.capture(),
                any(MessageRetrievalListener.class));
        assertTrue(fetchProfileCaptor.getAllValues().get(0).contains(FetchProfile.Item.FLAGS));
        assertTrue(fetchProfileCaptor.getAllValues().get(0).contains(FetchProfile.Item.ENVELOPE));
        assertEquals(2, fetchProfileCaptor.getAllValues().get(0).size());
    }

    @Test
    public void downloadMessages_withAccountNotSupportingFetchingFlags_shouldFetchUnsychronizedMessagesEnvelope()
            throws Exception {
        when(remoteFolder.supportsFetchingFlags()).thenReturn(false);

        messageDownloader.downloadMessages(account, remoteFolder, localFolder, singletonList(unsyncedRemoteMessage), true,
                true);

        verify(remoteFolder, atLeastOnce()).fetch(any(List.class), fetchProfileCaptor.capture(),
                any(MessageRetrievalListener.class));
        assertEquals(1, fetchProfileCaptor.getAllValues().get(0).size());
        assertTrue(fetchProfileCaptor.getAllValues().get(0).contains(FetchProfile.Item.ENVELOPE));
    }

    @Test
    public void downloadMessages_withUnsyncedNewSmallMessage_shouldFetchBodyOfSmallMessage()
            throws Exception {
        Message smallMessage = buildSmallNewMessage();
        when(remoteFolder.supportsFetchingFlags()).thenReturn(false);
        respondToFetchEnvelopesWithMessage(smallMessage);

        messageDownloader.downloadMessages(account, remoteFolder, localFolder, singletonList(smallMessage), true,
                true);

        verify(remoteFolder, atLeast(2)).fetch(any(List.class), fetchProfileCaptor.capture(),
                any(MessageRetrievalListener.class));
        assertEquals(1, fetchProfileCaptor.getAllValues().get(1).size());
        assertTrue(fetchProfileCaptor.getAllValues().get(1).contains(FetchProfile.Item.BODY));
    }

    @Test
    public void downloadMessages_withUnsyncedNewLargeMessage_shouldFetchStructureAndLimitedBodyOfLargeMessage()
            throws Exception {
        final Message largeMessage = buildLargeNewMessage();
        when(remoteFolder.supportsFetchingFlags()).thenReturn(false);
        respondToFetchEnvelopesWithMessage(largeMessage);
        when(localFolder.appendMessages(anyList())).then(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                when(localFolder.getMessage(anyString())).thenReturn(mock(LocalMessage.class));
                return null;
            }
        });
        when(localFolder.getMessage(anyString())).thenReturn(mock(LocalMessage.class));

        messageDownloader.downloadMessages(account, remoteFolder, localFolder, singletonList(largeMessage), true,
                true);

        //TODO: Don't bother fetching messages of a size we don't have
        verify(remoteFolder, atLeast(4)).fetch(any(List.class), fetchProfileCaptor.capture(),
                any(MessageRetrievalListener.class));
        assertEquals(1, fetchProfileCaptor.getAllValues().get(2).size());
        assertEquals(FetchProfile.Item.STRUCTURE, fetchProfileCaptor.getAllValues().get(2).get(0));
        assertEquals(1, fetchProfileCaptor.getAllValues().get(3).size());
        assertEquals(FetchProfile.Item.BODY_SANE, fetchProfileCaptor.getAllValues().get(3).get(0));
    }

    private Message buildSmallNewMessage() {
        Message message = mock(Message.class);
        when(message.olderThan(any(Date.class))).thenReturn(false);
        when(message.getSize()).thenReturn((long) MAXIMUM_SMALL_MESSAGE_SIZE);
        return message;
    }

    private Message buildLargeNewMessage() {
        Message message = mock(Message.class);
        when(message.olderThan(any(Date.class))).thenReturn(false);
        when(message.getSize()).thenReturn((long) (MAXIMUM_SMALL_MESSAGE_SIZE + 1));
        return message;
    }

    private void respondToFetchEnvelopesWithMessage(final Message message) throws MessagingException {
        doAnswer(new Answer() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                FetchProfile fetchProfile = (FetchProfile) invocation.getArguments()[1];
                if (invocation.getArguments()[2] != null) {
                    MessageRetrievalListener listener = (MessageRetrievalListener) invocation.getArguments()[2];
                    if (fetchProfile.contains(FetchProfile.Item.ENVELOPE)) {
                        listener.messageStarted("UID", 1, 1);
                        listener.messageFinished(message, 1, 1);
                        listener.messagesFinished(1);
                    }
                }
                return null;
            }
        }).when(remoteFolder).fetch(any(List.class), any(FetchProfile.class), any(MessageRetrievalListener.class));
    }
}
