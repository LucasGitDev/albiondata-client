package com.albiondata.client.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * Thin wrapper around AppAuth that builds the Google OAuth authorization
 * request and exchanges the auth code for tokens.
 *
 * Client ID is injected at build time via BuildConfig (see build.gradle.kts
 * placeholder; replace with real Google OAuth client ID from google-services.json).
 *
 * PKCE (S256) is enforced automatically by AppAuth per RFC 8252.
 * Custom redirect URI: albiondata://oauth/callback (handled by AuthCallbackActivity).
 */
class OAuthManager(context: Context) {

    private val authService = AuthorizationService(context)

    companion object {
        private const val REDIRECT_URI = "albiondata://oauth/callback"
        private const val SCOPE = "openid email profile"

        // Google's well-known OAuth 2.0 endpoints.
        private val AUTH_ENDPOINT = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
        private val TOKEN_ENDPOINT = Uri.parse("https://oauth2.googleapis.com/token")
    }

    /**
     * Returns an Intent that opens the Google authorization page in a Custom Tab.
     * Caller should launch it via startActivityForResult / ActivityResultLauncher.
     *
     * [clientId] — Google OAuth client ID (Android type, from google-services.json).
     */
    fun buildAuthIntent(clientId: String): Intent {
        val config = AuthorizationServiceConfiguration(AUTH_ENDPOINT, TOKEN_ENDPOINT)
        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setScopes(SCOPE)
            .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Exchanges the authorization code received in [responseIntent] for tokens.
     * Returns a [TokenResult] on success or null on failure/cancellation.
     */
    suspend fun exchangeCode(
        responseIntent: Intent,
        clientId: String,
    ): TokenResult? {
        val response = AuthorizationResponse.fromIntent(responseIntent) ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(
                response.createTokenExchangeRequest(),
            ) { tokenResponse, ex ->
                if (tokenResponse != null) {
                    val idToken = tokenResponse.idToken
                    val accessToken = tokenResponse.accessToken
                    val email = parseEmailFromIdToken(idToken)
                    cont.resume(
                        TokenResult(
                            accessToken = accessToken ?: "",
                            idToken = idToken ?: "",
                            email = email,
                        ),
                        null,
                    )
                } else {
                    cont.resume(null, null)
                }
            }
        }
    }

    fun destroy() {
        authService.dispose()
    }

    /** Very lightweight JWT payload decoder — no signature verification needed here. */
    private fun parseEmailFromIdToken(idToken: String?): String? {
        if (idToken == null) return null
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = String(
                android.util.Base64.decode(
                    payload.replace('-', '+').replace('_', '/'),
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                ),
            )
            val emailMatch = Regex("\"email\"\\s*:\\s*\"([^\"]+)\"").find(decoded)
            emailMatch?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
}

data class TokenResult(
    val accessToken: String,
    val idToken: String,
    val email: String?,
)
