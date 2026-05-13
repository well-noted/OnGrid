package com.ongrid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives AlarmManager broadcasts for [com.ongrid.app.data.local.DreamScheduleType.TIME_OF_DAY]
 * schedules and enqueues a one-shot [DreamWorker] scoped to the schedule's agent.
 *
 * After the alarm fires (one-shot by design), [DreamScheduleManager.rescheduleAlarm] is called
 * so the same schedule triggers again tomorrow at the same wall-clock time.
 */
class DreamAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val app = context.applicationContext as OnGridApplication

        // Look up the agentId synchronously via a coroutine and then enqueue
        CoroutineScope(Dispatchers.IO).launch {
            val schedule = app.dreamScheduleRepository.getScheduleById(scheduleId) ?: return@launch

            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<DreamWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiresBatteryNotLow(false).build()
                    )
                    .setInputData(workDataOf(DreamWorker.INPUT_KEY_AGENT_ID to schedule.agentId))
                    .addTag(TAG_SCHEDULED)
                    .build()
            )

            // Re-arm the alarm for the next day
            app.dreamScheduleManager.rescheduleAlarm(scheduleId)
        }
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val TAG_SCHEDULED = "dream_scheduled"
    }
}
