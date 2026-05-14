package com.ongrid.app

import android.util.Log
import com.ongrid.app.data.model.OllamaTool
import kotlinx.coroutines.flow.first

private const val EXECUTOR_TAG = "AgentToolExecutor"

/**
 * Shared tool-execution logic for agents running outside [ChatForegroundService]
 * (e.g. [AgentConversationWorker]).
 *
 * Supports web_search, fetch_url, form_memory, and all enabled MCP tools.
 * Does NOT support use_skill or initiate_agent_convo in this context.
 */
class AgentToolExecutor(private val app: OnGridApplication) {

    /** Build the tool list available to an agent in a handoff conversation. */
    suspend fun buildTools(): List<OllamaTool> {
        val mcpTools = app.mcpRepository.getAllEnabledTools()
            .values.map { (_, mcpTool) -> mcpTool.toOllamaTool() }
        return buildList {
            add(app.webSearchRepository.tool.toOllamaTool())
            add(app.webFetchRepository.tool.toOllamaTool())
            add(app.formMemoryRepository.tool.toOllamaTool())
            addAll(mcpTools)
        }
    }

    /**
     * Execute a single tool call. Returns (resultText, isError).
     *
     * @param funcName       The tool function name from the model's response.
     * @param args           The arguments map parsed from the model's tool call.
     * @param agentId        The calling agent's ID (needed for form_memory).
     * @param conversationId The current conversation ID (needed for form_memory).
     */
    suspend fun execute(
        funcName: String,
        args: Map<String, Any>,
        agentId: String,
        conversationId: String
    ): Pair<String, Boolean> {
        Log.d(EXECUTOR_TAG, "Executing tool: $funcName args=$args")
        val toolMap = app.mcpRepository.getAllEnabledTools()
        val serverEntry = toolMap[funcName]

        return try {
            when {
                funcName == "web_search" ->
                    app.webSearchRepository.search(args) to false

                funcName == "fetch_url" ->
                    app.webFetchRepository.fetch(args) to false

                funcName == "form_memory" -> {
                    val candidate = args["content"]?.toString()?.trim()
                    if (candidate.isNullOrBlank()) {
                        "Error: 'content' argument is required and must not be blank." to true
                    } else {
                        val existing = app.agentRepository.memoriesForAgentOnce(agentId)
                        val existingContents = existing.map { it.content }
                        val settings = app.settingsRepository.settings.first()
                        val (utilHost, utilModel) = app.agentRepository.resolveUtilityModel(
                            agentId,
                            settings.utilityModelHost.ifBlank { "" },
                            settings.utilityModelName.ifBlank { "" }
                        )
                        val conflict = if (utilHost.isNotBlank() && utilModel.isNotBlank()) {
                            app.utilityAgentRepository.checkMemoryConflict(
                                utilHost, utilModel, candidate, existingContents
                            )
                        } else null

                        when {
                            conflict == "duplicate" ->
                                "Memory already exists: \"$candidate\" — no duplicate stored." to false
                            conflict != null && conflict.startsWith("contradiction:") -> {
                                val conflicting = conflict.removePrefix("contradiction:").trim()
                                "Memory not stored — contradicts existing memory: \"$conflicting\"." to false
                            }
                            else -> {
                                app.formMemoryRepository.formMemory(agentId, conversationId, args) to false
                            }
                        }
                    }
                }

                serverEntry != null -> {
                    val r = app.mcpRepository.callTool(serverEntry.first, funcName, args)
                    r.content.joinToString("\n") { it.text } to r.isError
                }

                else -> "Tool '$funcName' is not available in agent collaboration context." to true
            }
        } catch (e: Exception) {
            Log.w(EXECUTOR_TAG, "Tool $funcName failed: ${e.message}")
            "Tool '$funcName' failed: ${e.message ?: "unknown error"}" to true
        }
    }
}
