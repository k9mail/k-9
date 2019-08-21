package com.fsck.k9.backend.eas

import com.fsck.k9.backend.api.BackendStorage
import com.fsck.k9.backend.eas.dto.*
import com.fsck.k9.mail.MessagingException

class MessageFetchCommand(private val client: EasClient,
                          private val provisionManager: EasProvisionManager,
                          private val backendStorage: BackendStorage) {

    fun fetch(folderServerId: String, messageServerId: String): EasMessage {
        val backendFolder = backendStorage.getFolder(folderServerId)

        val syncKey = backendFolder.getFolderExtraString(EXTRA_SYNC_KEY) ?: INITIAL_SYNC_KEY

        return provisionManager.ensureProvisioned {
            val syncResponse = client.sync(Sync(
                    SyncCollections(
                            SyncCollection(SYNC_CLASS_EMAIL,
                                    syncKey,
                                    folderServerId,
                                    options = SyncOptions(
                                            mimeSupport = SYNC_OPTION_MIME_SUPPORT_FULL,
                                            bodyPreference = SyncBodyPreference(SYNC_BODY_PREF_TYPE_MIME)
                                    ),
                                    commands = SyncCommands(fetch = listOf(
                                            SyncItem(serverId = messageServerId)
                                    ))
                            )
                    )
            ))

            val collection = syncResponse.collections?.collection

            if (collection?.status != STATUS_OK) {
                throw MessagingException("Couldn't fetch message")
            }

            val newSyncKey = collection.syncKey!!
            backendFolder.setFolderExtraString(EXTRA_SYNC_KEY, newSyncKey)

            val message = collection.responses?.fetch?.firstOrNull()?.extractEasMessage(folderServerId)
                    ?: throw MessagingException("Message not found")
            message
        }
    }
}
