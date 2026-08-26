package com.albiondata.client.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages the Google OAuth PKCE flow for Android.
 *
 * Flow:
 *  1. [buildAuthIntent] — caller launches the returned Intent (Custom Tab or system browser).
 *  2. AuthCallbackActivity receives the redirect → calls [handleAuthResponse].
 *  3. [handleAuthResponse] exchanges the code for tokens and stores them via [TokenStore].
 *
 * Background refresh: [refreshTokenIfNeeded] is designed to be called from VpnService before
 * each upload batch. It performs a direct token refresh request without UI.
 */
class AuthManager(context: Context) {

    companion object {
        private const val TAG = "AuthManager"

        private const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val GOOGLE_USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo"
        private const val REDIRECT_URI = "albiondata://oauth/callback"

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
        )

        /** Broadcast action sent when a background refresh fails with invalid_grant. */
        const val ACTION_AUTH_EXPIRED = "com.albiondata.client.AUTH_EXPIRED"
    }

    private val appContext = context.applicationContext
    val tokenStore = TokenStore(appContext)
    private val authService = AuthorizationService(appContext)
    private val httpClient = OkHttpClient()

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(GOOGLE_AUTH_ENDPOINT),
        Uri.parse(GOOGLE_TOKEN_ENDPOINT),
    )

    /**
     * Builds an Intent that opens the Google OAuth consent screen in a Custom Tab.
     * The caller must use [android.app.Activity.startActivity] or an ActivityResultLauncher.
     *
     * @param clientId The Google OAuth client ID (from BuildConfig or google-services.json).
     */
    fun buildAuthIntent(clientId: String): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setScopes(SCOPES)
            .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Handles the redirect Intent received by [com.albiondata.client.auth.AuthCallbackActivity].
     * Exchanges the authorization code for tokens and persists them.
     *
     * @param intent The Intent delivered to AuthCallbackActivity.
     * @param clientId The Google OAuth client ID.
     * @throws Exception if the exchange fails.
     */
    suspend fun handleAuthResponse(intent: Intent, clientId: String) {
        val authResponse = AuthorizationResponse.fromIntent(intent)
        val authException = AuthorizationException.fromIntent(intent)

        if (authException != null) {
            throw authException
        }
        requireNotNull(authResponse) { "No authorization response in intent" }

        val tokenResponse = exchangeCodeForToken(authResponse, clientId)
        val accessToken = requireNotNull(tokenResponse.accessToken) { "No access token in response" }

        // Fetch user info to get email and sub
        val (email, sub) = fetchUserInfo(accessToken)

        tokenStore.saveTokens(
            accessToken = accessToken,
            refreshToken = tokenResponse.refreshToken,
            expiresInSeconds = tokenResponse.accessTokenExpirationTime?.let {
                (it - System.currentTimeMillis()) / 1000
            },
            email = email,
            sub = sub,
        )
        Log.i(TAG, "OAuth login complete. email=$email")
    }

    /**
     * Checks if the stored access token is near expiry and refreshes it if so.
     * Safe to call from VpnService background coroutine — no UI required.
     *
     * @param clientId The Google OAuth client ID.
     * @return The current valid access token, or null if the user is not logged in.
     * @throws TokenExpiredException when the refresh token is invalid/revoked (caller should
     *   broadcast [ACTION_AUTH_EXPIRED] and stop authenticated uploads).
     */
    suspend fun refreshTokenIfNeeded(clientId: String): String? {
        if (tokenStore.ingestMode != "private") return null
        if (tokenStore.isAccessTokenValid()) return tokenStore.accessToken

        val refreshToken = tokenStore.refreshToken ?: return null

        Log.i(TAG, "Access token near expiry — refreshing")
        val request = TokenRequest.Builder(serviceConfig, clientId)
            .setGrantType("refresh_token")
            .setRefreshToken(refreshToken)
            .build()

        val response = performTokenRequest(request, NoClientAuthentication.INSTANCE)
        val newAccessToken = response.accessToken ?: return null

        tokenStore.saveTokens(
            accessToken = newAccessToken,
            refreshToken = response.refreshToken ?: refreshToken,
            expiresInSeconds = response.accessTokenExpirationTime?.let {
                (it - System.currentTimeMillis()) / 1000
            },
        )
        Log.i(TAG, "Token refreshed successfully")
        return newAccessToken
    }

    /** Clears all stored credentials. Caller should update Go layer via MobileCollector. */
    fun logout() {
        tokenStore.clear()
        Log.i(TAG, "User logged out — tokens cleared")
    }

    // ---- private helpers ----

    private suspend fun exchangeCodeForToken(
        authResponse: AuthorizationResponse,
        clientId: String,
    ): TokenResponse {
        val request = authResponse.createTokenExchangeRequest()
        return performTokenRequest(request, NoClientAuthentication.INSTANCE)
    }

    private suspend fun performTokenRequest(
        request: TokenRequest,
        clientAuth: ClientAuthentication,
    ): TokenResponse = suspendCoroutine { cont ->
        authService.performTokenRequest(request, clientAuth) { response, ex ->
            when {
                ex != null -> {
                    val isInvalidGrant = ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
                    if (isInvalidGrant) {
                        cont.resumeWithException(TokenExpiredException("Refresh token invalid or revoked", ex))
                    } else {
                        cont.resumeWithException(ex)
                    }
                }
                response != null -> cont.resume(response)
                else -> cont.resumeWithException(IllegalStateException("Empty token response"))
            }
        }
    }

    private suspend fun fetchUserInfo(accessToken: String): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(GOOGLE_USERINFO_ENDPOINT)
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                val body = httpClient.newCall(req).execute().use { it.body?.string() }
                    ?: return@withContext Pair(null, null)
                val json = JSONObject(body)
                Pair(
                    json.optString("email").takeIf { it.isNotEmpty() },
                    json.optString("id").takeIf { it.isNotEmpty() },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch user info", e)
                Pair(null, null)
            }
        }
}

/** Thrown when a refresh token is invalid or revoked — caller must re-authenticate the user. */
class TokenExpiredException(message: String, cause: Throwable? = null) : Exception(message, cause)
