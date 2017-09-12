package com.fsck.k9.controller;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessageRetrievalListener;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.ImapMessage;
import com.fsck.k9.mailstore.LocalFolder;
import timber.log.Timber;


class NonQresyncExtensionHandler {

    private final SyncHelper syncHelper;
    private final FlagSyncHelper flagSyncHelper;
    private final MessagingController controller;
    private final MessageDownloader messageDownloader;

    NonQresyncExtensionHandler(SyncHelper syncHelper, FlagSyncHelper flagSyncHelper, MessagingController controller,
            MessageDownloader messageDownloader) {
        this.syncHelper = syncHelper;
        this.flagSyncHelper = flagSyncHelper;
        this.controller = controller;
        this.messageDownloader = messageDownloader;
    }

    int continueSync(Account account, LocalFolder localFolder, ImapFolder imapFolder, MessagingListener listener)
            throws MessagingException, IOException {
        Map<String, Long> localUidMap = localFolder.getAllMessagesAndEffectiveDates();
        String folderName = localFolder.getName();
        final List<ImapMessage> remoteMessages = new ArrayList<>();
        Map<String, ImapMessage> remoteUidMap = new HashMap<>();

        int remoteMessageCount = imapFolder.getMessageCount();
        int remoteStart = syncHelper.getRemoteStart(localFolder, imapFolder);

        Timber.v("SYNC: About to fetch UIDs for messages %d through %d in folder %s",
                remoteStart, remoteMessageCount, folderName);

        findRemoteMessagesToDownload(account, imapFolder, localUidMap, remoteMessages, remoteUidMap, remoteStart,
                listener);

        if (account.syncRemoteDeletions()) {
            List<String> deletedUids = findDeletedMessageUids(localUidMap, remoteUidMap);
            syncHelper.deleteLocalMessages(deletedUids, account, localFolder, controller, listener);
        }

        // noinspection UnusedAssignment, free memory early?
        localUidMap = null;

        List<Message> newMessages = new ArrayList<>();
        List<Message> syncFlagMessages = new ArrayList<>();

        for (Message message : remoteMessages) {
            syncHelper.evaluateMessageForDownload(message, folderName, localFolder, imapFolder, account, newMessages,
                    syncFlagMessages, controller);
        }

        if (localFolder.isCachedHighestModSeqValid() && imapFolder.supportsModSeq() && K9.shouldUseCondstore()) {
            if (localFolder.getHighestModSeq() == imapFolder.getHighestModSeq()) {
                Timber.v("SYNC: HIGHESTMODSEQ has not changed, skipping flag synchronization");
            } else {
                downloadChangedMessageFlagsUsingCondstore(account, localFolder, imapFolder, syncFlagMessages);
            }
        } else {
            flagSyncHelper.refreshLocalMessageFlags(account, imapFolder, localFolder, syncFlagMessages);
        }

        return messageDownloader.downloadMessages(account, imapFolder, localFolder, newMessages, true, true);
    }

    private void findRemoteMessagesToDownload(Account account, ImapFolder imapFolder,
            Map<String, Long> localUidMap, List<ImapMessage> remoteMessages,
            Map<String, ImapMessage> remoteUidMap, int remoteStart, MessagingListener listener)
            throws MessagingException {
        String folderName = imapFolder.getName();
        final Date earliestDate = account.getEarliestPollDate();
        long earliestTimestamp = earliestDate != null ? earliestDate.getTime() : 0L;
        final AtomicInteger headerProgress = new AtomicInteger(0);
        int remoteMessageCount = imapFolder.getMessageCount();

        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxHeadersStarted(account, folderName);
        }

        List<ImapMessage> remoteMessageArray = new ArrayList<>();
        if (remoteMessageCount > 0) {
            remoteMessageArray = imapFolder.getMessages(remoteStart, remoteMessageCount, earliestDate, null);
        }

        int messageCount = remoteMessageArray.size();

        for (ImapMessage thisMess : remoteMessageArray) {
            headerProgress.incrementAndGet();
            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxHeadersProgress(account, folderName, headerProgress.get(), messageCount);
            }
            Long localMessageTimestamp = localUidMap.get(thisMess.getUid());
            if (localMessageTimestamp == null || localMessageTimestamp >= earliestTimestamp) {
                remoteMessages.add(thisMess);
                remoteUidMap.put(thisMess.getUid(), thisMess);
            }
        }

        Timber.v("SYNC: Fetched %d message UIDs for folder %s", remoteUidMap.size(), folderName);

        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxHeadersFinished(account, folderName, headerProgress.get(), remoteUidMap.size());
        }
    }

    private List<String> findDeletedMessageUids(Map<String, Long> localUidMap, Map<String, ImapMessage> remoteUidMap) {
        List<String> deletedMessageUids = new ArrayList<>();
        for (String localMessageUid : localUidMap.keySet()) {
            if (remoteUidMap.get(localMessageUid) == null) {
                deletedMessageUids.add(localMessageUid);
            }
        }
        return deletedMessageUids;
    }

    private void downloadChangedMessageFlagsUsingCondstore(final Account account, final LocalFolder localFolder,
            ImapFolder imapFolder, List<Message> messages) throws MessagingException {

        if (messages.size() != 0) {
            Timber.v("SYNC: Fetching and syncing flags for %d local messages using CONDSTORE", messages.size());
            long cachedHighestModSeq = localFolder.getHighestModSeq();
            List<ImapMessage> imapMessages = new ArrayList<>(messages.size());
            for (Message message : messages) {
                if (message instanceof ImapMessage) {
                    imapMessages.add((ImapMessage) message);
                }
            }
            imapFolder.fetchChangedMessageFlagsUsingCondstore(imapMessages, cachedHighestModSeq,
                    new MessageRetrievalListener<ImapMessage>() {
                        @Override
                        public void messageStarted(String uid, int number, int ofTotal) {

                        }

                        @Override
                        public void messageFinished(ImapMessage message, int number, int ofTotal) {
                            try {
                                flagSyncHelper.processDownloadedFlags(account, localFolder, message);
                            } catch (MessagingException e) {
                                Timber.e(e, "Error while synchronizing flags using CONDSTORE.");
                                controller.addErrorMessage(account, null, e);
                            }

                        }

                        @Override
                        public void messagesFinished(int total) {

                        }
                    });
        }
    }
}
