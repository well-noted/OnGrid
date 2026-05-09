package com.ongrid.app.data.network

import android.util.Log
import com.google.gson.Gson
import com.ongrid.app.data.model.McpCallToolResult
import com.ongrid.app.data.model.McpContent
import com.ongrid.app.data.model.McpInputSchema
import com.ongrid.app.data.model.McpJsonRpcRequest
import com.ongrid.app.data.model.McpJsonRpcResponse
import com.ongrid.app.data.model.McpTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "McpApi"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class McpApi(private val client: OkHttpClient) {

    // SSE connections must never time out at the HTTP layer; the session is kept open
    // while waiting for asynchronous JSON-RPC responses over the stream. Coroutine
    // withTimeout blocks handle cancellation instead.
    private val sseClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private val gson = Gson()
    private val requestIdCounter = AtomicInteger(1)

    // ---------------------------------------------------------------------------
    // Transport detection
    // ---------------------------------------------------------------------------

    /** Returns true if the URL points to an SSE endpoint (last path segment is "sse"). */
    private fun isSseUrl(url: String): Boolean {
        val lastSegment = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        return lastSegment.equals("sse", ignoreCase = true)
    }

    /**
     * Resolves the SSE endpoint event's data value into an absolute URL.
     * The value may be absolute ("http://...") or relative ("/messages?sessionId=...").
     */
    private fun resolveEndpointUrl(sseUrl: String, endpointData: String): String {
        val trimmed = endpointData.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        // Extract scheme://host:port from the SSE URL
        val schemeEnd = sseUrl.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val afterScheme = sseUrl.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOf('/')
        val hostPort = if (pathStart >= 0) afterScheme.substring(0, pathStart) else afterScheme
        val origin = sseUrl.substring(0, schemeEnd + 3) + hostPort
        return if (trimmed.startsWith("/")) "$origin$trimmed" else "$origin/$trimmed"
    }

    private fun initParams() = mapOf(
        "protocolVersion" to "2024-11-05",
        "capabilities" to mapOf("tools" to mapOf<String, Any>()),
        "clientInfo" to mapOf("name" to "OnGrid", "version" to "1.0")
    )

    // ---------------------------------------------------------------------------
    // SSE session helpers
    // ---------------------------------------------------------------------------

    /**
     * Opens an SSE connection to [sseUrl], waits for the server's "endpoint" event,
     * then invokes [block] with an [SseSession] that can send JSON-RPC requests and
     * receive their responses over the stream. The connection is closed when [block]
     * returns or throws.
     */
    private suspend fun <T> sseSession(
        sseUrl: String,
        authHeader: String?,
        block: suspend SseSession.() -> T
    ): T {
        val pendingResponses =
            ConcurrentHashMap<Int, CompletableDeferred<McpJsonRpcResponse>>()
        val endpointDeferred = CompletableDeferred<String>()

        val sseRequest = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .applyAuth(authHeader)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?, data: String
            ) {
                Log.d(TAG, "SSE event type=$type data=$data")
                when (type) {
                    "endpoint" -> endpointDeferred.complete(resolveEndpointUrl(sseUrl, data))
                    else -> runCatching {
                        gson.fromJson(data, McpJsonRpcResponse::class.java)
                    }.onSuccess { rpc ->
                        if (rpc.id != null || rpc.error != null) {
                            rpc.id?.let { pendingResponses[it]?.complete(rpc) }
                        }
                    }.onFailure {
                        Log.d(TAG, "SSE non-RPC event ignored: $data")
                    }
                }
            }

            override fun onFailure(
                eventSource: EventSource, t: Throwable?, response: Response?
            ) {
                val ex = t ?: IOException("SSE failed: HTTP ${response?.code}")
                Log.w(TAG, "SSE onFailure: ${ex.message}")
                if (!endpointDeferred.isCompleted) endpointDeferred.completeExceptionally(ex)
                pendingResponses.values.forEach {
                    if (!it.isCompleted) it.completeExceptionally(ex)
                }
            }
        }

        val eventSource =
            EventSources.createFactory(sseClient).newEventSource(sseRequest, listener)
        return try {
            Log.d(TAG, "SSE connecting to $sseUrl")
            val endpointUrl = try {
                withTimeout(10_000L) { endpointDeferred.await() }
            } catch (e: Exception) {
                throw IOException("SSE: no endpoint event received from $sseUrl (${e.message})")
            }
            Log.d(TAG, "SSE endpoint resolved: $endpointUrl")
            block(SseSession(endpointUrl, authHeader, pendingResponses))
        } finally {
            eventSource.cancel()
        }
    }

    private inner class SseSession(
        private val endpointUrl: String,
        private val authHeader: String?,
        private val pendingResponses: ConcurrentHashMap<Int, CompletableDeferred<McpJsonRpcResponse>>
    ) {
        /** POST a JSON-RPC request and suspend until the matching response arrives on the stream. */
        suspend fun send(request: McpJsonRpcRequest): McpJsonRpcResponse {
            val deferred = CompletableDeferred<McpJsonRpcResponse>()
            pendingResponses[request.id] = deferred
            val jsonBody = gson.toJson(request).toRequestBody(JSON_MEDIA_TYPE)
            val postReq = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody)
                .header("Content-Type", "application/json")
                .applyAuth(authHeader)
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(postReq).execute().use { response ->
                    Log.d(TAG, "SSE POST ${request.method} → HTTP ${response.code}")
                }
            }
            return try {
                withTimeout(30_000L) { deferred.await() }
            } catch (e: Exception) {
                pendingResponses.remove(request.id)
                throw IOException("SSE: no response for '${request.method}' (${e.message})")
            }
        }

        /** Send an MCP notification (no id, no response expected). */
        suspend fun notify(method: String, params: Any? = null) {
            data class Notification(
                val jsonrpc: String = "2.0", val method: String, val params: Any? = null
            )
            val jsonBody =
                gson.toJson(Notification(method = method, params = params))
                    .toRequestBody(JSON_MEDIA_TYPE)
            val postReq = Request.Builder()
                .url(endpointUrl)
                .post(jsonBody)
                .header("Content-Type", "application/json")
                .applyAuth(authHeader)
                .build()
            withContext(Dispatchers.IO) {
                runCatching { client.newCall(postReq).execute().use { } }
            }
        }

        /**
         * Performs the MCP initialize handshake on this session.
         * Sends `initialize`, waits for the response, then sends `notifications/initialized`.
         * Returns true on success.
         */
        suspend fun initialize() {
            val req = McpJsonRpcRequest(
                id = requestIdCounter.getAndIncrement(),
                method = "initialize",
                params = initParams()
            )
            val resp = send(req)
            if (resp.error != null) throw IOException("MCP initialize error: ${resp.error.message}")
            notify("notifications/initialized")
        }
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Verify the server is reachable.
     * - HTTP transport: sends an MCP `initialize` JSON-RPC request.
     * - SSE transport: opens the SSE connection and performs the full initialize handshake.
     */
    suspend fun initialize(baseUrl: String, authHeader: String? = null) {
        if (isSseUrl(baseUrl)) {
            sseSession(baseUrl, authHeader) { initialize() }
        } else {
            val rpcRequest = McpJsonRpcRequest(
                id = requestIdCounter.getAndIncrement(),
                method = "initialize",
                params = initParams()
            )
            val response = sendJsonRpc(baseUrl, rpcRequest, authHeader)
            if (response == null || response.error != null) {
                throw IOException("HTTP MCP initialize failed: ${response?.error?.message ?: "no response"}")
            }
        }
    }

    /** Fetch the list of tools exposed by the MCP server. */
    suspend fun listTools(baseUrl: String, authHeader: String? = null): List<McpTool> {
        if (isSseUrl(baseUrl)) {
            var tools = emptyList<McpTool>()
            sseSession(baseUrl, authHeader) {
                initialize()
                val req = McpJsonRpcRequest(
                    id = requestIdCounter.getAndIncrement(),
                    method = "tools/list"
                )
                val resp = send(req)
                if (resp.error != null) throw IOException("tools/list error: ${resp.error.message}")
                tools = parseToolsList(resp)
            }
            return tools
        } else {
            val rpcRequest = McpJsonRpcRequest(
                id = requestIdCounter.getAndIncrement(),
                method = "tools/list"
            )
            val response = sendJsonRpc(baseUrl, rpcRequest, authHeader)
                ?: throw IOException("HTTP tools/list: no response from server")
            if (response.error != null) throw IOException("tools/list error: ${response.error.message}")
            return parseToolsList(response)
        }
    }

    /** Call a tool on the MCP server and return the result content. */
    suspend fun callTool(
        baseUrl: String,
        toolName: String,
        arguments: Map<String, Any>,
        authHeader: String? = null
    ): McpCallToolResult = try {
        if (isSseUrl(baseUrl)) {
            var result = McpCallToolResult(
                content = listOf(McpContent(text = "SSE tool call failed")), isError = true
            )
            sseSession(baseUrl, authHeader) {
                initialize()
                val req = McpJsonRpcRequest(
                    id = requestIdCounter.getAndIncrement(),
                    method = "tools/call",
                    params = mapOf("name" to toolName, "arguments" to arguments)
                )
                val resp = send(req)
                result = if (resp.error != null) {
                    McpCallToolResult(
                        content = listOf(McpContent(text = "Error: ${resp.error.message}")),
                        isError = true
                    )
                } else {
                    parseCallToolResult(resp)
                }
            }
            result
        } else {
            val rpcRequest = McpJsonRpcRequest(
                id = requestIdCounter.getAndIncrement(),
                method = "tools/call",
                params = mapOf("name" to toolName, "arguments" to arguments)
            )
            val response = sendJsonRpc(baseUrl, rpcRequest, authHeader)
            if (response?.error != null) {
                McpCallToolResult(
                    content = listOf(McpContent(text = "Error: ${response.error.message}")),
                    isError = true
                )
            } else {
                parseCallToolResult(response)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "callTool failed for $baseUrl/$toolName: ${e.message}")
        McpCallToolResult(
            content = listOf(McpContent(text = "Tool call failed: ${e.message}")),
            isError = true
        )
    }

    // ---------------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------------

    private fun parseToolsList(response: McpJsonRpcResponse): List<McpTool> {
        val resultMap = response.result as? Map<*, *> ?: return emptyList()
        val toolsRaw = resultMap["tools"] as? List<*> ?: return emptyList()
        return toolsRaw.mapNotNull { rawTool ->
            val toolMap = rawTool as? Map<*, *> ?: return@mapNotNull null
            val name = toolMap["name"] as? String ?: return@mapNotNull null
            val description = toolMap["description"] as? String ?: ""
            val schemaMap = toolMap["inputSchema"] as? Map<*, *>
            val properties =
                (schemaMap?.get("properties") as? Map<String, Any>) ?: emptyMap()
            val required =
                (schemaMap?.get("required") as? List<*>)?.filterIsInstance<String>()
                    ?: emptyList()
            val type = schemaMap?.get("type") as? String ?: "object"
            McpTool(
                name = name,
                description = description,
                inputSchema = McpInputSchema(type = type, properties = properties, required = required)
            )
        }
    }

    private fun parseCallToolResult(response: McpJsonRpcResponse?): McpCallToolResult {
        val resultMap = response?.result as? Map<*, *>
        val contentList = (resultMap?.get("content") as? List<*>)?.mapNotNull { item ->
            val contentMap = item as? Map<*, *> ?: return@mapNotNull null
            McpContent(
                type = contentMap["type"] as? String ?: "text",
                text = contentMap["text"] as? String ?: ""
            )
        } ?: emptyList()
        val isError = resultMap?.get("isError") as? Boolean ?: false
        return McpCallToolResult(content = contentList, isError = isError)
    }

    // ---------------------------------------------------------------------------
    // HTTP Streamable transport (2025-03-26 spec)
    // ---------------------------------------------------------------------------

    private fun sendJsonRpc(
        baseUrl: String,
        request: McpJsonRpcRequest,
        authHeader: String? = null
    ): McpJsonRpcResponse? {
        val jsonBody = gson.toJson(request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url("$baseUrl/mcp")
            .post(jsonBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .applyAuth(authHeader)
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

private fun Request.Builder.applyAuth(authHeader: String?): Request.Builder =
    if (!authHeader.isNullOrBlank()) header("Authorization", authHeader) else this
