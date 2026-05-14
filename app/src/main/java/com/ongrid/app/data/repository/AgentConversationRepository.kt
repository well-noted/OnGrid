package com.ongrid.app.data.repository

import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.model.OllamaTool
import com.ongrid.app.data.model.OllamaToolFunction

/**
 * Provides the `initiate_agent_convo` built-in tool, which lets the active agent
 * open a new dedicated conversation channel with another agent.
 *
 * The tool only appears in the available-tools list when there is a current agent
 * active and at least one other active agent exists.  The list of other agents is
 * embedded in the description so the model knows who it can contact without needing
 * an extra system message.
 */
class AgentConversationRepository {

    /**
     * Build the `initiate_agent_convo` OllamaTool for the current turn.
     *
     * @param currentAgentId  The ID of the agent currently running the conversation.
     * @param allActiveAgents All ACTIVE agents in the app (including the current one).
     * @return A tool definition, or null if there are no other agents to contact.
     */
    fun buildTool(currentAgentId: String, allActiveAgents: List<AgentEntity>): OllamaTool? {
        val others = allActiveAgents.filter { it.id != currentAgentId }
        if (others.isEmpty()) return null

        val agentList = others.joinToString(", ") { agent ->
            val roleHint = if (agent.role.isNotBlank()) " (${agent.role})" else ""
            "${agent.name}$roleHint"
        }

        return OllamaTool(
            type = "function",
            function = OllamaToolFunction(
                name = "initiate_agent_convo",
                description = "Open a new conversation channel with another agent to collaborate on a task. " +
                    "Available agents: $agentList. " +
                    "Use this when the user wants two agents to coordinate. " +
                    "Compose an opening message that gives the other agent the context they need.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "target_agent_name" to mapOf(
                            "type" to "string",
                            "description" to "The exact name of the agent to contact (from the available agents list)"
                        ),
                        "message" to mapOf(
                            "type" to "string",
                            "description" to "Your opening message to that agent — give them the context they need"
                        ),
                        "goal" to mapOf(
                            "type" to "string",
                            "description" to "A concise statement of what you two need to accomplish together"
                        )
                    ),
                    "required" to listOf("target_agent_name", "message", "goal")
                )
            )
        )
    }
}
