package com.fsck.k9.controller;


import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.cache.EmailProviderCache;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Folder.FolderType;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalFolder.MoreMessages;
import com.fsck.k9.mailstore.LocalMessage;
import timber.log.Timber;


class SyncHelper {

    private static SyncHelper INSTANCE;

    private SyncHelper() {
    }

    static SyncHelper getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SyncHelper();
        }
        return INSTANCE;
    }

    /*
     * If the folder is a "special" folder we need to see if it exists
     * on the remote server. It if does not exist we'll try to create it. If we
     * can't create we'll abort. This will happen on every single Pop3 folder as
     * designed and on Imap folders during error conditions. This allows us
     * to treat Pop3 and Imap the same in this code.
     */
    boolean verifyOrCreateRemoteSpecialFolder(Account account, String folderName, Folder remoteFolder,
            MessagingListener listener, MessagingController controller) throws MessagingException {
        if ((folderName.equals(account.getTrashFolderName()) || folderName.equals(account.getSentFolderName()) ||
                folderName.equals(account.getDraftsFolderName())) && !remoteFolder.exists() &&
                !remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFinished(account, folderName, 0, 0);
            }

            Timber.i("Done synchronizing folder %s", folderName);
            return false;
        }
        return true;
    }

    int getRemoteStart(LocalFolder localFolder, Folder remoteFolder) throws MessagingException {
        int remoteMessageCount = remoteFolder.getMessageCount();

        int visibleLimit = localFolder.getVisibleLimit();
        if (visibleLimit < 0) {
            visibleLimit = K9.DEFAULT_VISIBLE_LIMIT;
        }

        int remoteStart;
        /* Message numbers start at 1.  */
        if (visibleLimit > 0) {
            remoteStart = Math.max(0, remoteMessageCount - visibleLimit) + 1;
        } else {
            remoteStart = 1;
        }
        return remoteStart;
    }

    void deleteLocalMessages(Collection<String> deletedMessageUids, Account account, LocalFolder localFolder,
            MessagingController controller, MessagingListener listener) throws IOException, MessagingException {
        String folderName = localFolder.getName();
        Timber.v("SYNC: Deleting %d messages in the local cache that are not present in the remote mailbox for folder %s",
                deletedMessageUids.size(), folderName);

        if (!deletedMessageUids.isEmpty()) {
            List<LocalMessage> destroyMessages = localFolder.getMessagesByUids(deletedMessageUids);
            localFolder.destroyMessages(destroyMessages);

            for (Message destroyMessage : destroyMessages) {
                for (MessagingListener l : controller.getListeners(listener)) {
                    l.synchronizeMailboxRemovedMessage(account, folderName, destroyMessage);
                }
            }
        }
    }

    boolean isMessageSuppressed(LocalMessage message, Context context) {
        long messageId = message.getId();
        long folderId = message.getFolder().getId();

        EmailProviderCache cache = EmailProviderCache.getCache(message.getFolder().getAccountUuid(), context);
        return cache.isMessageHidden(messageId, folderId);
    }

    boolean modeMismatch(Account.FolderMode aMode, Folder.FolderClass fMode) {
        if (aMode == Account.FolderMode.NONE
                || (aMode == Account.FolderMode.FIRST_CLASS &&
                fMode != Folder.FolderClass.FIRST_CLASS)
                || (aMode == Account.FolderMode.FIRST_AND_SECOND_CLASS &&
                fMode != Folder.FolderClass.FIRST_CLASS &&
                fMode != Folder.FolderClass.SECOND_CLASS)
                || (aMode == Account.FolderMode.NOT_SECOND_CLASS &&
                fMode == Folder.FolderClass.SECOND_CLASS)) {
            return true;
        } else {
            return false;
        }
    }

    void evaluateMessageForDownload(final Message message, final String folderName, final LocalFolder localFolder,
            final Folder remoteFolder, final Account account, final List<Message> unsyncedMessages,
            final List< Message> syncFlagMessages, MessagingController controller) throws MessagingException {
        if (message.isSet(Flag.DELETED)) {
            Timber.v("Message with uid %s is marked as deleted", message.getUid());

            syncFlagMessages.add(message);
            return;
        }

        Message localMessage = localFolder.getMessageUidAndFlags(message.getUid());

        if (localMessage == null) {
            if (!message.isSet(Flag.X_DOWNLOADED_FULL) && !message.isSet(Flag.X_DOWNLOADED_PARTIAL)) {
                Timber.v("Message with uid %s has not yet been downloaded", message.getUid());

                unsyncedMessages.add(message);
            } else {
                Timber.v("Message with uid %s is partially or fully downloaded", message.getUid());

                // Store the updated message locally
                localFolder.appendMessages(Collections.singletonList(message));

                localMessage = localFolder.getMessage(message.getUid());

                localMessage.setFlag(Flag.X_DOWNLOADED_FULL, message.isSet(Flag.X_DOWNLOADED_FULL));
                localMessage.setFlag(Flag.X_DOWNLOADED_PARTIAL, message.isSet(Flag.X_DOWNLOADED_PARTIAL));

                for (MessagingListener l : controller.getListeners()) {
                    if (!localMessage.isSet(Flag.SEEN)) {
                        l.synchronizeMailboxNewMessage(account, folderName, localMessage);
                    }
                }
            }
        } else if (!localMessage.isSet(Flag.DELETED)) {
            Timber.v("Message with uid %s is present in the local store", message.getUid());

            if (!localMessage.isSet(Flag.X_DOWNLOADED_FULL) && !localMessage.isSet(Flag.X_DOWNLOADED_PARTIAL)) {
                Timber.v("Message with uid %s is not downloaded, even partially; trying again", message.getUid());

                unsyncedMessages.add(message);
            } else {
                String newPushState = remoteFolder.getNewPushState(localFolder.getPushState(), message);
                if (newPushState != null) {
                    localFolder.setPushState(newPushState);
                }
                syncFlagMessages.add(message);
            }
        } else {
            Timber.v("Local copy of message with uid %s is marked as deleted", message.getUid());
        }
    }

    boolean shouldNotifyForMessage(Account account, LocalFolder localFolder, Message message, Contacts contacts) {
        // If we don't even have an account name, don't show the notification.
        // (This happens during initial account setup)
        if (account.getName() == null) {
            return false;
        }

        // Do not notify if the user does not have notifications enabled or if the message has
        // been read.
        if (!account.isNotifyNewMail() || message.isSet(Flag.SEEN)) {
            return false;
        }

        Account.FolderMode aDisplayMode = account.getFolderDisplayMode();
        Account.FolderMode aNotifyMode = account.getFolderNotifyNewMailMode();
        Folder.FolderClass fDisplayClass = localFolder.getDisplayClass();
        Folder.FolderClass fNotifyClass = localFolder.getNotifyClass();

        if (modeMismatch(aDisplayMode, fDisplayClass)) {
            // Never notify a folder that isn't displayed
            return false;
        }

        if (modeMismatch(aNotifyMode, fNotifyClass)) {
            // Do not notify folders in the wrong class
            return false;
        }

        // If the account is a POP3 account and the message is older than the oldest message we've
        // previously seen, then don't notify about it.
        if (account.getStoreUri().startsWith("pop3") &&
                message.olderThan(new Date(account.getLatestOldMessageSeenTime()))) {
            return false;
        }

        // No notification for new messages in Trash, Drafts, Spam or Sent folder.
        // But do notify if it's the INBOX (see issue 1817).
        Folder folder = message.getFolder();
        if (folder != null) {
            String folderName = folder.getName();
            if (!account.getInboxFolderName().equals(folderName) &&
                    (account.getTrashFolderName().equals(folderName)
                            || account.getDraftsFolderName().equals(folderName)
                            || account.getSpamFolderName().equals(folderName)
                            || account.getSentFolderName().equals(folderName))) {
                return false;
            }
        }

        if (message.getUid() != null && localFolder.getLastUid() != null) {
            try {
                Integer messageUid = Integer.parseInt(message.getUid());
                if (messageUid <= localFolder.getLastUid()) {
                    Timber.d("Message uid is %s, max message uid is %s. Skipping notification.",
                            messageUid, localFolder.getLastUid());
                    return false;
                }
            } catch (NumberFormatException e) {
                // Nothing to be done here.
            }
        }

        // Don't notify if the sender address matches one of our identities and the user chose not
        // to be notified for such messages.
        if (account.isAnIdentity(message.getFrom()) && !account.isNotifySelfNewMail()) {
            return false;
        }

        if (account.isNotifyContactsMailOnly() && !contacts.isAnyInContacts(message.getFrom())) {
            return false;
        }

        return true;
    }

    void updateMoreMessages(Account account, LocalFolder localFolder, Folder remoteFolder) throws IOException,
            MessagingException {
        final Date earliestDate = account.getEarliestPollDate();
        int remoteStart = getRemoteStart(localFolder, remoteFolder);

        if (remoteStart == 1) {
            localFolder.setMoreMessages(MoreMessages.FALSE);
        } else {
            boolean moreMessagesAvailable = remoteFolder.areMoreMessagesAvailable(remoteStart, earliestDate);

            MoreMessages newMoreMessages = (moreMessagesAvailable) ? MoreMessages.TRUE : MoreMessages.FALSE;
            localFolder.setMoreMessages(newMoreMessages);
        }
    }
}
