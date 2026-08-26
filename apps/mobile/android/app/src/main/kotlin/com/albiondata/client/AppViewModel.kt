package com.albiondata.client

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albiondata.client.auth.AuthManager
import com.albiondata.client.data.AppSettings
import com.albiondata.client.data.LogEvent
import com.albiondata.client.data.LogEventType
import com.albiondata.client.data.Realm
import com.albiondata.client.data.SettingsRepository
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_LOG_ENTRIES = 500

data class UiState(
    val captureRunning: Boolean = false,
    val packetCount: Long = 0L,
    val lastUploadStatus: String? = null,
    val settings: AppSettings = AppSettings(),
    val logEvents: List<LogEvent> = emptyList(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)
    private var logIdCounter = 0L
    // Circular buffer: O(1) append at capacity vs repeated drop(1) list copies
    private val logBuffer = ArrayDeque<LogEvent>(MAX_LOG_ENTRIES)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val packetCountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PacketCaptureVpnService.ACTION_PACKET_COUNT -> {
                    val count = intent.getLongExtra(PacketCaptureVpnService.EXTRA_PACKET_COUNT, 0L)
                    _uiState.update { it.copy(packetCount = count) }
                }
                PacketCaptureVpnService.ACTION_UPLOAD_STATUS -> {
                    val status = intent.getStringExtra(PacketCaptureVpnService.EXTRA_UPLOAD_STATUS)
                    _uiState.update { it.copy(lastUploadStatus = status) }
                    if (status != null) {
                        val type = if (status.startsWith("OK") || status.startsWith("2")) "UPLOAD" else "ERROR"
                        appendLog(LogEvent(id = ++logIdCounter, type = LogEventType.valueOf(type), message = "Upload: $status"))
                    }
                }
                PacketCaptureVpnService.ACTION_LOG_EVENT -> {
                    val type = intent.getStringExtra(PacketCaptureVpnService.EXTRA_LOG_TYPE)
                        ?.let { runCatching { LogEventType.valueOf(it) }.getOrNull() }
                        ?: LogEventType.INFO
                    val msg = intent.getStringExtra(PacketCaptureVpnService.EXTRA_LOG_MESSAGE) ?: return
                    appendLog(LogEvent(id = ++logIdCounter, type = type, message = msg))
                }
                AuthManager.ACTION_AUTH_EXPIRED -> {
                    // Service stopped; clear running state and notify user via status.
                    _uiState.update { it.copy(captureRunning = false, lastUploadStatus = "Auth expired — please log in again") }
                    appendLog(LogEvent(id = ++logIdCounter, type = LogEventType.ERROR, message = "Auth expired"))
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            repo.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }

        val filter = IntentFilter().apply {
            addAction(PacketCaptureVpnService.ACTION_PACKET_COUNT)
            addAction(PacketCaptureVpnService.ACTION_UPLOAD_STATUS)
            addAction(PacketCaptureVpnService.ACTION_LOG_EVENT)
            addAction(AuthManager.ACTION_AUTH_EXPIRED)
        }
        ContextCompat.registerReceiver(
            application,
            packetCountReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(packetCountReceiver)
    }

    private fun appendLog(event: LogEvent) {
        if (logBuffer.size >= MAX_LOG_ENTRIES) logBuffer.pollFirst()
        logBuffer.addLast(event)
        _uiState.update { it.copy(logEvents = logBuffer.toList()) }
    }

    fun clearLogs() {
        logBuffer.clear()
        _uiState.update { it.copy(logEvents = emptyList()) }
    }

    fun setCaptureRunning(running: Boolean) {
        _uiState.update { it.copy(captureRunning = running) }
        if (!running) {
            _uiState.update { it.copy(packetCount = 0L) }
        }
    }

    fun setPrivateMode(enabled: Boolean) {
        viewModelScope.launch { repo.setPrivateMode(enabled) }
    }

    fun setRealm(realm: Realm) {
        viewModelScope.launch { repo.setRealm(realm) }
    }

    fun setIngestUrl(url: String) {
        viewModelScope.launch { repo.setIngestUrl(url) }
    }

    fun saveAuthToken(token: String, email: String) {
        viewModelScope.launch { repo.saveAuthToken(token, email) }
    }

    fun logout() {
        viewModelScope.launch { repo.clearAuthToken() }
    }
}
