package com.fsck.k9.mail.oauth.gmail

import android.net.Uri
import com.fsck.k9.mail.common.BuildConfig
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2CodeGrantFlowManager
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2WebViewClient

class GmailWebViewClient(email: String, codeGrantFlowManager: OAuth2CodeGrantFlowManager) :
    OAuth2WebViewClient(email, codeGrantFlowManager) {
    override fun arrivedAtRedirectUri(uri: Uri?): Boolean {
        return BuildConfig.GOOGLE_CLIENT_ID_PACKAGE_NAME == uri!!.scheme
    }
}
