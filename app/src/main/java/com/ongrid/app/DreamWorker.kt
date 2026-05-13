package com.ongrid.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.ongrid.app.data.local.DreamLogEntity
import kotlinx.coroutines.flow.first

private const val TAG = "DreamWorker"

/**
 * WorkManager worker that runs background "dreaming" for each opted-in agent.
 *
 * Per-agent tasks (when [AgentEntity.isDreamingEnabled] = true):
 * 1. Estimate token usage for system prompt + brief + memories.
 * 2. If memory tokens exceed 30 % of [AgentEntity.maxContextTokens], triage memories
 *    (merge/prune via the utility agent).
 * 3. Review the OPEN section of the brief for tasks stagnant > 3 days.
 * 4. If [AgentEntity.isMoodTrackingEnabled], calculate mood from recent conversation turns.
 * 5. Persist all changes and write a [DreamLogEntity].
 */
class DreamWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OnGridApplication
    private val gson = Gson()

    override suspend fun doWork(): Result {
        Log.d(TAG, "Dream cycle starting")

        val agents = app.agentRepository.allAgents().first()
        val dreamingAgents = agents.filter { it.isDreamingEnabled }
        if (dreamingAgents.isEmpty()) return Result.success()

        val settings = app.settingsRepository.settings.first()
        if (!settings.utilityAgentEnabled) {
            Log.d(TAG, "Utility agent disabled globally — skipping dream cycle")
            return Result.success()
        }

        for (agent in dreamingAgents) {
            try {
                dreamAgent(agent.id)
            } catch (e: Exception) {
                Log.w(TAG, "Dream failed for agent ${agent.id}: ${e.message}")
            }
        }

        Log.d(TAG, "Dream cycle complete")
        return Result.success()
    }

    private suspend fun dreamAgent(agentId: String) {
        val agent = app.agentRepository.getAgent(agentId) ?: return
        val memories = app.agentRepository.memoriesForAgentOnce(agentId)

        // Resolve the utility model for this agent
        val settings = app.settingsRepository.settings.first()
        val globalHost = settings.utilityModelHost.ifBlank { "" }
        val globalModel = settings.utilityModelName.ifBlank { "" }
        val (utilHost, utilModel) = app.agentRepository.resolveUtilityModel(agentId, globalHost, globalModel)

        if (utilHost.isBlank() || utilModel.isBlank()) {
            Log.d(TAG, "No utility model resolved for agent ${agent.name} — skipping")
            return
        }

        val changeLog = mutableMapOf<String, Any>()
        var tokensSaved = 0
        var moodChange: String? = null

        // ── 1. Token triage ───────────────────────────────────────────────────
        val promptTokens = agent.systemPrompt.length / 4
        val briefTokens = agent.brief.length / 4
        val memoryTokens = memories.sumOf { it.content.length } / 4
        val totalMemoryBudget = (agent.maxContextTokens * 0.3).toInt()

        changeLog["tokens_before"] = promptTokens + briefTokens + memoryTokens

        if (memoryTokens > totalMemoryBudget) {
            val unpinnedContents = memories.filter { !it.isPinned }.map { it.content }
            if (unpinnedContents.isNotEmpty()) {
                val triage = app.utilityAgentRepository.triageMemories(utilHost, utilModel, unpinnedContents)
                val unpinned = memories.filter { !it.isPinned }

                // Delete flagged memories
                val toDelete = triage.delete.mapNotNull { unpinned.getOrNull(it) }
                toDelete.forEach { app.agentRepository.deleteMemory(it.id) }

                // Replace merged memories with synthesised fact
                if (triage.synthesised != null && triage.merge.isNotEmpty()) {
                    val toMerge = triage.merge.mapNotNull { unpinned.getOrNull(it) }
                    toMerge.forEach { app.agentRepository.deleteMemory(it.id) }
                    app.agentRepository.insertMemory(
                        com.ongrid.app.data.local.AgentMemoryEntity(
                            agentId = agentId,
                            content = triage.synthesised
                        )
                    )
                }

                val removed = toDelete.size + (if (triage.synthesised != null) triage.merge.size - 1 else 0).coerceAtLeast(0)
                tokensSaved = removed * (unpinnedContents.firstOrNull()?.length?.div(4) ?: 20)
                changeLog["memories_merged"] = triage.merge.size
                changeLog["memories_deleted"] = toDelete.size
                if (triage.synthesised != null) changeLog["synthesised_fact"] = triage.synthesised
            }
        }

        // ── 2. Brief stale-task review ────────────────────────────────────────
        if (agent.brief.isNotBlank() && agent.isAutoBriefEnabled) {
            val conversations = app.conversationRepository.conversationsForAgent(agentId).first()
            val lastConvAt = conversations.maxOfOrNull { it.updatedAt } ?: agent.briefUpdatedAt

            val review = app.utilityAgentRepository.reviewBriefForStaleTasks(
                utilHost, utilModel, agent.brief, agent.role, lastConvAt
            )
            if (review?.updatedBrief != null && review.changeDescription != "No changes") {
                app.agentRepository.updateBrief(agentId, review.updatedBrief)
                changeLog["brief_change"] = review.changeDescription
            }
        }

        // ── 3. Mood calculation ───────────────────────────────────────────────
        if (agent.isMoodTrackingEnabled) {
            val conversations = app.conversationRepository.conversationsForAgent(agentId).first()
            val recentConv = conversations.firstOrNull()
            if (recentConv != null) {
                val messages = app.database.messageDao().getByConversation(recentConv.id)
                val last5 = messages.takeLast(10)
                val exchange = last5.joinToString("\n") { "${it.role}: ${it.content.take(300)}" }
                val newMood = app.utilityAgentRepository.calculateMood(utilHost, utilModel, exchange)
                if (newMood != null && newMood != agent.currentMood) {
                    moodChange = "${agent.currentMood} -> $newMood"
                    app.agentRepository.updateMood(agentId, newMood)
                    changeLog["mood"] = moodChange!!
                }
            }
        }

        // ── 4. Write dream log ────────────────────────────────────────────────
        val summary = buildString {
            if (changeLog.containsKey("memories_merged") || changeLog.containsKey("memories_deleted")) {
                append("Consolidated ${changeLog["memories_merged"] ?: 0} memories; ")
                val deleted = changeLog["memories_deleted"] as? Int ?: 0
                if (deleted > 0) append("Removed $deleted stale memories; ")
                if (tokensSaved > 0) append("Saved ~$tokensSaved tokens; ")
            }
            if (changeLog.containsKey("brief_change")) append("Brief: ${changeLog["brief_change"]}; ")
            if (moodChange != null) append("Mood: $moodChange")
            if (isEmpty()) append("No changes needed")
        }.trimEnd(';', ' ')

        app.agentRepository.insertDreamLog(
            DreamLogEntity(
                agentId = agentId,
                summary = summary,
                fullLogJson = gson.toJson(changeLog),
                moodChange = moodChange
            )
        )
        app.agentRepository.updateLastDreamedAt(agentId, System.currentTimeMillis())
        Log.d(TAG, "Dream complete for ${agent.name}: $summary")
    }
}
