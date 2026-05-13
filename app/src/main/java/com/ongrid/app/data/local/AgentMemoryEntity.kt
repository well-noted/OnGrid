package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val content: String,
    val isPinned: Boolean = false,
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
    val extractedAt: Long = System.currentTimeMillis()
)
