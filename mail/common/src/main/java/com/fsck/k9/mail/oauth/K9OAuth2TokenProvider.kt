package com.fsck.k9.mail.oauth

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.authorizationserver.AuthorizationServer
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2CodeGrantFlowManager
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2NeedUserPromptException

class K9OAuth2TokenProvider(
    private val tokensStore: OAuth2TokensStore,
    private val oAuth2CodeGrantFlowManager: OAuth2CodeGrantFlowManager
) : OAuth2TokenProvider {

    @Throws(AuthenticationFailedException::class, OAuth2NeedUserPromptException::class)
    override fun getToken(email: String, timeoutMillis: Long): String {
        if (tokensStore.getAccessToken(email) == null) {
            val refreshToken = tokensStore.getRefreshToken(email)
            if (refreshToken != null) {
                try {
                    refreshToken(email, refreshToken)
                } catch (e: Exception) {
                    throw AuthenticationFailedException(e.message!!)
                }
            } else {
                oAuth2CodeGrantFlowManager.showAuthDialog(email)
                throw OAuth2NeedUserPromptException()
            }
        }
        return tokensStore.getAccessToken(email)!!
    }

    override fun invalidateToken(email: String) {
        tokensStore.invalidateAccessToken(email)
    }

    @Throws(AuthenticationFailedException::class)
    private fun refreshToken(email: String, refreshToken: String) {
        val server = getAuthorizationServer(email)
        server?.refreshToken(email, refreshToken)?.let { newToken ->
            tokensStore.saveAccessToken(email, newToken)
        }
    }

    private fun getAuthorizationServer(email: String): AuthorizationServer? {
        return OAuth2Provider.getProvider(email)?.authorizationServer
    }
}
