package com.fsck.k9.autodiscovery.providersxml

import android.content.res.XmlResourceParser
import com.fsck.k9.autodiscovery.api.ConnectionSettingsDiscovery
import com.fsck.k9.autodiscovery.api.DiscoveredServerSettings
import com.fsck.k9.autodiscovery.api.DiscoveryResults
import com.fsck.k9.autodiscovery.api.DiscoveryTarget
import com.fsck.k9.backend.BackendManager
import com.fsck.k9.helper.EmailHelper
import com.fsck.k9.helper.UrlEncodingHelper
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.oauth.OAuth2Provider
import java.net.URI
import java.net.URISyntaxException
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

class ProvidersXmlDiscovery(
    private val backendManager: BackendManager,
    private val xmlProvider: ProvidersXmlProvider
) : ConnectionSettingsDiscovery {

    override fun discover(email: String, target: DiscoveryTarget): DiscoveryResults? {
        val password = ""

        val user = EmailHelper.getLocalPartFromEmailAddress(email) ?: return null
        val domain = EmailHelper.getDomainFromEmailAddress(email) ?: return null

        val provider = findProviderForDomain(domain) ?: return null
        try {
            val xoauth2 = OAuth2Provider.isXOAuth2(domain)
            val xoauth2Label = if (xoauth2) AuthType.XOAUTH2.name else ""
            val xoauth2Colon = if (xoauth2) ":" else ""

            val userUrlEncoded = UrlEncodingHelper.encodeUtf8(user)
            val emailUrlEncoded = UrlEncodingHelper.encodeUtf8(email)

            val incomingUserUrlEncoded = provider.incomingUsernameTemplate
                    .replace("\$email", emailUrlEncoded)
                    .replace("\$user", userUrlEncoded)
                    .replace("\$domain", domain)
            val incomingUri = with(URI(provider.incomingUriTemplate)) {
                URI(scheme, "$xoauth2Label$xoauth2Colon$incomingUserUrlEncoded:$password", host, port, null, null, null).toString()
            }
            val incomingSettings = backendManager.decodeStoreUri(incomingUri).let {
                listOf(DiscoveredServerSettings(
                    it.type,
                    it.host,
                    it.port,
                    it.connectionSecurity,
                    it.authenticationType,
                    it.username
                ))
            }

            val outgoingUserUrlEncoded = provider.outgoingUsernameTemplate
                    ?.replace("\$email", emailUrlEncoded)
                    ?.replace("\$user", userUrlEncoded)
                    ?.replace("\$domain", domain)
            val outgoingUserInfo = if (outgoingUserUrlEncoded != null) "$outgoingUserUrlEncoded:$password$xoauth2Colon$xoauth2Label" else null
            val outgoingUri = with(URI(provider.outgoingUriTemplate)) {
                URI(scheme, outgoingUserInfo, host, port, null, null, null).toString()
            }
            val outgoingSettings = backendManager.decodeTransportUri(outgoingUri).let {
                listOf(DiscoveredServerSettings(
                    it.type,
                    it.host,
                    it.port,
                    it.connectionSecurity,
                    it.authenticationType,
                    it.username
                ))
            }

            return DiscoveryResults(incomingSettings, outgoingSettings)
        } catch (use: URISyntaxException) {
            return null
        }
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

        return if (incomingUriTemplate != null && incomingUsernameTemplate != null &&
                outgoingUriTemplate != null) {
            Provider(incomingUriTemplate, incomingUsernameTemplate, outgoingUriTemplate, outgoingUsernameTemplate)
        } else {
            null
        }
    }

    internal data class Provider(
        val incomingUriTemplate: String,
        val incomingUsernameTemplate: String,
        val outgoingUriTemplate: String,
        val outgoingUsernameTemplate: String?
    )
}
