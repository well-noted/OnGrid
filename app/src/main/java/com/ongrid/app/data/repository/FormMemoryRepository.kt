package com.ongrid.app.data.repository

import com.ongrid.app.data.local.AgentMemoryDao
import com.ongrid.app.data.local.AgentMemoryEntity
import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.McpTool

/**
 * Provides the built-in `form_memory` tool that an active agent can call to pin a fact to its
 * long-term memory.  The tool is only injected into a conversation when an agent workspace is
 * active (i.e. [com.ongrid.app.viewmodel.ChatViewModel.uiState] has a non-null `currentAgentId`).
 */
class FormMemoryRepository(private val agentMemoryDao: AgentMemoryDao) {

    val tool: McpTool = McpTool(
        name = "form_memory",
        description = "Pin a memory to your long-term memory so it persists across future " +
            "conversations. Call this whenever something important is discovered, decided, or " +
            "observed that you should remember. Only available inside an agent workspace.",
        inputSchema = McpInputSchema(
            type = "object",
            properties = mapOf(
                "content" to mapOf(
                    "type" to "string",
                    "description" to "A concise, self-contained fact or note to remember " +
                        "(maximum 500 characters)."
                )
            ),
            required = listOf("content")
        )
    )

    /**
     * Persist [arguments] as a pinned [AgentMemoryEntity] for [agentId].
     *
     * @param agentId        The ID of the agent whose memory store receives the new entry.
     * @param conversationId The ID of the conversation that triggered this call (may be null for
     *                       brand-new conversations that haven't been persisted yet).
     * @param arguments      The tool-call argument map; must contain a non-blank `"content"` key.
     * @return A human-readable confirmation string injected back into the conversation as the tool
     *         result, or an error description if the call was malformed.
     */
    suspend fun formMemory(
        agentId: String,
        conversationId: String?,
        arguments: Map<String, Any>
    ): String {
        val content = arguments["content"]
            ?.toString()
            ?.take(500)
            ?.takeIf { it.isNotBlank() }
            ?: return "Error: the 'content' argument is required and must not be blank."

        agentMemoryDao.insert(
            AgentMemoryEntity(
                agentId = agentId,
                content = content,
                isPinned = true,
                sourceConversationId = conversationId
            )
        )
        return "Memory pinned: \"$content\""
    }
}
