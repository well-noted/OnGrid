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

private const val TAG = "AgentConversationWorker"
private const val MAX_TURNS = 20
private const val MAX_RETRIES_PER_TURN = 4
private const val RETRY_BASE_DELAY_MS = 6_000L
const val GOAL_COMPLETE_SIGNAL = "[GOAL COMPLETE]"

class AgentConversationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OnGridApplication
    private val gson = Gson()

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

        val conversation = app.database.conversationDao().getById(conversationId)
            ?: run { Log.e(TAG, "Conversation $conversationId not found"); return Result.failure() }

        val agent1 = app.agentRepository.getAgent(agent1Id)
            ?: run { Log.e(TAG, "Agent $agent1Id not found"); return Result.failure() }
        val agent2 = app.agentRepository.getAgent(agent2Id)
            ?: run { Log.e(TAG, "Agent $agent2Id not found"); return Result.failure() }

        val baseUrl = "http://${conversation.serverHost}:${conversation.serverPort}"
        val modelName = conversation.modelName
        val goal = conversation.goal

        Log.d(TAG, "Agents: ${agent1.name} <-> ${agent2.name}, model=$modelName, url=$baseUrl")

        DreamNotificationHelper.notify(
            applicationContext,
            taskLabel = "Agent Conversation",
            agentName = "${agent1.name} <-> ${agent2.name}",
            serverSubtext = goal.take(60)
        )

        var turnCount = 0
        var goalReached = false
        // Allow caller to specify which agent speaks first (e.g. when resuming after user input).
        // Default: agent2 goes first (they respond to agent1's opening message).
        val startAgentId = inputData.getString(KEY_START_AGENT_ID) ?: agent2Id
        var currentSpeakerId = startAgentId
        var currentListenerId = if (startAgentId == agent1Id) agent2Id else agent1Id

        while (turnCount < MAX_TURNS && !goalReached) {
            val messages = app.database.messageDao().getByConversation(conversationId)
            val currentSpeaker = if (currentSpeakerId == agent1Id) agent1 else agent2
            val currentListener = if (currentListenerId == agent1Id) agent1 else agent2

            Log.d(TAG, "Turn $turnCount: ${currentSpeaker.name} is speaking")

            val memories = app.agentRepository.memoriesForAgentOnce(currentSpeakerId)
            val history = mutableListOf<OllamaChatMessage>()

            // System prompt: strong identity injection first, then persona, then rules
            val systemContent = buildString {
                // Identity anchor — must come before anything else so the model doesn't drift
                append("You are ${currentSpeaker.name}. ")
                append("You are in a private collaboration channel with ${currentListener.name}.\n")
                append("IMPORTANT: Write ONLY your own reply as ${currentSpeaker.name}. ")
                append("Do NOT write any lines, paragraphs, or prefixes for ${currentListener.name}. ")
                append("Do NOT label your own messages with your name.\n\n")

                if (currentSpeaker.systemPrompt.isNotBlank()) {
                    append(currentSpeaker.systemPrompt)
                    append("\n\n")
                }
                if (currentSpeaker.brief.isNotBlank()) {
                    append("Your current context:\n${currentSpeaker.brief}\n\n")
                }
                if (memories.isNotEmpty()) {
                    val bullet = memories.take(8).joinToString("\n") { "- ${it.content}" }
                    append("What you remember:\n$bullet\n\n")
                }
                append("Goal of this conversation: $goal\n\n")
                append("When the goal is fully accomplished, end your message with exactly: $GOAL_COMPLETE_SIGNAL")
            }
            history += OllamaChatMessage(role = "system", content = systemContent)

            // Build conversation history from the current speaker's perspective.
            // Own messages -> "assistant".  Other party's messages -> "user".
            // We strip the [Name]: prefix from the messages themselves — the system prompt
            // establishes who's who without needing inline labels that tempt the model to mirror.
            for (msg in messages) {
                if (msg.role == "TYPING") continue  // skip typing placeholders
                when {
                    msg.role == "USER" || msg.role == "user" -> {
                        history += OllamaChatMessage(
                            role = "user",
                            content = "[${currentListener.name} passed a message from the user]: ${msg.content}"
                        )
                    }
                    msg.senderAgentId == currentSpeakerId -> {
                        history += OllamaChatMessage(role = "assistant", content = msg.content)
                    }
                    msg.senderAgentId != null -> {
                        // Other agent's message — presented as a plain user turn
                        history += OllamaChatMessage(role = "user", content = msg.content)
                    }
                }
            }

            val request = OllamaChatRequest(
                model = modelName,
                messages = history,
                stream = true,
                options = OllamaRequestOptions(numCtx = 8192)
            )

            // Insert a TYPING placeholder so the UI shows a blinking cursor bubble
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

            var responseText: String? = null
            for (attempt in 1..MAX_RETRIES_PER_TURN) {
                if (attempt > 1) {
                    val waitMs = RETRY_BASE_DELAY_MS * (attempt - 1)
                    Log.w(TAG, "Turn $turnCount attempt $attempt: waiting ${waitMs}ms")
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
                        Log.w(TAG, "Turn $turnCount attempt $attempt: blank response")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Turn $turnCount attempt $attempt failed: ${e.message}")
                }
            }

            // Always remove the typing placeholder regardless of outcome
            app.database.messageDao().deleteById(typingId)

            if (responseText == null) {
                Log.e(TAG, "Turn $turnCount exhausted all retries, aborting")
                break
            }

            val msgEntity = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "ASSISTANT",
                content = responseText,
                timestamp = System.currentTimeMillis(),
                senderAgentId = currentSpeakerId
            )
            app.database.messageDao().insert(msgEntity)
            app.database.conversationDao().updateTimestamp(conversationId, System.currentTimeMillis())

            Log.d(TAG, "Turn $turnCount persisted")

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

            if (responseText.contains(GOAL_COMPLETE_SIGNAL)) {
                goalReached = true
                Log.d(TAG, "Goal marked complete by ${currentSpeaker.name}")
                break
            }

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

        Log.d(TAG, "Agent conversation finished. Turns: $turnCount, goalReached: $goalReached")
        return Result.success()
    }

    companion object {
        const val KEY_CONVERSATION_ID = "conversationId"
        const val KEY_AGENT1_ID = "agent1Id"
        const val KEY_AGENT2_ID = "agent2Id"
        /** Optional: which agent speaks first. Defaults to agent2 if omitted. */
        const val KEY_START_AGENT_ID = "startAgentId"
        private const val FOREGROUND_NOTIFICATION_ID = 1004

        fun enqueue(
            context: Context,
            conversationId: String,
            agent1Id: String,
            agent2Id: String,
            startAgentId: String? = null
        ) {
            val data = workDataOf(
                KEY_CONVERSATION_ID to conversationId,
                KEY_AGENT1_ID to agent1Id,
                KEY_AGENT2_ID to agent2Id,
                KEY_START_AGENT_ID to (startAgentId ?: agent2Id)
            )
            val request = OneTimeWorkRequestBuilder<AgentConversationWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("agent_convo_$conversationId")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
