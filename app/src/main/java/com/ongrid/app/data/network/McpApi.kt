package com.ongrid.app.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.model.McpCallToolResult
import com.ongrid.app.data.model.McpContent
import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.McpJsonRpcRequest
import com.ongrid.app.data.model.McpJsonRpcResponse
import com.ongrid.app.data.model.McpTool
import com.ongrid.app.data.model.McpToolsListResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "McpApi"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class McpApi(private val client: OkHttpClient) {

    private val gson = Gson()
    private val requestIdCounter = AtomicInteger(1)

    /**
     * Send an MCP initialize request to establish a session with the server.
     * Returns true if the server is reachable and responds with a valid initialize result.
     */
    suspend fun initialize(baseUrl: String): Boolean = try {
        val rpcRequest = McpJsonRpcRequest(
            id = requestIdCounter.getAndIncrement(),
            method = "initialize",
            params = mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf("tools" to mapOf<String, Any>()),
                "clientInfo" to mapOf("name" to "OnGrid", "version" to "1.0")
            )
        )
        val response = sendJsonRpc(baseUrl, rpcRequest)
        response?.error == null
    } catch (e: Exception) {
        Log.d(TAG, "initialize failed for $baseUrl: ${e.message}")
        false
    }

    /** Fetch the list of tools exposed by the MCP server. */
    suspend fun listTools(baseUrl: String): List<McpTool> = try {
        val rpcRequest = McpJsonRpcRequest(
            id = requestIdCounter.getAndIncrement(),
            method = "tools/list"
        )
        val response = sendJsonRpc(baseUrl, rpcRequest) ?: return emptyList()
        if (response.error != null) return emptyList()

        val resultMap = response.result as? Map<*, *> ?: return emptyList()
        val toolsRaw = resultMap["tools"] as? List<*> ?: return emptyList()

        toolsRaw.mapNotNull { rawTool ->
            val toolMap = rawTool as? Map<*, *> ?: return@mapNotNull null
            val name = toolMap["name"] as? String ?: return@mapNotNull null
            val description = toolMap["description"] as? String ?: ""
            val schemaMap = toolMap["inputSchema"] as? Map<*, *>
            val properties = (schemaMap?.get("properties") as? Map<String, Any>) ?: emptyMap()
            val required = (schemaMap?.get("required") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val type = schemaMap?.get("type") as? String ?: "object"
            McpTool(
                name = name,
                description = description,
                inputSchema = McpInputSchema(type = type, properties = properties, required = required)
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "listTools failed for $baseUrl: ${e.message}")
        emptyList()
    }

    /** Call a tool on the MCP server and return the result content. */
    suspend fun callTool(
        baseUrl: String,
        toolName: String,
        arguments: Map<String, Any>
    ): McpCallToolResult = try {
        val rpcRequest = McpJsonRpcRequest(
            id = requestIdCounter.getAndIncrement(),
            method = "tools/call",
            params = mapOf("name" to toolName, "arguments" to arguments)
        )
        val response = sendJsonRpc(baseUrl, rpcRequest)
        if (response?.error != null) {
            McpCallToolResult(
                content = listOf(McpContent(text = "Error: ${response.error.message}")),
                isError = true
            )
        } else {
            val resultMap = response?.result as? Map<*, *>
            val contentList = (resultMap?.get("content") as? List<*>)?.mapNotNull { item ->
                val contentMap = item as? Map<*, *> ?: return@mapNotNull null
                McpContent(
                    type = contentMap["type"] as? String ?: "text",
                    text = contentMap["text"] as? String ?: ""
                )
            } ?: emptyList()
            val isError = resultMap?.get("isError") as? Boolean ?: false
            McpCallToolResult(content = contentList, isError = isError)
        }
    } catch (e: Exception) {
        Log.w(TAG, "callTool failed for $baseUrl/$toolName: ${e.message}")
        McpCallToolResult(
            content = listOf(McpContent(text = "Tool call failed: ${e.message}")),
            isError = true
        )
    }

    private fun sendJsonRpc(baseUrl: String, request: McpJsonRpcRequest): McpJsonRpcResponse? {
        val jsonBody = gson.toJson(request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url("$baseUrl/mcp")
            .post(jsonBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .build()

        return client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "JSON-RPC failed: HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            // Handle SSE format (data: {...}\n\n) or plain JSON
            val jsonStr = if (body.startsWith("data:")) {
                body.lines()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.isNotEmpty() && it != "[DONE]" }
                    .firstOrNull() ?: return null
            } else {
                body
            }
            try {
                gson.fromJson(jsonStr, McpJsonRpcResponse::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse MCP response: $jsonStr", e)
                null
            }
        }
    }
}
