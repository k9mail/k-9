
package com.fsck.k9;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import android.content.Context;
import android.text.TextUtils;

import com.fsck.k9.backend.api.SyncConfig.ExpungePolicy;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.NetworkType;
import com.fsck.k9.mail.ServerSettings;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mailstore.StorageManager;
import com.fsck.k9.mailstore.StorageManager.StorageProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


/**
 * Account stores all of the settings for a single account defined by the user. Each account is defined by a UUID.
 */
public class Account implements BaseAccount {
    /**
     * Fixed name of outbox - not actually displayed.
     */
    public static final String OUTBOX_NAME = "Outbox";

    public enum Expunge {
        EXPUNGE_IMMEDIATELY,
        EXPUNGE_MANUALLY,
        EXPUNGE_ON_POLL;

        public ExpungePolicy toBackendExpungePolicy() {
            switch (this) {
                case EXPUNGE_IMMEDIATELY: return ExpungePolicy.IMMEDIATELY;
                case EXPUNGE_MANUALLY: return ExpungePolicy.MANUALLY;
                case EXPUNGE_ON_POLL: return ExpungePolicy.ON_POLL;
            }

            throw new AssertionError("Unhandled case: " + this.name());
        }
    }

    public enum DeletePolicy {
        NEVER(0),
        SEVEN_DAYS(1),
        ON_DELETE(2),
        MARK_AS_READ(3);

        public final int setting;

        DeletePolicy(int setting) {
            this.setting = setting;
        }

        public static DeletePolicy fromInt(int initialSetting) {
            for (DeletePolicy policy: values()) {
                if (policy.setting == initialSetting) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("DeletePolicy " + initialSetting + " unknown");
        }
    }

    public enum SortType {
        SORT_DATE(false),
        SORT_ARRIVAL(false),
        SORT_SUBJECT(true),
        SORT_SENDER(true),
        SORT_UNREAD(true),
        SORT_FLAGGED(true),
        SORT_ATTACHMENT(true);

        private boolean defaultAscending;

        SortType(boolean defaultAscending) {
            this.defaultAscending = defaultAscending;
        }

        public boolean isDefaultAscending() {
            return defaultAscending;
        }
    }

    public static final SortType DEFAULT_SORT_TYPE = SortType.SORT_DATE;
    public static final boolean DEFAULT_SORT_ASCENDING = false;
    public static final long NO_OPENPGP_KEY = 0;
    public static final int UNASSIGNED_ACCOUNT_NUMBER = -1;

    public static final int INTERVAL_MINUTES_NEVER = -1;
    public static final int DEFAULT_SYNC_INTERVAL = 60;

    private DeletePolicy deletePolicy = DeletePolicy.NEVER;

    private final String accountUuid;
    private ServerSettings incomingServerSettings;
    private ServerSettings outgoingServerSettings;

    /**
     * Storage provider ID, used to locate and manage the underlying DB/file
     * storage
     */
    private String localStorageProviderId;
    private String description;
    private String alwaysBcc;
    private int automaticCheckIntervalMinutes;
    private int displayCount;
    private int chipColor;
    private boolean notifyNewMail;
    private FolderMode folderNotifyNewMailMode;
    private boolean notifySelfNewMail;
    private boolean notifyContactsMailOnly;
    private String legacyInboxFolder;
    private String importedDraftsFolder;
    private String importedSentFolder;
    private String importedTrashFolder;
    private String importedArchiveFolder;
    private String importedSpamFolder;
    private Long inboxFolderId;
    private Long outboxFolderId;
    private Long draftsFolderId;
    private Long sentFolderId;
    private Long trashFolderId;
    private Long archiveFolderId;
    private Long spamFolderId;
    private SpecialFolderSelection draftsFolderSelection;
    private SpecialFolderSelection sentFolderSelection;
    private SpecialFolderSelection trashFolderSelection;
    private SpecialFolderSelection archiveFolderSelection;
    private SpecialFolderSelection spamFolderSelection;
    private String importedAutoExpandFolder;
    private Long autoExpandFolderId;
    private FolderMode folderDisplayMode;
    private FolderMode folderSyncMode;
    private FolderMode folderPushMode;
    private FolderMode folderTargetMode;
    private int accountNumber;
    private boolean notifySync;
    private SortType sortType;
    private Map<SortType, Boolean> sortAscending = new HashMap<>();
    private ShowPictures showPictures;
    private boolean isSignatureBeforeQuotedText;
    private Expunge expungePolicy = Expunge.EXPUNGE_IMMEDIATELY;
    private int maxPushFolders;
    private int idleRefreshMinutes;
    private boolean goToUnreadMessageSearch;
    private final Map<NetworkType, Boolean> compressionMap = new ConcurrentHashMap<>();
    private Searchable searchableFolders;
    private boolean subscribedFoldersOnly;
    private int maximumPolledMessageAge;
    private int maximumAutoDownloadMessageSize;
    // Tracks if we have sent a notification for this account for
    // current set of fetched messages
    private boolean ringNotified;
    private MessageFormat messageFormat;
    private boolean messageFormatAuto;
    private boolean messageReadReceipt;
    private QuoteStyle quoteStyle;
    private String quotePrefix;
    private boolean defaultQuotedTextShown;
    private boolean replyAfterQuote;
    private boolean stripSignature;
    private boolean syncRemoteDeletions;
    private String openPgpProvider;
    private long openPgpKey;
    private boolean autocryptPreferEncryptMutual;
    private boolean openPgpHideSignOnly;
    private boolean openPgpEncryptSubject;
    private boolean openPgpEncryptAllDrafts;
    private boolean markMessageAsReadOnView;
    private boolean markMessageAsReadOnDelete;
    private boolean alwaysShowCcBcc;
    private boolean allowRemoteSearch;
    private boolean remoteSearchFullText;
    private int remoteSearchNumResults;
    private boolean uploadSentMessages;
    private long lastSyncTime;
    private long lastFolderListRefreshTime;
    private boolean isFinishedSetup = false;
    private boolean muteMailingLists;
    private Set<String> mutedSenders, muteIfSentTo;

    private boolean changedVisibleLimits = false;

    /**
     * Database ID of the folder that was last selected for a copy or move operation.
     *
     * Note: For now this value isn't persisted. So it will be reset when K-9 Mail is restarted.
     */
    private Long lastSelectedFolderId = null;

    private List<Identity> identities;

    private final NotificationSetting notificationSetting = new NotificationSetting();

    public enum FolderMode {
        NONE, ALL, FIRST_CLASS, FIRST_AND_SECOND_CLASS, NOT_SECOND_CLASS
    }

    public enum SpecialFolderSelection {
        AUTOMATIC,
        MANUAL
    }

    public enum ShowPictures {
        NEVER, ALWAYS, ONLY_FROM_CONTACTS
    }

    public enum Searchable {
        ALL, DISPLAYABLE, NONE
    }

    public enum QuoteStyle {
        PREFIX, HEADER
    }

    public enum MessageFormat {
        TEXT, HTML, AUTO
    }


    public Account(String uuid) {
        this.accountUuid = uuid;
    }

    public synchronized void setChipColor(int color) {
        chipColor = color;
    }

    public synchronized int getChipColor() {
        return chipColor;
    }

    @Override
    public String getUuid() {
        return accountUuid;
    }

    public synchronized ServerSettings getIncomingServerSettings() {
        return incomingServerSettings;
    }

    public synchronized void setIncomingServerSettings(ServerSettings incomingServerSettings) {
        this.incomingServerSettings = incomingServerSettings;
    }

    public synchronized ServerSettings getOutgoingServerSettings() {
        return outgoingServerSettings;
    }

    public synchronized void setOutgoingServerSettings(ServerSettings outgoingServerSettings) {
        this.outgoingServerSettings = outgoingServerSettings;
    }

    @Override
    public synchronized String getDescription() {
        return description;
    }

    public synchronized void setDescription(String description) {
        this.description = description;
    }

    public String getDisplayName() {
        return description != null ? description : getEmail();
    }

    public synchronized String getName() {
        return identities.get(0).getName();
    }

    public synchronized void setName(String name) {
        Identity newIdentity = identities.get(0).withName(name);
        identities.set(0, newIdentity);
    }

    public synchronized boolean getSignatureUse() {
        return identities.get(0).getSignatureUse();
    }

    public synchronized void setSignatureUse(boolean signatureUse) {
        Identity newIdentity = identities.get(0).withSignatureUse(signatureUse);
        identities.set(0, newIdentity);
    }

    public synchronized String getSignature() {
        return identities.get(0).getSignature();
    }

    public synchronized void setSignature(String signature) {
        Identity newIdentity = identities.get(0).withSignature(signature);
        identities.set(0, newIdentity);
    }

    @Override
    public synchronized String getEmail() {
        return identities.get(0).getEmail();
    }

    public synchronized void setEmail(String email) {
        Identity newIdentity = identities.get(0).withEmail(email);
        identities.set(0, newIdentity);
    }

    public synchronized String getAlwaysBcc() {
        return alwaysBcc;
    }

    public synchronized void setAlwaysBcc(String alwaysBcc) {
        this.alwaysBcc = alwaysBcc;
    }

    /* Have we sent a new mail notification on this account */
    public boolean isRingNotified() {
        return ringNotified;
    }

    public void setRingNotified(boolean ringNotified) {
        this.ringNotified = ringNotified;
    }

    public String getLocalStorageProviderId() {
        return localStorageProviderId;
    }

    public void setLocalStorageProviderId(String id) {
        localStorageProviderId = id;
    }

    /**
     * Returns -1 for never.
     */
    public synchronized int getAutomaticCheckIntervalMinutes() {
        return automaticCheckIntervalMinutes;
    }

    /**
     * @param automaticCheckIntervalMinutes or -1 for never.
     */
    public synchronized boolean setAutomaticCheckIntervalMinutes(int automaticCheckIntervalMinutes) {
        int oldInterval = this.automaticCheckIntervalMinutes;
        this.automaticCheckIntervalMinutes = automaticCheckIntervalMinutes;

        return (oldInterval != automaticCheckIntervalMinutes);
    }

    public synchronized int getDisplayCount() {
        return displayCount;
    }

    public synchronized void setDisplayCount(int displayCount) {
        if (this.displayCount == displayCount) {
            return;
        }

        if (displayCount != -1) {
            this.displayCount = displayCount;
        } else {
            this.displayCount = K9.DEFAULT_VISIBLE_LIMIT;
        }

        changedVisibleLimits = true;
    }

    public synchronized boolean isNotifyNewMail() {
        return notifyNewMail;
    }

    public synchronized void setNotifyNewMail(boolean notifyNewMail) {
        this.notifyNewMail = notifyNewMail;
    }

    public synchronized FolderMode getFolderNotifyNewMailMode() {
        return folderNotifyNewMailMode;
    }

    public synchronized void setFolderNotifyNewMailMode(FolderMode folderNotifyNewMailMode) {
        this.folderNotifyNewMailMode = folderNotifyNewMailMode;
    }

    public synchronized boolean getMuteMailingLists() {
        return muteMailingLists;
    }

    public synchronized void setMuteMailingLists(boolean muteMailingLists) {
        this.muteMailingLists = muteMailingLists;
    }

    public synchronized String getMutedSendersAsString() {
        return TextUtils.join(";", getMutedSenders());
    }

    public synchronized String[] getMutedSenders() {
        if (mutedSenders == null) return new String[0];

        final String[] result = mutedSenders.toArray(new String[mutedSenders.size()]);
        Arrays.sort(result);
        return result;
    }

    public synchronized void setMutedSenders(String mutedSenders) {
        final List<String> list = mutedSenders == null || mutedSenders.equals("")
                ? Collections.emptyList()
                : Arrays.asList(TextUtils.split(mutedSenders, ";"));
        this.mutedSenders = new HashSet<String>(list);
    }

    public synchronized void setMutedSenders(Collection<String> mutedSenders) {
        this.mutedSenders = new HashSet<String>(mutedSenders);
    }

    public synchronized void addMutedSender(String sender) {
        this.mutedSenders.add(sender);
    }

    public synchronized String getMuteIfSentToAsString() {
        return TextUtils.join(";", getMuteIfSentTo());
    }

    public synchronized String[] getMuteIfSentTo() {
        if (muteIfSentTo == null) return new String[0];

        final String[] result = muteIfSentTo.toArray(new String[muteIfSentTo.size()]);
        Arrays.sort(result);
        return result;
    }

    public synchronized void setMuteIfSentTo(String muteIfSentTo) {
        final List<String> list = muteIfSentTo == null || muteIfSentTo.equals("")
                ? Collections.emptyList()
                : Arrays.asList(TextUtils.split(muteIfSentTo, ";"));
        this.muteIfSentTo = new HashSet<String>(list);
    }

    public synchronized void setMuteIfSentTo(Collection<String> muteIfSentTo) {
        this.muteIfSentTo = new HashSet<String>(muteIfSentTo);
    }

    public boolean isNotificationSuppressed(MimeMessage message) {
        if (muteMailingLists && message.getHeader("List-ID").length > 0)
            return true;

        if (mutedSenders != null && !mutedSenders.isEmpty()) {
            for (Address address : message.getSender()) {
                if (mutedSenders.contains(address.getAddress()))
                    return true;
            }
            for (Address address : message.getFrom()) {
                if (mutedSenders.contains(address.getAddress()))
                    return true;
            }
        }

        if (muteIfSentTo != null && !muteIfSentTo.isEmpty()) {
            for (RecipientType type : RecipientType.values()) {
                for (Address address : message.getRecipients(type)) {
                    if (muteIfSentTo.contains(address.getAddress()))
                        return true;
                }
            }
        }

        return false;
    }

    public synchronized DeletePolicy getDeletePolicy() {
        return deletePolicy;
    }

    public synchronized void setDeletePolicy(DeletePolicy deletePolicy) {
        this.deletePolicy = deletePolicy;
    }

    public synchronized String getImportedDraftsFolder() {
        return importedDraftsFolder;
    }

    public synchronized void setImportedDraftsFolder(String folderServerId) {
        importedDraftsFolder = folderServerId;
    }

    @Nullable
    public synchronized Long getDraftsFolderId() {
        return draftsFolderId;
    }

    public synchronized void setDraftsFolderId(@Nullable Long folderId) {
        draftsFolderId = folderId;
    }

    public synchronized void setDraftsFolderId(@Nullable Long folderId, SpecialFolderSelection selection) {
        draftsFolderId = folderId;
        draftsFolderSelection = selection;
    }

    public synchronized boolean hasDraftsFolder() {
        return draftsFolderId != null;
    }

    public synchronized String getImportedSentFolder() {
        return importedSentFolder;
    }

    public synchronized void setImportedSentFolder(String folderServerId) {
        importedSentFolder = folderServerId;
    }

    @Nullable
    public synchronized Long getSentFolderId() {
        return sentFolderId;
    }

    public synchronized void setSentFolderId(@Nullable Long folderId) {
        sentFolderId = folderId;
    }

    public synchronized void setSentFolderId(@Nullable Long folderId, SpecialFolderSelection selection) {
        sentFolderId = folderId;
        sentFolderSelection = selection;
    }

    public synchronized boolean hasSentFolder() {
        return sentFolderId != null;
    }

    public synchronized String getImportedTrashFolder() {
        return importedTrashFolder;
    }

    public synchronized void setImportedTrashFolder(String folderServerId) {
        importedTrashFolder = folderServerId;
    }

    @Nullable
    public synchronized Long getTrashFolderId() {
        return trashFolderId;
    }

    public synchronized void setTrashFolderId(@Nullable Long folderId) {
        trashFolderId = folderId;
    }

    public synchronized void setTrashFolderId(@Nullable Long folderId, SpecialFolderSelection selection) {
        trashFolderId = folderId;
        trashFolderSelection = selection;
    }

    public synchronized boolean hasTrashFolder() {
        return trashFolderId != null;
    }

    public synchronized String getImportedArchiveFolder() {
        return importedArchiveFolder;
    }

    public synchronized void setImportedArchiveFolder(String archiveFolder) {
        this.importedArchiveFolder = archiveFolder;
    }

    @Nullable
    public synchronized Long getArchiveFolderId() {
        return archiveFolderId;
    }

    public synchronized void setArchiveFolderId(@Nullable Long folderId) {
        archiveFolderId = folderId;
    }

    public synchronized void setArchiveFolderId(@Nullable Long folderId, SpecialFolderSelection selection) {
        this.archiveFolderId = folderId;
        archiveFolderSelection = selection;
    }

    public synchronized boolean hasArchiveFolder() {
        return archiveFolderId != null;
    }

    public synchronized String getImportedSpamFolder() {
        return importedSpamFolder;
    }

    public synchronized void setImportedSpamFolder(String folderServerId) {
        importedSpamFolder = folderServerId;
    }

    @Nullable
    public synchronized Long getSpamFolderId() {
        return spamFolderId;
    }

    public synchronized void setSpamFolderId(@Nullable Long folderId) {
        spamFolderId = folderId;
    }

    public synchronized void setSpamFolderId(@Nullable Long folderId, SpecialFolderSelection selection) {
        spamFolderId = folderId;
        spamFolderSelection = selection;
    }

    public synchronized boolean hasSpamFolder() {
        return spamFolderId != null;
    }

    @NotNull
    public SpecialFolderSelection getDraftsFolderSelection() {
        return draftsFolderSelection;
    }

    @NotNull
    public synchronized SpecialFolderSelection getSentFolderSelection() {
        return sentFolderSelection;
    }

    @NotNull
    public synchronized SpecialFolderSelection getTrashFolderSelection() {
        return trashFolderSelection;
    }

    @NotNull
    public synchronized SpecialFolderSelection getArchiveFolderSelection() {
        return archiveFolderSelection;
    }

    @NotNull
    public synchronized SpecialFolderSelection getSpamFolderSelection() {
        return spamFolderSelection;
    }

    @Nullable
    public synchronized Long getOutboxFolderId() {
        return outboxFolderId;
    }

    public synchronized void setOutboxFolderId(@Nullable Long folderId) {
        outboxFolderId = folderId;
    }

    public synchronized String getImportedAutoExpandFolder() {
        return importedAutoExpandFolder;
    }

    public synchronized void setImportedAutoExpandFolder(String name) {
        importedAutoExpandFolder = name;
    }

    @Nullable
    public synchronized Long getAutoExpandFolderId() {
        return autoExpandFolderId;
    }

    public synchronized void setAutoExpandFolderId(@Nullable Long folderId) {
        autoExpandFolderId = folderId;
    }

    public synchronized int getAccountNumber() {
        return accountNumber;
    }

    public synchronized void setAccountNumber(int accountNumber) {
        this.accountNumber = accountNumber;
    }

    public synchronized FolderMode getFolderDisplayMode() {
        return folderDisplayMode;
    }

    public synchronized boolean setFolderDisplayMode(FolderMode displayMode) {
        FolderMode oldDisplayMode = folderDisplayMode;
        folderDisplayMode = displayMode;
        return oldDisplayMode != displayMode;
    }

    public synchronized FolderMode getFolderSyncMode() {
        return folderSyncMode;
    }

    public synchronized boolean setFolderSyncMode(FolderMode syncMode) {
        FolderMode oldSyncMode = folderSyncMode;
        folderSyncMode = syncMode;

        if (syncMode == FolderMode.NONE && oldSyncMode != FolderMode.NONE) {
            return true;
        }
        return syncMode != FolderMode.NONE && oldSyncMode == FolderMode.NONE;
    }

    public synchronized FolderMode getFolderPushMode() {
        return folderPushMode;
    }

    public synchronized boolean setFolderPushMode(FolderMode pushMode) {
        FolderMode oldPushMode = folderPushMode;

        folderPushMode = pushMode;
        return pushMode != oldPushMode;
    }

    public synchronized boolean isNotifySync() {
        return notifySync;
    }

    public synchronized void setNotifySync(boolean notifySync) {
        this.notifySync = notifySync;
    }

    public synchronized SortType getSortType() {
        return sortType;
    }

    public synchronized void setSortType(SortType sortType) {
        this.sortType = sortType;
    }

    public synchronized boolean isSortAscending(SortType sortType) {
        if (sortAscending.get(sortType) == null) {
            sortAscending.put(sortType, sortType.isDefaultAscending());
        }
        return sortAscending.get(sortType);
    }

    public synchronized void setSortAscending(SortType sortType, boolean sortAscending) {
        this.sortAscending.put(sortType, sortAscending);
    }

    public synchronized ShowPictures getShowPictures() {
        return showPictures;
    }

    public synchronized void setShowPictures(ShowPictures showPictures) {
        this.showPictures = showPictures;
    }

    public synchronized FolderMode getFolderTargetMode() {
        return folderTargetMode;
    }

    public synchronized void setFolderTargetMode(FolderMode folderTargetMode) {
        this.folderTargetMode = folderTargetMode;
    }

    public synchronized boolean isSignatureBeforeQuotedText() {
        return isSignatureBeforeQuotedText;
    }

    public synchronized void setSignatureBeforeQuotedText(boolean mIsSignatureBeforeQuotedText) {
        this.isSignatureBeforeQuotedText = mIsSignatureBeforeQuotedText;
    }

    public synchronized boolean isNotifySelfNewMail() {
        return notifySelfNewMail;
    }

    public synchronized void setNotifySelfNewMail(boolean notifySelfNewMail) {
        this.notifySelfNewMail = notifySelfNewMail;
    }

    public synchronized boolean isNotifyContactsMailOnly() {
        return notifyContactsMailOnly;
    }

    public synchronized void setNotifyContactsMailOnly(boolean notifyContactsMailOnly) {
        this.notifyContactsMailOnly = notifyContactsMailOnly;
    }

    public synchronized Expunge getExpungePolicy() {
        return expungePolicy;
    }

    public synchronized void setExpungePolicy(Expunge expungePolicy) {
        this.expungePolicy = expungePolicy;
    }

    public synchronized int getMaxPushFolders() {
        return maxPushFolders;
    }

    public synchronized boolean setMaxPushFolders(int maxPushFolders) {
        int oldMaxPushFolders = this.maxPushFolders;
        this.maxPushFolders = maxPushFolders;
        return oldMaxPushFolders != maxPushFolders;
    }

    @Override
    public synchronized String toString() {
        return description;
    }

    public synchronized void setCompression(NetworkType networkType, boolean useCompression) {
        compressionMap.put(networkType, useCompression);
    }

    public synchronized boolean useCompression(NetworkType networkType) {
        Boolean useCompression = compressionMap.get(networkType);
        if (useCompression == null) {
            return true;
        }

        return useCompression;
    }

    public Map<NetworkType, Boolean> getCompressionMap() {
        return Collections.unmodifiableMap(compressionMap);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Account) {
            return ((Account)o).accountUuid.equals(accountUuid);
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return accountUuid.hashCode();
    }

    public synchronized List<Identity> getIdentities() {
        return identities;
    }

    public synchronized void setIdentities(List<Identity> newIdentities) {
        identities = new ArrayList<>(newIdentities);
    }

    public synchronized Identity getIdentity(int i) {
        if (i < identities.size()) {
            return identities.get(i);
        }
        throw new IllegalArgumentException("Identity with index " + i + " not found");
    }

    public boolean isAnIdentity(Address[] addrs) {
        if (addrs == null) {
            return false;
        }
        for (Address addr : addrs) {
            if (findIdentity(addr) != null) {
                return true;
            }
        }

        return false;
    }

    public boolean isAnIdentity(Address addr) {
        return findIdentity(addr) != null;
    }

    public synchronized Identity findIdentity(Address addr) {
        for (Identity identity : identities) {
            String email = identity.getEmail();
            if (email != null && email.equalsIgnoreCase(addr.getAddress())) {
                return identity;
            }
        }
        return null;
    }

    public synchronized Searchable getSearchableFolders() {
        return searchableFolders;
    }

    public synchronized void setSearchableFolders(Searchable searchableFolders) {
        this.searchableFolders = searchableFolders;
    }

    public synchronized int getIdleRefreshMinutes() {
        return idleRefreshMinutes;
    }

    public synchronized void setIdleRefreshMinutes(int idleRefreshMinutes) {
        this.idleRefreshMinutes = idleRefreshMinutes;
    }

    public synchronized boolean isGoToUnreadMessageSearch() {
        return goToUnreadMessageSearch;
    }

    public synchronized void setGoToUnreadMessageSearch(boolean goToUnreadMessageSearch) {
        this.goToUnreadMessageSearch = goToUnreadMessageSearch;
    }

    public synchronized boolean isSubscribedFoldersOnly() {
        return subscribedFoldersOnly;
    }

    public synchronized void setSubscribedFoldersOnly(boolean subscribedFoldersOnly) {
        this.subscribedFoldersOnly = subscribedFoldersOnly;
    }

    public synchronized int getMaximumPolledMessageAge() {
        return maximumPolledMessageAge;
    }

    public synchronized void setMaximumPolledMessageAge(int maximumPolledMessageAge) {
        this.maximumPolledMessageAge = maximumPolledMessageAge;
    }

    public synchronized int getMaximumAutoDownloadMessageSize() {
        return maximumAutoDownloadMessageSize;
    }

    public synchronized void setMaximumAutoDownloadMessageSize(int maximumAutoDownloadMessageSize) {
        this.maximumAutoDownloadMessageSize = maximumAutoDownloadMessageSize;
    }

    public Date getEarliestPollDate() {
        int age = getMaximumPolledMessageAge();
        if (age >= 0) {
            Calendar now = Calendar.getInstance();
            now.set(Calendar.HOUR_OF_DAY, 0);
            now.set(Calendar.MINUTE, 0);
            now.set(Calendar.SECOND, 0);
            now.set(Calendar.MILLISECOND, 0);
            if (age < 28) {
                now.add(Calendar.DATE, age * -1);
            } else switch (age) {
                case 28:
                    now.add(Calendar.MONTH, -1);
                    break;
                case 56:
                    now.add(Calendar.MONTH, -2);
                    break;
                case 84:
                    now.add(Calendar.MONTH, -3);
                    break;
                case 168:
                    now.add(Calendar.MONTH, -6);
                    break;
                case 365:
                    now.add(Calendar.YEAR, -1);
                    break;
                }

            return now.getTime();
        }

        return null;
    }

    public MessageFormat getMessageFormat() {
        return messageFormat;
    }

    public void setMessageFormat(MessageFormat messageFormat) {
        this.messageFormat = messageFormat;
    }

    public synchronized boolean isMessageFormatAuto() {
        return messageFormatAuto;
    }

    public synchronized void setMessageFormatAuto(boolean messageFormatAuto) {
        this.messageFormatAuto = messageFormatAuto;
    }

    public synchronized boolean isMessageReadReceipt() {
        return messageReadReceipt;
    }

    public synchronized void setMessageReadReceipt(boolean messageReadReceipt) {
        this.messageReadReceipt = messageReadReceipt;
    }

    public QuoteStyle getQuoteStyle() {
        return quoteStyle;
    }

    public void setQuoteStyle(QuoteStyle quoteStyle) {
        this.quoteStyle = quoteStyle;
    }

    public synchronized String getQuotePrefix() {
        return quotePrefix;
    }

    public synchronized void setQuotePrefix(String quotePrefix) {
        this.quotePrefix = quotePrefix;
    }

    public synchronized boolean isDefaultQuotedTextShown() {
        return defaultQuotedTextShown;
    }

    public synchronized void setDefaultQuotedTextShown(boolean shown) {
        defaultQuotedTextShown = shown;
    }

    public synchronized boolean isReplyAfterQuote() {
        return replyAfterQuote;
    }

    public synchronized void setReplyAfterQuote(boolean replyAfterQuote) {
        this.replyAfterQuote = replyAfterQuote;
    }

    public synchronized boolean isStripSignature() {
        return stripSignature;
    }

    public synchronized void setStripSignature(boolean stripSignature) {
        this.stripSignature = stripSignature;
    }

    public boolean isOpenPgpProviderConfigured() {
        return !TextUtils.isEmpty(openPgpProvider);
    }

    @Nullable
    public String getOpenPgpProvider() {
        if (TextUtils.isEmpty(openPgpProvider)) {
            return null;
        }
        return openPgpProvider;
    }

    public void setOpenPgpProvider(String openPgpProvider) {
        this.openPgpProvider = openPgpProvider;
    }

    public long getOpenPgpKey() {
        return openPgpKey;
    }

    public void setOpenPgpKey(long keyId) {
        openPgpKey = keyId;
    }

    public boolean hasOpenPgpKey() {
        return openPgpKey != NO_OPENPGP_KEY;
    }

    public boolean getAutocryptPreferEncryptMutual() {
        return autocryptPreferEncryptMutual;
    }

    public void setAutocryptPreferEncryptMutual(boolean autocryptPreferEncryptMutual) {
        this.autocryptPreferEncryptMutual = autocryptPreferEncryptMutual;
    }

    public boolean isOpenPgpHideSignOnly() {
        return openPgpHideSignOnly;
    }

    public void setOpenPgpHideSignOnly(boolean openPgpHideSignOnly) {
        this.openPgpHideSignOnly = openPgpHideSignOnly;
    }

    public boolean isOpenPgpEncryptSubject() {
        return openPgpEncryptSubject;
    }

    public void setOpenPgpEncryptSubject(boolean openPgpEncryptSubject) {
        this.openPgpEncryptSubject = openPgpEncryptSubject;
    }

    public boolean isOpenPgpEncryptAllDrafts() {
        return openPgpEncryptAllDrafts;
    }

    public void setOpenPgpEncryptAllDrafts(boolean openPgpEncryptAllDrafts) {
        this.openPgpEncryptAllDrafts = openPgpEncryptAllDrafts;
    }

    public boolean isAllowRemoteSearch() {
        return allowRemoteSearch;
    }

    public void setAllowRemoteSearch(boolean val) {
        allowRemoteSearch = val;
    }

    public int getRemoteSearchNumResults() {
        return remoteSearchNumResults;
    }

    public void setRemoteSearchNumResults(int val) {
        remoteSearchNumResults = (val >= 0 ? val : 0);
    }

    public boolean isUploadSentMessages() {
        return uploadSentMessages;
    }

    public void setUploadSentMessages(boolean uploadSentMessages) {
        this.uploadSentMessages = uploadSentMessages;
    }

    public String getLegacyInboxFolder() {
        return legacyInboxFolder;
    }

    void setLegacyInboxFolder(String name) {
        this.legacyInboxFolder = name;
    }

    @Nullable
    public synchronized Long getInboxFolderId() {
        return inboxFolderId;
    }

    public synchronized void setInboxFolderId(@Nullable Long folderId) {
        inboxFolderId = folderId;
    }

    public synchronized boolean isSyncRemoteDeletions() {
        return syncRemoteDeletions;
    }

    public synchronized void setSyncRemoteDeletions(boolean syncRemoteDeletions) {
        this.syncRemoteDeletions = syncRemoteDeletions;
    }

    public synchronized Long getLastSelectedFolderId() {
        return lastSelectedFolderId;
    }

    public synchronized void setLastSelectedFolderId(long folderId) {
        lastSelectedFolderId = folderId;
    }

    public synchronized NotificationSetting getNotificationSetting() {
        return notificationSetting;
    }

    /**
     * @return <code>true</code> if our {@link StorageProvider} is ready. (e.g.
     *         card inserted)
     */
    public boolean isAvailable(Context context) {
        String localStorageProviderId = getLocalStorageProviderId();
        boolean storageProviderIsInternalMemory = localStorageProviderId == null;
        return storageProviderIsInternalMemory || StorageManager.getInstance(context).isReady(localStorageProviderId);
    }

    public synchronized boolean isMarkMessageAsReadOnView() {
        return markMessageAsReadOnView;
    }

    public synchronized void setMarkMessageAsReadOnView(boolean value) {
        markMessageAsReadOnView = value;
    }

    public synchronized boolean isMarkMessageAsReadOnDelete() {
        return markMessageAsReadOnDelete;
    }

    public synchronized void setMarkMessageAsReadOnDelete(boolean value) {
        markMessageAsReadOnDelete = value;
    }

    public synchronized boolean isAlwaysShowCcBcc() {
        return alwaysShowCcBcc;
    }

    public synchronized void setAlwaysShowCcBcc(boolean show) {
        alwaysShowCcBcc = show;
    }
    public boolean isRemoteSearchFullText() {
        return false;   // Temporarily disabled
        //return remoteSearchFullText;
    }

    public void setRemoteSearchFullText(boolean val) {
        remoteSearchFullText = val;
    }

    public synchronized long getLastSyncTime() {
        return lastSyncTime;
    }

    public synchronized void setLastSyncTime(long lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public synchronized long getLastFolderListRefreshTime() {
        return lastFolderListRefreshTime;
    }

    public synchronized void setLastFolderListRefreshTime(long lastFolderListRefreshTime) {
        this.lastFolderListRefreshTime = lastFolderListRefreshTime;
    }

    boolean isChangedVisibleLimits() {
        return changedVisibleLimits;
    }

    void resetChangeMarkers() {
        changedVisibleLimits = false;
    }

    public synchronized boolean isFinishedSetup() {
        return isFinishedSetup;
    }

    public void markSetupFinished() {
        isFinishedSetup = true;
    }
}
