package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A persistent multi-agent "room" — a reusable template that spawns a new
 * [com.ongrid.app.data.local.ConversationEntity] (type = AGENT_ROOM) each time it runs.
 *
 * Scheduling is daily at [scheduleHour]:[scheduleMinute] when [scheduleEnabled] is true.
 * The [agentIds] JSON array may contain 2–N agent IDs; the worker iterates them round-robin.
 */
@Entity(tableName = "agent_rooms")
data class AgentRoomEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Shared system prompt injected into every agent's context when they speak in this room. */
    val systemPrompt: String = "",
    /** JSON array of [AgentEntity] IDs. Order determines speaking order. */
    val agentIds: String = "[]",
    /** ARGB color used for the room's UI card. */
    val color: Int = 0xFF1A73E8.toInt(),
    // Server the room defaults to
    val serverHost: String = "",
    val serverPort: Int = 11434,
    val modelName: String = "",
    // Schedule
    val scheduleEnabled: Boolean = false,
    val scheduleHour: Int = 9,
    val scheduleMinute: Int = 0,
    // Goal template — used as the goal for each spawned conversation
    val goalTemplate: String = "",
    /**
     * Optional agent who acts as turn orchestrator. Before each speaking turn the orchestrator
     * receives the conversation history and replies with just the name of the agent who should
     * speak next (which may be itself). Null = simple round-robin.
     */
    val orchestratorAgentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
