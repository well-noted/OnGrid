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
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: "room_worker"
        RoomNotificationHelper.ensureChannel(applicationContext)
        val notif = NotificationCompat.Builder(applicationContext, RoomNotificationHelper.CHANNEL_ID)
            .setContentTitle("🤝 Room active")
            .setContentText("Agents are collaborating…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(RoomNotificationHelper.notificationId(conversationId), notif)
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

        RoomNotificationHelper.notify(
            applicationContext,
            conversationId = conversationId,
            roomName = room.name,
            participantNames = agentNames,
            goalSnippet = goal.take(60)
        )

        // All-consent goal tracking: maps agentId → has signaled
        val goalSignaledBy = mutableSetOf<String>()
        var turnCount = 0
        var goalReached = false
        var speakerIndex = 0

        while (turnCount < MAX_TURNS && !goalReached) {
            // Fetch messages once per turn — reused for history, orchestrator, and starvation guard
            val allMessages = app.database.messageDao().getByConversation(conversationId)
            val assistantMessages = allMessages.filter { it.role == "ASSISTANT" && it.senderAgentId != null }
            val turnCountsMap: Map<String, Int> = agents.associate { agent ->
                agent.id to assistantMessages.count { it.senderAgentId == agent.id }
            }

            // ── Select next speaker ──────────────────────────────────────────────
            val currentSpeaker: AgentEntity = if (orchestratorAgent != null) {
                // Show a brief "Orchestrating…" indicator while the LLM picks the next speaker.
                // Using a distinct id so it doesn't collide with the speaker's own TYPING row.
                val orchestratingId = "typing_${conversationId}_orch"
                app.database.messageDao().insert(
                    MessageEntity(
                        id = orchestratingId,
                        conversationId = conversationId,
                        role = "TYPING",
                        content = "Orchestrating…",
                        timestamp = System.currentTimeMillis(),
                        senderAgentId = orchestratorAgent.id
                    )
                )
                val chosen = pickNextSpeaker(
                    orchestrator = orchestratorAgent,
                    agents = agents,
                    allMessages = allMessages,
                    turnCounts = turnCountsMap,
                    baseUrl = baseUrl,
                    modelName = modelName,
                    goal = goal,
                    room = room
                )
                app.database.messageDao().deleteById(orchestratingId)
                // Starvation guard: force-pick the most underrepresented agent if any agent is
                // more than 0.5 turns below the current average. This ensures all N agents
                // participate even if the orchestrator repeatedly picks the same subset.
                val avgTurns = turnCountsMap.values.average().takeIf { it.isFinite() } ?: 0.0
                val starved = agents
                    .filter { (turnCountsMap[it.id] ?: 0) < avgTurns - 0.5 }
                    .minByOrNull { turnCountsMap[it.id] ?: 0 }
                if (starved != null) {
                    Log.d(TAG, "Starvation guard: forcing ${starved.name} (turns=${turnCountsMap[starved.id]}, avg=${"%.1f".format(avgTurns)})")
                }
                starved ?: chosen ?: agents[speakerIndex % agents.size]
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

            // Build history (reuses allMessages fetched at the top of this turn)
            val history = mutableListOf<OllamaChatMessage>()
            history += OllamaChatMessage(role = "system", content = systemContent)
            for (msg in allMessages) {
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
            // If the agent produces no text after tool results, we send one nudge so
            // the agent has a chance to actually respond before yielding to the orchestrator.
            var nudgeSent = false

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
                        // Final flush — write anything below the throttle threshold
                        if (accumulated.length > lastContentWriteLength) {
                            app.database.messageDao().updateContent(typingId, accumulated.toString())
                            lastContentWriteLength = accumulated.length
                        }
                        if (accumulatedThinking.length > lastThinkingWriteLength) {
                            app.database.messageDao().updateThinkingContent(typingId, accumulatedThinking.toString())
                            lastThinkingWriteLength = accumulatedThinking.length
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
                    val trimmed = accumulated.toString().trim()
                    // If the model produced no text after receiving tool results, nudge it
                    // once so it actually replies before we yield to the orchestrator.
                    if (trimmed.isBlank() && toolDepth > 0 && !nudgeSent) {
                        nudgeSent = true
                        Log.d(TAG, "Turn $turnCount: blank response after tool results — nudging ${currentSpeaker.name}")
                        currentHistory += OllamaChatMessage(
                            role = "user",
                            content = "[system]: Tool results received. Please now provide your response to the conversation."
                        )
                        // Don't break — re-enter the inner loop to get the agent's text reply.
                    } else {
                        responseText = trimmed
                        break
                    }
                }

                // Execute tool calls
                Log.d(TAG, "Turn $turnCount depth $toolDepth: ${toolCalls.size} tool(s)")

                // ── Pre-tool reasoning snapshot ───────────────────────────────
                // If the agent produced thinking content (and/or partial text) before deciding
                // to call a tool, persist it NOW as a settled ASSISTANT row so it appears
                // BEFORE the tool messages in the timeline. Without this, the TYPING bubble
                // (which holds the thinking) is sorted last by the SQL ordering rule, making
                // it appear after the tool call rows — backwards from the actual sequence.
                val preToolThinking = accumulatedThinking.toString().trim()
                val preToolContent  = accumulated.toString().trim()
                if (preToolThinking.isNotEmpty() || preToolContent.isNotEmpty()) {
                    app.database.messageDao().insert(
                        MessageEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "ASSISTANT",
                            content = preToolContent,
                            thinkingContent = preToolThinking,
                            timestamp = System.currentTimeMillis(),
                            senderAgentId = currentSpeaker.id
                        )
                    )
                    // Reset accumulated thinking so the next round starts fresh
                    accumulatedThinking = StringBuilder()
                }
                // Clear the TYPING bubble content and thinking so the next streamed
                // round doesn't mix old reasoning with new.
                app.database.messageDao().updateContent(typingId, "")
                app.database.messageDao().updateThinkingContent(typingId, "")

                currentHistory += OllamaChatMessage(
                    role = "assistant",
                    content = preToolContent.ifEmpty { null },
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
                    // Use the same "user" role + explicit label that history reconstruction uses
                    // when rebuilding past turns from the DB. This keeps the format consistent
                    // across turns and avoids models that don't handle role="tool" cleanly.
                    currentHistory += OllamaChatMessage(
                        role = "user",
                        content = "[tool:${toolCall.function.name} result]: $result"
                    )
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
            val signalledComplete = responseText.contains(GOAL_COMPLETE_SIGNAL)
            val displayText = responseText.replace(GOAL_COMPLETE_SIGNAL, "").trim()
                .ifBlank {
                    // Agent sent only the goal signal with no real content — treat as an
                    // implicit agreement rather than a substantive turn. Show a minimal note
                    // and don't count it as a valid goal signal (forces agents to say something).
                    Log.w(TAG, "Turn $turnCount: ${currentSpeaker.name} sent empty content (only signal or blank)")
                    null
                }
            val thinkingText = accumulatedThinking.toString().trim()
            if (displayText == null) {
                // Skip inserting an invisible empty bubble; advance to next turn
                accumulatedThinking = StringBuilder()
                speakerIndex = (speakerIndex + 1) % agents.size
                turnCount++
                continue
            }
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

        RoomNotificationHelper.dismiss(applicationContext, conversationId)

        Log.d(TAG, "Finished. Turns=$turnCount, goalReached=$goalReached")
        return Result.success()
    }

    /**
     * Asks the orchestrator agent to pick the next speaker. Returns the resolved [AgentEntity]
     * or null if the response can't be parsed to a valid agent name.
     *
     * [allMessages] and [turnCounts] are pre-computed by the caller to avoid an extra DB hit.
     */
    private suspend fun pickNextSpeaker(
        orchestrator: AgentEntity,
        agents: List<AgentEntity>,
        allMessages: List<com.ongrid.app.data.local.MessageEntity>,
        turnCounts: Map<String, Int>,
        baseUrl: String,
        modelName: String,
        goal: String,
        room: com.ongrid.app.data.local.AgentRoomEntity
    ): AgentEntity? {
        val agentList = agents.joinToString(", ") { it.name }
        val turnSummary = agents.joinToString(", ") { "${it.name}: ${turnCounts[it.id] ?: 0}" }
        val unspoken = agents.filter { (turnCounts[it.id] ?: 0) == 0 }.map { it.name }

        val recentMessages = allMessages
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
            append("Turns taken so far — $turnSummary\n")
            append("Ensure all participants get a roughly equal number of turns. Prefer agents who have spoken fewer times.\n")
            if (unspoken.isNotEmpty()) {
                append("Has not yet spoken: ${unspoken.joinToString(", ")}.\n")
            }
            append("\n")
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
