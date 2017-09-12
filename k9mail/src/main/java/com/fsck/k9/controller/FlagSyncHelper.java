package com.fsck.k9.controller;


import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import android.content.Context;

import com.fsck.k9.Account;
import com.fsck.k9.activity.MessageReference;
import com.fsck.k9.helper.Contacts;
import com.fsck.k9.mail.FetchProfile;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.notification.NotificationController;
import timber.log.Timber;


class FlagSyncHelper {

    private static final Set<Flag> SYNC_FLAGS = EnumSet.of(Flag.SEEN, Flag.FLAGGED, Flag.ANSWERED, Flag.FORWARDED);

    private final Context context;
    private final MessagingController controller;
    private final Contacts contacts;
    private final NotificationController notificationController;
    private final SyncHelper syncHelper;

    public static FlagSyncHelper newInstance(Context context, MessagingController controller, SyncHelper syncHelper) {
        Context appContext = context.getApplicationContext();
        Contacts contacts = Contacts.getInstance(context);
        NotificationController notificationController = NotificationController.newInstance(appContext);
        return new FlagSyncHelper(appContext, controller, contacts, notificationController, syncHelper);
    }

    private FlagSyncHelper(Context context, MessagingController controller, Contacts contacts,
            NotificationController notificationController, SyncHelper syncHelper) {
        this.context = context;
        this.controller = controller;
        this.contacts = contacts;
        this.notificationController = notificationController;
        this.syncHelper = syncHelper;
    }

    void refreshLocalMessageFlags(final Account account, final Folder remoteFolder, final LocalFolder localFolder,
            List<Message> syncFlagMessages) throws MessagingException {
        final String folderName = remoteFolder.getName();
        if (remoteFolder.supportsFetchingFlags()) {
            Timber.d("SYNC: About to sync flags for %d remote messages for folder %s", syncFlagMessages.size(),
                    folderName);

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.FLAGS);

            List<Message> undeletedMessages = new LinkedList<>();
            for (Message message : syncFlagMessages) {
                if (!message.isSet(Flag.DELETED)) {
                    undeletedMessages.add(message);
                }
            }

            remoteFolder.fetch(undeletedMessages, fp, null);

            final AtomicInteger progress = new AtomicInteger(0);
            int todo = syncFlagMessages.size();
            for (Message remoteMessage : syncFlagMessages) {
                processDownloadedFlags(account, localFolder, remoteMessage);
                progress.incrementAndGet();
                for (MessagingListener l : controller.getListeners()) {
                    l.synchronizeMailboxProgress(account, folderName, progress.get(), todo);
                }
            }
        }
    }

    void processDownloadedFlags(Account account, LocalFolder localFolder, Message remoteMessage)
            throws MessagingException {
        String folderName = localFolder.getName();
        LocalMessage localMessage = localFolder.getMessage(remoteMessage.getUid());
        boolean messageChanged = syncFlags(localMessage, remoteMessage);
        if (messageChanged) {
            boolean shouldBeNotifiedOf = false;
            if (localMessage.isSet(Flag.DELETED) || syncHelper.isMessageSuppressed(localMessage, context)) {
                for (MessagingListener l : controller.getListeners()) {
                    l.synchronizeMailboxRemovedMessage(account, folderName, localMessage);
                }
            } else {
                if (syncHelper.shouldNotifyForMessage(account, localFolder, localMessage, contacts)) {
                    shouldBeNotifiedOf = true;
                }
            }

            // we're only interested in messages that need removing
            if (!shouldBeNotifiedOf) {
                MessageReference messageReference = localMessage.makeMessageReference();
                notificationController.removeNewMailNotification(account, messageReference);
            }
        }
    }

    private boolean syncFlags(LocalMessage localMessage, Message remoteMessage) throws MessagingException {
        boolean messageChanged = false;
        if (localMessage == null || localMessage.isSet(Flag.DELETED)) {
            return false;
        }
        if (remoteMessage.isSet(Flag.DELETED)) {
            if (localMessage.getFolder().syncRemoteDeletions()) {
                localMessage.setFlag(Flag.DELETED, true);
                messageChanged = true;
            }
        } else {
            for (Flag flag : SYNC_FLAGS) {
                if (remoteMessage.isSet(flag) != localMessage.isSet(flag)) {
                    localMessage.setFlag(flag, remoteMessage.isSet(flag));
                    messageChanged = true;
                }
            }
        }
        return messageChanged;
    }
}
