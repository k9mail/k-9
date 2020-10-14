package com.fsck.k9.mail.oauth.gmail

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.AuthenticationFailedException.Companion.OAUTH2_ERROR_INVALID_REFRESH_TOKEN
import com.fsck.k9.mail.AuthenticationFailedException.Companion.OAUTH2_ERROR_UNKNOWN
import com.fsck.k9.mail.common.BuildConfig
import com.fsck.k9.mail.oauth.authorizationserver.AuthorizationServer
import com.fsck.k9.mail.oauth.authorizationserver.OAuth2Tokens
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import timber.log.Timber

class GmailAuthorizationServer : AuthorizationServer {

    private val service: GoogleRestApi

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(GOOGLE_API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        service = retrofit.create(GoogleRestApi::class.java)
    }

    override fun exchangeCode(email: String, code: String): OAuth2Tokens? {
        val call: Call<ExchangeResponse>? =
            service.exchangeCode(code, BuildConfig.GOOGLE_CLIENT_ID, "authorization_code", REDIRECT_URI)
        val exchangeResponse: ExchangeResponse?
        val response: Response<ExchangeResponse>?
        try {
            response = call?.execute()
            exchangeResponse = response?.body()
        } catch (e: Exception) {
            throw AuthenticationFailedException(e.message!!)
        }

        return when {
            exchangeResponse == null -> null
            exchangeResponse.accessToken.isNullOrEmpty() -> null
            else -> OAuth2Tokens(exchangeResponse.accessToken!!, exchangeResponse.refreshToken!!)
        }
    }

    override fun refreshToken(email: String, refreshToken: String): String? {
        val call: Call<RefreshResponse>? =
            service.refreshToken(BuildConfig.GOOGLE_CLIENT_ID, refreshToken, "refresh_token")
        val response: Response<RefreshResponse>?
        val refreshResponse: RefreshResponse?
        try {
            response = call?.execute()
            refreshResponse = response?.body()
        } catch (e: IOException) {
            throw AuthenticationFailedException(e.message!!)
        }

        refreshResponse?.let {
            return it.accessToken
        } ?: run {
            response?.let {
                try {
                    val errorBody = response.errorBody()?.string()
                    val oAuth2Error =
                        Gson().fromJson(errorBody, OAuth2Error::class.java)
                    when (oAuth2Error.error) {
                        "invalid_grant" -> throw AuthenticationFailedException(OAUTH2_ERROR_INVALID_REFRESH_TOKEN)
                        else -> throw AuthenticationFailedException(OAUTH2_ERROR_UNKNOWN)
                    }
                } catch (e: IOException) {
                    throw AuthenticationFailedException(OAUTH2_ERROR_UNKNOWN)
                }
            } ?: run {
                throw AuthenticationFailedException(OAUTH2_ERROR_UNKNOWN)
            }
        }
    }

    override fun getAuthorizationUrl(email: String): String {
        if (BuildConfig.GOOGLE_CLIENT_ID == "null") {
            throw IllegalStateException("GOOGLE_CLIENT_ID is empty")
        }
        Timber.d("AUTHORIZATION_URL" + AUTHORIZATION_URL)
        return "$AUTHORIZATION_URL&login_hint=$email"
    }

    private class OAuth2Error {
        var error: String? = null

        @SerializedName("error_description")
        var errorDescription: String? = null
    }

    private class ExchangeResponse {
        @SerializedName("access_token")
        var accessToken: String? = null

        @SerializedName("id_token")
        var idToken: String? = null

        @SerializedName("refresh_token")
        var refreshToken: String? = null

        @SerializedName("expires_in")
        var expiresIn = 0

        @SerializedName("token_type")
        var tokenType: String? = null
    }

    private class RefreshResponse {
        @SerializedName("access_token")
        var accessToken: String? = null

        @SerializedName("expires_in")
        var expiresIn = 0

        @SerializedName("token_type")
        var tokenType: String? = null
    }

    private interface GoogleRestApi {
        @FormUrlEncoded
        @POST("oauth2/v4/token")
        fun refreshToken(
            @Field("client_id") clientId: String,
            @Field("refresh_token") refreshToken: String,
            @Field("grant_type") grantType: String
        ): Call<RefreshResponse>?

        @FormUrlEncoded
        @POST("oauth2/v4/token")
        fun exchangeCode(
            @Field("code") code: String,
            @Field("client_id") clientId: String,
            @Field("grant_type") grantType: String,
            @Field("redirect_uri") redirectUri: String
        ): Call<ExchangeResponse>?
    }

    companion object {
        private const val GOOGLE_API_BASE_URL = "https://www.googleapis.com/"
        private const val REDIRECT_URI =
            BuildConfig.GOOGLE_CLIENT_ID_PACKAGE_NAME + ":/oauth2redirect"
        private const val AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "scope=https://mail.google.com/&" +
            "response_type=code&" +
            "redirect_uri=" + REDIRECT_URI + "&" +
            "client_id=" + BuildConfig.GOOGLE_CLIENT_ID
    }
}
