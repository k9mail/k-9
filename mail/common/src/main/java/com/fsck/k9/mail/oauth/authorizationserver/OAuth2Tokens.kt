package com.fsck.k9.mail.oauth.authorizationserver

/**
 * OAuth2 tokens: access and refresh.
 * Access token: serve to access data on the resource server.
 * Refresh token: serve to renew the access token.
 * @see http://www.bubblecode.net/en/2016/01/22/understanding-oauth2/
 */
data class OAuth2Tokens(val accessToken: String, val refreshToken: String)
