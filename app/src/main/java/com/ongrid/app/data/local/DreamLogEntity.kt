package com.ongrid.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "dream_logs")
data class DreamLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(index = true) val agentId: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Human-readable summary, e.g. "Consolidated 4 memories; Saved 140 tokens" */
    val summary: String,
    /** Detailed technical JSON diff of what changed */
    val fullLogJson: String,
    /** e.g. "Neutral -> Enthusiastic", null if mood did not change or tracking is off */
    val moodChange: String? = null
)
