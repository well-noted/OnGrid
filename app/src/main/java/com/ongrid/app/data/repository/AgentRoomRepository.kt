package com.ongrid.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.AgentRoomDao
import com.ongrid.app.data.local.AgentRoomEntity
import com.ongrid.app.data.local.RoomMemoryDao
import com.ongrid.app.data.local.RoomMemoryEntity
import kotlinx.coroutines.flow.Flow

class AgentRoomRepository(
    private val roomDao: AgentRoomDao,
    private val memoryDao: RoomMemoryDao
) {
    private val gson = Gson()

    // ── Rooms ─────────────────────────────────────────────────────────────────

    fun allRooms(): Flow<List<AgentRoomEntity>> = roomDao.allRooms()

    suspend fun getRoom(id: String): AgentRoomEntity? = roomDao.getById(id)

    suspend fun allScheduledRooms(): List<AgentRoomEntity> = roomDao.allScheduledRooms()

    suspend fun createRoom(
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
    ): AgentRoomEntity {
        val room = AgentRoomEntity(
            name = name.trim(),
            systemPrompt = systemPrompt.trim(),
            agentIds = gson.toJson(agentIds),
            color = color,
            serverHost = serverHost,
            serverPort = serverPort,
            modelName = modelName,
            scheduleEnabled = scheduleEnabled,
            scheduleHour = scheduleHour,
            scheduleMinute = scheduleMinute,
            goalTemplate = goalTemplate.trim(),
            orchestratorAgentId = orchestratorAgentId
        )
        roomDao.insert(room)
        return room
    }

    suspend fun updateRoom(
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
        roomDao.update(
            id = id,
            name = name.trim(),
            systemPrompt = systemPrompt.trim(),
            agentIds = gson.toJson(agentIds),
            color = color,
            serverHost = serverHost,
            serverPort = serverPort,
            modelName = modelName,
            scheduleEnabled = scheduleEnabled,
            scheduleHour = scheduleHour,
            scheduleMinute = scheduleMinute,
            goalTemplate = goalTemplate.trim(),
            orchestratorAgentId = orchestratorAgentId,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun deleteRoom(id: String) {
        roomDao.deleteById(id)
        memoryDao.deleteAllForRoom(id)
    }

    suspend fun touchRoom(id: String) {
        roomDao.touchRoom(id, System.currentTimeMillis())
    }

    fun parseAgentIds(json: String): List<String> = try {
        gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    // ── Pooled Memories ───────────────────────────────────────────────────────

    fun memoriesForRoom(roomId: String): Flow<List<RoomMemoryEntity>> =
        memoryDao.memoriesForRoom(roomId)

    suspend fun memoriesForRoomOnce(roomId: String): List<RoomMemoryEntity> =
        memoryDao.memoriesForRoomOnce(roomId)

    suspend fun addMemory(roomId: String, content: String, sourceConversationId: String? = null) {
        memoryDao.insert(
            RoomMemoryEntity(
                roomId = roomId,
                content = content.trim(),
                sourceConversationId = sourceConversationId
            )
        )
    }

    suspend fun pinMemory(memoryId: String) { memoryDao.setPinned(memoryId, true) }
    suspend fun unpinMemory(memoryId: String) { memoryDao.setPinned(memoryId, false) }
    suspend fun deleteMemory(memoryId: String) { memoryDao.deleteById(memoryId) }
    suspend fun clearNonPinnedMemories(roomId: String) { memoryDao.deleteNonPinned(roomId) }
}
