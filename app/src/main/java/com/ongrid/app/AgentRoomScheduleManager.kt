package com.ongrid.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ongrid.app.data.local.AgentRoomEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Manages per-room [AlarmManager] registrations for scheduled room conversations.
 *
 * Mirrors [DreamScheduleManager]. Each room fires once daily at [AgentRoomEntity.scheduleHour]:
 * [AgentRoomEntity.scheduleMinute] when [AgentRoomEntity.scheduleEnabled] is true.
 *
 * Call [syncAll] on Application start to restore alarms after device reboot.
 */
class AgentRoomScheduleManager(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Re-register all active room alarms (e.g. after a device reboot). */
    fun syncAll() {
        scope.launch {
            val app = context.applicationContext as OnGridApplication
            app.agentRoomRepository.allScheduledRooms().forEach { scheduleAlarm(it) }
        }
    }

    /** Register or cancel the alarm for this room based on its enabled state. */
    fun schedule(room: AgentRoomEntity) {
        if (room.scheduleEnabled) scheduleAlarm(room) else cancelAlarm(room.id)
    }

    fun cancel(roomId: String) = cancelAlarm(roomId)

    /** Re-arm the daily alarm for the next occurrence. Called by [AgentRoomAlarmReceiver]. */
    fun rescheduleAlarm(roomId: String) {
        scope.launch {
            val app = context.applicationContext as OnGridApplication
            val room = app.agentRoomRepository.getRoom(roomId) ?: return@launch
            if (room.scheduleEnabled) scheduleAlarm(room)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun scheduleAlarm(room: AgentRoomEntity) {
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            nextOccurrenceMs(room.scheduleHour, room.scheduleMinute),
            10 * 60 * 1000L,
            buildPendingIntent(room.id)
        )
    }

    private fun cancelAlarm(roomId: String) {
        alarmManager.cancel(buildPendingIntent(roomId))
    }

    private fun buildPendingIntent(roomId: String): PendingIntent {
        val intent = Intent(context, AgentRoomAlarmReceiver::class.java).apply {
            putExtra(AgentRoomAlarmReceiver.EXTRA_ROOM_ID, roomId)
        }
        return PendingIntent.getBroadcast(
            context,
            // Use a distinct request code space from DreamScheduleManager (offset by Int.MAX_VALUE/2)
            roomId.hashCode() xor 0x55AA55AA.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextOccurrenceMs(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }
}
