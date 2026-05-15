package com.ongrid.app

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.MessageEntity
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaRequestOptions
import kotlinx.coroutines.delay
import java.util.UUID

private const val TAG = "RoomConversationWorker"
private const val MAX_TURNS = 30
private const val MAX_TOOL_DEPTH = 6
private const val MAX_RETRIES_PER_TURN = 4
private const val RETRY_BASE_DELAY_MS = 6_000L

/**
 * Drives a multi-agent room conversation.
 *
 * Turn routing:
 *  - If the room has an [orchestratorAgentId], the orchestrator is queried (lightweight, no tools)
 *    before each turn to select the next speaker by name. The orchestrator's response is parsed
 *    for any agent name and that agent speaks next. Falls back to round-robin if parsing fails.
 *  - Otherwise, agents speak in round-robin order.
 *
 * Goal completion: tracks which agents have signaled [GOAL_COMPLETE_SIGNAL]. Any agent that later
 * responds WITHOUT the signal is removed from the set (they changed their mind). Conversation ends
 * when all agents have simultaneously signaled.
 */
class RoomConversationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OnGridApplication

    override suspend fun getForegroundInfo(): ForegroundInfo {
        DreamNotificationHelper.ensureChannel(applicationContext)
        val notif = NotificationCompat.Builder(applicationContext, DreamNotificationHelper.CHANNEL_ID)
            .setContentTitle("Agent Room")
            .setContentText("Agents are collaborating")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notif)
    }

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: return Result.failure()

        Log.d(TAG, "Starting room $roomId / conversation $conversationId")

        // Clean up any stale TYPING rows from a previous interrupted run
        app.database.messageDao().promoteTypingWithContent(conversationId)
        app.database.messageDao().deleteEmptyTyping(conversationId)

        val room = app.agentRoomRepository.getRoom(roomId)
            ?: run { Log.e(TAG, "Room $roomId not found"); return Result.failure() }

        val conversation = app.database.conversationDao().getById(conversationId)
            ?: run { Log.e(TAG, "Conversation $conversationId not found"); return Result.failure() }

        val agentIds = app.agentRoomRepository.parseAgentIds(room.agentIds)
        if (agentIds.size < 2) {
            Log.e(TAG, "Room needs at least 2 agents (has ${agentIds.size})")
            return Result.failure()
        }

        val agents: List<AgentEntity> = agentIds.mapNotNull { app.agentRepository.getAgent(it) }
        if (agents.size < 2) {
            Log.e(TAG, "Could not resolve agents for room $roomId")
            return Result.failure()
        }

        val orchestratorAgent: AgentEntity? = room.orchestratorAgentId
            ?.let { app.agentRepository.getAgent(it) }

        val baseUrl = "http://${room.serverHost}:${room.serverPort}"
        val modelName = room.modelName
        val goal = conversation.goal.ifBlank { room.goalTemplate }
        val executor = AgentToolExecutor(app)
        val tools = executor.buildTools().takeIf { it.isNotEmpty() }

        val agentNames = agents.joinToString(", ") { it.name }
        Log.d(TAG, "Agents: $agentNames | Orchestrator: ${orchestratorAgent?.name ?: "round-robin"}")

        DreamNotificationHelper.notify(
            applicationContext,
            taskLabel = room.name,
            agentName = agentNames,
            serverSubtext = goal.take(60)
        )

        // All-consent goal tracking: maps agentId → has signaled
        val goalSignaledBy = mutableSetOf<String>()
        var turnCount = 0
        var goalReached = false
        var speakerIndex = 0

        while (turnCount < MAX_TURNS && !goalReached) {
            // ── Select next speaker ──────────────────────────────────────────────
            val currentSpeaker: AgentEntity = if (orchestratorAgent != null) {
                val chosen = pickNextSpeaker(
                    orchestrator = orchestratorAgent,
                    agents = agents,
                    conversationId = conversationId,
                    baseUrl = baseUrl,
                    modelName = modelName,
                    goal = goal,
                    room = room
                )
                chosen ?: agents[speakerIndex % agents.size]
            } else {
                agents[speakerIndex % agents.size]
            }

            val otherAgents = agents.filter { it.id != currentSpeaker.id }
            val otherNames = otherAgents.joinToString(", ") { it.name }
            Log.d(TAG, "Turn $turnCount: ${currentSpeaker.name} speaking")

            val memories = app.agentRepository.memoriesForAgentOnce(currentSpeaker.id)
            val roomMemories = app.agentRoomRepository.memoriesForRoomOnce(roomId)

            val signalersStr = if (goalSignaledBy.isEmpty()) ""
            else agents.filter { it.id in goalSignaledBy }.joinToString(", ") { it.name }

            val systemContent = buildString {
                // 1. Agent's own persona and background first
                if (currentSpeaker.systemPrompt.isNotBlank()) {
                    append(currentSpeaker.systemPrompt); append("\n\n")
                }
                if (currentSpeaker.brief.isNotBlank()) {
                    append("Your current context:\n${currentSpeaker.brief}\n\n")
                }
                if (memories.isNotEmpty()) {
                    val bullet = memories.take(8).joinToString("\n") { "- ${it.content}" }
                    append("Your memories:\n$bullet\n\n")
                }
                if (roomMemories.isNotEmpty()) {
                    val bullet = roomMemories.take(10).joinToString("\n") { "- ${it.content}" }
                    append("Shared room context:\n$bullet\n\n")
                }
                if (room.systemPrompt.isNotBlank()) {
                    append("Room guidelines:\n${room.systemPrompt}\n\n")
                }
                append("Goal: $goal\n\n")
                // 2. Identity and behavioural constraints last — highest precedence
                append("---\n")
                append("For this conversation you are ${currentSpeaker.name}, speaking inside the room \"${room.name}\". ")
                append("The other participants are: $otherNames.\n")
                append("Write ONLY your own reply. Do not write lines for other participants, do not label your own turn, do not add disclaimers about being an AI.\n")
                // Goal completion instructions
                when {
                    goalSignaledBy.isEmpty() ->
                        append("When you believe the goal is fully accomplished, end your message with exactly: $GOAL_COMPLETE_SIGNAL")
                    signalersStr.isNotEmpty() && currentSpeaker.id !in goalSignaledBy ->
                        append("$signalersStr believe the goal is accomplished. If you agree, end your message with exactly: $GOAL_COMPLETE_SIGNAL. Otherwise, continue working.")
                    currentSpeaker.id in goalSignaledBy ->
                        append("You previously indicated the goal is accomplished. If you still agree, end with: $GOAL_COMPLETE_SIGNAL. Otherwise continue.")
                }
            }

            // Build history
            val messages = app.database.messageDao().getByConversation(conversationId)
            val history = mutableListOf<OllamaChatMessage>()
            history += OllamaChatMessage(role = "system", content = systemContent)
            for (msg in messages) {
                when {
                    msg.role == "TYPING" -> continue
                    msg.role == "TOOL" -> {
                        val toolLabel = if (msg.toolName != null) "tool:${msg.toolName}" else "tool"
                        history += OllamaChatMessage(role = "user", content = "[$toolLabel result]: ${msg.content.take(600)}")
                    }
                    msg.role == "TOOL_ERROR" -> {
                        val toolLabel = if (msg.toolName != null) "tool:${msg.toolName} error" else "tool error"
                        history += OllamaChatMessage(role = "user", content = "[$toolLabel]: ${msg.content.take(300)}")
                    }
                    msg.role == "USER" || msg.role == "user" ->
                        history += OllamaChatMessage(role = "user", content = "[user]: ${msg.content}")
                    msg.role == "SYSTEM" ->
                        history += OllamaChatMessage(role = "user", content = "[system]: ${msg.content}")
                    msg.senderAgentId == currentSpeaker.id ->
                        history += OllamaChatMessage(role = "assistant", content = msg.content)
                    msg.senderAgentId != null -> {
                        val senderName = agents.find { it.id == msg.senderAgentId }?.name ?: "Agent"
                        history += OllamaChatMessage(role = "user", content = "[$senderName]: ${msg.content}")
                    }
                }
            }

            // Insert TYPING placeholder
            val typingId = "typing_${conversationId}_${currentSpeaker.id}"
            app.database.messageDao().insert(
                MessageEntity(
                    id = typingId,
                    conversationId = conversationId,
                    role = "TYPING",
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    senderAgentId = currentSpeaker.id
                )
            )

            // ── Tool-call loop ───────────────────────────────────────────────────
            var currentHistory = history.toMutableList()
            var responseText: String? = null
            var accumulatedThinking = StringBuilder()
            var toolDepth = 0
            var turnAborted = false

            while (toolDepth <= MAX_TOOL_DEPTH) {
                val request = OllamaChatRequest(
                    model = modelName,
                    messages = currentHistory,
                    stream = true,
                    tools = tools,
                    options = OllamaRequestOptions(numCtx = 8192)
                )

                var accumulated = StringBuilder()
                var toolCalls = emptyList<com.ongrid.app.data.model.OllamaToolCall>()
                var ollamaOk = false
                var lastContentWriteLength = 0
                var lastThinkingWriteLength = 0

                for (attempt in 1..MAX_RETRIES_PER_TURN) {
                    if (attempt > 1) {
                        val wait = RETRY_BASE_DELAY_MS * (attempt - 1)
                        Log.w(TAG, "Turn $turnCount depth $toolDepth attempt $attempt: waiting ${wait}ms")
                        delay(wait)
                    }
                    accumulated = StringBuilder()
                    toolCalls = emptyList()
                    lastContentWriteLength = 0
                    lastThinkingWriteLength = 0
                    try {
                        app.ollamaRepository.streamChat(baseUrl, request).collect { chunk ->
                            val thinkingDelta = chunk.message?.thinking ?: ""
                            if (thinkingDelta.isNotEmpty()) {
                                accumulatedThinking.append(thinkingDelta)
                                if (accumulatedThinking.length - lastThinkingWriteLength >= 15) {
                                    app.database.messageDao().updateThinkingContent(typingId, accumulatedThinking.toString())
                                    lastThinkingWriteLength = accumulatedThinking.length
                                }
                            }

                            val delta = chunk.message?.content ?: ""
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                if (accumulated.length - lastContentWriteLength >= 15) {
                                    app.database.messageDao().updateContent(typingId, accumulated.toString())
                                    lastContentWriteLength = accumulated.length
                                }
                            }
                            chunk.message?.tool_calls?.let { calls ->
                                if (calls.isNotEmpty()) toolCalls = calls
                            }
                        }
                        if (accumulated.isNotBlank() || toolCalls.isNotEmpty()) {
                            ollamaOk = true; break
                        }
                        Log.w(TAG, "Turn $turnCount attempt $attempt: blank response")
                    } catch (e: Exception) {
                        Log.w(TAG, "Turn $turnCount attempt $attempt failed: ${e.message}")
                    }
                }

                if (!ollamaOk) {
                    Log.e(TAG, "Turn $turnCount exhausted retries")
                    turnAborted = true; break
                }

                if (toolCalls.isEmpty()) {
                    responseText = accumulated.toString().trim()
                    break
                }

                // Execute tool calls
                Log.d(TAG, "Turn $turnCount depth $toolDepth: ${toolCalls.size} tool(s)")
                app.database.messageDao().updateContent(typingId, "")
                currentHistory += OllamaChatMessage(
                    role = "assistant",
                    content = accumulated.toString().ifEmpty { null },
                    tool_calls = toolCalls
                )
                for (toolCall in toolCalls) {
                    val (result, isError) = executor.execute(
                        funcName = toolCall.function.name,
                        args = toolCall.function.arguments,
                        agentId = currentSpeaker.id,
                        conversationId = conversationId
                    )
                    Log.d(TAG, "Tool ${toolCall.function.name}: ${result.take(80)}")
                    currentHistory += OllamaChatMessage(role = "tool", content = result)
                    app.database.messageDao().insert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = if (isError) "TOOL_ERROR" else "TOOL",
                            content = result,
                            timestamp = System.currentTimeMillis(),
                            senderAgentId = currentSpeaker.id,
                            toolName = toolCall.function.name
                        )
                    )
                }
                toolDepth++
            }

            // Cleanup TYPING placeholder
            app.database.messageDao().deleteById(typingId)

            if (turnAborted || responseText == null) {
                Log.e(TAG, "Turn $turnCount produced no response")
                app.database.messageDao().insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "SYSTEM",
                        content = "⚠ ${currentSpeaker.name} could not respond after $MAX_RETRIES_PER_TURN attempts. Tap to retry.",
                        timestamp = System.currentTimeMillis()
                    )
                )
                break
            }

            // Persist agent response
            val displayText = responseText.replace(GOAL_COMPLETE_SIGNAL, "").trim()
            val thinkingText = accumulatedThinking.toString().trim()
            app.database.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "ASSISTANT",
                    content = displayText,
                    thinkingContent = thinkingText,
                    timestamp = System.currentTimeMillis(),
                    senderAgentId = currentSpeaker.id
                )
            )
            accumulatedThinking = StringBuilder()
            app.database.conversationDao().updateTimestamp(conversationId, System.currentTimeMillis())
            app.agentRoomRepository.touchRoom(roomId)
            Log.d(TAG, "Turn $turnCount persisted (${displayText.length} chars)")

            // Auto-title after first turn
            if (turnCount == 0) {
                val shortGoal = goal.take(50).let { if (goal.length > 50) "$it…" else it }
                app.database.conversationDao().updateTitle(
                    conversationId,
                    "${room.name}: $shortGoal",
                    System.currentTimeMillis()
                )
            }

            // ── All-consent goal tracking ────────────────────────────────────────
            if (responseText.contains(GOAL_COMPLETE_SIGNAL)) {
                goalSignaledBy.add(currentSpeaker.id)
                Log.d(TAG, "${currentSpeaker.name} signaled complete. Confirmed: ${goalSignaledBy.size}/${agents.size}")
                if (goalSignaledBy.size == agents.size) {
                    goalReached = true
                    Log.d(TAG, "All agents confirmed goal complete")
                }
            } else {
                if (goalSignaledBy.remove(currentSpeaker.id)) {
                    Log.d(TAG, "${currentSpeaker.name} did not confirm — removed from signal set")
                }
            }

            if (goalReached) break

            // Advance round-robin index regardless of orchestrator (used as fallback)
            speakerIndex = (speakerIndex + 1) % agents.size
            turnCount++
        }

        val statusLabel = when {
            goalReached -> "Goal complete"
            turnCount >= MAX_TURNS -> "Max turns reached"
            else -> "Conversation ended"
        }
        DreamNotificationHelper.notify(
            applicationContext,
            taskLabel = statusLabel,
            agentName = agentNames,
            serverSubtext = goal.take(60)
        )

        Log.d(TAG, "Finished. Turns=$turnCount, goalReached=$goalReached")
        return Result.success()
    }

    /**
     * Asks the orchestrator agent to pick the next speaker. Returns the resolved [AgentEntity]
     * or null if the response can't be parsed to a valid agent name.
     */
    private suspend fun pickNextSpeaker(
        orchestrator: AgentEntity,
        agents: List<AgentEntity>,
        conversationId: String,
        baseUrl: String,
        modelName: String,
        goal: String,
        room: com.ongrid.app.data.local.AgentRoomEntity
    ): AgentEntity? {
        val agentList = agents.joinToString(", ") { it.name }
        val recentMessages = app.database.messageDao().getByConversation(conversationId)
            .filter { it.role == "ASSISTANT" }
            .takeLast(6)
            .joinToString("\n") { msg ->
                val name = agents.find { it.id == msg.senderAgentId }?.name ?: "Agent"
                "$name: ${msg.content.take(120)}"
            }

        val orchestratorPrompt = buildString {
            append("You are ${orchestrator.name}, the conversation orchestrator for the room \"${room.name}\".\n")
            append("Participants: $agentList\n")
            append("Goal: $goal\n\n")
            if (recentMessages.isNotEmpty()) {
                append("Recent conversation:\n$recentMessages\n\n")
            }
            if (room.systemPrompt.isNotBlank()) {
                append("Room guidelines: ${room.systemPrompt}\n\n")
            }
            append("Based on the goal and conversation so far, who should speak next?\n")
            append("Reply with ONLY the exact name of one participant — nothing else.")
        }

        return try {
            val request = OllamaChatRequest(
                model = modelName,
                messages = listOf(
                    OllamaChatMessage(role = "user", content = orchestratorPrompt)
                ),
                stream = false,
                options = OllamaRequestOptions(numCtx = 2048)
            )
            var chosenName: String? = null
            app.ollamaRepository.streamChat(baseUrl, request).collect { chunk ->
                val content = chunk.message?.content ?: ""
                if (content.isNotBlank()) chosenName = (chosenName ?: "") + content
            }
            val name = chosenName?.trim() ?: return null
            // Fuzzy match: find agent whose name appears in the orchestrator's reply
            agents.firstOrNull { agent ->
                name.contains(agent.name, ignoreCase = true) ||
                agent.name.contains(name, ignoreCase = true)
            }.also {
                Log.d(TAG, "Orchestrator ${orchestrator.name} chose: '$name' → ${it?.name ?: "no match"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Orchestrator selection failed: ${e.message}")
            null
        }
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        const val KEY_CONVERSATION_ID = "conversationId"
        private const val FOREGROUND_NOTIFICATION_ID = 1005

        fun enqueue(context: Context, roomId: String, conversationId: String) {
            val request = OneTimeWorkRequestBuilder<RoomConversationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ROOM_ID to roomId,
                        KEY_CONVERSATION_ID to conversationId
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("room_convo_$roomId")
                .addTag("room_convo_$conversationId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "room_convo_$roomId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
