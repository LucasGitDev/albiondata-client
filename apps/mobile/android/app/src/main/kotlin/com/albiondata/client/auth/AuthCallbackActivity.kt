package com.albiondata.client.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationException

/**
 * Trampoline activity that receives the OAuth redirect URI
 * (albiondata://oauth/callback) and forwards the result to whichever
 * activity started the auth flow via [OAuthManager.handleRedirectIntent].
 *
 * Declared with android:launchMode="singleTask" so the redirect never
 * spawns a duplicate instance.
 */
class AuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent) {
        // Forward the raw intent to OAuthManager; the pending result will be
        // delivered back to MainActivity via the ActivityResultLauncher callback.
        val broadcast = Intent(ACTION_OAUTH_RESPONSE).apply {
            `package` = packageName
            putExtras(intent)
        }
        sendBroadcast(broadcast)
        finish()
    }

    companion object {
        const val ACTION_OAUTH_RESPONSE = "com.albiondata.client.OAUTH_RESPONSE"
    }
}
