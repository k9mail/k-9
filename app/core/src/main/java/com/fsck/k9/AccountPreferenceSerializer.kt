package com.fsck.k9

import com.fsck.k9.Account.*
import com.fsck.k9.helper.Utility
import com.fsck.k9.mail.NetworkType
import com.fsck.k9.mail.filter.Base64
import com.fsck.k9.mailstore.StorageManager
import com.fsck.k9.preferences.Storage
import com.fsck.k9.preferences.StorageEditor
import timber.log.Timber
import java.util.*

class AccountPreferenceSerializer(
        private val storageManager: StorageManager,
        private val resourceProvider: CoreResourceProvider
) {

    @Synchronized
    fun loadAccount(account: Account, storage: Storage) {
        val accountUuid = account.uuid
        with (account) {
            storeUri = Base64.decode(storage.getString("$accountUuid.storeUri", null))
            localStorageProviderId = storage.getString("$accountUuid.localStorageProvider", storageManager.defaultProviderId)
            transportUri = Base64.decode(storage.getString("$accountUuid.transportUri", null))
            description = storage.getString("$accountUuid.description", null)
            alwaysBcc = storage.getString("$accountUuid.alwaysBcc", alwaysBcc)
            automaticCheckIntervalMinutes = storage.getInt("$accountUuid.automaticCheckIntervalMinutes", Account.INTERVAL_MINUTES_NEVER)
            idleRefreshMinutes = storage.getInt("$accountUuid.idleRefreshMinutes", 24)
            isPushPollOnConnect = storage.getBoolean("$accountUuid.pushPollOnConnect", true)
            displayCount = storage.getInt("$accountUuid.displayCount", K9.DEFAULT_VISIBLE_LIMIT)
            if (displayCount < 0) {
                displayCount = K9.DEFAULT_VISIBLE_LIMIT
            }
            latestOldMessageSeenTime = storage.getLong("$accountUuid.latestOldMessageSeenTime", 0)
            isNotifyNewMail = storage.getBoolean("$accountUuid.notifyNewMail", false)

            folderNotifyNewMailMode = getEnumStringPref<FolderMode>(storage, "$accountUuid.folderNotifyNewMailMode", FolderMode.ALL)
            isNotifySelfNewMail = storage.getBoolean("$accountUuid.notifySelfNewMail", true)
            isNotifyContactsMailOnly = storage.getBoolean("$accountUuid.notifyContactsMailOnly", false)
            isNotifySync = storage.getBoolean("$accountUuid.notifyMailCheck", false)
            deletePolicy = DeletePolicy.fromInt(storage.getInt("$accountUuid.deletePolicy", DeletePolicy.NEVER.setting))
            inboxFolder = storage.getString("$accountUuid.inboxFolderName", INBOX)

            val draftsFolder = storage.getString("$accountUuid.draftsFolderName", null)
            val draftsFolderSelection = getEnumStringPref<SpecialFolderSelection>(storage, "$accountUuid.draftsFolderSelection",
                    SpecialFolderSelection.AUTOMATIC)
            setDraftsFolder(draftsFolder, draftsFolderSelection)

            val sentFolder = storage.getString("$accountUuid.sentFolderName", null)
            val sentFolderSelection = getEnumStringPref<SpecialFolderSelection>(storage, "$accountUuid.sentFolderSelection",
                    SpecialFolderSelection.AUTOMATIC)
            setSentFolder(sentFolder, sentFolderSelection)

            val trashFolder = storage.getString("$accountUuid.trashFolderName", null)
            val trashFolderSelection = getEnumStringPref<SpecialFolderSelection>(storage, "$accountUuid.trashFolderSelection",
                    SpecialFolderSelection.AUTOMATIC)
            setTrashFolder(trashFolder, trashFolderSelection)

            val archiveFolder = storage.getString("$accountUuid.archiveFolderName", null)
            val archiveFolderSelection = getEnumStringPref<SpecialFolderSelection>(storage, "$accountUuid.archiveFolderSelection",
                    SpecialFolderSelection.AUTOMATIC)
            setArchiveFolder(archiveFolder, archiveFolderSelection)

            val spamFolder = storage.getString("$accountUuid.spamFolderName", null)
            val spamFolderSelection = getEnumStringPref<SpecialFolderSelection>(storage, "$accountUuid.spamFolderSelection",
                    SpecialFolderSelection.AUTOMATIC)
            setSpamFolder(spamFolder, spamFolderSelection)

            expungePolicy = getEnumStringPref<Expunge>(storage, "$accountUuid.expungePolicy", Expunge.EXPUNGE_IMMEDIATELY)
            isSyncRemoteDeletions = storage.getBoolean("$accountUuid.syncRemoteDeletions", true)

            maxPushFolders = storage.getInt("$accountUuid.maxPushFolders", 10)
            isGoToUnreadMessageSearch = storage.getBoolean("$accountUuid.goToUnreadMessageSearch", false)
            isSubscribedFoldersOnly = storage.getBoolean("$accountUuid.subscribedFoldersOnly", false)
            maximumPolledMessageAge = storage.getInt("$accountUuid.maximumPolledMessageAge", -1)
            maximumAutoDownloadMessageSize = storage.getInt("$accountUuid.maximumAutoDownloadMessageSize", 32768)
            messageFormat = getEnumStringPref<MessageFormat>(storage, "$accountUuid.messageFormat", DEFAULT_MESSAGE_FORMAT)
            val messageFormatAuto = storage.getBoolean("$accountUuid.messageFormatAuto", DEFAULT_MESSAGE_FORMAT_AUTO)
            if (messageFormatAuto && messageFormat == MessageFormat.TEXT) {
                messageFormat = MessageFormat.AUTO
            }
            isMessageReadReceipt = storage.getBoolean("$accountUuid.messageReadReceipt", DEFAULT_MESSAGE_READ_RECEIPT)
            quoteStyle = getEnumStringPref<QuoteStyle>(storage, "$accountUuid.quoteStyle", DEFAULT_QUOTE_STYLE)
            quotePrefix = storage.getString("$accountUuid.quotePrefix", DEFAULT_QUOTE_PREFIX)
            isDefaultQuotedTextShown = storage.getBoolean("$accountUuid.defaultQuotedTextShown", DEFAULT_QUOTED_TEXT_SHOWN)
            isReplyAfterQuote = storage.getBoolean("$accountUuid.replyAfterQuote", DEFAULT_REPLY_AFTER_QUOTE)
            isStripSignature = storage.getBoolean("$accountUuid.stripSignature", DEFAULT_STRIP_SIGNATURE)
            for (type in NetworkType.values()) {
                val useCompression = storage.getBoolean("$accountUuid.useCompression.$type",
                        true)
                setCompression(type, useCompression)
            }

            autoExpandFolder = storage.getString("$accountUuid.autoExpandFolderName", INBOX)

            accountNumber = storage.getInt("$accountUuid.accountNumber", UNASSIGNED_ACCOUNT_NUMBER)

            chipColor = storage.getInt("$accountUuid.chipColor", FALLBACK_ACCOUNT_COLOR)

            sortType = getEnumStringPref<SortType>(storage, "$accountUuid.sortTypeEnum", SortType.SORT_DATE)

            setSortAscending(sortType, storage.getBoolean("$accountUuid.sortAscending", false))

            showPictures = getEnumStringPref<ShowPictures>(storage, "$accountUuid.showPicturesEnum", ShowPictures.NEVER)

            notificationSetting.setVibrateEnabled(storage.getBoolean("$accountUuid.vibrate", false))
            notificationSetting.vibratePattern = storage.getInt("$accountUuid.vibratePattern", 0)
            notificationSetting.vibrateTimes = storage.getInt("$accountUuid.vibrateTimes", 5)
            notificationSetting.isRingEnabled = storage.getBoolean("$accountUuid.ring", true)
            notificationSetting.ringtone = storage.getString("$accountUuid.ringtone",
                    "content://settings/system/notification_sound")
            notificationSetting.setLed(storage.getBoolean("$accountUuid.led", true))
            notificationSetting.ledColor = storage.getInt("$accountUuid.ledColor", chipColor)

            folderDisplayMode = getEnumStringPref<FolderMode>(storage, "$accountUuid.folderDisplayMode", FolderMode.NOT_SECOND_CLASS)

            folderSyncMode = getEnumStringPref<FolderMode>(storage, "$accountUuid.folderSyncMode", FolderMode.FIRST_CLASS)

            folderPushMode = getEnumStringPref<FolderMode>(storage, "$accountUuid.folderPushMode", FolderMode.FIRST_CLASS)

            folderTargetMode = getEnumStringPref<FolderMode>(storage, "$accountUuid.folderTargetMode", FolderMode.NOT_SECOND_CLASS)

            searchableFolders = getEnumStringPref<Searchable>(storage, "$accountUuid.searchableFolders", Searchable.ALL)

            isSignatureBeforeQuotedText = storage.getBoolean("$accountUuid.signatureBeforeQuotedText", false)
            identities = loadIdentities(accountUuid, storage)

            openPgpProvider = storage.getString("$accountUuid.openPgpProvider", "")
            openPgpKey = storage.getLong("$accountUuid.cryptoKey", NO_OPENPGP_KEY)
            isOpenPgpHideSignOnly = storage.getBoolean("$accountUuid.openPgpHideSignOnly", true)
            isOpenPgpEncryptSubject = storage.getBoolean("$accountUuid.openPgpEncryptSubject", true)
            isOpenPgpEncryptAllDrafts = storage.getBoolean("$accountUuid.openPgpEncryptAllDrafts", true)
            autocryptPreferEncryptMutual = storage.getBoolean("$accountUuid.autocryptMutualMode", false)
            isAllowRemoteSearch = storage.getBoolean("$accountUuid.allowRemoteSearch", false)
            isRemoteSearchFullText = storage.getBoolean("$accountUuid.remoteSearchFullText", false)
            remoteSearchNumResults = storage.getInt("$accountUuid.remoteSearchNumResults", DEFAULT_REMOTE_SEARCH_NUM_RESULTS)
            isUploadSentMessages = storage.getBoolean("$accountUuid.uploadSentMessages", true)

            isEnabled = storage.getBoolean("$accountUuid.enabled", true)
            isMarkMessageAsReadOnView = storage.getBoolean("$accountUuid.markMessageAsReadOnView", true)
            isAlwaysShowCcBcc = storage.getBoolean("$accountUuid.alwaysShowCcBcc", false)

            isResizeImageEnabled = storage.getBoolean("$accountUuid.resizeImageEnabled", DEFAULT_RESIZE_IMAGE_ENABLED)
            resizeImageCircumference = storage.getInt("$accountUuid.resizeImageCircumference", DEFAULT_RESIZE_IMAGE_CIRCUMFERENCE)
            resizeImageQuality = storage.getInt("$accountUuid.resizeImageQuality", DEFAULT_RESIZE_IMAGE_QUALITY)

            // Use email address as account description if necessary
            if (description == null) {
                description = email
            }
        }
    }

    @Synchronized
    private fun loadIdentities(accountUuid: String, storage: Storage): List<Identity> {
        val newIdentities = ArrayList<Identity>()
        var ident = 0
        var gotOne: Boolean
        do {
            gotOne = false
            val name = storage.getString("$accountUuid.$IDENTITY_NAME_KEY.$ident", null)
            val email = storage.getString("$accountUuid.$IDENTITY_EMAIL_KEY.$ident", null)
            val signatureUse = storage.getBoolean("$accountUuid.signatureUse.$ident", true)
            val signature = storage.getString("$accountUuid.signature.$ident", null)
            val description = storage.getString("$accountUuid.$IDENTITY_DESCRIPTION_KEY.$ident", null)
            val replyTo = storage.getString("$accountUuid.replyTo.$ident", null)
            if (email != null) {
                val identity = Identity(
                    name = name,
                    email = email,
                    signatureUse = signatureUse,
                    signature = signature,
                    description = description,
                    replyTo = replyTo
                )
                newIdentities.add(identity)
                gotOne = true
            }
            ident++
        } while (gotOne)

        if (newIdentities.isEmpty()) {
            val name = storage.getString("$accountUuid.name", null)
            val email = storage.getString("$accountUuid.email", null)
            val signatureUse = storage.getBoolean("$accountUuid.signatureUse", true)
            val signature = storage.getString("$accountUuid.signature", null)
            val identity = Identity(
                name = name,
                email = email,
                signatureUse = signatureUse,
                signature = signature,
                description = email
            )
            newIdentities.add(identity)
        }

        return newIdentities
    }

    @Synchronized
    fun save(editor: StorageEditor, storage: Storage, account: Account) {
        val accountUuid = account.uuid

        if (!storage.getString("accountUuids", "").contains(account.uuid)) {
            var accountUuids = storage.getString("accountUuids", "")
            accountUuids += (if (accountUuids.isNotEmpty()) "," else "") + account.uuid
            editor.putString("accountUuids", accountUuids)
        }

        with (account) {
            editor.putString("$accountUuid.storeUri", Base64.encode(storeUri))
            editor.putString("$accountUuid.localStorageProvider", localStorageProviderId)
            editor.putString("$accountUuid.transportUri", Base64.encode(transportUri))
            editor.putString("$accountUuid.description", description)
            editor.putString("$accountUuid.alwaysBcc", alwaysBcc)
            editor.putInt("$accountUuid.automaticCheckIntervalMinutes", automaticCheckIntervalMinutes)
            editor.putInt("$accountUuid.idleRefreshMinutes", idleRefreshMinutes)
            editor.putBoolean("$accountUuid.pushPollOnConnect", isPushPollOnConnect)
            editor.putInt("$accountUuid.displayCount", displayCount)
            editor.putLong("$accountUuid.latestOldMessageSeenTime", latestOldMessageSeenTime)
            editor.putBoolean("$accountUuid.notifyNewMail", isNotifyNewMail)
            editor.putString("$accountUuid.folderNotifyNewMailMode", folderNotifyNewMailMode.name)
            editor.putBoolean("$accountUuid.notifySelfNewMail", isNotifySelfNewMail)
            editor.putBoolean("$accountUuid.notifyContactsMailOnly", isNotifyContactsMailOnly)
            editor.putBoolean("$accountUuid.notifyMailCheck", isNotifySync)
            editor.putInt("$accountUuid.deletePolicy", deletePolicy.setting)
            editor.putString("$accountUuid.inboxFolderName", inboxFolder)
            editor.putString("$accountUuid.draftsFolderName", draftsFolder)
            editor.putString("$accountUuid.sentFolderName", sentFolder)
            editor.putString("$accountUuid.trashFolderName", trashFolder)
            editor.putString("$accountUuid.archiveFolderName", archiveFolder)
            editor.putString("$accountUuid.spamFolderName", spamFolder)
            editor.putString("$accountUuid.archiveFolderSelection", archiveFolderSelection.name)
            editor.putString("$accountUuid.draftsFolderSelection", draftsFolderSelection.name)
            editor.putString("$accountUuid.sentFolderSelection", sentFolderSelection.name)
            editor.putString("$accountUuid.spamFolderSelection", spamFolderSelection.name)
            editor.putString("$accountUuid.trashFolderSelection", trashFolderSelection.name)
            editor.putString("$accountUuid.autoExpandFolderName", autoExpandFolder)
            editor.putInt("$accountUuid.accountNumber", accountNumber)
            editor.putString("$accountUuid.sortTypeEnum", sortType.name)
            editor.putBoolean("$accountUuid.sortAscending", isSortAscending(sortType))
            editor.putString("$accountUuid.showPicturesEnum", showPictures.name)
            editor.putString("$accountUuid.folderDisplayMode", folderDisplayMode.name)
            editor.putString("$accountUuid.folderSyncMode", folderSyncMode.name)
            editor.putString("$accountUuid.folderPushMode", folderPushMode.name)
            editor.putString("$accountUuid.folderTargetMode", folderTargetMode.name)
            editor.putBoolean("$accountUuid.signatureBeforeQuotedText", isSignatureBeforeQuotedText)
            editor.putString("$accountUuid.expungePolicy", expungePolicy.name)
            editor.putBoolean("$accountUuid.syncRemoteDeletions", isSyncRemoteDeletions)
            editor.putInt("$accountUuid.maxPushFolders", maxPushFolders)
            editor.putString("$accountUuid.searchableFolders", searchableFolders.name)
            editor.putInt("$accountUuid.chipColor", chipColor)
            editor.putBoolean("$accountUuid.goToUnreadMessageSearch", isGoToUnreadMessageSearch)
            editor.putBoolean("$accountUuid.subscribedFoldersOnly", isSubscribedFoldersOnly)
            editor.putInt("$accountUuid.maximumPolledMessageAge", maximumPolledMessageAge)
            editor.putInt("$accountUuid.maximumAutoDownloadMessageSize", maximumAutoDownloadMessageSize)
            val messageFormatAuto = if (Account.MessageFormat.AUTO == messageFormat) {
                // saving MessageFormat.AUTO as is to the database will cause downgrades to crash on
                // startup, so we save as MessageFormat.TEXT instead with a separate flag for auto.
                editor.putString("$accountUuid.messageFormat", Account.MessageFormat.TEXT.name)
                true
            } else {
                editor.putString("$accountUuid.messageFormat", messageFormat.name)
                false
            }
            editor.putBoolean("$accountUuid.messageFormatAuto", messageFormatAuto)
            editor.putBoolean("$accountUuid.messageReadReceipt", isMessageReadReceipt)
            editor.putString("$accountUuid.quoteStyle", quoteStyle.name)
            editor.putString("$accountUuid.quotePrefix", quotePrefix)
            editor.putBoolean("$accountUuid.defaultQuotedTextShown", isDefaultQuotedTextShown)
            editor.putBoolean("$accountUuid.replyAfterQuote", isReplyAfterQuote)
            editor.putBoolean("$accountUuid.stripSignature", isStripSignature)
            editor.putLong("$accountUuid.cryptoKey", openPgpKey)
            editor.putBoolean("$accountUuid.openPgpHideSignOnly", isOpenPgpHideSignOnly)
            editor.putBoolean("$accountUuid.openPgpEncryptSubject", isOpenPgpEncryptSubject)
            editor.putBoolean("$accountUuid.openPgpEncryptAllDrafts", isOpenPgpEncryptAllDrafts)
            editor.putString("$accountUuid.openPgpProvider", openPgpProvider)
            editor.putBoolean("$accountUuid.autocryptMutualMode", autocryptPreferEncryptMutual)
            editor.putBoolean("$accountUuid.allowRemoteSearch", isAllowRemoteSearch)
            editor.putBoolean("$accountUuid.remoteSearchFullText", isRemoteSearchFullText)
            editor.putInt("$accountUuid.remoteSearchNumResults", remoteSearchNumResults)
            editor.putBoolean("$accountUuid.uploadSentMessages", isUploadSentMessages)
            editor.putBoolean("$accountUuid.enabled", isEnabled)
            editor.putBoolean("$accountUuid.markMessageAsReadOnView", isMarkMessageAsReadOnView)
            editor.putBoolean("$accountUuid.alwaysShowCcBcc", isAlwaysShowCcBcc)

            editor.putBoolean("$accountUuid.resizeImageEnabled", isResizeImageEnabled)
            editor.putInt("$accountUuid.resizeImageCircumference", resizeImageCircumference)
            editor.putInt("$accountUuid.resizeImageQuality", resizeImageQuality)

            editor.putBoolean("$accountUuid.vibrate", notificationSetting.isVibrateEnabled)
            editor.putInt("$accountUuid.vibratePattern", notificationSetting.vibratePattern)
            editor.putInt("$accountUuid.vibrateTimes", notificationSetting.vibrateTimes)
            editor.putBoolean("$accountUuid.ring", notificationSetting.isRingEnabled)
            editor.putString("$accountUuid.ringtone", notificationSetting.ringtone)
            editor.putBoolean("$accountUuid.led", notificationSetting.isLedEnabled)
            editor.putInt("$accountUuid.ledColor", notificationSetting.ledColor)

            for (type in NetworkType.values()) {
                val useCompression = compressionMap[type]
                if (useCompression != null) {
                    editor.putBoolean("$accountUuid.useCompression.$type", useCompression)
                }
            }
        }

        saveIdentities(account, storage, editor)
    }


    @Synchronized
    fun delete(editor: StorageEditor, storage: Storage, account: Account) {
        val accountUuid = account.uuid

        // Get the list of account UUIDs
        val uuids = storage.getString("accountUuids", "").split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        // Create a list of all account UUIDs excluding this account
        val newUuids = ArrayList<String>(uuids.size)
        for (uuid in uuids) {
            if (uuid != accountUuid) {
                newUuids.add(uuid)
            }
        }

        // Only change the 'accountUuids' value if this account's UUID was listed before
        if (newUuids.size < uuids.size) {
            val accountUuids = Utility.combine(newUuids.toTypedArray(), ',')
            editor.putString("accountUuids", accountUuids)
        }

        editor.remove("$accountUuid.storeUri")
        editor.remove("$accountUuid.transportUri")
        editor.remove("$accountUuid.description")
        editor.remove("$accountUuid.name")
        editor.remove("$accountUuid.email")
        editor.remove("$accountUuid.alwaysBcc")
        editor.remove("$accountUuid.automaticCheckIntervalMinutes")
        editor.remove("$accountUuid.pushPollOnConnect")
        editor.remove("$accountUuid.idleRefreshMinutes")
        editor.remove("$accountUuid.lastAutomaticCheckTime")
        editor.remove("$accountUuid.latestOldMessageSeenTime")
        editor.remove("$accountUuid.notifyNewMail")
        editor.remove("$accountUuid.notifySelfNewMail")
        editor.remove("$accountUuid.deletePolicy")
        editor.remove("$accountUuid.draftsFolderName")
        editor.remove("$accountUuid.sentFolderName")
        editor.remove("$accountUuid.trashFolderName")
        editor.remove("$accountUuid.archiveFolderName")
        editor.remove("$accountUuid.spamFolderName")
        editor.remove("$accountUuid.archiveFolderSelection")
        editor.remove("$accountUuid.draftsFolderSelection")
        editor.remove("$accountUuid.sentFolderSelection")
        editor.remove("$accountUuid.spamFolderSelection")
        editor.remove("$accountUuid.trashFolderSelection")
        editor.remove("$accountUuid.autoExpandFolderName")
        editor.remove("$accountUuid.accountNumber")
        editor.remove("$accountUuid.vibrate")
        editor.remove("$accountUuid.vibratePattern")
        editor.remove("$accountUuid.vibrateTimes")
        editor.remove("$accountUuid.ring")
        editor.remove("$accountUuid.ringtone")
        editor.remove("$accountUuid.folderDisplayMode")
        editor.remove("$accountUuid.folderSyncMode")
        editor.remove("$accountUuid.folderPushMode")
        editor.remove("$accountUuid.folderTargetMode")
        editor.remove("$accountUuid.signatureBeforeQuotedText")
        editor.remove("$accountUuid.expungePolicy")
        editor.remove("$accountUuid.syncRemoteDeletions")
        editor.remove("$accountUuid.maxPushFolders")
        editor.remove("$accountUuid.searchableFolders")
        editor.remove("$accountUuid.chipColor")
        editor.remove("$accountUuid.led")
        editor.remove("$accountUuid.ledColor")
        editor.remove("$accountUuid.goToUnreadMessageSearch")
        editor.remove("$accountUuid.subscribedFoldersOnly")
        editor.remove("$accountUuid.maximumPolledMessageAge")
        editor.remove("$accountUuid.maximumAutoDownloadMessageSize")
        editor.remove("$accountUuid.messageFormatAuto")
        editor.remove("$accountUuid.quoteStyle")
        editor.remove("$accountUuid.quotePrefix")
        editor.remove("$accountUuid.sortTypeEnum")
        editor.remove("$accountUuid.sortAscending")
        editor.remove("$accountUuid.showPicturesEnum")
        editor.remove("$accountUuid.replyAfterQuote")
        editor.remove("$accountUuid.stripSignature")
        editor.remove("$accountUuid.cryptoApp") // this is no longer set, but cleans up legacy values
        editor.remove("$accountUuid.cryptoAutoSignature")
        editor.remove("$accountUuid.cryptoAutoEncrypt")
        editor.remove("$accountUuid.cryptoApp")
        editor.remove("$accountUuid.cryptoKey")
        editor.remove("$accountUuid.cryptoSupportSignOnly")
        editor.remove("$accountUuid.openPgpProvider")
        editor.remove("$accountUuid.openPgpHideSignOnly")
        editor.remove("$accountUuid.openPgpEncryptSubject")
        editor.remove("$accountUuid.openPgpEncryptAllDrafts")
        editor.remove("$accountUuid.autocryptMutualMode")
        editor.remove("$accountUuid.enabled")
        editor.remove("$accountUuid.markMessageAsReadOnView")
        editor.remove("$accountUuid.alwaysShowCcBcc")
        editor.remove("$accountUuid.allowRemoteSearch")
        editor.remove("$accountUuid.remoteSearchFullText")
        editor.remove("$accountUuid.remoteSearchNumResults")
        editor.remove("$accountUuid.uploadSentMessages")
        editor.remove("$accountUuid.defaultQuotedTextShown")
        editor.remove("$accountUuid.displayCount")
        editor.remove("$accountUuid.inboxFolderName")
        editor.remove("$accountUuid.localStorageProvider")
        editor.remove("$accountUuid.messageFormat")
        editor.remove("$accountUuid.messageReadReceipt")
        editor.remove("$accountUuid.notifyMailCheck")

        editor.remove("$accountUuid.resizeImageEnabled")
        editor.remove("$accountUuid.resizeImageCircumference")
        editor.remove("$accountUuid.resizeImageQuality")

        for (type in NetworkType.values()) {
            editor.remove("$accountUuid.useCompression." + type.name)
        }
        deleteIdentities(account, storage, editor)
        // TODO: Remove preference settings that may exist for individual folders in the account.
    }

    @Synchronized
    private fun saveIdentities(account: Account, storage: Storage, editor: StorageEditor) {
        deleteIdentities(account, storage, editor)
        var ident = 0

        with (account) {
            for (identity in identities) {
                editor.putString("$uuid.$IDENTITY_NAME_KEY.$ident", identity.name)
                editor.putString("$uuid.$IDENTITY_EMAIL_KEY.$ident", identity.email)
                editor.putBoolean("$uuid.signatureUse.$ident", identity.signatureUse)
                editor.putString("$uuid.signature.$ident", identity.signature)
                editor.putString("$uuid.$IDENTITY_DESCRIPTION_KEY.$ident", identity.description)
                editor.putString("$uuid.replyTo.$ident", identity.replyTo)
                ident++
            }
        }
    }

    @Synchronized
    private fun deleteIdentities(account: Account, storage: Storage, editor: StorageEditor) {
        val accountUuid = account.uuid

        var identityIndex = 0
        var gotOne: Boolean
        do {
            gotOne = false
            val email = storage.getString("$accountUuid.$IDENTITY_EMAIL_KEY.$identityIndex", null)
            if (email != null) {
                editor.remove("$accountUuid.$IDENTITY_NAME_KEY.$identityIndex")
                editor.remove("$accountUuid.$IDENTITY_EMAIL_KEY.$identityIndex")
                editor.remove("$accountUuid.signatureUse.$identityIndex")
                editor.remove("$accountUuid.signature.$identityIndex")
                editor.remove("$accountUuid.$IDENTITY_DESCRIPTION_KEY.$identityIndex")
                editor.remove("$accountUuid.replyTo.$identityIndex")
                gotOne = true
            }
            identityIndex++
        } while (gotOne)
    }

    fun move(editor: StorageEditor, account: Account, storage: Storage, moveUp: Boolean) {
        val uuids = storage.getString("accountUuids", "").split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val newUuids = arrayOfNulls<String>(uuids.size)
        if (moveUp) {
            for (i in uuids.indices) {
                if (i > 0 && uuids[i] == account.uuid) {
                    newUuids[i] = newUuids[i - 1]
                    newUuids[i - 1] = account.uuid
                } else {
                    newUuids[i] = uuids[i]
                }
            }
        } else {
            for (i in uuids.indices.reversed()) {
                if (i < uuids.size - 1 && uuids[i] == account.uuid) {
                    newUuids[i] = newUuids[i + 1]
                    newUuids[i + 1] = account.uuid
                } else {
                    newUuids[i] = uuids[i]
                }
            }
        }
        val accountUuids = Utility.combine(newUuids, ',')
        editor.putString("accountUuids", accountUuids)
    }

    private fun <T : Enum<T>> getEnumStringPref(storage: Storage, key: String, defaultEnum: T): T {
        val stringPref = storage.getString(key, null)

        return if (stringPref == null) {
            defaultEnum
        } else {
            try {
                java.lang.Enum.valueOf<T>(defaultEnum.declaringClass, stringPref)
            } catch (ex: IllegalArgumentException) {
                Timber.w(ex, "Unable to convert preference key [%s] value [%s] to enum of type %s",
                        key, stringPref, defaultEnum.declaringClass)

                defaultEnum
            }

        }
    }

    fun loadDefaults(account: Account) {
        with (account) {
            localStorageProviderId = storageManager.defaultProviderId
            automaticCheckIntervalMinutes = Account.INTERVAL_MINUTES_NEVER
            idleRefreshMinutes = 24
            isPushPollOnConnect = true
            displayCount = K9.DEFAULT_VISIBLE_LIMIT
            accountNumber = UNASSIGNED_ACCOUNT_NUMBER
            isNotifyNewMail = true
            folderNotifyNewMailMode = FolderMode.ALL
            isNotifySync = true
            isNotifySelfNewMail = true
            isNotifyContactsMailOnly = false
            folderDisplayMode = FolderMode.NOT_SECOND_CLASS
            folderSyncMode = FolderMode.FIRST_CLASS
            folderPushMode = FolderMode.FIRST_CLASS
            folderTargetMode = FolderMode.NOT_SECOND_CLASS
            sortType = DEFAULT_SORT_TYPE
            setSortAscending(DEFAULT_SORT_TYPE, DEFAULT_SORT_ASCENDING)
            showPictures = ShowPictures.NEVER
            isSignatureBeforeQuotedText = false
            expungePolicy = Expunge.EXPUNGE_IMMEDIATELY
            autoExpandFolder = INBOX
            inboxFolder = INBOX
            maxPushFolders = 10
            isGoToUnreadMessageSearch = false
            isSubscribedFoldersOnly = false
            maximumPolledMessageAge = -1
            maximumAutoDownloadMessageSize = 32768
            messageFormat = DEFAULT_MESSAGE_FORMAT
            isMessageFormatAuto = DEFAULT_MESSAGE_FORMAT_AUTO
            isMessageReadReceipt = DEFAULT_MESSAGE_READ_RECEIPT
            quoteStyle = DEFAULT_QUOTE_STYLE
            quotePrefix = DEFAULT_QUOTE_PREFIX
            isDefaultQuotedTextShown = DEFAULT_QUOTED_TEXT_SHOWN
            isReplyAfterQuote = DEFAULT_REPLY_AFTER_QUOTE
            isStripSignature = DEFAULT_STRIP_SIGNATURE
            isSyncRemoteDeletions = true
            openPgpKey = NO_OPENPGP_KEY
            isAllowRemoteSearch = false
            isRemoteSearchFullText = false
            remoteSearchNumResults = DEFAULT_REMOTE_SEARCH_NUM_RESULTS
            isUploadSentMessages = true
            isEnabled = true
            isMarkMessageAsReadOnView = true
            isAlwaysShowCcBcc = false

            isResizeImageEnabled = DEFAULT_RESIZE_IMAGE_ENABLED
            resizeImageCircumference = DEFAULT_RESIZE_IMAGE_CIRCUMFERENCE
            resizeImageQuality = DEFAULT_RESIZE_IMAGE_QUALITY

            setArchiveFolder(null, SpecialFolderSelection.AUTOMATIC)
            setDraftsFolder(null, SpecialFolderSelection.AUTOMATIC)
            setSentFolder(null, SpecialFolderSelection.AUTOMATIC)
            setSpamFolder(null, SpecialFolderSelection.AUTOMATIC)
            setTrashFolder(null, SpecialFolderSelection.AUTOMATIC)
            setArchiveFolder(null, SpecialFolderSelection.AUTOMATIC)

            searchableFolders = Searchable.ALL

            identities = ArrayList<Identity>()

            val identity = Identity(
                signatureUse = true,
                signature = resourceProvider.defaultSignature(),
                description = resourceProvider.defaultIdentityDescription()
            )
            identities.add(identity)

            with (notificationSetting) {
                isVibrateEnabled = false
                vibratePattern = 0
                vibrateTimes = 5
                isRingEnabled = true
                ringtone = "content://settings/system/notification_sound"
                ledColor = chipColor
            }
        }
    }

    companion object {
        const val ACCOUNT_DESCRIPTION_KEY = "description"
        const val STORE_URI_KEY = "storeUri"
        const val TRANSPORT_URI_KEY = "transportUri"

        const val IDENTITY_NAME_KEY = "name"
        const val IDENTITY_EMAIL_KEY = "email"
        const val IDENTITY_DESCRIPTION_KEY = "description"

        const val FALLBACK_ACCOUNT_COLOR = 0x0099CC

        @JvmField
        val DEFAULT_MESSAGE_FORMAT = MessageFormat.HTML
        @JvmField
        val DEFAULT_QUOTE_STYLE = QuoteStyle.PREFIX
        const val DEFAULT_MESSAGE_FORMAT_AUTO = false
        const val DEFAULT_MESSAGE_READ_RECEIPT = false
        const val DEFAULT_QUOTE_PREFIX = ">"
        const val DEFAULT_QUOTED_TEXT_SHOWN = true
        const val DEFAULT_REPLY_AFTER_QUOTE = false
        const val DEFAULT_STRIP_SIGNATURE = true
        const val DEFAULT_REMOTE_SEARCH_NUM_RESULTS = 25
    }
}
