package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "project_memories")
data class ProjectMemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val content: String,
    val sourceConversationId: String,
    val extractedAt: Long = System.currentTimeMillis()
)
