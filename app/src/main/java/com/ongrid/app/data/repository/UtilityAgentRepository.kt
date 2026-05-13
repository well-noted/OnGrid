package com.ongrid.app.data.repository

import android.util.Log
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.network.OllamaApi

private const val TAG = "UtilityAgentRepo"

class UtilityAgentRepository(private val api: OllamaApi) {

    /**
     * Generate a short conversation title (≤ 6 words) from the opening exchange.
     * Returns null if the request fails or output is empty.
     */
    suspend fun generateTitle(
        baseUrl: String,
        modelName: String,
        userMessage: String,
        assistantSnippet: String = ""
    ): String? = try {
        val context = buildString {
            append("User: ")
            append(userMessage.take(300))
            if (assistantSnippet.isNotBlank()) {
                append("\nAssistant: ")
                append(assistantSnippet.take(200))
            }
        }
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = "Generate a short conversation title (maximum 6 words, no quotes, no trailing punctuation) for this exchange:\n\n$context"
                )
            ),
            stream = false
        )
        api.chatOnce(baseUrl, request)
            ?.trim()
            ?.replace(Regex("^[\"']|[\"']$"), "")
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "generateTitle failed: ${e.message}")
        null
    }

    /**
     * Extract 0–3 short memory facts from a conversation exchange worth storing for a project.
     * Returns an empty list if nothing noteworthy was found or the request fails.
     */
    suspend fun extractMemories(
        baseUrl: String,
        modelName: String,
        exchange: String
    ): List<String> {
        return try {
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = """Extract up to 3 discrete, reusable facts from the conversation below that would be worth remembering for future sessions in this project. Each fact should be a single concise sentence. If there is nothing worth remembering, reply with exactly "none". Output one fact per line, no bullet points, no numbering.

Conversation:
${exchange.take(1500)}"""
                )
            ),
            stream = false
        )
        val raw = api.chatOnce(baseUrl, request)?.trim() ?: return emptyList()
        if (raw.lowercase() == "none") return emptyList()
        raw.lines()
            .map { it.trim().trimStart('-', '*', '•', '·').trim() }
            .filter { it.isNotBlank() && it.lowercase() != "none" }
            .take(3)
        } catch (e: Exception) {
            Log.w(TAG, "extractMemories failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Auto-tag a conversation with 1–3 relevant tags from a fixed vocabulary.
     * Returns an empty list on failure.
     */
    suspend fun generateTags(
        baseUrl: String,
        modelName: String,
        conversationSnippet: String
    ): List<String> = try {
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = """Assign 1 to 3 tags to the following conversation snippet. Choose only from this vocabulary: coding, wiki, research, planning, writing, debugging, math, creative, general. Reply with just the tags as a comma-separated list, nothing else.

Snippet:
${conversationSnippet.take(800)}"""
                )
            ),
            stream = false
        )
        val raw = api.chatOnce(baseUrl, request)?.trim() ?: return emptyList()
        raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .take(3)
    } catch (e: Exception) {
        Log.w(TAG, "generateTags failed: ${e.message}")
        emptyList()
    }

    /**
     * Given the first user message, return the name of a skill that seems relevant, or null.
     */
    suspend fun suggestSkill(
        baseUrl: String,
        modelName: String,
        userMessage: String,
        availableSkillNames: List<String>
    ): String? {
        if (availableSkillNames.isEmpty()) return null
        return try {
            val skillList = availableSkillNames.joinToString(", ")
            val request = OllamaChatRequest(
                model = modelName,
                messages = listOf(
                    OllamaChatMessage(
                        role = "user",
                        content = """Given the following user message, identify which skill (if any) from the list would be most helpful. Reply with only the skill name exactly as listed, or reply with "none" if no skill is relevant.

Skills: $skillList

User message: ${userMessage.take(400)}"""
                    )
                ),
                stream = false
            )
            val raw = api.chatOnce(baseUrl, request)?.trim() ?: return null
            if (raw.lowercase() == "none") null
            else availableSkillNames.firstOrNull { it.equals(raw, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "suggestSkill failed: ${e.message}")
            null
        }
    }

    /**
     * Given the first user message, find IDs of similar recent conversations.
     * Returns an empty list on failure.
     */
    suspend fun findSimilarConversations(
        baseUrl: String,
        modelName: String,
        userMessage: String,
        recentConversationSummaries: List<Pair<String, String>>
    ): List<String> {
        if (recentConversationSummaries.isEmpty()) return emptyList()
        return try {
            val summaryList = recentConversationSummaries.take(20)
                .joinToString("\n") { (id, snippet) -> "[$id] $snippet" }
            val request = OllamaChatRequest(
                model = modelName,
                messages = listOf(
                    OllamaChatMessage(
                        role = "user",
                        content = """Given the user message below, list the IDs of any conversations from the provided list that are clearly similar in topic. Reply with only a comma-separated list of IDs (the text in square brackets), or reply with "none" if nothing is similar.

User message: ${userMessage.take(300)}

Recent conversations:
$summaryList"""
                    )
                ),
                stream = false
            )
            val raw = api.chatOnce(baseUrl, request)?.trim() ?: return emptyList()
            if (raw.lowercase() == "none") return emptyList()
            val validIds = recentConversationSummaries.map { it.first }.toSet()
            raw.split(",")
                .map { it.trim().removeSurrounding("[", "]") }
                .filter { it in validIds }
        } catch (e: Exception) {
            Log.w(TAG, "findSimilarConversations failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Summarise a list of messages into a compact paragraph for context compression.
     * Returns null on failure.
     */
    suspend fun summariseMessages(
        baseUrl: String,
        modelName: String,
        messages: List<OllamaChatMessage>
    ): String? = try {
        val transcript = messages.joinToString("\n") { msg ->
            "${msg.role.replaceFirstChar { it.uppercase() }}: ${msg.content?.take(500) ?: ""}"
        }
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = "Summarise the following conversation excerpt into a single compact paragraph that preserves key facts, decisions, and context. Be concise.\n\n$transcript"
                )
            ),
            stream = false
        )
        api.chatOnce(baseUrl, request)?.trim()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "summariseMessages failed: ${e.message}")
        null
    }

    /**
     * Update the living state document (brief) for an agent after a conversation.
     * Returns the updated brief text, or null on failure.
     */
    suspend fun updateAgentBrief(
        baseUrl: String,
        modelName: String,
        currentBrief: String,
        agentRole: String,
        agentSystemPrompt: String,
        conversationExchange: String
    ): String? = try {
        val promptSnippet = agentSystemPrompt.take(200)
        val briefSection = if (currentBrief.isBlank()) "(none yet)" else currentBrief
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = """You are maintaining the state document for an agent whose role is: $agentRole. Their instructions are: $promptSnippet. Update the following brief based on the conversation that just occurred. Preserve accurate existing content. Return in exactly this format under 100 words: STATUS: [one sentence] / RECENT: [one or two things just done] / OPEN: [one or two unresolved items]

Current brief:
$briefSection

Conversation:
${conversationExchange.take(1200)}"""
                )
            ),
            stream = false
        )
        api.chatOnce(baseUrl, request)?.trim()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "updateAgentBrief failed: ${e.message}")
        null
    }

    /**
     * Check whether a candidate memory [content] is already covered by or contradicts any of
     * [existingMemories].
     *
     * Returns one of:
     * - `"ok"` — the fact is novel and should be stored.
     * - `"duplicate"` — an existing memory already covers this fact (returns as-is).
     * - `"contradiction:<existing fact>"` — the candidate contradicts an existing memory.
     * - `null` on failure (should default to storing the memory).
     */
    suspend fun checkMemoryConflict(
        baseUrl: String,
        modelName: String,
        candidate: String,
        existingMemories: List<String>
    ): String? {
        if (existingMemories.isEmpty()) return "ok"
        return try {
            val numbered = existingMemories.mapIndexed { i, m -> "[$i] $m" }.joinToString("\n")
            val request = OllamaChatRequest(
                model = modelName,
                messages = listOf(
                    OllamaChatMessage(
                        role = "user",
                        content = """You are a memory deduplication assistant. Given a candidate memory and a list of existing memories, respond with EXACTLY one of:
- "ok" — the candidate is a genuinely new fact not covered by any existing memory
- "duplicate" — an existing memory already captures this fact
- "contradiction: <the conflicting existing memory text>" — the candidate directly contradicts an existing memory

Existing memories:
$numbered

Candidate memory: "$candidate"

Reply with only the single word or phrase as described above. No other text."""
                    )
                ),
                stream = false
            )
            api.chatOnce(baseUrl, request)?.trim()?.lowercase()
        } catch (e: Exception) {
            Log.w(TAG, "checkMemoryConflict failed: ${e.message}")
            null
        }
    }

    /**
     * Extract 0–3 specific facts an agent should remember for future conversations.
     * Returns an empty list if nothing noteworthy was found or the request fails.
     */
    suspend fun extractAgentMemories(
        baseUrl: String,
        modelName: String,
        agentRole: String,
        conversationExchange: String,
        existingMemories: List<String>
    ): List<String> {
        return try {
            val existingList = if (existingMemories.isEmpty()) "(none)"
            else existingMemories.take(20).joinToString("\n") { "- $it" }
            val request = OllamaChatRequest(
                model = modelName,
                messages = listOf(
                    OllamaChatMessage(
                        role = "user",
                        content = """You are updating the memory of an agent whose role is: $agentRole. Based on this conversation, identify 0–3 specific facts this agent should remember for future conversations with this user. Focus on user preferences, recurring patterns, and important decisions. Do not duplicate these existing memories:
$existingList

Return one fact per line or nothing if nothing is worth remembering.

Conversation:
${conversationExchange.take(1200)}"""
                    )
                ),
                stream = false
            )
            val raw = api.chatOnce(baseUrl, request)?.trim() ?: return emptyList()
            if (raw.lowercase() == "none" || raw.isBlank()) return emptyList()
            raw.lines()
                .map { it.trim().trimStart('-', '*', '•', '·').trim() }
                .filter { it.isNotBlank() && it.lowercase() != "none" }
                .take(3)
        } catch (e: Exception) {
            Log.w(TAG, "extractAgentMemories failed: ${e.message}")
            emptyList()
        }
    }

    // ── Phase 2: Dreaming helpers ─────────────────────────────────────────────

    /**
     * Calculate an affective mood label for an agent based on the last 5 conversational turns.
     * Returns one of: Neutral, Enthusiastic, Frustrated, Meticulous, Focused, Curious.
     */
    suspend fun calculateMood(
        baseUrl: String,
        modelName: String,
        recentExchange: String
    ): String? = try {
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = """Analyse the emotional tone of this conversation exchange from the perspective of the assistant.
Choose exactly one label that best describes the assistant's current disposition:
Neutral, Enthusiastic, Curious, Focused, Reflective, Frustrated, Meticulous, Excited, Tired.
Reply with only the single label word, nothing else.

Exchange:
${recentExchange.take(2000)}"""
                )
            ),
            stream = false
        )
        val raw = api.chatOnce(baseUrl, request)?.trim() ?: return null
        val allowed = setOf("Neutral", "Enthusiastic", "Curious", "Focused", "Reflective", "Frustrated", "Meticulous", "Excited", "Tired")
        allowed.firstOrNull { it.equals(raw, ignoreCase = true) }
    } catch (e: Exception) {
        Log.w(TAG, "calculateMood failed: ${e.message}")
        null
    }

    /**
     * Triage a list of memories: merge similar unpinned ones into synthesised facts and flag
     * low-utility entries for deletion.
     *
     * Returns a [TriageResult] with lists of entries to keep/merge/delete.
     */
    suspend fun triageMemories(
        baseUrl: String,
        modelName: String,
        memories: List<String>
    ): TriageResult {
        if (memories.isEmpty()) return TriageResult()
        return try {
            val numbered = memories.mapIndexed { i, m -> "[$i] $m" }.joinToString("\n")
            val request = OllamaChatRequest(
                model = modelName,
                messages = listOf(
                    OllamaChatMessage(
                        role = "user",
                        content = """You are a memory curator for an AI agent. Your primary goals are:
1. Find and merge memories that are redundant, overlapping, or semantically identical into a single synthesised fact.
2. Delete memories that are outdated, trivial, or no longer useful.
3. Keep everything else unchanged.

Respond with EXACTLY this JSON (no markdown fences, no extra text):
{
  "keep": [list of indices to keep unchanged],
  "merge": [list of indices that overlap or are redundant and should be merged],
  "delete": [list of indices that are outdated or low-utility],
  "synthesised": "single merged fact combining the merged entries, or empty string if none"
}

Memories:
$numbered"""
                    )
                ),
                stream = false
            )
            val raw = api.chatOnce(baseUrl, request)?.trim() ?: return TriageResult()
            parseTriageResult(raw)
        } catch (e: Exception) {
            Log.w(TAG, "triageMemories failed: ${e.message}")
            TriageResult()
        }
    }

    private fun parseTriageResult(json: String): TriageResult = try {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        fun indices(key: String): List<Int> =
            obj.getAsJsonArray(key)?.mapNotNull { it.asIntOrNull() } ?: emptyList()
        TriageResult(
            keep = indices("keep"),
            merge = indices("merge"),
            delete = indices("delete"),
            synthesised = obj.get("synthesised")?.asString?.takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        TriageResult()
    }

    private fun com.google.gson.JsonElement.asIntOrNull(): Int? = try { asInt } catch (_: Exception) { null }

    /**
     * Review the OPEN section of an agent's brief and flag tasks stagnant for > 3 days.
     * Returns an updated brief with stale tasks moved to RECENT or archived, plus a
     * human-readable description of what changed.
     */
    suspend fun reviewBriefForStaleTasks(
        baseUrl: String,
        modelName: String,
        currentBrief: String,
        agentRole: String,
        lastConversationAt: Long
    ): BriefReviewResult? = try {
        val daysSinceLastActivity =
            (System.currentTimeMillis() - lastConversationAt) / (1000L * 60 * 60 * 24)
        val request = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = """You are reviewing the state brief for an agent whose role is: $agentRole.
The last conversation with this agent was $daysSinceLastActivity day(s) ago.
Tasks in OPEN that have had no activity for more than 3 days should be moved to RECENT (marked as archived) or removed.
Return EXACTLY this JSON (no markdown fences):
{
  "updatedBrief": "the full revised brief keeping STATUS / RECENT / OPEN format",
  "changeDescription": "one sentence describing what was changed, or 'No changes' if nothing changed"
}

Current brief:
$currentBrief"""
                )
            ),
            stream = false
        )
        val raw = api.chatOnce(baseUrl, request)?.trim() ?: return null
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        BriefReviewResult(
            updatedBrief = obj.get("updatedBrief")?.asString?.takeIf { it.isNotBlank() },
            changeDescription = obj.get("changeDescription")?.asString ?: "No changes"
        )
    } catch (e: Exception) {
        Log.w(TAG, "reviewBriefForStaleTasks failed: ${e.message}")
        null
    }
}

data class TriageResult(
    val keep: List<Int> = emptyList(),
    val merge: List<Int> = emptyList(),
    val delete: List<Int> = emptyList(),
    val synthesised: String? = null
)

data class BriefReviewResult(
    val updatedBrief: String?,
    val changeDescription: String
)
