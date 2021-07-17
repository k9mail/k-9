package com.fsck.k9.autodiscovery.providersxml

import android.content.res.XmlResourceParser
import android.net.Uri
import com.fsck.k9.autodiscovery.api.ConnectionSettingsDiscovery
import com.fsck.k9.autodiscovery.api.DiscoveredServerSettings
import com.fsck.k9.autodiscovery.api.DiscoveryResults
import com.fsck.k9.autodiscovery.api.DiscoveryTarget
import com.fsck.k9.helper.EmailHelper
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.oauth.OAuth2Provider
import com.fsck.k9.preferences.Protocols
import java.net.URI
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

class ProvidersXmlDiscovery(
    private val xmlProvider: ProvidersXmlProvider
) : ConnectionSettingsDiscovery {

    override fun discover(email: String, target: DiscoveryTarget): DiscoveryResults? {
        val domain = EmailHelper.getDomainFromEmailAddress(email) ?: return null

        val provider = findProviderForDomain(domain) ?: return null

        val incomingSettings = provider.toIncomingServerSettings(email) ?: return null
        val outgoingSettings = provider.toOutgoingServerSettings(email) ?: return null
        return DiscoveryResults(listOf(incomingSettings), listOf(outgoingSettings))
    }

    private fun findProviderForDomain(domain: String): Provider? {
        return try {
            xmlProvider.getXml().use { xml ->
                parseProviders(xml, domain)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error while trying to load provider settings.")
            null
        }
    }

    private fun parseProviders(xml: XmlResourceParser, domain: String): Provider? {
        do {
            val xmlEventType = xml.next()
            if (xmlEventType == XmlPullParser.START_TAG && xml.name == "provider") {
                val providerDomain = xml.getAttributeValue(null, "domain")
                if (domain.equals(providerDomain, ignoreCase = true)) {
                    val provider = parseProvider(xml)
                    if (provider != null) return provider
                }
            }
        } while (xmlEventType != XmlPullParser.END_DOCUMENT)

        return null
    }

    private fun parseProvider(xml: XmlResourceParser): Provider? {
        var incomingUriTemplate: String? = null
        var incomingUsernameTemplate: String? = null
        var outgoingUriTemplate: String? = null
        var outgoingUsernameTemplate: String? = null

        do {
            val xmlEventType = xml.next()
            if (xmlEventType == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "incoming" -> {
                        incomingUriTemplate = xml.getAttributeValue(null, "uri")
                        incomingUsernameTemplate = xml.getAttributeValue(null, "username")
                    }
                    "outgoing" -> {
                        outgoingUriTemplate = xml.getAttributeValue(null, "uri")
                        outgoingUsernameTemplate = xml.getAttributeValue(null, "username")
                    }
                }
            }
        } while (!(xmlEventType == XmlPullParser.END_TAG && xml.name == "provider"))

        return if (incomingUriTemplate != null && incomingUsernameTemplate != null && outgoingUriTemplate != null &&
            outgoingUsernameTemplate != null
        ) {
            Provider(incomingUriTemplate, incomingUsernameTemplate, outgoingUriTemplate, outgoingUsernameTemplate)
        } else {
            null
        }
    }

    private fun Provider.toIncomingServerSettings(email: String): DiscoveredServerSettings? {
        val user = EmailHelper.getLocalPartFromEmailAddress(email) ?: return null
        val domain = EmailHelper.getDomainFromEmailAddress(email) ?: return null

        val username = incomingUsernameTemplate.fillInUsernameTemplate(email, user, domain)

        val xoauth2 = OAuth2Provider.isXOAuth2(domain)
        val xoauth2Label = if (xoauth2) AuthType.XOAUTH2.name else ""
        val xoauth2Colon = if (xoauth2) ":" else ""

        val security = when {
            incomingUriTemplate.startsWith("imap+ssl") -> ConnectionSecurity.SSL_TLS_REQUIRED
            incomingUriTemplate.startsWith("imap+tls") -> ConnectionSecurity.STARTTLS_REQUIRED
            else -> error("Connection security required")
        }

        val incomingUri = with(URI(incomingUriTemplate)) {
            URI(scheme, "$xoauth2Label$xoauth2Colon$username", host, port, null, null, null).toString()
        }
        val uri = Uri.parse(incomingUri)
        val host = uri.host ?: error("Host name required")
        val port = if (uri.port == -1) {
            if (security == ConnectionSecurity.STARTTLS_REQUIRED) 143 else 993
        } else {
            uri.port
        }

        val authType = if (xoauth2) AuthType.XOAUTH2 else AuthType.PLAIN
        return DiscoveredServerSettings(Protocols.IMAP, host, port, security, authType, username)
    }

    private fun Provider.toOutgoingServerSettings(email: String): DiscoveredServerSettings? {

        val user = EmailHelper.getLocalPartFromEmailAddress(email) ?: return null
        val domain = EmailHelper.getDomainFromEmailAddress(email) ?: return null

        val xoauth2 = OAuth2Provider.isXOAuth2(domain)
        val xoauth2Label = if (xoauth2) AuthType.XOAUTH2.name else ""
        val xoauth2Colon = if (xoauth2) ":" else ""

        val username = outgoingUsernameTemplate.fillInUsernameTemplate(email, user, domain)

        val security = when {
            outgoingUriTemplate.startsWith("smtp+ssl") -> ConnectionSecurity.SSL_TLS_REQUIRED
            outgoingUriTemplate.startsWith("smtp+tls") -> ConnectionSecurity.STARTTLS_REQUIRED
            else -> error("Connection security required")
        }

        val outgoingUri = with(URI(outgoingUriTemplate)) {
            URI(scheme, "$username$xoauth2Colon$xoauth2Label", host, port, null, null, null).toString()
        }

        val uri = Uri.parse(outgoingUri)
        val host = uri.host ?: error("Host name required")
        val port = if (uri.port == -1) {
            if (security == ConnectionSecurity.STARTTLS_REQUIRED) 587 else 465
        } else {
            uri.port
        }

        val authType = if (xoauth2) AuthType.XOAUTH2 else AuthType.PLAIN
        return DiscoveredServerSettings(Protocols.SMTP, host, port, security, authType, username)
    }

    private fun String.fillInUsernameTemplate(email: String, user: String, domain: String): String {
        return this.replace("\$email", email).replace("\$user", user).replace("\$domain", domain)
    }

    internal data class Provider(
        val incomingUriTemplate: String,
        val incomingUsernameTemplate: String,
        val outgoingUriTemplate: String,
        val outgoingUsernameTemplate: String
    )
}
