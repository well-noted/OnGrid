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
import com.ongrid.app.data.model.OllamaRequestOptions
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.data.model.OllamaTool
import com.ongrid.app.data.model.OllamaToolCall
import com.ongrid.app.data.repository.DEFAULT_SYSTEM_PROMPT
import kotlinx.coroutines.flow.combine
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
    val availableTools: List<OllamaTool> = emptyList(),
    val disabledToolNames: Set<String> = emptySet(),
    /** True when /api/show reports that the current model has the "thinking" capability. */
    val supportsThinking: Boolean = false,
    /** Whether extended reasoning is currently enabled for outgoing requests. */
    val thinkingEnabled: Boolean = false,
    /** Token budget for thinking (0 = let the model decide). */
    val thinkingBudget: Int = 8192,
    /** Live thinking text accumulating while the model reasons during the current turn. */
    val streamingThinkingContent: String = "",
    /** System prompt sent at the top of every request. */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** When true, a planning instruction is appended to the system prompt. */
    val agentPlanningEnabled: Boolean = true
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication
    private val repo = app.conversationRepository
    private val serverRepo = app.serverRepository
    private val ollamaRepo = app.ollamaRepository

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sendJob: kotlinx.coroutines.Job? = null

    var currentServer: OllamaServer? = null
    var currentModel: String = ""

    /** Non-null once a conversation has been created or loaded. */
    var currentConversationId: String? = null
        private set

    init {
        viewModelScope.launch {
            app.settingsRepository.systemPrompt
                .combine(app.settingsRepository.agentPlanningEnabled) { prompt, planning ->
                    prompt to planning
                }
                .collect { (prompt, planning) ->
                    _uiState.value = _uiState.value.copy(
                        systemPrompt = prompt,
                        agentPlanningEnabled = planning
                    )
                }
        }
    }

    /** True until the first assistant response has been fully persisted. */
    private var titleGenerated: Boolean = false

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
        titleGenerated = false
        _messages.value = emptyList()
        _uiState.value = _uiState.value.copy(
            disabledToolNames = emptySet(),
            supportsThinking = false,
            thinkingEnabled = false,
            streamingThinkingContent = ""
        )
        checkThinkingSupport(server.baseUrl, modelName)
    }

    /** Called when the user reopens an existing conversation from the list. */
    fun resumeConversation(conversationId: String) {
        viewModelScope.launch {
            currentConversationId = conversationId
            titleGenerated = true  // existing conversations already have a title
            val entity = repo.getConversation(conversationId) ?: return@launch
            currentServer = OllamaServer(host = entity.serverHost, port = entity.serverPort)
            currentModel = entity.modelName
            _messages.value = repo.getMessages(conversationId)
            loadTools()
            checkThinkingSupport(currentServer!!.baseUrl, currentModel)
        }
    }

    /**
     * Switch models mid-session (from the in-chat model picker).
     * Changes take effect from the next message sent.
     */
    fun changeModel(serverHost: String, serverPort: Int, modelName: String) {
        currentServer = OllamaServer(host = serverHost, port = serverPort)
        currentModel = modelName
        _uiState.value = _uiState.value.copy(
            supportsThinking = false,
            thinkingEnabled = false,
            streamingThinkingContent = ""
        )
        viewModelScope.launch {
            serverRepo.saveLastUsed(serverHost, serverPort, modelName)
            checkThinkingSupport("http://$serverHost:$serverPort", modelName)
        }
    }

    // ── Thinking controls ─────────────────────────────────────────────────────

    /** Toggle extended reasoning on/off. Only relevant when [ChatUiState.supportsThinking] is true. */
    fun toggleThinking() {
        _uiState.value = _uiState.value.copy(thinkingEnabled = !_uiState.value.thinkingEnabled)
    }

    /** Set the token budget for extended reasoning (0 = let the model decide). */
    fun setThinkingBudget(tokens: Int) {
        _uiState.value = _uiState.value.copy(thinkingBudget = tokens.coerceIn(0, 32768))
    }

    /** Query /api/show and update [ChatUiState.supportsThinking] accordingly. */
    private fun checkThinkingSupport(baseUrl: String, modelName: String) {
        viewModelScope.launch {
            val supports = ollamaRepo.checkThinkingSupport(baseUrl, modelName)
            _uiState.value = _uiState.value.copy(
                supportsThinking = supports,
                // Disable thinking if the new model doesn't support it
                thinkingEnabled = if (supports) _uiState.value.thinkingEnabled else false
            )
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

    /** Toggle the agent planning instruction on/off and persist the new value. */
    fun toggleAgentPlanning() {
        val newVal = !_uiState.value.agentPlanningEnabled
        _uiState.value = _uiState.value.copy(agentPlanningEnabled = newVal)
        viewModelScope.launch { app.settingsRepository.saveAgentPlanningEnabled(newVal) }
    }

    /** Toggle a specific tool on/off for the current conversation. */
    fun toggleTool(toolName: String) {
        val current = _uiState.value.disabledToolNames
        _uiState.value = _uiState.value.copy(
            disabledToolNames = if (toolName in current) current - toolName else current + toolName
        )
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
        val disabled = _uiState.value.disabledToolNames
        val tools = _uiState.value.availableTools
            .filter { it.function.name !in disabled }
            .takeIf { it.isNotEmpty() }
        val thinkingEnabled = _uiState.value.thinkingEnabled && _uiState.value.supportsThinking
        val thinkingBudget = _uiState.value.thinkingBudget
        val request = OllamaChatRequest(
            model = currentModel,
            messages = history,
            stream = true,
            tools = tools,
            think = if (_uiState.value.supportsThinking) thinkingEnabled else null,
            options = if (thinkingEnabled && thinkingBudget > 0)
                OllamaRequestOptions(thinkingBudget = thinkingBudget)
            else null
        )

        // Drain any leftover events from a previous (completed or cancelled) request.
        while (true) { app.chatServiceChannel.tryReceive().getOrNull() ?: break }

        app.pendingChatRequest = PendingChatRequest(
            assistantMsgId,
            server.baseUrl,
            request,
            guidedPlanningEnabled = _uiState.value.agentPlanningEnabled && tools != null
        )
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // *** Must be called here — synchronously on the main thread — before we ever suspend. ***
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), ChatForegroundService::class.java)
        )

        sendJob = viewModelScope.launch {
            // Ensure a conversation entity exists; auto-title from the first user message.
            val convId = ensureConversation(server, userMsg)
            repo.saveMessage(convId, userMsg)

            for (event in app.chatServiceChannel) {
                when (event) {
                    is ChatServiceEvent.Token ->
                        updateStreamingMessage(event.msgId, event.content)

                    is ChatServiceEvent.ThinkingToken ->
                        _uiState.value = _uiState.value.copy(
                            streamingThinkingContent = event.thinking
                        )

                    is ChatServiceEvent.SetPlan -> {
                        _messages.value = _messages.value.map { msg ->
                            if (msg.id == event.msgId) msg.copy(isPlan = true) else msg
                        }
                    }

                    is ChatServiceEvent.SetPlanSteps -> {
                        _messages.value = _messages.value.map { msg ->
                            if (msg.id == event.msgId) msg.copy(planSteps = event.steps) else msg
                        }
                    }

                    is ChatServiceEvent.FinalizeMessage ->
                        if (event.content.isBlank() && event.toolCalls.isEmpty()) {
                            _messages.value = _messages.value.filter { it.id != event.msgId }
                        } else {
                            finalizeMessage(event.msgId, event.content, event.toolCalls)
                        }

                    is ChatServiceEvent.AppendMessage -> {
                        _messages.value = _messages.value + event.message
                        repo.saveMessage(convId, event.message)
                    }

                    is ChatServiceEvent.TurnComplete -> {
                        val thinkingContent = event.thinkingContent
                        if (event.error != null) {
                            finalizeMessage(event.msgId, "Error: ${event.error}")
                            _uiState.value = _uiState.value.copy(error = event.error)
                        } else {
                            finalizeMessageWithThinking(event.msgId, event.lastContent, thinkingContent)
                        }
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            streamingThinkingContent = ""
                        )

                        // Persist the finalized assistant message.
                        val finalMsg = _messages.value.find { it.id == event.msgId }
                        if (finalMsg != null) repo.saveMessage(convId, finalMsg)
                        repo.touchConversation(convId)

                        // Generate a title from the first user message after the first turn.
                        if (!titleGenerated) {
                            titleGenerated = true
                            val server = currentServer
                            val firstUserContent = userMsg.content
                            if (server != null) {
                                val generated = ollamaRepo.generateTitle(
                                    server.baseUrl, currentModel, firstUserContent
                                )
                                if (!generated.isNullOrBlank()) {
                                    repo.updateTitle(convId, generated)
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * Abort the current in-flight response. Stops the foreground service (which cancels
     * its streaming coroutine) and cancels the ViewModel's channel-consumer job.
     */
    fun stopGeneration() {
        getApplication<Application>().stopService(
            Intent(getApplication(), ChatForegroundService::class.java)
        )
        sendJob?.cancel()
        sendJob = null
        // Finalize any streaming bubble that's still blinking
        _messages.value = _messages.value.map { msg ->
            if (msg.isStreaming) msg.copy(isStreaming = false) else msg
        }
        _uiState.value = _uiState.value.copy(isLoading = false, streamingThinkingContent = "")
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

    private fun buildOllamaHistory(): List<OllamaChatMessage> {
        val history = mutableListOf<OllamaChatMessage>()
        val systemText = buildSystemPrompt()
        if (systemText.isNotBlank()) {
            history += OllamaChatMessage(role = "system", content = systemText)
        }
        _messages.value
            .filter { !it.isStreaming }
            .mapTo(history) { msg ->
                OllamaChatMessage(
                    role = msg.role.name.lowercase(),
                    content = if (msg.ollamaToolCalls.isNotEmpty()) msg.content.ifEmpty { null } else msg.content,
                    tool_calls = msg.ollamaToolCalls.takeIf { it.isNotEmpty() }
                )
            }
        return history
    }

    private fun buildSystemPrompt(): String {
        val base = _uiState.value.systemPrompt.trim()
        return if (_uiState.value.agentPlanningEnabled) {
            base + "\n\nWhen asked to perform a task that requires multiple steps or tool calls, " +
                "begin by outputting a brief numbered plan before taking any action. " +
                "After each step completes, briefly confirm the result before continuing to the next step."
        } else {
            base
        }
    }

    private fun updateStreamingMessage(id: String, content: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) msg.copy(content = content) else msg
        }
    }

    private fun finalizeMessage(id: String, content: String, ollamaToolCalls: List<OllamaToolCall> = emptyList()) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) msg.copy(content = content, isStreaming = false, ollamaToolCalls = ollamaToolCalls) else msg
        }
    }

    private fun finalizeMessageWithThinking(id: String, content: String, thinking: String?) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) msg.copy(
                content = content,
                isStreaming = false,
                thinkingContent = thinking
            ) else msg
        }
    }
}
