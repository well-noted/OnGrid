package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.model.McpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class McpUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication

    val servers: StateFlow<List<McpServer>> = app.mcpRepository.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(McpUiState())
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    /** Add a new MCP server and immediately attempt to connect and load its tools. */
    fun addServer(name: String, url: String, authHeader: String?) {
        viewModelScope.launch {
            _uiState.value = McpUiState(isRefreshing = true)
            try {
                val normalizedUrl = url.trimEnd('/')
                val server = McpServer(name = name, baseUrl = normalizedUrl, authHeader = authHeader.takeIf { !it.isNullOrBlank() })
                val serverWithTools = app.mcpRepository.refreshTools(server)
                app.mcpRepository.upsertServer(serverWithTools)
                val toolCount = serverWithTools.tools.size
                _uiState.value = McpUiState(
                    successMessage = if (toolCount > 0)
                        "Connected: found $toolCount tool(s)"
                    else
                        "Connected (no tools found)"
                )
            } catch (e: Exception) {
                _uiState.value = McpUiState(error = e.message ?: "Failed to connect")
            }
        }
    }

    /** Refresh the tool list for an existing MCP server. */
    fun refreshServer(server: McpServer) {
        viewModelScope.launch {
            _uiState.value = McpUiState(isRefreshing = true)
            try {
                val updated = app.mcpRepository.refreshTools(server)
                app.mcpRepository.upsertServer(updated)
                _uiState.value = McpUiState(
                    successMessage = "Refreshed: ${updated.tools.size} tool(s)"
                )
            } catch (e: Exception) {
                _uiState.value = McpUiState(error = e.message ?: "Refresh failed")
            }
        }
    }

    /** Toggle enabled/disabled status for a server. */
    fun toggleServer(server: McpServer) {
        viewModelScope.launch {
            app.mcpRepository.upsertServer(server.copy(enabled = !server.enabled))
        }
    }

    /** Remove an MCP server. */
    fun removeServer(serverId: String) {
        viewModelScope.launch {
            app.mcpRepository.removeServer(serverId)
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(error = null, successMessage = null)
    }
}
