package com.ongrid.app

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.OutOfQuotaPolicy
import androidx.work.workDataOf
import com.ongrid.app.data.local.MessageEntity
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaRequestOptions
import kotlinx.coroutines.delay
import java.util.UUID

private const val TAG = "AgentConversationWorker"
private const val MAX_TURNS = 20
private const val MAX_TOOL_DEPTH = 6
private const val MAX_RETRIES_PER_TURN = 4
private const val RETRY_BASE_DELAY_MS = 6_000L
const val GOAL_COMPLETE_SIGNAL = "[GOAL COMPLETE]"

class AgentConversationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OnGridApplication

    override suspend fun getForegroundInfo(): ForegroundInfo {
        DreamNotificationHelper.ensureChannel(applicationContext)
        val notif = NotificationCompat.Builder(applicationContext, DreamNotificationHelper.CHANNEL_ID)
            .setContentTitle("Agent Conversation")
            .setContentText("Agents are collaborating in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notif)
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(KEY_CONVERSATION_ID) ?: return Result.failure()
        val agent1Id = inputData.getString(KEY_AGENT1_ID) ?: return Result.failure()
        val agent2Id = inputData.getString(KEY_AGENT2_ID) ?: return Result.failure()

        Log.d(TAG, "Starting agent conversation $conversationId")

        // Resolve stale TYPING rows left by a previous interrupted run.
        // Non-empty rows are promoted to ASSISTANT so the partial content is preserved in context.
        // Empty rows (nothing was generated) are simply removed.
        app.database.messageDao().promoteTypingWithContent(conversationId)
        app.database.messageDao().deleteEmptyTyping(conversationId)

        val conversation = app.database.conversationDao().getById(conversationId)
            ?: run { Log.e(TAG, "Conversation $conversationId not found"); return Result.failure() }

        val agent1 = app.agentRepository.getAgent(agent1Id)
            ?: run { Log.e(TAG, "Agent $agent1Id not found"); return Result.failure() }
        val agent2 = app.agentRepository.getAgent(agent2Id)
            ?: run { Log.e(TAG, "Agent $agent2Id not found"); return Result.failure() }

        val baseUrl = "http://${conversation.serverHost}:${conversation.serverPort}"
        val modelName = conversation.modelName
        val goal = conversation.goal
        val executor = AgentToolExecutor(app)
        val tools = executor.buildTools().takeIf { it.isNotEmpty() }

        val toolNames: List<String> = tools?.map { it.function.name } ?: emptyList()
        Log.d(TAG, "Agents: ${agent1.name} <-> ${agent2.name}, model=$modelName, tools=${tools?.size ?: 0}")

        DreamNotificationHelper.notify(
            applicationContext,
            taskLabel = "Agent Conversation",
            agentName = "${agent1.name} <-> ${agent2.name}",
            serverSubtext = goal.take(60)
        )

        var turnCount = 0
        var goalReached = false
        val startAgentId = inputData.getString(KEY_START_AGENT_ID) ?: agent2Id
        var currentSpeakerId = startAgentId
        var currentListenerId = if (startAgentId == agent1Id) agent2Id else agent1Id

        // Dual-consent goal tracking: first agent to signal is recorded here.
        // The conversation only ends when the *other* agent also signals.
        var firstGoalSignalerId: String? = null

        while (turnCount < MAX_TURNS && !goalReached) {
            val messages = app.database.messageDao().getByConversation(conversationId)
            val currentSpeaker = if (currentSpeakerId == agent1Id) agent1 else agent2
            val currentListener = if (currentListenerId == agent1Id) agent1 else agent2

            Log.d(TAG, "Turn $turnCount: ${currentSpeaker.name} speaking")

            val memories = app.agentRepository.memoriesForAgentOnce(currentSpeakerId)

            val systemContent = buildString {
                append("You are ${currentSpeaker.name}. ")
                append("You are in a private collaboration channel with ${currentListener.name}.\n")
                append("IMPORTANT: Write ONLY your own reply as ${currentSpeaker.name}. ")
                append("Do NOT write any lines, paragraphs, or prefixes for ${currentListener.name}. ")
                append("Do NOT label your own messages with your name.\n\n")
                if (currentSpeaker.systemPrompt.isNotBlank()) {
                    append(currentSpeaker.systemPrompt); append("\n\n")
                }
                if (currentSpeaker.brief.isNotBlank()) {
                    append("Your current context:\n${currentSpeaker.brief}\n\n")
                }
                if (memories.isNotEmpty()) {
                    val bullet = memories.take(8).joinToString("\n") { "- ${it.content}" }
                    append("What you remember:\n$bullet\n\n")
                }
                append("Goal of this conversation: $goal\n\n")
                // Only invite goal signaling if the other agent hasn't already signaled.
                if (firstGoalSignalerId == null || firstGoalSignalerId == currentSpeakerId) {
                    append("When you believe the goal is fully accomplished, end your message with exactly: $GOAL_COMPLETE_SIGNAL")
                } else {
                    append("${currentListener.name} believes the goal is accomplished. ")
                    append("If you agree, end your message with exactly: $GOAL_COMPLETE_SIGNAL. ")
                    append("Otherwise, continue working toward the goal.")
                }
            }

            // Build initial history for this turn
            val baseHistory = mutableListOf<OllamaChatMessage>()
            baseHistory += OllamaChatMessage(role = "system", content = systemContent)
            for (msg in messages) {
                when {
                    // TYPING rows are live placeholders — never part of history
                    msg.role == "TYPING" -> continue
                    // TOOL / TOOL_ERROR rows are display-only artifacts. The real tool call/result
                    // exchange happened inside the worker's in-memory currentHistory and produced
                    // the final ASSISTANT response that was persisted. Including them here would
                    // cause the agent to see their own tool result as a prior response.
                    msg.role == "TOOL" || msg.role == "TOOL_ERROR" -> continue
                    msg.role == "USER" || msg.role == "user" ->
                        baseHistory += OllamaChatMessage(
                            role = "user",
                            content = "[${currentListener.name} passed a message from the user]: ${msg.content}"
                        )
                    msg.role == "SYSTEM" ->
                        baseHistory += OllamaChatMessage(role = "user", content = "[system]: ${msg.content}")
                    msg.senderAgentId == currentSpeakerId ->
                        baseHistory += OllamaChatMessage(role = "assistant", content = msg.content)
                    msg.senderAgentId != null ->
                        baseHistory += OllamaChatMessage(role = "user", content = msg.content)
                }
            }

            // Insert TYPING placeholder so UI shows streaming content
            val typingId = "typing_${conversationId}_${currentSpeakerId}"
            app.database.messageDao().insert(
                MessageEntity(
                    id = typingId,
                    conversationId = conversationId,
                    role = "TYPING",
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    senderAgentId = currentSpeakerId
                )
            )

            // --- Tool-call loop ---
            var currentHistory = baseHistory.toMutableList()
            var responseText: String? = null
            var accumulatedThinking = StringBuilder()  // collected across the whole turn
            var toolDepth = 0
            var turnAborted = false
            var nudgeSent = false

            while (toolDepth <= MAX_TOOL_DEPTH) {
                val request = OllamaChatRequest(
                    model = modelName,
                    messages = currentHistory,
                    stream = true,
                    tools = tools,
                    options = OllamaRequestOptions(numCtx = 8192)
                )

                // Retry loop for Ollama availability
                var accumulated = StringBuilder()
                var toolCalls = emptyList<com.ongrid.app.data.model.OllamaToolCall>()
                var ollaMaOk = false

                for (attempt in 1..MAX_RETRIES_PER_TURN) {
                    if (attempt > 1) {
                        val wait = RETRY_BASE_DELAY_MS * (attempt - 1)
                        Log.w(TAG, "Turn $turnCount depth $toolDepth attempt $attempt: waiting ${wait}ms")
                        delay(wait)
                    }
                    accumulated = StringBuilder()
                    toolCalls = emptyList()
                    var lastDbWriteLength = 0
                    var lastThinkingWriteLength = 0
                    try {
                        app.ollamaRepository.streamChat(baseUrl, request).collect { chunk ->
                            // Collect reasoning tokens (thinking-capable models only)
                            val thinkingDelta = chunk.message?.thinking ?: ""
                            if (thinkingDelta.isNotEmpty()) {
                                accumulatedThinking.append(thinkingDelta)
                                // Stream thinking into TYPING row so the UI shows it live
                                if (accumulatedThinking.length - lastThinkingWriteLength >= 15) {
                                    app.database.messageDao().updateThinkingContent(typingId, accumulatedThinking.toString())
                                    lastThinkingWriteLength = accumulatedThinking.length
                                }
                            }

                            val delta = chunk.message?.content ?: ""
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                // Throttle DB writes: only push to the TYPING row every 15 chars.
                                // This keeps the UI smooth without a Room invalidation per token.
                                if (accumulated.length - lastDbWriteLength >= 15) {
                                    app.database.messageDao().updateContent(typingId, accumulated.toString())
                                    lastDbWriteLength = accumulated.length
                                }
                            }
                            chunk.message?.tool_calls?.let { calls ->
                                if (calls.isNotEmpty()) toolCalls = calls
                            }
                        }
                        if (accumulated.isNotBlank() || toolCalls.isNotEmpty()) {
                            ollaMaOk = true; break
                        }
                        Log.w(TAG, "Turn $turnCount attempt $attempt: blank response")
                    } catch (e: Exception) {
                        Log.w(TAG, "Turn $turnCount attempt $attempt failed: ${e.message}")
                    }
                }

                if (!ollaMaOk) {
                    Log.e(TAG, "Turn $turnCount exhausted retries, aborting")
                    turnAborted = true; break
                }

                // No tool calls — decide whether this is truly the final response
                if (toolCalls.isEmpty()) {
                    val trimmed = accumulated.toString().trim()
                    when {
                        // Case A: blank after tool execution — nudge agent to reply
                        trimmed.isBlank() && toolDepth > 0 && !nudgeSent -> {
                            nudgeSent = true
                            Log.d(TAG, "Turn $turnCount: blank after tool results — nudging $currentSpeakerId")
                            currentHistory += OllamaChatMessage(
                                role = "user",
                                content = "[system]: Tool results received. Please now provide your response to the conversation."
                            )
                        }
                        // Case B: agent verbally described tool intent without calling it
                        trimmed.isNotBlank() && !nudgeSent &&
                                toolNames.any { trimmed.contains(it, ignoreCase = true) } -> {
                            nudgeSent = true
                            Log.d(TAG, "Turn $turnCount: agent described tool intent in text — prompting function call")
                            val intentThinking = accumulatedThinking.toString().trim()
                            app.database.messageDao().insert(
                                MessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    conversationId = conversationId,
                                    role = "ASSISTANT",
                                    content = trimmed,
                                    thinkingContent = intentThinking,
                                    timestamp = System.currentTimeMillis(),
                                    senderAgentId = currentSpeakerId
                                )
                            )
                            accumulatedThinking = StringBuilder()
                            currentHistory += OllamaChatMessage(role = "assistant", content = trimmed)
                            currentHistory += OllamaChatMessage(
                                role = "user",
                                content = "[system]: Please invoke that tool using the structured function call interface now."
                            )
                            app.database.messageDao().updateContent(typingId, "")
                            app.database.messageDao().updateThinkingContent(typingId, "")
                        }
                        else -> {
                            responseText = trimmed
                            break
                        }
                    }
                }

                // Has tool calls → execute them, persist results to DB, and loop back.
                Log.d(TAG, "Turn $turnCount depth $toolDepth: executing ${toolCalls.size} tool(s)")

                // ── Pre-tool reasoning snapshot ───────────────────────────────
                // Flush any thinking (and partial text) produced BEFORE the tool decision
                // as a settled ASSISTANT row so it sorts BEFORE the tool messages in the
                // timeline. Without this the TYPING bubble (which holds thinking) is always
                // sorted last, making reasoning appear after the tool call — backwards.
                val preToolThinking = accumulatedThinking.toString().trim()
                val preToolContent  = accumulated.toString().trim()
                if (preToolThinking.isNotEmpty() || preToolContent.isNotEmpty()) {
                    app.database.messageDao().insert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "ASSISTANT",
                            content = preToolContent,
                            thinkingContent = preToolThinking,
                            timestamp = System.currentTimeMillis(),
                            senderAgentId = currentSpeakerId
                        )
                    )
                    accumulatedThinking = StringBuilder()
                }
                // Clear the TYPING bubble so the next streamed round starts clean.
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
                        agentId = currentSpeakerId,
                        conversationId = conversationId
                    )
                    Log.d(TAG, "Tool ${toolCall.function.name} result: ${result.take(80)}")
                    // Use the same "user" role + explicit label that history reconstruction uses.
                    // Avoids role="tool" handling issues with models that expect tool_call_id.
                    currentHistory += OllamaChatMessage(
                        role = "user",
                        content = "[tool:${toolCall.function.name} result]: $result"
                    )

                    // Persist the tool result so it appears as a bubble in the chat.
                    // Use "TOOL_ERROR" role for failures so the bubble renders red.
                    app.database.messageDao().insert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = if (isError) "TOOL_ERROR" else "TOOL",
                            content = result,
                            timestamp = System.currentTimeMillis(),
                            senderAgentId = currentSpeakerId,
                            toolName = toolCall.function.name
                        )
                    )
                }
                toolDepth++
            }

            // Always clean up typing placeholder
            app.database.messageDao().deleteById(typingId)

            if (turnAborted || responseText == null) {
                Log.e(TAG, "Turn $turnCount produced no response, stopping")
                // Write a visible system message so the user knows what happened
                app.database.messageDao().insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "SYSTEM",
                        content = "⚠ ${currentSpeaker.name} could not respond after $MAX_RETRIES_PER_TURN attempts. Tap to retry.",
                        timestamp = System.currentTimeMillis(),
                        senderAgentId = null
                    )
                )
                break
            }

            // Persist the agent's response (strip the goal signal from displayed text).
            // Include any reasoning/thinking content the model produced this turn.
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
                    senderAgentId = currentSpeakerId
                )
            )
            // Reset thinking accumulator for the next turn
            accumulatedThinking = StringBuilder()
            app.database.conversationDao().updateTimestamp(conversationId, System.currentTimeMillis())
            Log.d(TAG, "Turn $turnCount persisted (${displayText.length} chars)")

            // Update title after first reply
            if (turnCount == 0) {
                val currentTitle = conversation.title
                if (currentTitle == "New Conversation" || currentTitle.startsWith("${agent1.name} <->")) {
                    val shortGoal = goal.take(50).let { if (goal.length > 50) "$it..." else it }
                    app.database.conversationDao().updateTitle(
                        conversationId,
                        "${agent1.name} <-> ${agent2.name}: $shortGoal",
                        System.currentTimeMillis()
                    )
                }
            }

            // ── Dual-consent goal completion ──────────────────────────────────
            if (responseText.contains(GOAL_COMPLETE_SIGNAL)) {
                when {
                    // No one has signaled yet — record this agent as the first
                    firstGoalSignalerId == null -> {
                        firstGoalSignalerId = currentSpeakerId
                        Log.d(TAG, "${currentSpeaker.name} signaled goal complete — waiting for ${currentListener.name} to confirm")
                        // Fall through to swap speakers and let the other agent respond
                    }
                    // The OTHER agent is now confirming — conversation ends
                    firstGoalSignalerId != currentSpeakerId -> {
                        goalReached = true
                        Log.d(TAG, "Both agents confirmed goal complete")
                    }
                    // Same agent signaled twice (shouldn't happen normally) — clear and continue
                    else -> {
                        firstGoalSignalerId = null
                        Log.d(TAG, "${currentSpeaker.name} signaled again; clearing and continuing")
                    }
                }
            } else {
                // Speaker did NOT signal → if the other agent had already signaled, reset
                // (they've changed their mind by continuing to engage)
                if (firstGoalSignalerId != null && firstGoalSignalerId != currentSpeakerId) {
                    Log.d(TAG, "${currentSpeaker.name} did not confirm — clearing first-signal flag")
                    firstGoalSignalerId = null
                }
            }

            if (goalReached) break

            val tmp = currentSpeakerId
            currentSpeakerId = currentListenerId
            currentListenerId = tmp
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
            agentName = "${agent1.name} <-> ${agent2.name}",
            serverSubtext = goal.take(60)
        )

        Log.d(TAG, "Finished. Turns: $turnCount, goalReached: $goalReached")
        return Result.success()
    }

    companion object {
        const val KEY_CONVERSATION_ID = "conversationId"
        const val KEY_AGENT1_ID = "agent1Id"
        const val KEY_AGENT2_ID = "agent2Id"
        const val KEY_START_AGENT_ID = "startAgentId"
        private const val FOREGROUND_NOTIFICATION_ID = 1004

        fun enqueue(
            context: Context,
            conversationId: String,
            agent1Id: String,
            agent2Id: String,
            startAgentId: String? = null
        ) {
            val request = OneTimeWorkRequestBuilder<AgentConversationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CONVERSATION_ID to conversationId,
                        KEY_AGENT1_ID to agent1Id,
                        KEY_AGENT2_ID to agent2Id,
                        KEY_START_AGENT_ID to (startAgentId ?: agent2Id)
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("agent_convo_$conversationId")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "agent_convo_$conversationId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
