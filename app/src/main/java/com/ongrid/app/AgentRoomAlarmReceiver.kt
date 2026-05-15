package com.ongrid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ongrid.app.data.local.ConversationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the daily AlarmManager broadcast for a scheduled [AgentRoomEntity].
 * Creates a fresh [ConversationEntity] for the room and enqueues a [RoomConversationWorker].
 * Re-arms the alarm for the next day via [AgentRoomScheduleManager.rescheduleAlarm].
 */
class AgentRoomAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return
        val app = context.applicationContext as OnGridApplication

        CoroutineScope(Dispatchers.IO).launch {
            val room = app.agentRoomRepository.getRoom(roomId) ?: return@launch
            if (!room.scheduleEnabled) return@launch
            if (room.serverHost.isBlank() || room.modelName.isBlank()) return@launch

            // Create a new conversation instance for this scheduled run
            val conversation = ConversationEntity(
                serverHost = room.serverHost,
                serverPort = room.serverPort,
                modelName = room.modelName,
                title = "${room.name} (scheduled)",
                conversationType = "AGENT_ROOM",
                participantAgentIds = room.agentIds,
                goal = room.goalTemplate,
                roomId = room.id
            )
            app.database.conversationDao().insert(conversation)

            RoomConversationWorker.enqueue(context, roomId, conversation.id)

            // Re-arm for tomorrow
            app.agentRoomScheduleManager.rescheduleAlarm(roomId)
        }
    }

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
    }
}
