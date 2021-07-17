package com.fsck.k9.mail.oauth.authorizationserver.codegrantflow

import android.webkit.WebViewClient

interface OAuth2PromptRequestHandler {
    fun handleRedirectUrl(webViewClient: WebViewClient, url: String)

    fun onObtainCodeSuccessful()

    fun onObtainAccessTokenSuccessful()

    fun onError(errorMessage: String)
}
