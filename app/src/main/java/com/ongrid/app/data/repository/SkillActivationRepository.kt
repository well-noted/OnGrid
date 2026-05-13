package com.ongrid.app.data.repository

import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.McpTool

/**
 * Provides the built-in `use_skill` tool that an active agent can call to load a skill's
 * instructions into the current conversation.  The tool is only injected when an agent
 * workspace is active and there are skills assigned to that agent.
 */
class SkillActivationRepository {

    val tool: McpTool = McpTool(
        name = "use_skill",
        description = "Load a skill's instructions into the current conversation. " +
            "Call this when a skill listed in your available skills is relevant to the task at hand. " +
            "Only available inside an agent workspace.",
        inputSchema = McpInputSchema(
            type = "object",
            properties = mapOf(
                "skill_name" to mapOf(
                    "type" to "string",
                    "description" to "The exact name of the skill to activate."
                )
            ),
            required = listOf("skill_name")
        )
    )
}
