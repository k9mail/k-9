package com.fsck.k9.autodiscovery.providersxml

import androidx.test.core.app.ApplicationProvider
import com.fsck.k9.RobolectricTest
import com.fsck.k9.autodiscovery.api.DiscoveryTarget
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProvidersXmlDiscoveryTest : RobolectricTest() {
    private val xmlProvider = ProvidersXmlProvider(ApplicationProvider.getApplicationContext())
    private val providersXmlDiscovery = ProvidersXmlDiscovery(xmlProvider)

    @Test
    fun discover_withGmailDomain_shouldReturnCorrectSettings() {
        val connectionSettings = providersXmlDiscovery.discover("user@gmail.com", DiscoveryTarget.INCOMING_AND_OUTGOING)

        assertThat(connectionSettings).isNotNull()
        with(connectionSettings!!.incoming.first()) {
            assertThat(host).isEqualTo("imap.gmail.com")
            assertThat(security).isEqualTo(ConnectionSecurity.SSL_TLS_REQUIRED)
            assertThat(authType).isEqualTo(AuthType.XOAUTH2)
            assertThat(username).isEqualTo("user@gmail.com")
        }
        with(connectionSettings.outgoing.first()) {
            assertThat(host).isEqualTo("smtp.gmail.com")
            assertThat(security).isEqualTo(ConnectionSecurity.SSL_TLS_REQUIRED)
            assertThat(authType).isEqualTo(AuthType.XOAUTH2)
            assertThat(username).isEqualTo("user@gmail.com")
        }
    }

    @Test
    fun discover_withUnknownDomain_shouldReturnNull() {
        val connectionSettings = providersXmlDiscovery.discover(
            "user@not.present.in.providers.xml.example", DiscoveryTarget.INCOMING_AND_OUTGOING
        )

        assertThat(connectionSettings).isNull()
    }
}
