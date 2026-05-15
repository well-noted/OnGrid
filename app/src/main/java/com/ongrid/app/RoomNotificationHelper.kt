package com.ongrid.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Manages "🤝 Room active" notifications displayed while [RoomConversationWorker] is running.
 *
 * Uses a per-conversation notification ID (2000–2999 range) so multiple concurrent rooms
 * each show their own status notification without clobbering each other or the dream
 * notification (ID 1003) or the room foreground placeholder.
 */
object RoomNotificationHelper {

    const val CHANNEL_ID = "agent_rooms"
    private const val NOTIFICATION_ID_BASE = 2000

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent Rooms",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while agents are collaborating in a room"
                setShowBadge(false)
            }
        )
    }

    /** Stable per-conversation notification ID in the 2000–2999 range. */
    fun notificationId(conversationId: String): Int =
        NOTIFICATION_ID_BASE + abs(conversationId.hashCode()) % 1000

    /**
     * Post or update the room-active notification.
     *
     * @param conversationId  Used to derive a stable, unique notification ID.
     * @param roomName        Shown in the notification title.
     * @param participantNames Pre-joined agent names, e.g. "Atlas, Sage, Echo".
     * @param goalSnippet     Short goal text shown in the subtext line.
     */
    fun notify(
        context: Context,
        conversationId: String,
        roomName: String,
        participantNames: String,
        goalSnippet: String
    ) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId(conversationId),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("🤝 Room active — $roomName")
            .setContentText("Participants: $participantNames")
            .setSubText(goalSnippet)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(conversationId), notification)
    }

    /** Dismiss the room notification for a specific conversation. */
    fun dismiss(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(conversationId))
    }
}
