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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.MessageEntity
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaRequestOptions
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "AgentConversationWorker"
private const val MAX_TURNS = 20
private const val MAX_RETRIES_PER_TURN = 4
private const val RETRY_BASE_DELAY_MS = 6_000L  // 6 s, 12 s, 18 s, 24 s
const val GOAL_COMPLETE_SIGNAL = "[GOAL COMPLETE]"

/**
 * WorkManager worker that drives a turn-by-turn conversation between two agents.
 *
 * Each invocation runs up to [MAX_TURNS] alternating turns.  After each turn the
 * response is persisted to the database.  If either agent ends its message with
 * [GOAL_COMPLETE_SIGNAL] the loop exits early and a completion notification is posted.
 *
 * The worker starts with a short initial delay so that Ollama has time to finish the
 * initiating agent's streaming response before the first turn fires.  Within each turn
 * it retries up to [MAX_RETRIES_PER_TURN] times with linear backoff in case Ollama is
 * still busy.
 *
 * Input data keys:
 *  - [KEY_CONVERSATION_ID]  The AGENT_HANDOFF conversation to drive.
 *  - [KEY_AGENT1_ID]        Initiating agent (sent the opening message already).
 *  - [KEY_AGENT2_ID]        Responding agent (takes the first turn in this worker).
 */
class AgentConversationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OnGridApplication
    private val gson = Gson()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        DreamNotificationHelper.ensureChannel(applicationContext)
        val notif = NotificationCompat.Builder(applicationContext, DreamNotificationHelper.CHANNEL_ID)
            .setContentTitle("⚡ Agent Conversation")
            .setContentText("Agents are collaborating in the background…")
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

        val conversation = app.database.conversationDao().getById(conversationId)
            ?: run { Log.e(TAG, "Conversation $conversationId not found"); return Result.failure() }

        val agent1 = app.agentRepository.getAgent(agent1Id)
            ?: run { Log.e(TAG, "Agent $agent1Id not found"); return Result.failure() }
        val agent2 = app.agentRepository.getAgent(agent2Id)
            ?: run { Log.e(TAG, "Agent $agent2Id not found"); return Result.failure() }

        val baseUrl = "http://${conversation.serverHost}:${conversation.serverPort}"
        val modelName = conversation.modelName
        val goal = conversation.goal

        Log.d(TAG, "Agents: ${agent1.name} ↔ ${agent2.name}, model=$modelName, url=$baseUrl")

        DreamNotificationHelper.notify(
            applicationContext,
            taskLabel = "Agent Conversation",
            agentName = "${agent1.name} ↔ ${agent2.name}",
            serverSubtext = goal.take(60)
        )

        var turnCount = 0
        var goalReached = false

        // Determine who goes first: the agent who did NOT send the last message.
        // Agent1 sent the opening message, so Agent2 goes first.
        var currentSpeakerId = agent2Id
        var currentListenerId = agent1Id

        while (turnCount < MAX_TURNS && !goalReached) {
            val messages = app.database.messageDao().getByConversation(conversationId)
            val currentSpeaker = if (currentSpeakerId == agent1Id) agent1 else agent2
            val currentListener = if (currentListenerId == agent1Id) agent1 else agent2

            Log.d(TAG, "Turn $turnCount: ${currentSpeaker.name} is speaking")

            // Memories for the current speaker
            val memories = app.agentRepository.memoriesForAgentOnce(currentSpeakerId)

            // Build Ollama history from the current speaker's perspective:
            // - Their own prior messages → "assistant"
            // - The other agent's messages → "user"
            // - User messages → "user" (labelled)
            val history = mutableListOf<OllamaChatMessage>()

            // System: speaker's identity + goal context
            val systemContent = buildString {
                if (currentSpeaker.systemPrompt.isNotBlank()) {
                    append(currentSpeaker.systemPrompt)
                    append("\n\n")
                }
                if (currentSpeaker.brief.isNotBlank()) {
                    append("Your current context:\n${currentSpeaker.brief}\n\n")
                }
                if (memories.isNotEmpty()) {
                    val bullet = memories.take(8).joinToString("\n") { "• ${it.content}" }
                    append("What you remember:\n$bullet\n\n")
                }
                append("You are in a collaboration channel with ${currentListener.name}.\n")
                append("Goal: $goal\n\n")
                append("When the goal is fully accomplished, end your message with exactly: $GOAL_COMPLETE_SIGNAL")
            }
            history += OllamaChatMessage(role = "system", content = systemContent)

            // Conversation history
            for (msg in messages) {
                when {
                    msg.role == "USER" || msg.role == "user" -> {
                        // Human joining the conversation
                        history += OllamaChatMessage(role = "user", content = "[User]: ${msg.content}")
                    }
                    msg.senderAgentId == currentSpeakerId -> {
                        history += OllamaChatMessage(role = "assistant", content = msg.content)
                    }
                    msg.senderAgentId != null -> {
                        // Other agent's turn — appears as "user" from speaker's perspective
                        history += OllamaChatMessage(role = "user", content = "[${currentListener.name}]: ${msg.content}")
                    }
                    // Skip system/tool messages in the handoff history
                }
            }

            // --- Call Ollama with retry on failure ---
            val request = OllamaChatRequest(
                model = modelName,
                messages = history,
                stream = true,
                options = OllamaRequestOptions(numCtx = 8192)
            )

            var responseText: String? = null
            for (attempt in 1..MAX_RETRIES_PER_TURN) {
                if (attempt > 1) {
                    val waitMs = RETRY_BASE_DELAY_MS * (attempt - 1)
                    Log.w(TAG, "Turn $turnCount attempt $attempt: waiting ${waitMs}ms before retry")
                    delay(waitMs)
                }
                val accumulated = StringBuilder()
                try {
                    app.ollamaRepository.streamChat(baseUrl, request).collect { chunk ->
                        val delta = chunk.message?.content ?: ""
                        if (delta.isNotEmpty()) accumulated.append(delta)
                    }
                    val txt = accumulated.toString().trim()
                    if (txt.isNotBlank()) {
                        responseText = txt
                        Log.d(TAG, "Turn $turnCount attempt $attempt succeeded (${txt.length} chars)")
                        break
                    } else {
                        Log.w(TAG, "Turn $turnCount attempt $attempt: blank response from Ollama")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Turn $turnCount attempt $attempt failed: ${e.message}")
                }
            }

            if (responseText == null) {
                Log.e(TAG, "Turn $turnCount exhausted all retries, aborting conversation")
                break
            }

            // Persist the response
            val msgEntity = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "ASSISTANT",  // Must match MessageRole enum name
                content = responseText,
                timestamp = System.currentTimeMillis(),
                senderAgentId = currentSpeakerId
            )
            app.database.messageDao().insert(msgEntity)
            app.database.conversationDao().updateTimestamp(conversationId, System.currentTimeMillis())

            Log.d(TAG, "Turn $turnCount persisted: ${responseText.take(80)}…")

            // Generate/update title after first response if still default
            if (turnCount == 0) {
                val currentTitle = conversation.title
                if (currentTitle == "New Conversation" || currentTitle.startsWith("${agent1.name} ↔")) {
                    val shortGoal = goal.take(50).let { if (goal.length > 50) "$it…" else it }
                    app.database.conversationDao().updateTitle(
                        conversationId,
                        "${agent1.name} ↔ ${agent2.name}: $shortGoal",
                        System.currentTimeMillis()
                    )
                }
            }

            // Check for goal completion
            if (responseText.contains(GOAL_COMPLETE_SIGNAL)) {
                goalReached = true
                Log.d(TAG, "Goal marked complete by ${currentSpeaker.name}")
                break
            }

            // Alternate speakers
            val tmp = currentSpeakerId
            currentSpeakerId = currentListenerId
            currentListenerId = tmp

            turnCount++
        }

        // Post completion notification
        val statusLabel = when {
            goalReached -> "✓ Goal complete"
            turnCount >= MAX_TURNS -> "Max turns reached"
            else -> "Conversation ended"
        }
        DreamNotificationHelper.notify(
            applicationContext,
            taskLabel = statusLabel,
            agentName = "${agent1.name} ↔ ${agent2.name}",
            serverSubtext = goal.take(60)
        )

        Log.d(TAG, "Agent conversation finished. Turns: $turnCount, goalReached: $goalReached")
        return Result.success()
    }

    companion object {
        const val KEY_CONVERSATION_ID = "conversationId"
        const val KEY_AGENT1_ID = "agent1Id"
        const val KEY_AGENT2_ID = "agent2Id"
        private const val FOREGROUND_NOTIFICATION_ID = 1004

        /** Delay before the first turn, giving Ollama time to finish the initiating agent's response. */
        private const val INITIAL_DELAY_SECONDS = 8L

        fun enqueue(
            context: Context,
            conversationId: String,
            agent1Id: String,
            agent2Id: String
        ) {
            val request = OneTimeWorkRequestBuilder<AgentConversationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_CONVERSATION_ID to conversationId,
                        KEY_AGENT1_ID to agent1Id,
                        KEY_AGENT2_ID to agent2Id
                    )
                )
                .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("agent_convo_$conversationId")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
