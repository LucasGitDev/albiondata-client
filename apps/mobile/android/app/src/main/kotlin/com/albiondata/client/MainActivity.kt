package com.albiondata.client

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.albiondata.client.auth.AuthCallbackActivity
import com.albiondata.client.auth.AuthManager
import com.albiondata.client.auth.OAuthManager
import com.albiondata.client.ui.SettingsScreen
import com.albiondata.client.ui.StatusScreen
import com.albiondata.client.ui.theme.AlbionDataClientTheme
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

private object Nav {
    const val STATUS = "status"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private lateinit var oauthManager: OAuthManager
    private val authManager by lazy { AuthManager(applicationContext) }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchVpnService()
        }
    }

    private val oauthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            handleOAuthResult(result.data!!)
        }
    }

    // Broadcast receiver for auth state changes emitted by AuthCallbackActivity / VpnService.
    private val authReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AuthCallbackActivity.ACTION_LOGIN_SUCCESS -> {
                    val token = authManager.tokenStore.accessToken ?: return
                    val email = authManager.tokenStore.userEmail ?: ""
                    viewModel.saveAuthToken(token, email)
                    Log.i(TAG, "Auth broadcast: login success — email=$email")
                }
                AuthCallbackActivity.ACTION_LOGIN_FAILED -> {
                    Log.w(TAG, "Auth broadcast: login failed")
                }
                AuthManager.ACTION_AUTH_EXPIRED -> {
                    viewModel.logout()
                    Log.w(TAG, "Auth broadcast: session expired")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        oauthManager = OAuthManager(this)

        val filter = IntentFilter().apply {
            addAction(AuthCallbackActivity.ACTION_LOGIN_SUCCESS)
            addAction(AuthCallbackActivity.ACTION_LOGIN_FAILED)
            addAction(AuthManager.ACTION_AUTH_EXPIRED)
        }
        registerReceiver(authReceiver, filter, RECEIVER_NOT_EXPORTED)

        setContent {
            AlbionDataClientTheme {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsState()
                val scope = rememberCoroutineScope()

                NavHost(navController = navController, startDestination = Nav.STATUS) {
                    composable(Nav.STATUS) {
                        StatusScreen(
                            uiState = uiState,
                            onStartCapture = { requestVpnPermission() },
                            onStopCapture = { stopVpnService() },
                            onOpenSettings = { navController.navigate(Nav.SETTINGS) },
                            onLoginRequired = { navController.navigate(Nav.SETTINGS) },
                        )
                    }
                    composable(Nav.SETTINGS) {
                        SettingsScreen(
                            settings = uiState.settings,
                            onBack = { navController.popBackStack() },
                            onPrivateModeToggle = { enabled ->
                                viewModel.setPrivateMode(enabled)
                            },
                            onIngestUrlSave = { url -> viewModel.setIngestUrl(url) },
                            onLoginClick = { startOAuthFlow() },
                            onLogoutClick = {
                                viewModel.logout()
                                authManager.logout()
                                if (uiState.captureRunning) stopVpnService()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        oauthManager.destroy()
        unregisterReceiver(authReceiver)
        super.onDestroy()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val intent = Intent(this, PacketCaptureVpnService::class.java).apply {
            action = PacketCaptureVpnService.ACTION_START
        }
        startForegroundService(intent)
        viewModel.setCaptureRunning(true)
    }

    private fun stopVpnService() {
        val intent = Intent(this, PacketCaptureVpnService::class.java).apply {
            action = PacketCaptureVpnService.ACTION_STOP
        }
        startService(intent)
        viewModel.setCaptureRunning(false)
    }

    private fun startOAuthFlow() {
        val clientId = BuildConfig.GOOGLE_CLIENT_ID
        if (clientId.isBlank() || clientId.startsWith("YOUR_")) {
            Log.w(TAG, "Google OAuth client ID not configured — skipping auth flow")
            return
        }
        val authIntent = authManager.buildAuthIntent(clientId)
        oauthLauncher.launch(authIntent)
    }

    private fun handleOAuthResult(data: Intent) {
        // Fallback path: if AppAuth returns result via onActivityResult (non-Custom Tab flow).
        // The primary path is AuthCallbackActivity broadcast.
        lifecycleScope.launch {
            val result = oauthManager.exchangeCode(data, BuildConfig.GOOGLE_CLIENT_ID)
            if (result != null) {
                viewModel.saveAuthToken(result.accessToken, result.email ?: "")
                Log.i(TAG, "OAuth direct result — email=${result.email}")
            }
        }
    }
}
