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
import com.albiondata.client.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val captureRunning: Boolean = false,
    val packetCount: Long = 0L,
    val lastUploadStatus: String? = null,
    val settings: AppSettings = AppSettings(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)

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
                }
                AuthManager.ACTION_AUTH_EXPIRED -> {
                    // Service stopped; clear running state and notify user via status.
                    _uiState.update { it.copy(captureRunning = false, lastUploadStatus = "Auth expired — please log in again") }
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

    fun setCaptureRunning(running: Boolean) {
        _uiState.update { it.copy(captureRunning = running) }
        if (!running) {
            _uiState.update { it.copy(packetCount = 0L) }
        }
    }

    fun setPrivateMode(enabled: Boolean) {
        viewModelScope.launch { repo.setPrivateMode(enabled) }
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
