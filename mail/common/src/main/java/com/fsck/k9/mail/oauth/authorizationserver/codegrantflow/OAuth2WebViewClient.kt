package com.fsck.k9.mail.oauth.authorizationserver.codegrantflow

import android.annotation.TargetApi
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fsck.k9.mail.AuthenticationFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Bass class for standard authorization code flow like Google's or Microsoft's
 */
abstract class OAuth2WebViewClient(
    private val email: String,
    private val codeGrantFlowManager: OAuth2CodeGrantFlowManager
) : WebViewClient() {
    protected abstract fun arrivedAtRedirectUri(uri: Uri?): Boolean

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return handleUrl(url)
    }

    @TargetApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return handleUrl(request.url.toString())
    }

    private fun handleUrl(url: String): Boolean {
        Timber.d(url)
        val uri = Uri.parse(url)
        if (arrivedAtRedirectUri(uri)) {
            val error = uri.getQueryParameter("error")
            if (error != null) {
                Timber.e("got oauth error: $error")
                codeGrantFlowManager.promptRequestHandler?.onError(error)
                return true
            }

            uri.getQueryParameter("code")?.let { oauthCode ->
                codeGrantFlowManager.promptRequestHandler?.onObtainCodeSuccessful()
                GlobalScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            codeGrantFlowManager.exchangeCode(email, oauthCode)
                            withContext(Dispatchers.Main) {
                                codeGrantFlowManager.promptRequestHandler?.onObtainAccessTokenSuccessful()
                            }
                        } catch (e: AuthenticationFailedException) {
                            withContext(Dispatchers.Main) {
                                codeGrantFlowManager.promptRequestHandler?.onError("Error when exchanging code")
                            }
                        }
                    }
                }
            } ?: run {
                codeGrantFlowManager.promptRequestHandler?.onError("No auth code")
            }
            return true
        }
        return false
    }
}
