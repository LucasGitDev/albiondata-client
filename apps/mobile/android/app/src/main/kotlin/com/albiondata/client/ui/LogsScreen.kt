package com.albiondata.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.albiondata.client.data.LogEvent
import com.albiondata.client.data.LogEventType
import com.albiondata.client.ui.theme.AlbionDataClientTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<LogEvent>,
    onBack: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new log entries arrive.
    // Use lastOrNull()?.id so scroll triggers even when list is at max capacity
    // and size stays constant (oldest entry dropped, new one added).
    LaunchedEffect(logs.lastOrNull()?.id) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No logs yet. Start capture to see events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(logs, key = { it.id }) { event ->
                    LogEventRow(event)
                }
            }
        }
    }
}

@Composable
private fun LogEventRow(event: LogEvent) {
    val (chipColor, chipText) = when (event.type) {
        LogEventType.CAPTURE -> MaterialTheme.colorScheme.primaryContainer to "CAPTURE"
        LogEventType.UPLOAD  -> MaterialTheme.colorScheme.secondaryContainer to "UPLOAD"
        LogEventType.ERROR   -> MaterialTheme.colorScheme.errorContainer to "ERROR"
        LogEventType.INFO    -> MaterialTheme.colorScheme.surfaceVariant to "INFO"
    }
    val onChipColor = when (event.type) {
        LogEventType.CAPTURE -> MaterialTheme.colorScheme.onPrimaryContainer
        LogEventType.UPLOAD  -> MaterialTheme.colorScheme.onSecondaryContainer
        LogEventType.ERROR   -> MaterialTheme.colorScheme.onErrorContainer
        LogEventType.INFO    -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = event.formattedTime,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        SuggestionChip(
            onClick = {},
            label = { Text(chipText, style = MaterialTheme.typography.labelSmall) },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = chipColor,
                labelColor = onChipColor,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = event.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogsScreenPreview() {
    AlbionDataClientTheme {
        LogsScreen(
            logs = listOf(
                LogEvent(1, type = LogEventType.INFO, message = "VPN started"),
                LogEvent(2, type = LogEventType.CAPTURE, message = "100 packets captured"),
                LogEvent(3, type = LogEventType.UPLOAD, message = "Upload OK (200)"),
                LogEvent(4, type = LogEventType.ERROR, message = "Upload failed: timeout"),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogsScreenEmptyPreview() {
    AlbionDataClientTheme {
        LogsScreen(logs = emptyList())
    }
}
