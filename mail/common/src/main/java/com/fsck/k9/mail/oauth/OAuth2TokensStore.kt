package com.fsck.k9.mail.oauth

import android.content.Context
import android.content.SharedPreferences
import java.util.HashMap

/**
 * Store access and refresh token.
 * Access token are in RAM. Refresh token are saved in sharedPreferences.
 */
class OAuth2TokensStore(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(REFRESH_TOKEN_SP, Context.MODE_PRIVATE)

    private val accessTokens: HashMap<String, String> = HashMap()

    fun saveAccessToken(email: String, token: String) {
        accessTokens[email] = token
    }

    fun getAccessToken(email: String): String? {
        return accessTokens[email]
    }

    fun invalidateAccessToken(email: String) {
        accessTokens.remove(email)
    }

    fun saveRefreshToken(email: String, token: String) {
        sharedPreferences.edit().putString(email, token).apply()
    }

    fun getRefreshToken(email: String): String? {
        return sharedPreferences.getString(email, null)
    }

    fun invalidateRefreshToken(email: String) {
        sharedPreferences.edit().remove(email).apply()
    }

    companion object {
        private const val REFRESH_TOKEN_SP = "refresh_token"
    }
}
