package com.ongrid.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ongrid.app.data.local.DreamScheduleEntity
import com.ongrid.app.data.local.DreamScheduleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Manages per-schedule [AlarmManager] registration for
 * [DreamScheduleType.TIME_OF_DAY] triggers.
 *
 * Uses [AlarmManager.setWindow] (no special permissions required) with a 10-minute
 * window — sufficient precision for a nightly maintenance task.
 *
 * Call [syncAll] on Application start to restore alarms after device reboot.
 * Call [schedule] after inserting/enabling a schedule, [cancel] after deleting it.
 */
class DreamScheduleManager(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Re-register all active TIME_OF_DAY alarms (e.g. after a device reboot). */
    fun syncAll() {
        scope.launch {
            val app = context.applicationContext as OnGridApplication
            app.database.dreamScheduleDao()
                .allEnabledSchedules()
                .filter { it.scheduleType == DreamScheduleType.TIME_OF_DAY }
                .forEach { scheduleAlarm(it) }
        }
    }

    /** Register or cancel an alarm based on the schedule's enabled state and type. */
    fun schedule(entity: DreamScheduleEntity) {
        if (entity.scheduleType == DreamScheduleType.TIME_OF_DAY && entity.isEnabled) {
            scheduleAlarm(entity)
        } else {
            cancelAlarm(entity.id)
        }
    }

    fun cancel(scheduleId: String) = cancelAlarm(scheduleId)

    /** Called by [DreamAlarmReceiver] after the alarm fires to re-arm it for tomorrow. */
    fun rescheduleAlarm(scheduleId: String) {
        scope.launch {
            val app = context.applicationContext as OnGridApplication
            val entity = app.database.dreamScheduleDao()
                .allEnabledSchedules()
                .find { it.id == scheduleId } ?: return@launch
            scheduleAlarm(entity)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun scheduleAlarm(entity: DreamScheduleEntity) {
        val triggerAtMs = nextOccurrenceMs(entity.timeHour, entity.timeMinute)
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            10 * 60 * 1000L, // 10-minute window
            buildPendingIntent(entity.id)
        )
    }

    private fun cancelAlarm(scheduleId: String) {
        alarmManager.cancel(buildPendingIntent(scheduleId))
    }

    private fun buildPendingIntent(scheduleId: String): PendingIntent {
        val intent = Intent(context, DreamAlarmReceiver::class.java).apply {
            putExtra(DreamAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Returns the epoch-millisecond timestamp of the next occurrence of [hour]:[minute]. */
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
