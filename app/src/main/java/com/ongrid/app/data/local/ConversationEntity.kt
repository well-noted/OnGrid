package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("projectId"), Index("agentId")]
)
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String? = null,
    val agentId: String? = null,
    val title: String = "New Conversation",
    val serverHost: String,
    val serverPort: Int,
    val modelName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val thinkingEnabled: Boolean = false,
    val tags: String = "",
    // Multi-agent coordination
    /** "STANDARD" for regular conversations, "AGENT_HANDOFF" for agent-to-agent channels. */
    val conversationType: String = "STANDARD",
    /** JSON list of agent IDs participating in an AGENT_HANDOFF conversation. */
    val participantAgentIds: String = "[]",
    /** The goal or task the agents are collaborating to complete. */
    val goal: String = ""
)
