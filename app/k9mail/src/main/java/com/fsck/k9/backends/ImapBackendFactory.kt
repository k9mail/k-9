package com.fsck.k9.backends

import android.content.Context
import android.net.ConnectivityManager
import com.fsck.k9.Account
import com.fsck.k9.backend.BackendFactory
import com.fsck.k9.backend.api.Backend
import com.fsck.k9.backend.imap.ImapBackend
import com.fsck.k9.backend.imap.ImapStoreUriCreator
import com.fsck.k9.backend.imap.ImapStoreUriDecoder
import com.fsck.k9.mail.NetworkType
import com.fsck.k9.mail.ServerSettings
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mail.store.imap.ImapStore
import com.fsck.k9.mail.store.imap.ImapStoreConfig
import com.fsck.k9.mail.transport.smtp.SmtpTransport
import com.fsck.k9.mail.transport.smtp.SmtpTransportUriCreator
import com.fsck.k9.mail.transport.smtp.SmtpTransportUriDecoder
import com.fsck.k9.mailstore.K9BackendStorageFactory

class ImapBackendFactory(
    private val context: Context,
    private val powerManager: PowerManager,
    private val backendStorageFactory: K9BackendStorageFactory,
    private val trustedSocketFactory: TrustedSocketFactory,
    private val oAuth2TokenProvider: OAuth2TokenProvider

) : BackendFactory {
    override val transportUriPrefix = "smtp"

    override fun createBackend(account: Account): Backend {
        val accountName = account.displayName
        val backendStorage = backendStorageFactory.createBackendStorage(account)
        val imapStore = createImapStore(account)
        val smtpTransport = createSmtpTransport(account)
        return ImapBackend(accountName, backendStorage, imapStore, powerManager, smtpTransport)
    }

    private fun createImapStore(account: Account): ImapStore {
        val oAuth2TokenProvider: OAuth2TokenProvider? = oAuth2TokenProvider
        val serverSettings = ImapStoreUriDecoder.decode(account.storeUri)
        val config = createImapStoreConfig(account)
        return ImapStore(
                serverSettings,
                config,
                trustedSocketFactory,
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
                oAuth2TokenProvider
        )
    }

    private fun createImapStoreConfig(account: Account): ImapStoreConfig {
        return object : ImapStoreConfig {
            override val logLabel
                get() = account.description

            override fun isSubscribedFoldersOnly() = account.isSubscribedFoldersOnly

            override fun useCompression(type: NetworkType) = account.useCompression(type)
        }
    }

    private fun createSmtpTransport(account: Account): SmtpTransport {
        val serverSettings = decodeTransportUri(account.transportUri)
        val oauth2TokenProvider: OAuth2TokenProvider? = oAuth2TokenProvider
        return SmtpTransport(serverSettings, trustedSocketFactory, oauth2TokenProvider)
    }

    override fun decodeStoreUri(storeUri: String): ServerSettings {
        return ImapStoreUriDecoder.decode(storeUri)
    }

    override fun createStoreUri(serverSettings: ServerSettings): String {
        return ImapStoreUriCreator.create(serverSettings)
    }

    override fun decodeTransportUri(transportUri: String): ServerSettings {
        return SmtpTransportUriDecoder.decodeSmtpUri(transportUri)
    }

    override fun createTransportUri(serverSettings: ServerSettings): String {
        return SmtpTransportUriCreator.createSmtpUri(serverSettings)
    }
}
