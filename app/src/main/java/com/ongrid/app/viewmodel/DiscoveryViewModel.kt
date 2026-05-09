package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.local.SavedServerEntity
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.data.repository.toOllamaServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class DiscoveryUiState {
    object Idle : DiscoveryUiState()
    object Scanning : DiscoveryUiState()
    object Done : DiscoveryUiState()
    data class Error(val message: String) : DiscoveryUiState()
}

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication

    private val _uiState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Idle)
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    /**
     * Saved servers as the source of truth — the screen always shows whatever is in the DB.
     * Newly discovered or manually-added servers are written directly to DB so this Flow
     * updates automatically.
     */
    val servers: StateFlow<List<SavedServerEntity>> = app.serverRepository.savedServers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Scan the local network for Ollama servers and persist each one found. */
    fun startScan() {
        viewModelScope.launch {
            _uiState.value = DiscoveryUiState.Scanning
            try {
                app.networkScanner.discoverServers(getApplication()).collect { server ->
                    val detailed = app.ollamaRepository.fetchServerDetails(server) ?: server
                    app.serverRepository.saveServer(detailed)
                }
                _uiState.value = DiscoveryUiState.Done
            } catch (e: Exception) {
                _uiState.value = DiscoveryUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Allow the user to manually add a server by hostname/IP. */
    fun addManualServer(host: String, port: Int = 11434) {
        viewModelScope.launch {
            val probe = app.networkScanner.probeOllamaServer(host, port)
            if (probe != null) {
                val detailed = app.ollamaRepository.fetchServerDetails(probe) ?: probe
                app.serverRepository.saveServer(detailed)
            } else {
                _uiState.value = DiscoveryUiState.Error("Could not reach Ollama at $host:$port")
            }
        }
    }

    fun removeServer(entity: SavedServerEntity) {
        viewModelScope.launch {
            app.serverRepository.removeServer(entity.host, entity.port)
        }
    }
}

