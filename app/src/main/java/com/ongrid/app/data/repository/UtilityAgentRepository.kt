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
}
