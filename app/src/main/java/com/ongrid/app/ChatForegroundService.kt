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
import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaTool
import com.ongrid.app.data.model.OllamaToolCall
import com.ongrid.app.data.model.OllamaToolFunction
import com.ongrid.app.data.model.PlanStep
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
        // True until the first tool-calling round is detected; planning only fires once.
        var planningPending = pending.guidedPlanningEnabled
        // Holds the plan bubble ID and live step list so the tool loop can update them.
        var planMsgId: String? = null
        var currentPlanSteps: MutableList<PlanStep> = mutableListOf()

        try {
            while (true) {
                val accumulated = StringBuilder()
                val thinkingAccumulated = StringBuilder()
                var toolCalls: List<OllamaToolCall> = emptyList()
                var streamError: String? = null

                try {
                    app.ollamaRepository.streamChat(pending.baseUrl, currentRequest)
                        .collect { chunk ->
                            // Thinking tokens come before content tokens
                            val thinkingDelta = chunk.message?.thinking ?: ""
                            if (thinkingDelta.isNotEmpty()) {
                                thinkingAccumulated.append(thinkingDelta)
                                app.chatServiceChannel.send(
                                    ChatServiceEvent.ThinkingToken(
                                        currentMsgId,
                                        thinkingAccumulated.toString()
                                    )
                                )
                            }

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
                        ChatServiceEvent.TurnComplete(
                            msgId = currentMsgId,
                            lastContent = accumulated.toString(),
                            thinkingContent = thinkingAccumulated.toString().ifEmpty { null }
                        )
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
                    ChatServiceEvent.FinalizeMessage(currentMsgId, accumulated.toString(), toolCalls)
                )

                // ---- Lazy guided planning ----------------------------------------
                // Planning fires exactly once: the first time the model decides to use
                // tools. It has no effect on simple non-tool replies, and it doesn't
                // run again on subsequent tool-call rounds within the same turn.
                if (planningPending) {
                    planningPending = false
                    val newPlanMsgId = UUID.randomUUID().toString()
                    planMsgId = newPlanMsgId
                    app.chatServiceChannel.send(
                        ChatServiceEvent.AppendMessage(
                            ChatMessage(
                                id = newPlanMsgId,
                                role = MessageRole.ASSISTANT,
                                content = "",
                                isStreaming = true,
                                isPlan = true
                            )
                        )
                    )
                    val planningRequest = currentRequest.copy(
                        tools = null,
                        think = null,
                        options = null,
                        messages = currentRequest.messages +
                            OllamaChatMessage(
                                role = "assistant",
                                content = accumulated.toString().ifEmpty { null },
                                tool_calls = toolCalls
                            ) +
                            OllamaChatMessage(
                                role = "user",
                                content = "Before executing the tool calls above, briefly outline " +
                                    "your step-by-step plan for completing this task as a " +
                                    "numbered list (e.g. \"1. Do X\n2. Do Y\")."
                            )
                    )
                    val planAccumulated = StringBuilder()
                    try {
                        app.ollamaRepository.streamChat(pending.baseUrl, planningRequest)
                            .collect { chunk ->
                                val delta = chunk.message?.content ?: ""
                                if (delta.isNotEmpty()) {
                                    planAccumulated.append(delta)
                                    app.chatServiceChannel.send(
                                        ChatServiceEvent.Token(newPlanMsgId, planAccumulated.toString())
                                    )
                                }
                            }
                    } catch (_: Exception) {
                        // Planning is best-effort — a failure here should not abort execution.
                    }
                    app.chatServiceChannel.send(
                        ChatServiceEvent.FinalizeMessage(newPlanMsgId, planAccumulated.toString())
                    )
                    // Parse the numbered list into discrete steps and push them to the UI.
                    val parsedSteps = parsePlanSteps(planAccumulated.toString())
                    if (parsedSteps.isNotEmpty()) {
                        currentPlanSteps = parsedSteps.toMutableList()
                        app.chatServiceChannel.send(
                            ChatServiceEvent.SetPlanSteps(newPlanMsgId, currentPlanSteps.toList())
                        )
                    }
                    // Inject the plan into the live request history so the model can
                    // reference it during every subsequent tool-call round this turn.
                    if (planAccumulated.isNotEmpty()) {
                        currentRequest = currentRequest.copy(
                            messages = currentRequest.messages +
                                OllamaChatMessage(role = "assistant", content = planAccumulated.toString()),
                            // Add mark_steps_complete to the tool list so the model can
                            // check off steps as it works through them.
                            tools = currentRequest.tools.orEmpty() + MARK_STEPS_COMPLETE_TOOL
                        )
                    }
                }

                // Extend the conversation history with the assistant message that
                // contained the tool calls, then add each tool result.
                // Use null (not "") for content when no text preceded the tool calls — Gemma
                // and other models treat an explicit empty-string content differently from an
                // absent content field and can ignore tool results when it is present.
                val nextMessages = currentRequest.messages.toMutableList()
                nextMessages += OllamaChatMessage(
                    role = "assistant",
                    content = accumulated.toString().ifEmpty { null },
                    tool_calls = toolCalls
                )

                val toolMap = app.mcpRepository.getAllEnabledTools()
                var anyToolFailed = false
                for (toolCall in toolCalls) {
                    val funcName = toolCall.function.name
                    val args = toolCall.function.arguments
                    val serverEntry = toolMap[funcName]

                    val (resultText, isError) = try {
                        val schema: McpInputSchema? = when {
                            funcName == "mark_steps_complete" -> null
                            funcName == "web_search" -> app.webSearchRepository.tool.inputSchema
                            serverEntry != null -> serverEntry.second.inputSchema
                            else -> null
                        }
                        val validationError = schema?.let { validateToolArgs(funcName, args, it) }
                        if (validationError != null) {
                            validationError to true
                        } else when {
                            funcName == "mark_steps_complete" -> {
                                // Intercept plan step completions — update the UI, no MCP call.
                                @Suppress("UNCHECKED_CAST")
                                val indices = (args["step_numbers"] as? List<*>)
                                    ?.mapNotNull { (it as? Number)?.toInt() }
                                    ?: emptyList()
                                val activePlanMsgId = planMsgId
                                if (activePlanMsgId != null && indices.isNotEmpty()) {
                                    val updated = currentPlanSteps.map { step ->
                                        if (step.index in indices) step.copy(isDone = true) else step
                                    }
                                    currentPlanSteps = updated.toMutableList()
                                    app.chatServiceChannel.send(
                                        ChatServiceEvent.SetPlanSteps(activePlanMsgId, currentPlanSteps.toList())
                                    )
                                }
                                val label = indices.joinToString(", ") { "step $it" }
                                "Marked $label as complete." to false
                            }
                            funcName == "web_search" -> {
                                app.webSearchRepository.search(args) to false
                            }
                            serverEntry != null -> {
                                val r = app.mcpRepository.callTool(serverEntry.first, funcName, args)
                                r.content.joinToString("\n") { it.text } to r.isError
                            }
                            else -> {
                                val availableNames = buildList {
                                    add("web_search")
                                    addAll(toolMap.keys)
                                }
                                val availableList = if (availableNames.isEmpty()) "none"
                                    else availableNames.joinToString(", ")
                                "Tool '$funcName' not found. Available tools: $availableList" to true
                            }
                        }
                    } catch (e: Exception) {
                        "Tool '$funcName' failed: ${e.message ?: "unknown error"}" to true
                    }

                    if (isError) anyToolFailed = true

                    // mark_steps_complete is silent — don't pollute the conversation with a
                    // tool-result bubble for a housekeeping call.
                    if (funcName == "mark_steps_complete") {
                        nextMessages += OllamaChatMessage(role = "tool", content = resultText)
                        continue
                    }

                    // Tell the ViewModel to append the tool-result bubble to the conversation
                    val toolResultMsg = ChatMessage(
                        role = MessageRole.TOOL,
                        content = resultText,
                        toolCallId = funcName,
                        isError = isError
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
                    tools = currentRequest.tools,
                    think = currentRequest.think,
                    options = currentRequest.options
                )
                // Loop → stream the follow-up turn
            }
        } finally {
            stopSelf(startId)
        }
    }

    // -------------------------------------------------------------------------
    // Tool argument validation
    // -------------------------------------------------------------------------

    /**
     * Validates that all required fields declared in [schema] are present in [args]
     * and non-blank. Returns a descriptive error string if validation fails, or null
     * if the arguments are acceptable.
     */
    private fun validateToolArgs(
        toolName: String,
        args: Map<String, Any>,
        schema: McpInputSchema
    ): String? {
        val missing = schema.required.filter { field ->
            val v = args[field]
            v == null || (v is String && v.isBlank())
        }
        if (missing.isEmpty()) return null
        val details = missing.joinToString("; ") { field ->
            val propInfo = schema.properties[field] as? Map<*, *>
            val type = propInfo?.get("type") as? String ?: "any"
            val desc = propInfo?.get("description") as? String
            if (desc != null) "'$field' ($type — $desc)" else "'$field' ($type)"
        }
        return "Tool '$toolName' called with missing required argument(s): $details. " +
            "Please call it again with all required fields provided."
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

        /** Regex that matches a numbered-list line: "1. text", "2) text", etc. */
        private val STEP_REGEX = Regex("""^\s*\d+[.)]\s+(.+)$""", RegexOption.MULTILINE)

        /** Parses a free-text plan response into discrete [PlanStep]s. */
        fun parsePlanSteps(text: String): List<PlanStep> =
            STEP_REGEX.findAll(text)
                .mapIndexed { i, m -> PlanStep(index = i + 1, text = m.groupValues[1].trim()) }
                .toList()

        /**
         * A built-in tool given to the model after planning so it can mark individual
         * plan steps as complete as it works through them.
         */
        val MARK_STEPS_COMPLETE_TOOL = OllamaTool(
            function = OllamaToolFunction(
                name = "mark_steps_complete",
                description = "Mark one or more steps from your plan as complete. " +
                    "Call this after each step succeeds so the user can see your progress.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "step_numbers" to mapOf(
                            "type" to "array",
                            "description" to "1-based step numbers to mark as complete",
                            "items" to mapOf("type" to "integer")
                        )
                    ),
                    "required" to listOf("step_numbers")
                )
            )
        )
    }
}
