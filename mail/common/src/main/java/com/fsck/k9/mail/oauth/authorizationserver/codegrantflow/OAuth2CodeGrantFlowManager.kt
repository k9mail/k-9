package com.fsck.k9.mail.oauth.authorizationserver.codegrantflow

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.OAuth2Provider
import com.fsck.k9.mail.oauth.OAuth2TokensStore

/**
 * Manage the user authentication code grant flow procedure.
 */
class OAuth2CodeGrantFlowManager(private val tokensStore: OAuth2TokensStore) {

    var promptRequestHandler: OAuth2PromptRequestHandler? = null

    @Throws(AuthenticationFailedException::class)
    fun showAuthDialog(email: String) {
        OAuth2Provider.getProvider(email)?.let { provider ->
            promptRequestHandler?.handleRedirectUrl(provider.webViewClient(email, this),
                provider.authorizationServer.getAuthorizationUrl(email))
        }
    }

    @Throws(AuthenticationFailedException::class)
    fun exchangeCode(email: String, code: String) {
        OAuth2Provider.getProvider(email)?.let {
            it.authorizationServer.exchangeCode(email, code)?.let { tokens ->
                tokensStore.saveAccessToken(email, tokens.accessToken)
                tokensStore.saveRefreshToken(email, tokens.refreshToken)
            }
        }
    }

    fun invalidateRefreshToken(email: String) {
        tokensStore.invalidateRefreshToken(email)
    }
}
