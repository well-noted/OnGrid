package com.ongrid.app.data.repository

import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.McpTool
import com.ongrid.app.data.network.WebSearchApi

/**
 * Provides the built-in `web_search` tool that agents can use to look up information
 * without requiring an external MCP server.
 *
 * The tool is always enabled and uses DuckDuckGo's Instant Answer API — no API key or
 * additional configuration is required.
 */
class WebSearchRepository(private val api: WebSearchApi) {

    /** The `McpTool`-compatible definition that is sent to the model as a callable function. */
    val tool: McpTool = McpTool(
        name = "web_search",
        description = "Search the web for facts, definitions, or general knowledge. " +
            "Use this whenever you need information that may not be in your training data. " +
            "Not suited for real-time data (e.g. live stock prices or today's weather).",
        inputSchema = McpInputSchema(
            type = "object",
            properties = mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "The search query string"
                )
            ),
            required = listOf("query")
        )
    )

    /**
     * Execute a web search with the arguments provided by the model.
     *
     * @param arguments The function-call argument map; must contain a `"query"` key.
     * @return A formatted string result suitable for injecting back into the conversation.
     */
    suspend fun search(arguments: Map<String, Any>): String {
        val query = arguments["query"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return "Error: the 'query' argument is required and must not be blank."
        return api.search(query)
    }
}
