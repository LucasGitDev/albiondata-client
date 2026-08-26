package com.albiondata.client.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.albiondata.client.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Transparent trampoline activity that captures the OAuth redirect URI
 * (`albiondata://oauth/callback`) and delegates token exchange to [AuthManager].
 *
 * After exchange, it sends a broadcast so MainActivity / VpnService can react,
 * then finishes immediately — it is never shown to the user.
 */
class AuthCallbackActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AuthCallback"

        /** Broadcast sent on successful token exchange. */
        const val ACTION_LOGIN_SUCCESS = "com.albiondata.client.LOGIN_SUCCESS"

        /** Broadcast sent when token exchange fails. */
        const val ACTION_LOGIN_FAILED = "com.albiondata.client.LOGIN_FAILED"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val authManager by lazy { AuthManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }

        val clientId = BuildConfig.GOOGLE_CLIENT_ID
        if (clientId.isBlank()) {
            Log.e(TAG, "GOOGLE_CLIENT_ID is not set in BuildConfig")
            broadcastFailure("GOOGLE_CLIENT_ID not configured")
            finish()
            return
        }

        scope.launch {
            try {
                authManager.handleAuthResponse(intent, clientId)
                sendBroadcast(Intent(ACTION_LOGIN_SUCCESS))
                Log.i(TAG, "Login successful — broadcast sent")
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                broadcastFailure(e.message ?: "Unknown error")
            } finally {
                finish()
            }
        }
    }

    private fun broadcastFailure(message: String) {
        val intent = Intent(ACTION_LOGIN_FAILED).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        sendBroadcast(intent)
    }
}
