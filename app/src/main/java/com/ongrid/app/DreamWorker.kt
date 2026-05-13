package com.ongrid.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.ongrid.app.data.local.DreamLogEntity
import kotlinx.coroutines.flow.first

private const val TAG = "DreamWorker"

/**
 * WorkManager worker that runs background "dreaming" for each opted-in agent.
 *
 * Per-agent tasks (when [AgentEntity.isDreamingEnabled] = true):
 * 1. Deduplicate and triage memories — always scans for redundant/overlapping entries,
 *    merges them into synthesised facts, and deletes stale ones. Flags over-budget usage.
 * 2. Review the OPEN section of the brief for tasks stagnant > 3 days.
 * 3. If [AgentEntity.isMoodTrackingEnabled], calculate mood from recent conversation turns.
 * 4. Persist all changes and write a [DreamLogEntity].
 *
 * While running, a persistent "☁️ Dreaming…" notification is shown and live log lines
 * are emitted to [OnGridApplication.dreamLogChannel] so that the UI can surface a
 * terminal-feed overlay when the app is open.
 */
class DreamWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as OnGridApplication
    private val gson = Gson()

    override suspend fun doWork(): Result {
        Log.d(TAG, "Dream cycle starting")

        val settings = app.settingsRepository.settings.first()
        if (!settings.utilityAgentEnabled) {
            Log.d(TAG, "Utility agent disabled globally — skipping dream cycle")
            return Result.success()
        }

        val serverSubtext = buildString {
            append(settings.utilityModelHost.ifBlank { "Utility Server" })
            append(" | Agent: ")
            append(settings.utilityModelName.ifBlank { "—" })
        }

        // If a specific agentId was provided (scheduled or manual trigger), only dream
        // that agent. Otherwise fall back to all agents with dreaming enabled (periodic run).
        val scopedAgentId = inputData.getString(INPUT_KEY_AGENT_ID)
        val dreamingAgents = if (scopedAgentId != null) {
            val agent = app.agentRepository.getAgent(scopedAgentId)
            if (agent == null || !agent.isDreamingEnabled) {
                Log.d(TAG, "Agent $scopedAgentId not found or dreaming disabled — skipping")
                return Result.success()
            }
            listOf(agent)
        } else {
            val all = app.agentRepository.allAgents().first()
            all.filter { it.isDreamingEnabled }
        }

        if (dreamingAgents.isEmpty()) return Result.success()

        for (agent in dreamingAgents) {
            DreamNotificationHelper.notify(
                applicationContext,
                taskLabel = "Memory Triage",
                agentName = agent.name,
                serverSubtext = serverSubtext
            )
            emitLog("▶ Starting dream cycle for ${agent.name}")
            try {
                dreamAgent(agent.id)
            } catch (e: Exception) {
                Log.w(TAG, "Dream failed for agent ${agent.id}: ${e.message}")
                emitLog("⚠ Dream failed for ${agent.name}: ${e.message}")
            }
        }

        DreamNotificationHelper.dismiss(applicationContext)
        emitLog("✓ Dream cycle complete")
        Log.d(TAG, "Dream cycle complete")
        return Result.success()
    }

    /** Emit a live-log line consumed by the UI terminal feed. */
    private fun emitLog(line: String) {
        app.dreamLogChannel.trySend(line)
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
            emitLog("  ↳ No utility model resolved — skipping ${agent.name}")
            return
        }

        val changeLog = mutableMapOf<String, Any>()
        var tokensSaved = 0
        var moodChange: String? = null

        // ── 1. Memory deduplication & token triage ───────────────────────────
        val promptTokens = agent.systemPrompt.length / 4
        val briefTokens = agent.brief.length / 4
        val memoryTokens = memories.sumOf { it.content.length } / 4
        val totalMemoryBudget = (agent.maxContextTokens * 0.3).toInt()
        val overBudget = memoryTokens > totalMemoryBudget

        changeLog["tokens_before"] = promptTokens + briefTokens + memoryTokens
        emitLog("  [1/4] Memory dedup & triage: ${promptTokens + briefTokens + memoryTokens} tokens (budget ${agent.maxContextTokens})${if (overBudget) " ⚠ over budget" else ""}")

        val unpinned = memories.filter { !it.isPinned }
        if (memories.isNotEmpty()) {
            val allContents = memories.map { it.content }
            val pinnedCount = memories.count { it.isPinned }
            emitLog("  ↳ Scanning ${memories.size} memories ($pinnedCount pinned) for redundancy…")
            val triage = app.utilityAgentRepository.triageMemories(utilHost, utilModel, allContents)

            // Delete flagged memories
            val toDelete = triage.delete.mapNotNull { memories.getOrNull(it) }
            toDelete.forEach { app.agentRepository.deleteMemory(it.id) }

            // Replace merged memories with synthesised fact
            if (triage.synthesised != null && triage.merge.isNotEmpty()) {
                val toMerge = triage.merge.mapNotNull { memories.getOrNull(it) }
                // Synthesised entry inherits pinned status if ALL merged entries were pinned
                val inheritPinned = toMerge.all { it.isPinned }
                toMerge.forEach { app.agentRepository.deleteMemory(it.id) }
                app.agentRepository.insertMemory(
                    com.ongrid.app.data.local.AgentMemoryEntity(
                        agentId = agentId,
                        content = triage.synthesised,
                        isPinned = inheritPinned
                    )
                )
            }

            val removed = toDelete.size + (if (triage.synthesised != null) triage.merge.size - 1 else 0).coerceAtLeast(0)
            tokensSaved = removed * (allContents.firstOrNull()?.length?.div(4) ?: 20)
            changeLog["memories_merged"] = triage.merge.size
            changeLog["memories_deleted"] = toDelete.size
            if (triage.synthesised != null) changeLog["synthesised_fact"] = triage.synthesised

            val actions = buildString {
                if (triage.merge.size > 0) append("merged ${triage.merge.size}")
                if (triage.delete.isNotEmpty()) {
                    if (isNotEmpty()) append(", ")
                    append("deleted ${triage.delete.size}")
                }
                if (isEmpty()) append("no changes")
            }
            emitLog("  ↳ Dedup complete: $actions. Saved ~$tokensSaved tokens")
        } else {
            emitLog("  ↳ No memories to triage")
        }

        // ── 2. Brief stale-task review ────────────────────────────────────────
        emitLog("  [2/4] Brief review")
        if (agent.isAutoBriefEnabled) {
            val conversations = app.conversationRepository.conversationsForAgent(agentId).first()
            if (agent.brief.isBlank()) {
                // No brief yet — generate one from the most recent conversation
                emitLog("  ↳ Brief is empty — generating initial brief")
                val recentConv = conversations.firstOrNull()
                val exchange = if (recentConv != null) {
                    val msgs = app.database.messageDao().getByConversation(recentConv.id)
                    msgs.takeLast(10).joinToString("\n") { "${it.role}: ${it.content.take(300)}" }
                } else ""
                val newBrief = app.utilityAgentRepository.updateAgentBrief(
                    utilHost, utilModel, "", agent.role, agent.systemPrompt, exchange
                )
                if (!newBrief.isNullOrBlank()) {
                    app.agentRepository.updateBrief(agentId, newBrief)
                    changeLog["brief_change"] = "Initial brief generated"
                    emitLog("  ↳ Brief generated")
                } else {
                    emitLog("  ↳ Could not generate brief (no response from model)")
                }
            } else {
                // Check for conversations that have happened since the brief was last updated.
                // If new activity exists, refresh the brief with that content. Otherwise fall
                // back to stale-task archival.
                val newConvs = conversations.filter { it.updatedAt > agent.briefUpdatedAt }
                if (newConvs.isNotEmpty()) {
                    emitLog("  ↳ ${newConvs.size} new conversation(s) since last brief — refreshing")
                    val recentConv = newConvs.maxByOrNull { it.updatedAt }!!
                    val msgs = app.database.messageDao().getByConversation(recentConv.id)
                    val exchange = msgs.takeLast(10)
                        .joinToString("\n") { "${it.role}: ${it.content.take(300)}" }
                    val newBrief = app.utilityAgentRepository.updateAgentBrief(
                        utilHost, utilModel, agent.brief, agent.role, agent.systemPrompt, exchange
                    )
                    if (!newBrief.isNullOrBlank() && newBrief != agent.brief) {
                        app.agentRepository.updateBrief(agentId, newBrief)
                        changeLog["brief_change"] = "Refreshed from recent activity"
                        emitLog("  ↳ Brief refreshed")
                    } else {
                        emitLog("  ↳ Brief already up to date")
                    }
                } else {
                    // No new conversations — scan the existing brief for stale open tasks.
                    val lastConvAt = conversations.maxOfOrNull { it.updatedAt } ?: agent.briefUpdatedAt
                    val review = app.utilityAgentRepository.reviewBriefForStaleTasks(
                        utilHost, utilModel, agent.brief, agent.role, lastConvAt
                    )
                    if (review?.updatedBrief != null && review.updatedBrief != agent.brief) {
                        app.agentRepository.updateBrief(agentId, review.updatedBrief)
                        changeLog["brief_change"] = review.changeDescription
                        emitLog("  ↳ Brief updated: ${review.changeDescription}")
                    } else {
                        emitLog("  ↳ Brief is current — no stale tasks")
                    }
                }
            }
        } else {
            emitLog("  ↳ Skipped (auto-brief disabled)")
        }

        // ── 3. Mood calculation ───────────────────────────────────────────────
        emitLog("  [3/4] Mood calculation")
        if (agent.isMoodTrackingEnabled) {
            val conversations = app.conversationRepository.conversationsForAgent(agentId).first()
            // Find the last assistant message the agent sent across recent conversations
            // (search the most recent conversations first, up to 3, to find a real reply)
            var lastAssistantContent: String? = null
            for (conv in conversations.take(3)) {
                val msgs = app.database.messageDao().getByConversation(conv.id)
                val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                if (lastAssistant != null) {
                    lastAssistantContent = lastAssistant.content
                    break
                }
            }
            if (lastAssistantContent != null) {
                // Build a brief exchange around that message for context
                val exchange = "Assistant: ${lastAssistantContent.take(600)}"
                val newMood = app.utilityAgentRepository.calculateMood(utilHost, utilModel, exchange)
                if (newMood != null && newMood != agent.currentMood) {
                    moodChange = "${agent.currentMood} -> $newMood"
                    app.agentRepository.updateMood(agentId, newMood)
                    changeLog["mood"] = moodChange!!
                    emitLog("  ↳ Mood shift: $moodChange")
                } else {
                    emitLog("  ↳ Mood unchanged: ${agent.currentMood}")
                }
            } else {
                emitLog("  ↳ No assistant messages found to assess mood")
            }
        } else {
            emitLog("  ↳ Skipped (mood tracking disabled)")
        }

        // ── 4. Write dream log ────────────────────────────────────────────────
        emitLog("  [4/4] Writing dream log")
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
        emitLog("✓ ${agent.name}: $summary")
        Log.d(TAG, "Dream complete for ${agent.name}: $summary")
    }

    companion object {
        /** Optional WorkManager input key. When set, only that agent's dream runs. */
        const val INPUT_KEY_AGENT_ID = "agent_id"
    }
}
