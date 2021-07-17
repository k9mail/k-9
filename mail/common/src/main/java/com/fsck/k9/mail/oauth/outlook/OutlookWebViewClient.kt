package com.fsck.k9.mail.oauth.outlook

import android.net.Uri
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2CodeGrantFlowManager
import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2WebViewClient

class OutlookWebViewClient(email: String, codeGrantFlowManager: OAuth2CodeGrantFlowManager) :
    OAuth2WebViewClient(email, codeGrantFlowManager) {
    override fun arrivedAtRedirectUri(uri: Uri?): Boolean {
        return "msal" + OutlookAuthorizationServer.CLIENT_ID == uri?.scheme
    }
}
