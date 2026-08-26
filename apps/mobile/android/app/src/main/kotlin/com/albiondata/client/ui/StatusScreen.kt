package com.albiondata.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.albiondata.client.UiState
import com.albiondata.client.data.AppSettings
import com.albiondata.client.ui.theme.AlbionDataClientTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    uiState: UiState,
    onStartCapture: () -> Unit = {},
    onStopCapture: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onLoginRequired: () -> Unit = {},
) {
    val settings = uiState.settings
    val canStart = !uiState.captureRunning &&
        (!settings.privateMode || settings.authToken != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albion Data Client") },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Status card ──────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.captureRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusRow(label = "VPN", value = if (uiState.captureRunning) "Active" else "Idle")
                    StatusRow(label = "Packets captured", value = uiState.packetCount.toString())
                    StatusRow(
                        label = "Last upload",
                        value = uiState.lastUploadStatus ?: "—",
                    )
                }
            }

            // ── Mode card ────────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusRow(
                        label = "Upload mode",
                        value = if (settings.privateMode) "Private" else "Public (AODP)",
                    )
                    StatusRow(
                        label = "Auth",
                        value = when {
                            !settings.privateMode -> "Not required"
                            settings.userEmail != null -> "Signed in as ${settings.userEmail}"
                            else -> "Not signed in"
                        },
                    )
                    StatusRow(label = "Ingest URL", value = settings.ingestUrl)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Private mode login prompt ─────────────────────────────────────
            if (settings.privateMode && settings.authToken == null && !uiState.captureRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Sign in required",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Private mode requires a Google account. Sign in via Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onLoginRequired,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Go to Settings")
                        }
                    }
                }
            }

            // ── Controls ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = onStartCapture,
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start Capture")
                }
                Button(
                    onClick = onStopCapture,
                    enabled = uiState.captureRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop Capture")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.5f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusIdlePreview() {
    AlbionDataClientTheme {
        StatusScreen(uiState = UiState())
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusCapturingPreview() {
    AlbionDataClientTheme {
        StatusScreen(
            uiState = UiState(
                captureRunning = true,
                packetCount = 1234,
                lastUploadStatus = "OK (200)",
                settings = AppSettings(privateMode = true, userEmail = "user@example.com", authToken = "tok"),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusPrivateNoAuthPreview() {
    AlbionDataClientTheme {
        StatusScreen(
            uiState = UiState(
                settings = AppSettings(privateMode = true),
            ),
        )
    }
}
