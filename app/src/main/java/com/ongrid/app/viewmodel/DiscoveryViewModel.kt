package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.model.OllamaServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _servers = MutableStateFlow<List<OllamaServer>>(emptyList())
    val servers: StateFlow<List<OllamaServer>> = _servers.asStateFlow()

    /** Scan the local network for Ollama servers. */
    fun startScan() {
        viewModelScope.launch {
            _uiState.value = DiscoveryUiState.Scanning
            _servers.value = emptyList()
            try {
                app.networkScanner.discoverServers(getApplication()).collect { server ->
                    // Fetch model list for each found server
                    val detailed = app.ollamaRepository.fetchServerDetails(server) ?: server
                    _servers.value = _servers.value + detailed
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
                if (_servers.value.none { it.host == detailed.host && it.port == detailed.port }) {
                    _servers.value = _servers.value + detailed
                }
            } else {
                _uiState.value = DiscoveryUiState.Error("Could not reach Ollama at $host:$port")
            }
        }
    }
}
