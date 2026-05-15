package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.AgentRoomScheduleManager
import com.ongrid.app.OnGridApplication
import com.ongrid.app.RoomConversationWorker
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentRoomEntity
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.RoomMemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoomViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication
    private val roomRepo = app.agentRoomRepository
    private val agentRepo = app.agentRepository
    private val scheduleManager = app.agentRoomScheduleManager

    val allRooms: StateFlow<List<AgentRoomEntity>> =
        roomRepo.allRooms().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeAgents: StateFlow<List<AgentEntity>> =
        agentRepo.activeAgents().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Currently selected room id (for detail screen). */
    private val _selectedRoomId = MutableStateFlow<String?>(null)
    val selectedRoomId: StateFlow<String?> = _selectedRoomId.asStateFlow()

    val selectedRoom: StateFlow<AgentRoomEntity?> = _selectedRoomId
        .flatMapLatest { id -> if (id != null) roomRepo.allRooms().flatMapLatest { list ->
            flowOf(list.firstOrNull { it.id == id })
        } else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val roomMemories: StateFlow<List<RoomMemoryEntity>> = _selectedRoomId
        .flatMapLatest { id -> if (id != null) roomRepo.memoriesForRoom(id) else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val roomConversations: StateFlow<List<ConversationEntity>> = _selectedRoomId
        .flatMapLatest { id ->
            if (id != null) app.database.conversationDao().getByRoom(id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectRoom(id: String?) { _selectedRoomId.value = id }

    // ── Room CRUD ─────────────────────────────────────────────────────────────

    fun createRoom(
        name: String,
        systemPrompt: String,
        agentIds: List<String>,
        color: Int,
        serverHost: String,
        serverPort: Int,
        modelName: String,
        scheduleEnabled: Boolean,
        scheduleHour: Int,
        scheduleMinute: Int,
        goalTemplate: String,
        orchestratorAgentId: String?,
        onCreated: (AgentRoomEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            val room = roomRepo.createRoom(
                name = name,
                systemPrompt = systemPrompt,
                agentIds = agentIds,
                color = color,
                serverHost = serverHost,
                serverPort = serverPort,
                modelName = modelName,
                scheduleEnabled = scheduleEnabled,
                scheduleHour = scheduleHour,
                scheduleMinute = scheduleMinute,
                goalTemplate = goalTemplate,
                orchestratorAgentId = orchestratorAgentId
            )
            scheduleManager.schedule(room)
            onCreated(room)
        }
    }

    fun updateRoom(
        id: String,
        name: String,
        systemPrompt: String,
        agentIds: List<String>,
        color: Int,
        serverHost: String,
        serverPort: Int,
        modelName: String,
        scheduleEnabled: Boolean,
        scheduleHour: Int,
        scheduleMinute: Int,
        goalTemplate: String,
        orchestratorAgentId: String?
    ) {
        viewModelScope.launch {
            roomRepo.updateRoom(
                id = id,
                name = name,
                systemPrompt = systemPrompt,
                agentIds = agentIds,
                color = color,
                serverHost = serverHost,
                serverPort = serverPort,
                modelName = modelName,
                scheduleEnabled = scheduleEnabled,
                scheduleHour = scheduleHour,
                scheduleMinute = scheduleMinute,
                goalTemplate = goalTemplate,
                orchestratorAgentId = orchestratorAgentId
            )
            val updated = roomRepo.getRoom(id) ?: return@launch
            scheduleManager.schedule(updated)
        }
    }

    fun deleteRoom(id: String) {
        viewModelScope.launch {
            scheduleManager.cancel(id)
            roomRepo.deleteRoom(id)
            if (_selectedRoomId.value == id) _selectedRoomId.value = null
        }
    }

    // ── Launching a session ───────────────────────────────────────────────────

    /**
     * Creates a conversation for [room] and enqueues a [RoomConversationWorker].
     * Returns the new conversation ID so the caller can navigate to it.
     */
    fun launchRoom(room: AgentRoomEntity, goalOverride: String? = null, onLaunched: (String) -> Unit) {
        viewModelScope.launch {
            val goal = goalOverride?.trim()?.ifBlank { null } ?: room.goalTemplate
            val conv = ConversationEntity(
                serverHost = room.serverHost,
                serverPort = room.serverPort,
                modelName = room.modelName,
                title = "${room.name}: ${goal.take(40).let { if (goal.length > 40) "$it…" else it }}",
                conversationType = "AGENT_ROOM",
                participantAgentIds = room.agentIds,
                goal = goal,
                roomId = room.id
            )
            app.database.conversationDao().insert(conv)
            RoomConversationWorker.enqueue(app, room.id, conv.id)
            onLaunched(conv.id)
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    /**
     * Delete a single session (conversation + all its messages).
     * Cancels the room worker first in case the session is still running.
     */
    fun deleteSession(conversationId: String) {
        viewModelScope.launch {
            // Cancel any running worker for this session
            androidx.work.WorkManager.getInstance(app)
                .cancelAllWorkByTag("room_convo_$conversationId")
            // Delete messages then the conversation record
            app.database.messageDao().deleteByConversation(conversationId)
            app.database.conversationDao().deleteById(conversationId)
        }
    }

    // ── Memories ──────────────────────────────────────────────────────────────

    fun addMemory(roomId: String, content: String) {
        viewModelScope.launch { roomRepo.addMemory(roomId, content) }
    }

    fun pinMemory(memoryId: String) {
        viewModelScope.launch { roomRepo.pinMemory(memoryId) }
    }

    fun unpinMemory(memoryId: String) {
        viewModelScope.launch { roomRepo.unpinMemory(memoryId) }
    }

    fun deleteMemory(memoryId: String) {
        viewModelScope.launch { roomRepo.deleteMemory(memoryId) }
    }

    fun clearNonPinnedMemories(roomId: String) {
        viewModelScope.launch { roomRepo.clearNonPinnedMemories(roomId) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun parseAgentIds(json: String): List<String> = roomRepo.parseAgentIds(json)
}
