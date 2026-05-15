package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class AgentStatus { ACTIVE, PAUSED, RETIRED }

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String = "",
    val systemPrompt: String = "",
    val brief: String = "",
    val briefUpdatedAt: Long = 0L,
    val status: AgentStatus = AgentStatus.ACTIVE,
    val defaultSkillIds: String = "[]",
    val defaultDisabledToolNames: String = "[]",
    val color: Int = 0,
    val avatarIcon: String = "",
    val utilityModelHost: String = "",
    val utilityModelName: String = "",
    val retiredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // Phase 2 — Autonomous Agency
    val isDreamingEnabled: Boolean = true,
    val isMoodTrackingEnabled: Boolean = false,
    val isAutoBriefEnabled: Boolean = true,
    val maxContextTokens: Int = 4096,
    val currentMood: String = "Neutral",
    val lastDreamedAt: Long = 0L,
    // Phase 3 — Semantic memory recall
    val isSemanticRecallEnabled: Boolean = false,
    // Phase 4 — Recent conversation context
    val isRecentContextEnabled: Boolean = false,
    // Conversation defaults
    val defaultThinkingEnabled: Boolean = false
)
