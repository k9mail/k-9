package com.fsck.k9.mail

class AuthenticationFailedException @JvmOverloads constructor(
    message: String,
    throwable: Throwable? = null,
    val messageFromServer: String? = null
) : MessagingException(message, throwable) {
    val isMessageFromServerAvailable = messageFromServer != null

    companion object {
        const val OAUTH2_ERROR_INVALID_REFRESH_TOKEN = "oauth2-invalid refresh token"
        const val OAUTH2_ERROR_UNKNOWN = "oauth2-unknown"
    }
}
