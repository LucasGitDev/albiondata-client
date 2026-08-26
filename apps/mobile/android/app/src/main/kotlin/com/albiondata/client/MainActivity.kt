package com.albiondata.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.albiondata.client.auth.OAuthManager
import com.albiondata.client.ui.SettingsScreen
import com.albiondata.client.ui.StatusScreen
import com.albiondata.client.ui.theme.AlbionDataClientTheme
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

// TODO: Replace with real Google OAuth client ID from google-services.json.
// Left as a compile-time constant so it can be injected via buildConfigField in CI.
private const val GOOGLE_CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com"

private object Nav {
    const val STATUS = "status"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private lateinit var oauthManager: OAuthManager

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        oauthManager = OAuthManager(this)

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
                                // Stop capture if running in private mode.
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
        if (GOOGLE_CLIENT_ID.startsWith("YOUR_")) {
            Log.w(TAG, "Google OAuth client ID not configured — skipping auth flow")
            return
        }
        val authIntent = oauthManager.buildAuthIntent(GOOGLE_CLIENT_ID)
        oauthLauncher.launch(authIntent)
    }

    private fun handleOAuthResult(data: Intent) {
        // Launch coroutine to exchange code for tokens.
        // coroutineScope not available here; use lifecycle scope.
        androidx.lifecycle.lifecycleScope.launch {
            val result = oauthManager.exchangeCode(data, GOOGLE_CLIENT_ID)
            if (result != null) {
                viewModel.saveAuthToken(result.accessToken, result.email ?: "")
                Log.i(TAG, "OAuth success — email=${result.email}")
            } else {
                Log.w(TAG, "OAuth token exchange failed or cancelled")
            }
        }
    }
}
