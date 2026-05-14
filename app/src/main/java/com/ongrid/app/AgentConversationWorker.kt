package com.ongrid.app

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
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
                if (msg.role == "TYPING") continue
                when {
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
                    try {
                        app.ollamaRepository.streamChat(baseUrl, request).collect { chunk ->
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

                // No tool calls → this is the final text response
                if (toolCalls.isEmpty()) {
                    responseText = accumulated.toString().trim()
                    break
                }

                // Has tool calls → execute them and loop back
                Log.d(TAG, "Turn $turnCount depth $toolDepth: executing ${toolCalls.size} tool(s)")
                currentHistory += OllamaChatMessage(
                    role = "assistant",
                    content = accumulated.toString().ifEmpty { null },
                    tool_calls = toolCalls
                )
                for (toolCall in toolCalls) {
                    val (result, _) = executor.execute(
                        funcName = toolCall.function.name,
                        args = toolCall.function.arguments,
                        agentId = currentSpeakerId,
                        conversationId = conversationId
                    )
                    Log.d(TAG, "Tool ${toolCall.function.name} result: ${result.take(80)}")
                    currentHistory += OllamaChatMessage(role = "tool", content = result)
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
                        content = "⚠ ${currentSpeaker.name} could not respond after $MAX_RETRIES_PER_TURN attempts. Tap send to resume.",
                        timestamp = System.currentTimeMillis(),
                        senderAgentId = null
                    )
                )
                break
            }

            // Persist the agent's response (strip the goal signal from displayed text)
            val displayText = responseText.replace(GOAL_COMPLETE_SIGNAL, "").trim()
            app.database.messageDao().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "ASSISTANT",
                    content = displayText,
                    timestamp = System.currentTimeMillis(),
                    senderAgentId = currentSpeakerId
                )
            )
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
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
