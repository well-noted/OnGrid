package com.ongrid.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.model.McpCallToolResult
import com.ongrid.app.data.model.McpContent
import com.ongrid.app.data.model.McpServer
import com.ongrid.app.data.model.McpTool
import com.ongrid.app.data.network.McpApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mcp_servers")
private val MCP_SERVERS_KEY = stringPreferencesKey("mcp_servers_json")

class McpRepository(
    private val api: McpApi,
    private val context: Context
) {
    private val gson = Gson()

    /** Observe the saved list of MCP servers from persistent storage. */
    val servers: Flow<List<McpServer>> = context.dataStore.data.map { prefs ->
        val json = prefs[MCP_SERVERS_KEY] ?: return@map emptyList()
        try {
            val type = object : TypeToken<List<McpServer>>() {}.type
            gson.fromJson<List<McpServer>>(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Persist the full list of MCP servers. */
    suspend fun saveServers(servers: List<McpServer>) {
        context.dataStore.edit { prefs ->
            prefs[MCP_SERVERS_KEY] = gson.toJson(servers)
        }
    }

    /** Add or update a single MCP server in the stored list. */
    suspend fun upsertServer(server: McpServer) {
        val current = getSavedServers().toMutableList()
        val index = current.indexOfFirst { it.id == server.id }
        if (index >= 0) current[index] = server else current.add(server)
        saveServers(current)
    }

    /** Remove a server from the stored list by ID. */
    suspend fun removeServer(serverId: String) {
        val current = getSavedServers().filter { it.id != serverId }
        saveServers(current)
    }

    /** Connect to the MCP server and refresh its tool list. */
    suspend fun refreshTools(server: McpServer): McpServer = withContext(Dispatchers.IO) {
        val tools = api.listTools(server.baseUrl, server.authHeader)
        server.copy(tools = tools, enabled = true, lastConnected = System.currentTimeMillis())
    }

    /** Call a tool on a specific MCP server. Returns the full result including isError. */
    suspend fun callTool(
        serverId: String,
        toolName: String,
        arguments: Map<String, Any>
    ): McpCallToolResult = withContext(Dispatchers.IO) {
        val server = getSavedServers().find { it.id == serverId }
            ?: return@withContext McpCallToolResult(
                content = listOf(McpContent(text = "Error: MCP server not found")),
                isError = true
            )
        api.callTool(server.baseUrl, toolName, arguments, server.authHeader)
    }

    /** Return all tools from all enabled MCP servers, as toolName -> (serverId, McpTool). */
    suspend fun getAllEnabledTools(): Map<String, Pair<String, McpTool>> =
        getSavedServers()
            .filter { it.enabled }
            .flatMap { server -> server.tools.map { tool -> tool.name to (server.id to tool) } }
            .toMap()

    private suspend fun getSavedServers(): List<McpServer> = servers.first()
}
