package com.fsck.k9.mail.oauth.authorizationserver

import com.fsck.k9.mail.AuthenticationFailedException

/**
 * Oauth2 rely on authorization server:
 * An authorization code grant flow procedure in a webview (it is the authorization server which provide pages).
 * The exchange code serve to get access code and refresh token from authentication server.
 * The refresh token serve to renew the access token.
 * This interface regroup method needed for user authorization code grant flow procedure and for refreshing token.
 * @see http://www.bubblecode.net/en/2016/01/22/understanding-oauth2/
 */
interface AuthorizationServer {

    /**
     * Get the url for authentication code grant flow procedure.
     */
    fun getAuthorizationUrl(email: String): String

    /**
     * At the end of the user authentication flow procedure, we got a code.
     * This request permit to get the tokens from this code.
     * @param code the code obtain at the end of user authentication flow procedure.
     */
    @Throws(AuthenticationFailedException::class)
    fun exchangeCode(email: String, code: String): OAuth2Tokens?

    /**
     * Get new access token with refresh token
     * @param refreshToken refresh token got before
     */
    @Throws(AuthenticationFailedException::class)
    fun refreshToken(email: String, refreshToken: String): String?
}
