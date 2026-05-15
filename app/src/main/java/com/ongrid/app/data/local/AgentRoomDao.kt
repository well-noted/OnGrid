package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRoomDao {
    @Query("SELECT * FROM agent_rooms ORDER BY updatedAt DESC")
    fun allRooms(): Flow<List<AgentRoomEntity>>

    @Query("SELECT * FROM agent_rooms WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentRoomEntity?

    @Query("SELECT * FROM agent_rooms WHERE scheduleEnabled = 1")
    suspend fun allScheduledRooms(): List<AgentRoomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(room: AgentRoomEntity)

    @Query("UPDATE agent_rooms SET name = :name, systemPrompt = :systemPrompt, agentIds = :agentIds, color = :color, serverHost = :serverHost, serverPort = :serverPort, modelName = :modelName, scheduleEnabled = :scheduleEnabled, scheduleHour = :scheduleHour, scheduleMinute = :scheduleMinute, goalTemplate = :goalTemplate, orchestratorAgentId = :orchestratorAgentId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(
        id: String,
        name: String,
        systemPrompt: String,
        agentIds: String,
        color: Int,
        serverHost: String,
        serverPort: Int,
        modelName: String,
        scheduleEnabled: Boolean,
        scheduleHour: Int,
        scheduleMinute: Int,
        goalTemplate: String,
        orchestratorAgentId: String?,
        updatedAt: Long
    )

    @Query("UPDATE agent_rooms SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchRoom(id: String, updatedAt: Long)

    @Query("DELETE FROM agent_rooms WHERE id = :id")
    suspend fun deleteById(id: String)
}
