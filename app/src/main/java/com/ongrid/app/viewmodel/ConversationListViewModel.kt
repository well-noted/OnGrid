package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.ProjectEntity
import com.ongrid.app.data.local.SavedServerEntity
import com.ongrid.app.data.repository.LastUsedPrefs
import com.ongrid.app.data.local.ProjectMemoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ServerSetupState {
    /** DB query still in flight — show a loading indicator. */
    object Loading : ServerSetupState()
    /** No saved servers — navigate to Discovery and auto-scan. */
    object NoServers : ServerSetupState()
    /** At least one server is configured. */
    data class Ready(
        val servers: List<SavedServerEntity>,
        val lastUsed: LastUsedPrefs
    ) : ServerSetupState()
}

class ConversationListViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication
    private val repo = app.conversationRepository
    private val serverRepo = app.serverRepository

    // ── Server setup state ────────────────────────────────────────────────────

    val serverSetupState: StateFlow<ServerSetupState> =
        combine(serverRepo.savedServers, serverRepo.lastUsedPrefs) { servers, lastUsed ->
            if (servers.isEmpty()) ServerSetupState.NoServers
            else ServerSetupState.Ready(servers, lastUsed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSetupState.Loading)

    // ── Projects ──────────────────────────────────────────────────────────────

    val projects: StateFlow<List<ProjectEntity>> = repo.allProjects
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allConversations: StateFlow<List<ConversationEntity>> = repo.allConversations
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val standaloneConversations: StateFlow<List<ConversationEntity>> = repo.standaloneConversations
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All active agents — used by ConversationListScreen to display agent names on handoff convos. */
    val activeAgents: StateFlow<List<AgentEntity>> = app.agentRepository.activeAgents()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val standaloneAndHandoffConversations: StateFlow<List<ConversationEntity>> =
        repo.standaloneAndHandoffConversations
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    /** Conversations filtered by the currently selected project (null = standalone only). */
    val displayedConversations: StateFlow<List<ConversationEntity>> =
        combine(_selectedProjectId, allConversations, standaloneAndHandoffConversations) { projectId, all, standalone ->
            if (projectId == null) standalone else all.filter { it.projectId == projectId }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectProject(projectId: String?) {
        _selectedProjectId.value = projectId
    }

    fun createProject(name: String) = viewModelScope.launch {
        repo.createProject(name)
    }

    fun deleteProject(id: String) = viewModelScope.launch {
        repo.deleteProject(id)
        if (_selectedProjectId.value == id) _selectedProjectId.value = null
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        repo.deleteConversation(id)
    }

    fun assignToProject(conversationId: String, projectId: String?) = viewModelScope.launch {
        repo.assignToProject(conversationId, projectId)
    }

    fun updateProject(id: String, name: String, description: String) = viewModelScope.launch {
        repo.updateProject(id, name, description)
    }

    /** Memories for the currently selected project — updates automatically when selection changes. */
    val selectedProjectMemories: StateFlow<List<ProjectMemoryEntity>> =
        _selectedProjectId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else app.database.projectMemoryDao().memoriesForProject(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteProjectMemory(memoryId: String) = viewModelScope.launch {
        app.database.projectMemoryDao().delete(memoryId)
    }
}

