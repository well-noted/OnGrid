package com.ongrid.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.ChatForegroundService
import com.ongrid.app.ChatServiceEvent
import com.ongrid.app.OnGridApplication
import com.ongrid.app.PendingChatRequest
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.data.model.OllamaTool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableTools: List<OllamaTool> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication
    private val repo = app.conversationRepository
    private val serverRepo = app.serverRepository

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    var currentServer: OllamaServer? = null
    var currentModel: String = ""

    /** Non-null once a conversation has been created or loaded. */
    var currentConversationId: String? = null
        private set

    /**
     * All saved servers and their model lists — used by the in-chat model picker.
     * Each entry is Pair(serverDisplayName, List<modelName>).
     */
    val savedServersWithModels: StateFlow<List<Pair<String, List<String>>>> =
        serverRepo.savedServers.map { saved ->
            saved.map { entity ->
                val models: List<String> = try {
                    Gson().fromJson(entity.modelsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) { emptyList() }
                entity.displayName to models
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Initialisation ────────────────────────────────────────────────────────

    /** Called when the user starts a brand-new chat after picking a server/model. */
    fun initNewConversation(server: OllamaServer, modelName: String) {
        currentServer = server
        currentModel = modelName
        currentConversationId = null
        _messages.value = emptyList()
    }

    /** Called when the user reopens an existing conversation from the list. */
    fun resumeConversation(conversationId: String) {
        viewModelScope.launch {
            currentConversationId = conversationId
            val entity = repo.getConversation(conversationId) ?: return@launch
            currentServer = OllamaServer(host = entity.serverHost, port = entity.serverPort)
            currentModel = entity.modelName
            _messages.value = repo.getMessages(conversationId)
            loadTools()
        }
    }

    /**
     * Switch models mid-session (from the in-chat model picker).
     * Changes take effect from the next message sent.
     */
    fun changeModel(serverHost: String, serverPort: Int, modelName: String) {
        currentServer = OllamaServer(host = serverHost, port = serverPort)
        currentModel = modelName
        viewModelScope.launch {
            serverRepo.saveLastUsed(serverHost, serverPort, modelName)
        }
    }

    /** Load available MCP tools plus built-in tools for the current session. */
    fun loadTools() {
        viewModelScope.launch {
            val toolMap = app.mcpRepository.getAllEnabledTools()
            val mcpTools = toolMap.values.map { (_, mcpTool) -> mcpTool.toOllamaTool() }
            val builtInTools = listOf(app.webSearchRepository.tool.toOllamaTool())
            _uiState.value = _uiState.value.copy(availableTools = builtInTools + mcpTools)
        }
    }

    /**
     * Send a user message and stream the assistant's response.
     *
     * [ChatForegroundService] owns the entire conversational turn — including any tool-call
     * round-trips — so [startForegroundService] is called exactly once, synchronously on the
     * main thread, while the app is still foregrounded.
     */
    fun sendMessage(text: String) {
        val server = currentServer ?: return
        if (currentModel.isBlank()) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = text)
        _messages.value = _messages.value + userMsg

        // Add the assistant placeholder before calling buildOllamaHistory so the placeholder
        // (isStreaming = true) is filtered out of the history we send to the model.
        val assistantMsgId = java.util.UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )

        val history = buildOllamaHistory()
        val tools = _uiState.value.availableTools.takeIf { it.isNotEmpty() }
        val request = OllamaChatRequest(
            model = currentModel,
            messages = history,
            stream = true,
            tools = tools
        )

        // Drain any leftover events from a previous (completed or cancelled) request.
        while (true) { app.chatServiceChannel.tryReceive().getOrNull() ?: break }

        app.pendingChatRequest = PendingChatRequest(assistantMsgId, server.baseUrl, request)
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // *** Must be called here — synchronously on the main thread — before we ever suspend. ***
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), ChatForegroundService::class.java)
        )

        viewModelScope.launch {
            // Ensure a conversation entity exists; auto-title from the first user message.
            val convId = ensureConversation(server, userMsg)
            repo.saveMessage(convId, userMsg)

            for (event in app.chatServiceChannel) {
                when (event) {
                    is ChatServiceEvent.Token ->
                        updateStreamingMessage(event.msgId, event.content)

                    is ChatServiceEvent.FinalizeMessage ->
                        if (event.content.isBlank()) {
                            _messages.value = _messages.value.filter { it.id != event.msgId }
                        } else {
                            finalizeMessage(event.msgId, event.content)
                        }

                    is ChatServiceEvent.AppendMessage -> {
                        _messages.value = _messages.value + event.message
                        repo.saveMessage(convId, event.message)
                    }

                    is ChatServiceEvent.TurnComplete -> {
                        if (event.error != null) {
                            finalizeMessage(event.msgId, "Error: ${event.error}")
                            _uiState.value = _uiState.value.copy(error = event.error)
                        } else {
                            finalizeMessage(event.msgId, event.lastContent)
                        }
                        _uiState.value = _uiState.value.copy(isLoading = false)

                        // Persist the finalized assistant message.
                        val finalMsg = _messages.value.find { it.id == event.msgId }
                        if (finalMsg != null) repo.saveMessage(convId, finalMsg)
                        repo.touchConversation(convId)
                        break
                    }
                }
            }
        }
    }

    /** Clear all messages for the current conversation (in memory and on disk). */
    fun clearMessages() {
        _messages.value = emptyList()
        val convId = currentConversationId ?: return
        viewModelScope.launch { repo.clearMessages(convId) }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun ensureConversation(server: OllamaServer, firstUserMsg: ChatMessage): String {
        currentConversationId?.let { return it }
        val title = firstUserMsg.content.take(60).let {
            if (firstUserMsg.content.length > 60) "$it…" else it
        }
        val entity = repo.createConversation(
            serverHost = server.host,
            serverPort = server.port,
            modelName = currentModel,
            title = title
        )
        currentConversationId = entity.id
        // Persist the model choice as lastUsed so the next "New Chat" uses it directly.
        serverRepo.saveLastUsed(server.host, server.port, currentModel)
        return entity.id
    }

    private fun buildOllamaHistory(): List<OllamaChatMessage> =
        _messages.value
            .filter { !it.isStreaming }
            .map { msg ->
                OllamaChatMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content
                )
            }

    private fun updateStreamingMessage(id: String, content: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) msg.copy(content = content) else msg
        }
    }

    private fun finalizeMessage(id: String, content: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) msg.copy(content = content, isStreaming = false) else msg
        }
    }
}
