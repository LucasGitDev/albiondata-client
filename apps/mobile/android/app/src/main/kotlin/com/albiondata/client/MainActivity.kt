package com.albiondata.client

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.albiondata.client.ui.theme.AlbionDataClientTheme

class MainActivity : ComponentActivity() {

    // Known limitation (TASK-11.10): captureRunning is Activity-local state.
    // If the OS restarts the service via START_STICKY, the UI will incorrectly show idle.
    // Acceptable for validation phase; must be replaced with service-bound state before prod.
    private var captureRunning by mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AlbionDataClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StatusScreen(
                        modifier = Modifier.padding(innerPadding),
                        captureRunning = captureRunning,
                        onStartCapture = { requestVpnPermission() },
                        onStopCapture = { stopVpnService() },
                    )
                }
            }
        }
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
        captureRunning = true
    }

    private fun stopVpnService() {
        val intent = Intent(this, PacketCaptureVpnService::class.java).apply {
            action = PacketCaptureVpnService.ACTION_STOP
        }
        startService(intent)
        captureRunning = false
    }
}

@Composable
fun StatusScreen(
    modifier: Modifier = Modifier,
    captureRunning: Boolean = false,
    onStartCapture: () -> Unit = {},
    onStopCapture: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Albion Data Client",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (captureRunning) "Status: capturing" else "Status: idle",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = onStartCapture,
                enabled = !captureRunning,
            ) {
                Text(text = stringResource(R.string.start_capture))
            }
            Button(
                onClick = onStopCapture,
                enabled = captureRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(text = stringResource(R.string.stop_capture))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StatusScreenPreview() {
    AlbionDataClientTheme {
        StatusScreen(captureRunning = false)
    }
}
