package com.ongrid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service that owns the complete Ollama conversational turn, including any
 * tool-call round-trips.
 *
 * Why a foreground service?
 *   Android kills long-lived TCP connections when the app is backgrounded.  Elevating to a
 *   foreground service keeps the socket alive for the full duration of the response.
 *
 * Why does the service handle tool calls too?
 *   After the first streaming turn, if the model requests tools, we must execute them and send
 *   a follow-up request.  Calling [startForegroundService] a second time from the ViewModel
 *   fails with "mAllowStartForeground false" because the app is now backgrounded.  By keeping
 *   all of this work inside the already-running foreground service we need only one
 *   [startForegroundService] call — made synchronously from the UI thread in [ChatViewModel]
 *   while the app is still foregrounded.
 *
 * Progress is published to [OnGridApplication.chatServiceChannel] and consumed by [ChatViewModel].
 */
class ChatForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val app get() = application as OnGridApplication

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pending = app.pendingChatRequest
        if (pending == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        app.pendingChatRequest = null

        serviceScope.launch {
            runFullTurn(pending, startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Full-turn streaming logic (streaming + tool calls + follow-up streaming)
    // -------------------------------------------------------------------------

    private suspend fun runFullTurn(pending: PendingChatRequest, startId: Int) {
        var currentMsgId = pending.assistantMsgId
        var currentRequest = pending.request

        try {
            while (true) {
                val accumulated = StringBuilder()
                var toolCalls: List<OllamaToolCall> = emptyList()
                var streamError: String? = null

                try {
                    app.ollamaRepository.streamChat(pending.baseUrl, currentRequest)
                        .collect { chunk ->
                            val delta = chunk.message?.content ?: ""
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                app.chatServiceChannel.send(
                                    ChatServiceEvent.Token(currentMsgId, accumulated.toString())
                                )
                            }
                            chunk.message?.tool_calls?.let { calls ->
                                if (calls.isNotEmpty()) toolCalls = calls
                            }
                        }
                } catch (e: Exception) {
                    streamError = e.message
                }

                if (streamError != null) {
                    app.chatServiceChannel.send(
                        ChatServiceEvent.TurnComplete(currentMsgId, accumulated.toString(), streamError)
                    )
                    break
                }

                // No tool calls → turn is done
                if (toolCalls.isEmpty()) {
                    app.chatServiceChannel.send(
                        ChatServiceEvent.TurnComplete(currentMsgId, accumulated.toString())
                    )
                    if (!app.isAppForegrounded) {
                        postCompletionNotification(accumulated.toString())
                    }
                    break
                }

                // ---- Handle tool calls ----------------------------------------
                // Clear the streaming cursor on the current assistant bubble before
                // appending tool results — only the final placeholder should blink.
                app.chatServiceChannel.send(
                    ChatServiceEvent.FinalizeMessage(currentMsgId, accumulated.toString())
                )

                // Extend the conversation history with the assistant message that
                // contained the tool calls, then add each tool result.
                val nextMessages = currentRequest.messages.toMutableList()
                nextMessages += OllamaChatMessage(
                    role = "assistant",
                    content = accumulated.toString(),
                    tool_calls = toolCalls
                )

                val toolMap = app.mcpRepository.getAllEnabledTools()
                var anyToolFailed = false
                for (toolCall in toolCalls) {
                    val funcName = toolCall.function.name
                    val args = toolCall.function.arguments
                    val serverEntry = toolMap[funcName]

                    val (resultText, isError) = try {
                        if (serverEntry != null) {
                            val r = app.mcpRepository.callTool(serverEntry.first, funcName, args)
                            r.content.joinToString("\n") { it.text } to r.isError
                        } else {
                            "Tool '$funcName' not found in any connected MCP server." to true
                        }
                    } catch (e: Exception) {
                        "Tool '$funcName' failed: ${e.message ?: "unknown error"}" to true
                    }

                    if (isError) anyToolFailed = true

                    // Tell the ViewModel to append the tool-result bubble to the conversation
                    val toolResultMsg = ChatMessage(
                        role = MessageRole.TOOL,
                        content = resultText,
                        toolCallId = funcName
                    )
                    app.chatServiceChannel.send(ChatServiceEvent.AppendMessage(toolResultMsg))

                    nextMessages += OllamaChatMessage(role = "tool", content = resultText)
                }

                // If any tool failed, inject a user nudge so the model retries with
                // corrected arguments rather than just reporting the failure.
                if (anyToolFailed) {
                    val retryPrompt = "One or more tool calls failed (see results above). " +
                        "Please retry the failed call(s) with corrected arguments."
                    nextMessages += OllamaChatMessage(role = "user", content = retryPrompt)
                }

                // Create a new assistant placeholder for the follow-up turn and tell the
                // ViewModel to add it so the UI shows the streaming indicator immediately.
                val nextMsgId = UUID.randomUUID().toString()
                app.chatServiceChannel.send(
                    ChatServiceEvent.AppendMessage(
                        ChatMessage(
                            id = nextMsgId,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            isStreaming = true
                        )
                    )
                )

                currentMsgId = nextMsgId
                currentRequest = OllamaChatRequest(
                    model = currentRequest.model,
                    messages = nextMessages,
                    stream = true,
                    tools = currentRequest.tools
                )
                // Loop → stream the follow-up turn
            }
        } finally {
            stopSelf(startId)
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Response",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while an AI response is being fetched in the background"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        if (nm.getNotificationChannel(COMPLETE_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "AI Response Ready",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when the AI has finished responding"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postCompletionNotification(content: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val aiPerson = Person.Builder().setName("AI").build()
        val preview = if (content.length > 200) content.take(200) + "…" else content
        val style = NotificationCompat.MessagingStyle(aiPerson)
            .addMessage(preview, System.currentTimeMillis(), aiPerson)
        val notification = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setStyle(style)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Fetching AI response\u2026")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "chat_stream"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETE_CHANNEL_ID = "chat_complete"
        private const val COMPLETE_NOTIFICATION_ID = 1002
    }
}
