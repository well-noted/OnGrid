package com.ongrid.app.data.model

data class McpServer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val tools: List<McpTool> = emptyList(),
    val lastConnected: Long? = null
) {
    val displayUrl: String get() = baseUrl.removePrefix("http://").removePrefix("https://")
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: McpInputSchema = McpInputSchema()
) {
    fun toOllamaTool(): com.ongrid.app.data.model.OllamaTool =
        com.ongrid.app.data.model.OllamaTool(
            function = com.ongrid.app.data.model.OllamaToolFunction(
                name = name,
                description = description,
                parameters = mapOf(
                    "type" to (inputSchema.type),
                    "properties" to (inputSchema.properties),
                    "required" to (inputSchema.required)
                )
            )
        )
}

data class McpInputSchema(
    val type: String = "object",
    val properties: Map<String, Any> = emptyMap(),
    val required: List<String> = emptyList()
)

// MCP JSON-RPC protocol models
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: Any? = null
)

data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: Any? = null,
    val error: McpError? = null
)

data class McpError(
    val code: Int,
    val message: String
)

data class McpToolsListResult(
    val tools: List<McpToolDefinition> = emptyList()
)

data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, Any> = emptyMap()
)

data class McpCallToolResult(
    val content: List<McpContent> = emptyList(),
    val isError: Boolean = false
)

data class McpContent(
    val type: String = "text",
    val text: String = ""
)
