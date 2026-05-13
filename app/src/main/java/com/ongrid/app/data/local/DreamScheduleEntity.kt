package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class DreamScheduleType {
    /** Triggers once per day at a specific wall-clock time (e.g. 02:00). */
    TIME_OF_DAY,

    /** Triggers each time the device connects to a Wi-Fi network. */
    WIFI_CONNECT
}

@Entity(tableName = "dream_schedules")
data class DreamScheduleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val scheduleType: DreamScheduleType,
    /** Hour (0–23) used for [DreamScheduleType.TIME_OF_DAY]. */
    val timeHour: Int = 2,
    /** Minute (0–59) used for [DreamScheduleType.TIME_OF_DAY]. */
    val timeMinute: Int = 0,
    /** User-visible label; auto-generated from type when blank. */
    val label: String = "",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
