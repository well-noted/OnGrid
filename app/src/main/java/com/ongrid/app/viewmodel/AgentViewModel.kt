package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.DreamWorker
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentMemoryEntity
import com.ongrid.app.data.local.AgentStatus
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.DreamLogEntity
import com.ongrid.app.data.local.DreamScheduleEntity
import com.ongrid.app.data.local.DreamScheduleType
import com.ongrid.app.data.local.SavedServerEntity
import com.ongrid.app.data.local.SkillEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Server reachability state shown by the avatar ping dot. */
enum class ServerPingStatus { UNKNOWN, PINGING, REACHABLE, NOT_OLLAMA, UNREACHABLE }

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication
    private val agentRepo = app.agentRepository
    private val serverRepo = app.serverRepository
    private val skillRepo = app.skillRepository
    private val scheduleRepo = app.dreamScheduleRepository

    /** All agents (for list screen / rail). */
    val allAgents: StateFlow<List<AgentEntity>> =
        agentRepo.allAgents().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeAgents: StateFlow<List<AgentEntity>> =
        agentRepo.activeAgents().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val savedServers: StateFlow<List<SavedServerEntity>> =
        serverRepo.savedServers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allSkills: StateFlow<List<SkillEntity>> =
        skillRepo.allSkills.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedAgentId = MutableStateFlow<String?>(null)

    val selectedAgent: StateFlow<AgentEntity?> =
        _selectedAgentId.flatMapLatest { id ->
            if (id == null) flowOf(null) else agentRepo.observeAgent(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val agentMemories: StateFlow<List<AgentMemoryEntity>> =
        _selectedAgentId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else agentRepo.memoriesForAgent(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val agentConversations: StateFlow<List<ConversationEntity>> =
        _selectedAgentId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else app.conversationRepository.conversationsForAgent(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Dream journal entries for the currently selected agent. */
    val dreamLogs: StateFlow<List<DreamLogEntity>> =
        _selectedAgentId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else agentRepo.dreamLogsForAgent(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Dream schedules for the currently selected agent. */
    val dreamSchedules: StateFlow<List<DreamScheduleEntity>> =
        _selectedAgentId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else scheduleRepo.schedulesForAgent(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Live terminal-feed lines emitted by [com.ongrid.app.DreamWorker].
     * Collected while the Agent screen is visible; lines are dropped when no collector
     * is active (acceptable for a live status feed).
     */
    val dreamLiveFeed = app.dreamLogChannel.receiveAsFlow()

    /** True while a manual Dream Now operation is in progress. */
    private val _isDreaming = MutableStateFlow(false)
    val isDreaming: StateFlow<Boolean> = _isDreaming.asStateFlow()

    /** Reachability of the server configured for this agent (used to colour the avatar dot). */
    private val _pingStatus = MutableStateFlow(ServerPingStatus.UNKNOWN)
    val pingStatus: StateFlow<ServerPingStatus> = _pingStatus.asStateFlow()

    fun selectAgent(agentId: String) {
        _selectedAgentId.value = agentId
        _pingStatus.value = ServerPingStatus.UNKNOWN
    }

    /**
     * Ping the server associated with [agentId].
     * Uses the agent's own utility-model host if set, otherwise the global utility host.
     * Updates [pingStatus] with the result.
     */
    fun pingAgentServer(agentId: String) = viewModelScope.launch {
        val agent = agentRepo.getAgent(agentId) ?: return@launch
        val settings = app.settingsRepository.settings.first()
        val host = agent.utilityModelHost.ifBlank { settings.utilityModelHost }
        if (host.isBlank()) {
            _pingStatus.value = ServerPingStatus.UNKNOWN
            return@launch
        }
        _pingStatus.value = ServerPingStatus.PINGING
        _pingStatus.value = when (app.ollamaApi.ping(host)) {
            com.ongrid.app.data.network.ServerPingResult.REACHABLE -> ServerPingStatus.REACHABLE
            com.ongrid.app.data.network.ServerPingResult.NOT_OLLAMA -> ServerPingStatus.NOT_OLLAMA
            com.ongrid.app.data.network.ServerPingResult.UNREACHABLE -> ServerPingStatus.UNREACHABLE
        }
    }

    fun createAgent(
        name: String,
        role: String,
        color: Int,
        onCreated: (AgentEntity) -> Unit
    ) = viewModelScope.launch {
        val agent = agentRepo.createAgent(name, role, "", color)
        syncShortcuts()
        onCreated(agent)
    }

    fun updateName(agentId: String, name: String) = viewModelScope.launch {
        agentRepo.updateName(agentId, name)
    }

    fun updateRole(agentId: String, role: String) = viewModelScope.launch {
        agentRepo.updateRole(agentId, role)
    }

    fun updateSystemPrompt(agentId: String, systemPrompt: String) = viewModelScope.launch {
        agentRepo.updateSystemPrompt(agentId, systemPrompt)
    }

    fun updateBrief(agentId: String, brief: String) = viewModelScope.launch {
        agentRepo.updateBrief(agentId, brief)
    }

    fun updateStatus(agentId: String, status: AgentStatus) = viewModelScope.launch {
        agentRepo.updateStatus(agentId, status)
        syncShortcuts()
    }

    fun updateColor(agentId: String, color: Int) = viewModelScope.launch {
        agentRepo.updateColor(agentId, color)
        syncShortcuts()
    }

    fun updateAvatarIcon(agentId: String, icon: String) = viewModelScope.launch {
        agentRepo.updateAvatarIcon(agentId, icon)
    }

    fun updateUtilityModel(agentId: String, host: String, model: String) = viewModelScope.launch {
        agentRepo.updateUtilityModel(agentId, host, model)
    }

    fun clearUtilityModel(agentId: String) = viewModelScope.launch {
        agentRepo.updateUtilityModel(agentId, "", "")
    }

    fun setDefaultSkills(agentId: String, skillIds: List<String>) = viewModelScope.launch {
        agentRepo.setDefaultSkills(agentId, skillIds)
    }

    fun setDefaultDisabledTools(agentId: String, toolNames: List<String>) = viewModelScope.launch {
        agentRepo.setDefaultDisabledTools(agentId, toolNames)
    }

    fun pinMemory(memoryId: String) = viewModelScope.launch {
        agentRepo.pinMemory(memoryId)
    }

    fun unpinMemory(memoryId: String) = viewModelScope.launch {
        agentRepo.unpinMemory(memoryId)
    }

    fun deleteMemory(memoryId: String) = viewModelScope.launch {
        agentRepo.deleteMemory(memoryId)
    }

    fun clearUnpinnedMemories(agentId: String) = viewModelScope.launch {
        agentRepo.deleteNonPinnedMemories(agentId)
    }

    fun removeConversationFromAgent(conversationId: String) = viewModelScope.launch {
        app.conversationRepository.assignToAgent(conversationId, null)
    }

    fun pinConversationMessageToMemory(
        agentId: String,
        messageContent: String,
        conversationId: String,
        messageId: String
    ) = viewModelScope.launch {
        agentRepo.insertMemory(
            AgentMemoryEntity(
                agentId = agentId,
                content = messageContent.take(500),
                isPinned = true,
                sourceConversationId = conversationId,
                sourceMessageId = messageId
            )
        )
    }

    // ── Phase 2: Cognition Settings ────────────────────────────────────────

    fun saveCognitionSettings(
        agentId: String,
        isDreamingEnabled: Boolean,
        isMoodTrackingEnabled: Boolean,
        isAutoBriefEnabled: Boolean,
        maxContextTokens: Int
    ) = viewModelScope.launch {
        agentRepo.updateCognitionSettings(
            agentId, isDreamingEnabled, isMoodTrackingEnabled, isAutoBriefEnabled, maxContextTokens
        )
    }

    fun resetMood(agentId: String) = viewModelScope.launch {
        agentRepo.resetMood(agentId)
    }

    fun updateSemanticRecallEnabled(agentId: String, enabled: Boolean) = viewModelScope.launch {
        agentRepo.updateSemanticRecallEnabled(agentId, enabled)
    }

    fun updateRecentContextEnabled(agentId: String, enabled: Boolean) = viewModelScope.launch {
        agentRepo.updateRecentContextEnabled(agentId, enabled)
    }

    /**
     * Schedule a one-time DreamWorker to run immediately ("Dream Now" manual trigger).
     * The [isDreaming] state is updated optimistically and reset once the WorkManager
     * request is enqueued (actual work runs off the UI thread via WorkManager).
     */
    fun triggerDreamNow() {
        val agentId = _selectedAgentId.value ?: return
        _isDreaming.value = true
        val request = OneTimeWorkRequestBuilder<DreamWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .setInputData(workDataOf(DreamWorker.INPUT_KEY_AGENT_ID to agentId))
            .addTag(DREAM_NOW_TAG)
            .build()
        WorkManager.getInstance(app).enqueue(request)
        // Reset indicator after a short delay so the button feels responsive
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _isDreaming.value = false
        }
    }

    private fun syncShortcuts() {
        viewModelScope.launch {
            app.agentShortcutManager.sync(allAgents.value)
        }
    }

    /** Parse a JSON skill IDs array stored in the agent entity. */
    fun parseSkillIds(json: String): List<String> = agentRepo.parseSkillIds(json)

    /** Parse a JSON disabled tool names array stored in the agent entity. */
    fun parseDisabledTools(json: String): List<String> = agentRepo.parseDisabledTools(json)

    // ── Dream Scheduler ────────────────────────────────────────────────────

    fun addDreamSchedule(agentId: String, type: DreamScheduleType, hour: Int, minute: Int, label: String) =
        viewModelScope.launch {
            val entity = DreamScheduleEntity(
                agentId = agentId,
                scheduleType = type,
                timeHour = hour,
                timeMinute = minute,
                label = label
            )
            scheduleRepo.addSchedule(entity)
            app.dreamScheduleManager.schedule(entity)
        }

    fun deleteDreamSchedule(schedule: DreamScheduleEntity) = viewModelScope.launch {
        scheduleRepo.deleteSchedule(schedule.id)
        app.dreamScheduleManager.cancel(schedule.id)
    }

    fun toggleDreamSchedule(schedule: DreamScheduleEntity, enabled: Boolean) = viewModelScope.launch {
        scheduleRepo.setEnabled(schedule.id, enabled)
        val updated = schedule.copy(isEnabled = enabled)
        app.dreamScheduleManager.schedule(updated)
    }

    companion object {
        private const val DREAM_NOW_TAG = "dream_now_manual"
    }
}

