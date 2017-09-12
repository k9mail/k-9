package com.fsck.k9.controller;


import java.util.List;

import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.K9RobolectricTestRunner;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.FetchProfile.Item;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@SuppressWarnings("unchecked")
@RunWith(K9RobolectricTestRunner.class)
public class FlagSyncHelperTest {

    private Account account;
    private Folder remoteFolder;
    private LocalFolder localFolder;
    private MessagingListener listener;

    private SyncHelper syncHelper;
    private FlagSyncHelper flagSyncHelper;

    @Before
    public void setUp() {
        account = mock(Account.class);
        remoteFolder = mock(Folder.class);
        localFolder = mock(LocalFolder.class);
        Context appContext = ShadowApplication.getInstance().getApplicationContext();
        MessagingController controller = mock(MessagingController.class);
        listener = mock(MessagingListener.class);
        when(controller.getListeners()).thenReturn(singleton(listener));

        syncHelper = mock(SyncHelper.class);
        flagSyncHelper = FlagSyncHelper.newInstance(appContext, controller, syncHelper);
    }

    @Test
    public void refreshLocalMessageFlags_withSupportsFetchingFlagsTrue_shouldFetchAndProcessRemoteFlags()
            throws Exception {
        List<Message> syncFlagMessages = singletonList(createMessage(false));
        FetchProfile flagsProfile = new FetchProfile();
        flagsProfile.add(Item.FLAGS);
        when(remoteFolder.supportsFetchingFlags()).thenReturn(true);

        flagSyncHelper.refreshLocalMessageFlags(account, remoteFolder, localFolder, syncFlagMessages);

        verify(remoteFolder).fetch(syncFlagMessages, flagsProfile, null);
    }

    @Test
    public void refreshLocalMessageFlags_withSupportsFetchingFlagsFalse_shouldNotFetchRemoteFlags() throws Exception {
        List<Message> syncFlagMessages = singletonList(createMessage(false));
        FetchProfile flagsProfile = new FetchProfile();
        flagsProfile.add(Item.FLAGS);
        when(remoteFolder.supportsFetchingFlags()).thenReturn(false);

        flagSyncHelper.refreshLocalMessageFlags(account, remoteFolder, localFolder, syncFlagMessages);

        verify(remoteFolder, never()).fetch(syncFlagMessages, flagsProfile, null);
    }

    @Test
    public void refreshLocalMessageFlags_withDeletedMessages_shouldNotFetchFlagsForDeletedMessages() throws Exception {
        Message nonDeletedMessage = createMessage(false);
        Message deletedMessage = createMessage(true);
        List<Message> syncFlagMessages = asList(nonDeletedMessage, deletedMessage);
        FetchProfile flagsProfile = new FetchProfile();
        flagsProfile.add(Item.FLAGS);
        when(remoteFolder.supportsFetchingFlags()).thenReturn(true);

        flagSyncHelper.refreshLocalMessageFlags(account, remoteFolder, localFolder, syncFlagMessages);

        verify(remoteFolder).fetch(singletonList(nonDeletedMessage), flagsProfile, null);
    }

    @Test
    public void refreshLocalMessageFlags_withListener_shouldCallListener() throws Exception {
        List<Message> syncFlagMessages = singletonList(createMessage(false));
        when(remoteFolder.supportsFetchingFlags()).thenReturn(true);

        flagSyncHelper.refreshLocalMessageFlags(account, remoteFolder, localFolder, syncFlagMessages);

        verify(listener).synchronizeMailboxProgress(account, localFolder.getName(), 1, 1);
    }

    @Test
    public void processDownloadedFlags_withModifiedRemoteMessage_shouldUpdateLocalMessage() throws Exception {
        Message remoteMessage = createMessage(false);
        LocalMessage localMessage = createLocalMessageInFolder(false, true);
        when(remoteMessage.isSet(Flag.SEEN)).thenReturn(true);
        when(localMessage.isSet(Flag.SEEN)).thenReturn(false);

        flagSyncHelper.processDownloadedFlags(account, localFolder, remoteMessage);

        verify(localMessage).setFlag(Flag.SEEN, true);
    }

    @Test
    public void processDownloadedFlags_withDeletedLocalMessage_shouldNotUpdateLocalMessage() throws Exception {
        Message remoteMessage = createMessage(false);
        LocalMessage localMessage = createLocalMessageInFolder(false, true);
        when(remoteMessage.isSet(Flag.SEEN)).thenReturn(true);
        when(localMessage.isSet(Flag.SEEN)).thenReturn(false);
        when(localMessage.isSet(Flag.DELETED)).thenReturn(true);

        flagSyncHelper.processDownloadedFlags(account, localFolder, remoteMessage);

        verify(localMessage, never()).setFlag(any(Flag.class), anyBoolean());
    }

    @Test
    public void processDownloadedFlags_withDeletedRemoteMessageAndSyncRemoteDeletionsTrue_shouldDeleteLocalMessage()
            throws Exception {
        Message remoteMessage = createMessage(false);
        LocalMessage localMessage = createLocalMessageInFolder(false, true);
        when(remoteMessage.isSet(Flag.DELETED)).thenReturn(true);
        when(localMessage.getFolder()).thenReturn(localFolder);
        when(localFolder.syncRemoteDeletions()).thenReturn(true);

        flagSyncHelper.processDownloadedFlags(account, localFolder, remoteMessage);

        verify(localMessage).setFlag(Flag.DELETED, true);
    }

    @Test
    public void processDownloadedFlags_withDeletedRemoteMessageAndSyncRemoteDeletionsFalse_shouldNotDeleteLocalMessage()
            throws Exception {
        Message remoteMessage = createMessage(false);
        LocalMessage localMessage = createLocalMessageInFolder(false, true);
        when(remoteMessage.isSet(Flag.DELETED)).thenReturn(true);
        when(localMessage.getFolder()).thenReturn(localFolder);
        when(localFolder.syncRemoteDeletions()).thenReturn(false);

        flagSyncHelper.processDownloadedFlags(account, localFolder, remoteMessage);

        verify(localMessage, never()).setFlag(Flag.DELETED, true);
    }

    @Test
    public void processDownloadedFlags_withModifiedRemoteMessageAndSuppressedLocalMessage_shouldNotifyListener()
            throws Exception {
        Message remoteMessage = createMessage(false);
        LocalMessage localMessage = createLocalMessageInFolder(true, true);
        when(remoteMessage.isSet(Flag.SEEN)).thenReturn(true);
        when(localMessage.isSet(Flag.SEEN)).thenReturn(false);

        flagSyncHelper.processDownloadedFlags(account, localFolder, remoteMessage);

        verify(listener).synchronizeMailboxRemovedMessage(account, localFolder.getName(), localMessage);
    }

    @Test
    public void processDownloadedFlags_withModifiedRemoteMessageAndNonSuppressedLocalMessage_shouldNotNotifyListener()
            throws Exception {
        Message remoteMessage = createMessage(false);
        LocalMessage localMessage = createLocalMessageInFolder(false, true);
        when(remoteMessage.isSet(Flag.SEEN)).thenReturn(true);
        when(localMessage.isSet(Flag.SEEN)).thenReturn(false);

        flagSyncHelper.processDownloadedFlags(account, localFolder, remoteMessage);

        verify(listener, never()).synchronizeMailboxRemovedMessage(account, localFolder.getName(), localMessage);
    }

    private Message createMessage(boolean deletedFlagSet) {
        Message message = mock(Message.class);
        when(message.isSet(Flag.DELETED)).thenReturn(deletedFlagSet);
        return message;
    }

    private LocalMessage createLocalMessageInFolder(boolean suppressed, boolean notifiedOf) throws MessagingException {
        LocalMessage localMessage = mock(LocalMessage.class);
        when(localFolder.getMessage(anyString())).thenReturn(localMessage);
        when(syncHelper.isMessageSuppressed(eq(localMessage), any(Context.class))).thenReturn(suppressed);
        when(syncHelper.shouldNotifyForMessage(eq(account), eq(localFolder), eq(localMessage), any(Contacts.class)))
                .thenReturn(notifiedOf);
        return localMessage;
    }
}
