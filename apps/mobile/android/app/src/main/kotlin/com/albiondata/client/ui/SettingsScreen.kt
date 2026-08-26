package com.albiondata.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.albiondata.client.data.AppSettings
import com.albiondata.client.data.Realm
import com.albiondata.client.ui.theme.AlbionDataClientTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit = {},
    onPrivateModeToggle: (Boolean) -> Unit = {},
    onRealmSelect: (Realm) -> Unit = {},
    onIngestUrlSave: (String) -> Unit = {},
    onLoginClick: () -> Unit = {},
    onLogoutClick: () -> Unit = {},
) {
    var urlDraft by remember(settings.ingestUrl) { mutableStateOf(settings.ingestUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Mode card ────────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Upload mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (settings.privateMode)
                            "Private — data sent to your private API. Google login required."
                        else
                            "Public — data shared with Albion Online Data Project (AODP). No login needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (settings.privateMode) "Private mode" else "Public mode",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(
                            checked = settings.privateMode,
                            onCheckedChange = onPrivateModeToggle,
                        )
                    }
                }
            }

            // ── Realm selector (public mode only) ────────────────────────────
            if (!settings.privateMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Game server region", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Select the region that matches your Albion Online server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Realm.entries.forEach { realm ->
                                FilterChip(
                                    selected = settings.realm == realm,
                                    onClick = { onRealmSelect(realm) },
                                    label = { Text(realm.label) },
                                )
                            }
                        }
                    }
                }
            }

            // ── Ingest URL card (private mode only) ─────────────────────────
            if (settings.privateMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Ingest URL", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlDraft,
                            onValueChange = { urlDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onIngestUrlSave(urlDraft) },
                            enabled = urlDraft.isNotBlank() && urlDraft != settings.ingestUrl,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Save URL")
                        }
                    }
                }
            }

            // ── Auth card (only relevant in private mode) ────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google account", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    if (!settings.privateMode) {
                        Text(
                            "Not required in public mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (settings.userEmail != null) {
                        Text(
                            "Signed in as ${settings.userEmail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onLogoutClick,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Sign out")
                        }
                    } else {
                        Text(
                            "Sign in to enable private data upload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onLoginClick) {
                            Text("Sign in with Google")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPublicPreview() {
    AlbionDataClientTheme {
        SettingsScreen(settings = AppSettings(privateMode = false))
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPublicEastPreview() {
    AlbionDataClientTheme {
        SettingsScreen(settings = AppSettings(privateMode = false, realm = Realm.EAST))
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPrivateLoggedInPreview() {
    AlbionDataClientTheme {
        SettingsScreen(
            settings = AppSettings(
                privateMode = true,
                userEmail = "user@example.com",
                authToken = "token",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPrivateLoggedOutPreview() {
    AlbionDataClientTheme {
        SettingsScreen(settings = AppSettings(privateMode = true))
    }
}
