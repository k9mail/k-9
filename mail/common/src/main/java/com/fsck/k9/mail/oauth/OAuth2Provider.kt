package com.fsck.k9.mail.oauth

import com.fsck.k9.mail.oauth.authorizationserver.AuthorizationServer
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2CodeGrantFlowManager
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2WebViewClient
import com.fsck.k9.mail.oauth.gmail.GmailAuthorizationServer
import com.fsck.k9.mail.oauth.gmail.GmailWebViewClient
import com.fsck.k9.mail.oauth.outlook.OutlookAuthorizationServer
import com.fsck.k9.mail.oauth.outlook.OutlookWebViewClient

enum class OAuth2Provider(
    val authorizationServer: AuthorizationServer,
    val isInDomain: (String) -> Boolean,
    val webViewClient: (String, OAuth2CodeGrantFlowManager) -> OAuth2WebViewClient
) {
    GMAIL(GmailAuthorizationServer(), {
        it in listOf("gmail.com", "android.com", "google.com", "googlemail.com")
    }, { email: String, codeGrantFlowManager: OAuth2CodeGrantFlowManager ->
        GmailWebViewClient(email, codeGrantFlowManager)
    }),
    OUTLOOK(OutlookAuthorizationServer(), {
        val domainWoExt = it.split('.')[0]
        domainWoExt in listOf("hotmail", "live", "msn", "outlook")
    }, { email: String, codeGrantFlowManager: OAuth2CodeGrantFlowManager ->
        OutlookWebViewClient(email, codeGrantFlowManager)
    });

    companion object {
        private fun getTypeFromDomain(domain: String): OAuth2Provider? {
            return values().firstOrNull { it.isInDomain(domain) }
        }

        fun getProvider(email: String): OAuth2Provider? {
            val domain = email.split("@".toRegex()).toTypedArray()[1]
            return getTypeFromDomain(domain)
        }

        fun isXOAuth2(domain: String): Boolean {
            return values().any { it.isInDomain(domain) }
        }
    }
}
