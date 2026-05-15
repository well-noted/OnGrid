package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A memory entry that is shared by all agents participating in a given room.
 * These are injected alongside each agent's individual memories when they speak.
 */
@Entity(tableName = "room_memories")
data class RoomMemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val roomId: String,
    val content: String,
    val isPinned: Boolean = false,
    val sourceConversationId: String? = null,
    val extractedAt: Long = System.currentTimeMillis()
)
