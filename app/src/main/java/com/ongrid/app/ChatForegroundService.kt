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
import com.ongrid.app.data.local.AgentStatus
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
        // Mutable copy so skills are removed from the available set after being activated
        val remainingSkillMap = pending.availableSkillMap.toMutableMap()

        try {
            while (true) {
                val accumulated = StringBuilder()
                val thinkingAccumulated = StringBuilder()
                var toolCalls: List<OllamaToolCall> = emptyList()
                var streamError: String? = null
                var finalPromptTokens: Int? = null
                var finalEvalTokens: Int? = null

                try {
                    app.ollamaRepository.streamChat(pending.baseUrl, currentRequest)
                        .collect { chunk ->
                            // Thinking delta (reasoning tokens)
                            val thinkingDelta = chunk.message?.thinking ?: ""
                            if (thinkingDelta.isNotEmpty()) {
                                thinkingAccumulated.append(thinkingDelta)
                                app.chatServiceChannel.send(
                                    ChatServiceEvent.ThinkingToken(currentMsgId, thinkingAccumulated.toString())
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
                            if (chunk.done) {
                                finalPromptTokens = chunk.promptEvalCount
                                finalEvalTokens = chunk.evalCount
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
                    val promptTokens = finalPromptTokens
                    val evalTokens = finalEvalTokens
                    if (promptTokens != null && evalTokens != null) {
                        app.chatServiceChannel.send(
                            ChatServiceEvent.TokenUsage(promptTokens, evalTokens)
                        )
                    }
                    app.chatServiceChannel.send(
                        ChatServiceEvent.TurnComplete(
                            currentMsgId,
                            accumulated.toString(),
                            thinkingContent = thinkingAccumulated.toString().ifEmpty { null }
                        )
                    )
                    if (!app.isAppForegrounded) {
                        postCompletionNotification(accumulated.toString(), pending)
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
                            funcName == "web_search" -> app.webSearchRepository.tool.inputSchema
                            funcName == "fetch_url" -> app.webFetchRepository.tool.inputSchema
                            funcName == "form_memory" -> app.formMemoryRepository.tool.inputSchema
                            funcName == "use_skill" -> app.skillActivationRepository.tool.inputSchema
                            serverEntry != null -> serverEntry.second.inputSchema
                            else -> null
                        }
                        val validationError = schema?.let { validateToolArgs(funcName, args, it) }
                        if (validationError != null) {
                            validationError to true
                        } else when {
                            funcName == "web_search" -> {
                                app.webSearchRepository.search(args) to false
                            }
                            funcName == "fetch_url" -> {
                                app.webFetchRepository.fetch(args) to false
                            }
                            funcName == "form_memory" -> {
                                val agentId = pending.agentId
                                if (agentId == null) {
                                    "Error: form_memory is only available inside an agent workspace." to true
                                } else {
                                    val candidate = args["content"]?.toString()?.trim()
                                    if (candidate.isNullOrBlank()) {
                                        "Error: the 'content' argument is required and must not be blank." to true
                                    } else {
                                        // Check existing memories for duplicates / contradictions
                                        val existingMemories = app.agentRepository.memoriesForAgentOnce(agentId)
                                        val existingContents = existingMemories.map { it.content }
                                        val settings = app.settingsRepository.settings.first()
                                        val globalHost = settings.utilityModelHost.ifBlank { "" }
                                        val globalModel = settings.utilityModelName.ifBlank { "" }
                                        val (utilHost, utilModel) = app.agentRepository.resolveUtilityModel(agentId, globalHost, globalModel)

                                        val conflict = if (utilHost.isNotBlank() && utilModel.isNotBlank()) {
                                            app.utilityAgentRepository.checkMemoryConflict(
                                                utilHost, utilModel, candidate, existingContents
                                            )
                                        } else null

                                        when {
                                            conflict != null && conflict == "duplicate" -> {
                                                "Memory already exists: \"$candidate\" — no duplicate stored." to false
                                            }
                                            conflict != null && conflict.startsWith("contradiction:") -> {
                                                val conflicting = conflict.removePrefix("contradiction:").trim()
                                                "Memory not stored — contradicts existing memory: \"$conflicting\". Update the existing memory if the new fact supersedes it." to false
                                            }
                                            else -> {
                                                val result = app.formMemoryRepository.formMemory(
                                                    agentId, pending.conversationId, args
                                                )
                                                // Notify the ViewModel so it can refresh the in-memory list.
                                                app.chatServiceChannel.send(ChatServiceEvent.MemoryFormed(agentId))
                                                result to false
                                            }
                                        }
                                    }
                                }
                            }
                            funcName == "use_skill" -> {
                                val skillName = args["skill_name"]?.toString()?.trim() ?: ""
                                val entry = remainingSkillMap[skillName]
                                if (entry == null) {
                                    val names = remainingSkillMap.keys.joinToString(", ").ifEmpty { "none" }
                                    "Skill '$skillName' not found. Available skills: $names" to true
                                } else {
                                    val (skillId, skillContent) = entry
                                    // Remove from available set so it can't be double-activated
                                    remainingSkillMap.remove(skillName)
                                    // Inject skill content as the first system message in the next request
                                    nextMessages.add(0, OllamaChatMessage(role = "system", content = skillContent))
                                    // Notify the ViewModel so it marks the skill active
                                    app.chatServiceChannel.send(ChatServiceEvent.SkillActivated(skillId))
                                    "Skill '$skillName' is now active. Its instructions have been loaded." to false
                                }
                            }
                            serverEntry != null -> {
                                val r = app.mcpRepository.callTool(serverEntry.first, funcName, args)
                                r.content.joinToString("\n") { it.text } to r.isError
                            }
                            else -> {
                                val availableNames = buildList {
                                    add("web_search")
                                    add("fetch_url")
                                    if (pending.agentId != null) {
                                        add("form_memory")
                                        add("use_skill")
                                    }
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

                    // Tell the ViewModel to append the tool-result bubble to the conversation
                    val toolResultMsg = ChatMessage(
                        role = MessageRole.TOOL,
                        content = resultText,
                        toolCallId = funcName,
                        isError = isError
                    )
                    app.chatServiceChannel.send(ChatServiceEvent.AppendMessage(toolResultMsg))

                    // Truncate large tool results before adding to history. The UI bubble shows
                    // the full content, but the model only needs enough to act on — a full article
                    // HTML dump can be 20K+ characters and silently overflow the context window,
                    // causing the follow-up turn to return empty.
                    // Cap scales with the model's context window: ~25% of numCtx (in chars),
                    // so a 3B model at 32K gets ~32K chars while a 70B at 128K gets ~128K chars.
                    val numCtx = currentRequest.options?.numCtx ?: 32768
                    val maxToolResultChars = (numCtx * 4 * 0.25).toInt().coerceIn(4000, 24000)
                    val historyContent = if (resultText.length > maxToolResultChars)
                        resultText.take(maxToolResultChars) +
                            "\n\n[Result truncated — ${resultText.length - maxToolResultChars} further characters omitted]"
                    else resultText
                    nextMessages += OllamaChatMessage(role = "tool", content = historyContent)
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

    private fun postCompletionNotification(content: String, pending: PendingChatRequest) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build an agent-specific Person so Android groups the notification as a "Conversation".
        val personName = pending.agentName ?: "AI"
        val aiPerson = Person.Builder()
            .setName(personName)
            .setKey(pending.agentId ?: "ai_default")
            .setImportant(pending.agentId != null)
            .build()

        val preview = if (content.length > 200) content.take(200) + "…" else content
        val style = NotificationCompat.MessagingStyle(aiPerson)
            .setConversationTitle(if (pending.agentId != null) personName else null)
            .setGroupConversation(pending.agentId != null)
            .addMessage(preview, System.currentTimeMillis(), aiPerson)

        val moodSubText = pending.agentMood
            ?.takeIf { it.isNotBlank() && it != "Neutral" }
            ?.let { "Mood: $it" }

        // Unique notification ID per agent so each agent has its own conversation entry.
        val notifId = pending.agentId?.hashCode()?.let { COMPLETE_NOTIFICATION_ID + (it and 0xFFFF) }
            ?: COMPLETE_NOTIFICATION_ID

        val builder = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setStyle(style)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (moodSubText != null) builder.setSubText(moodSubText)

        // Associate with the agent's dynamic shortcut so Android promotes it to the
        // "Conversations" section of the notification shade (Android 11+).
        pending.agentId?.let { builder.setShortcutId("agent_$it") }

        getSystemService(NotificationManager::class.java).notify(notifId, builder.build())
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
