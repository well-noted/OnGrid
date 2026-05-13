package com.ongrid.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Manages the "☁️ Dreaming…" notification displayed while [DreamWorker] is active.
 *
 * The notification is ongoing and silent so it appears in the status bar without
 * interrupting the user. Tapping it opens [MainActivity] with the dream-log flag set,
 * which causes the UI to surface the live terminal feed overlay.
 */
object DreamNotificationHelper {

    const val CHANNEL_ID = "dream_cycle"
    const val NOTIFICATION_ID = 1003

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Dreaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while OnGrid performs background memory triage"
                setShowBadge(false)
            }
        )
    }

    /**
     * Post or update the dreaming notification.
     *
     * @param taskLabel   Short label shown in the title, e.g. "Memory Triage".
     * @param agentName   The agent currently being processed, e.g. "Atlas".
     * @param serverSubtext Status line shown beneath the content text,
     *                    e.g. "Mesa Server: Active | Agent: OnGrid-Ollama-70B".
     */
    fun notify(
        context: Context,
        taskLabel: String,
        agentName: String,
        serverSubtext: String
    ) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_SHOW_DREAM_LOG, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("\u2601\uFE0F Dreaming\u2026 [Task: $taskLabel]")
            .setContentText("Agent: $agentName")
            .setSubText(serverSubtext)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    /** Dismiss the dreaming notification when the cycle completes. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
