package com.fsck.k9.mail.oauth.outlook

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.authorizationserver.AuthorizationServer
import com.fsck.k9.mail.oauth.authorizationserver.OAuth2Tokens
import com.google.gson.annotations.SerializedName
import java.io.IOException
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

class OutlookAuthorizationServer : AuthorizationServer {

    private val service: OutlookService

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(OUTLOOK_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        service = retrofit.create(OutlookService::class.java)
    }

    override fun getAuthorizationUrl(email: String): String {
        return "$AUTHORIZATION_URL&login_hint=$email"
    }

    override fun exchangeCode(email: String, code: String): OAuth2Tokens? {
        val call =
            service.exchangeCode(code, CLIENT_ID, "authorization_code", REDIRECT_URI)
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
        val call =
            service.refreshToken(CLIENT_ID, refreshToken, "refresh_token", "https://login.live.com/oauth20_desktop.srf")
        val response: RefreshResponse?
        response = try {
            call?.execute()?.body()
        } catch (e: IOException) {
            throw AuthenticationFailedException(e.message!!)
        }
        if (response == null) {
            throw AuthenticationFailedException("Error when getting refresh token")
        }
        return response.accessToken
    }

    private interface OutlookService {
        @FormUrlEncoded
        @POST("oauth20_token.srf")
        fun refreshToken(
            @Field("client_id") clientId: String,
            @Field("refresh_token") refreshToken: String,
            @Field("grant_type") grantType: String,
            @Field("redirect_uri") redirectUri: String
        ): Call<RefreshResponse>?

        @FormUrlEncoded
        @POST("oauth20_token.srf")
        fun exchangeCode(
            @Field("code") code: String,
            @Field("client_id") clientId: String,
            @Field("grant_type") grantType: String,
            @Field("redirect_uri") redirectUri: String
        ): Call<ExchangeResponse>?
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

    companion object {
        private const val OUTLOOK_BASE_URL = "https://login.live.com/"
        const val CLIENT_ID = "309d8028-bdcb-4c7a-8d3e-990247a02474"
        private const val REDIRECT_URI = "msal$CLIENT_ID://auth"
        private const val AUTHORIZATION_URL = "https://login.live.com/oauth20_authorize.srf?" +
            "client_id=" + CLIENT_ID + "&" +
            "scope=wl.imap wl.offline_access&" +
            "response_type=code&" +
            "redirect_uri=" + REDIRECT_URI
    }
}
