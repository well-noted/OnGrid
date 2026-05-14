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
import com.ongrid.app.data.local.SkillEntity
import com.ongrid.app.data.local.ProjectMemoryEntity
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentMemoryEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableTools: List<OllamaTool> = emptyList(),
    val disabledToolNames: Set<String> = emptySet(),
    /** True when the current model advertises the "thinking" capability. */
    val supportsThinking: Boolean = false,
    /** True when the current model advertises the "tools" capability. */
    val supportsTools: Boolean = false,
    /** Whether extended reasoning is enabled for this conversation. */
    val thinkingEnabled: Boolean = false,
    /** True once a ThinkingToken arrived this turn (for live banner display only). */
    val lastTurnUsedThinking: Boolean = false,
    /** Thinking token budget sent to the model when thinking is enabled (0–32768). */
    val thinkingBudget: Int = 8192,
    /** Accumulated reasoning content streamed by the current (in-progress) turn. */
    val streamingThinkingContent: String = "",
    /** Maximum context length the model supports (null if unknown). */
    val modelContextLength: Int? = null,
    /** Total tokens used in the last completed turn (prompt + generated). */
    val tokensUsedLastTurn: Int = 0,
    /** All skills available to activate in this session. */
    val availableSkills: List<SkillEntity> = emptyList(),
    /** IDs of skills currently injected into the conversation. */
    val activeSkillIds: Set<String> = emptySet(),
    /** True when the skill picker sheet should be shown. */
    val showSkillPicker: Boolean = false,
    /** Non-null when the utility agent suggests a skill for the current message. */
    val suggestedSkillName: String? = null,
    /** IDs of conversations that seem similar to the current one (project grouping suggestion). */
    val similarConversationIds: List<String> = emptyList(),
    /** True when token usage exceeds 80% of context length. */
    val showCompressionButton: Boolean = false,
    /** The project currently associated with this conversation. */
    val currentProjectId: String? = null,
    /** Memories loaded from the current project. */
    val projectMemories: List<ProjectMemoryEntity> = emptyList(),
    /** The agent currently associated with this conversation. */
    val currentAgentId: String? = null,
    /** The loaded agent entity. */
    val currentAgent: AgentEntity? = null,
    /** Memories loaded from the current agent. */
    val agentMemories: List<AgentMemoryEntity> = emptyList(),
    /** Resolved utility server base URL (falls back to conversation server). */
    val utilityBaseUrl: String = "",
    /** Resolved utility model name (falls back to conversation model). */
    val utilityModelName: String = "",
    /** Pre-filled text to populate the chat input (set when app is launched via share or shortcut). */
    val prefillText: String = "",
    /**
     * Semantic recall snippets retrieved from past conversations for the current agent.
     * Injected into context by [buildOllamaHistory] and cleared after each turn.
     */
    val semanticRecallSnippets: List<String> = emptyList(),
    /**
     * Pre-built recent-context block (conversations from the last 48 h) for the current agent.
     * Built in [loadAgent] and injected into context by [buildOllamaHistory].
     */
    val recentContextSnippet: String = "",
    /** Non-null while the user is typing an @mention; drives the agent picker popup. */
    val agentMentionQuery: String? = null,
    /** Agents shown in the @mention popup, filtered by the current query. */
    val mentionableAgents: List<AgentEntity> = emptyList(),
    /** True when the current conversation is an agent-to-agent handoff. */
    val isAgentHandoff: Boolean = false,
    /** Participant agent IDs for the handoff conversation (agent1, agent2). */
    val handoffParticipantIds: List<String> = emptyList(),
    /** Goal text for the handoff conversation (shown in the header). */
    val handoffGoal: String = ""
) {
    /** True when extended reasoning is enabled for this conversation. */
    val isThinkingOn: Boolean
        get() = thinkingEnabled
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication
    private val repo = app.conversationRepository
    private val serverRepo = app.serverRepository
    private val ollamaRepo = app.ollamaRepository
    private val utilityAgentRepo = app.utilityAgentRepository
    private val settingsRepo = app.settingsRepository
    private val agentRepo = app.agentRepository
    private val embeddingRepo = app.embeddingRepository

    /** All active agents — drives the @mention popup and the initiate_agent_convo tool. */
    val activeAgents: StateFlow<List<AgentEntity>> =
        agentRepo.activeAgents().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sendJob: kotlinx.coroutines.Job? = null
    /** Active only while viewing an AGENT_HANDOFF conversation — pushes DB writes to _messages. */
    private var handoffObserverJob: kotlinx.coroutines.Job? = null

    var currentServer: OllamaServer? = null
    var currentModel: String = ""

    /** Non-null once a conversation has been created or loaded. */
    var currentConversationId: String? = null
        private set

    /** True until the first assistant response has been fully persisted. */
    private var titleGenerated: Boolean = false

    init {
        // Keep availableSkills in uiState in sync with the database.
        viewModelScope.launch {
            app.skillRepository.allSkills.collect { skills ->
                _uiState.value = _uiState.value.copy(availableSkills = skills)
            }
        }
    }

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

    /** All projects — used by the project grouping sheet in ChatScreen. */
    val allProjects: StateFlow<List<com.ongrid.app.data.local.ProjectEntity>> =
        repo.allProjects.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Latest utility agent settings — cached so sendMessage can read without suspending. */
    private val currentSettings: StateFlow<com.ongrid.app.data.repository.UtilitySettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, com.ongrid.app.data.repository.UtilitySettings())

    // ── Initialisation ────────────────────────────────────────────────────────

    /** Called when the user starts a brand-new chat after picking a server/model. */
    fun initNewConversation(server: OllamaServer, modelName: String) {
        currentServer = server
        currentModel = modelName
        currentConversationId = null
        titleGenerated = false
        _messages.value = emptyList()
        handoffObserverJob?.cancel()
        handoffObserverJob = null
        _uiState.value = _uiState.value.copy(
            isAgentHandoff = false,
            handoffParticipantIds = emptyList(),
            handoffGoal = "",
            disabledToolNames = emptySet(),
            supportsThinking = false,
            thinkingEnabled = false,
            lastTurnUsedThinking = false,
            streamingThinkingContent = "",
            modelContextLength = null,
            tokensUsedLastTurn = 0,
            currentProjectId = null,
            projectMemories = emptyList(),
            currentAgentId = null,
            currentAgent = null,
            agentMemories = emptyList(),
            suggestedSkillName = null,
            similarConversationIds = emptyList(),
            showCompressionButton = false
        )
        // Apply the global default; DataStore is fast after first read
        viewModelScope.launch {
            val defaultOn = serverRepo.defaultThinkingOn.first()
            _uiState.value = _uiState.value.copy(thinkingEnabled = defaultOn)
        }
        viewModelScope.launch { resolveUtilityModel() }
        checkThinkingSupport(server.baseUrl, modelName)
    }

    /** Called when the user reopens an existing conversation from the list. */
    fun resumeConversation(conversationId: String) {
        // If a generation is in flight for a *different* conversation, stop it before switching.
        // Without this, the active sendJob keeps consuming from the shared chatServiceChannel and
        // writes ThinkingToken / Token events into the UI state that is now showing the new convo.
        if (currentConversationId != conversationId) {
            sendJob?.cancel()
            sendJob = null
            getApplication<Application>().stopService(
                Intent(getApplication(), ChatForegroundService::class.java)
            )
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                streamingThinkingContent = ""
            )
        }
        viewModelScope.launch {
            val alreadyActiveAndLoading =
                currentConversationId == conversationId && _uiState.value.isLoading
            currentConversationId = conversationId
            titleGenerated = true  // existing conversations already have a title
            val entity = repo.getConversation(conversationId) ?: return@launch
            currentServer = OllamaServer(host = entity.serverHost, port = entity.serverPort)
            currentModel = entity.modelName
            // Skip reloading from DB if this conversation is already live and streaming —
            // the in-memory streaming bubble hasn't been saved yet and would be lost,
            // turning the blinking cursor into a spinner on return.
            if (!alreadyActiveAndLoading) {
                _messages.value = repo.getMessages(conversationId)
            }
            // For AGENT_HANDOFF conversations, keep _messages live so worker output appears.
            handoffObserverJob?.cancel()
            val isHandoff = entity.conversationType == "AGENT_HANDOFF"
            if (isHandoff) {
                handoffObserverJob = viewModelScope.launch {
                    // AGENT_HANDOFF conversations are driven entirely by the background worker;
                    // there is no streaming bubble to protect, so always apply DB updates.
                    repo.observeMessages(conversationId).collect { msgs ->
                        _messages.value = msgs
                    }
                }
            }
            val participantIds: List<String> = if (isHandoff) {
                try {
                    com.google.gson.Gson().fromJson(
                        entity.participantAgentIds,
                        object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                    )
                } catch (e: Exception) { emptyList() }
            } else emptyList()
            val hadThinking = _messages.value.any { it.thinkingContent != null }
            _uiState.value = _uiState.value.copy(
                thinkingEnabled = entity.thinkingEnabled,
                lastTurnUsedThinking = hadThinking,
                currentProjectId = entity.projectId,
                isAgentHandoff = isHandoff,
                handoffParticipantIds = participantIds,
                handoffGoal = if (isHandoff) entity.goal else ""
            )
            // Load project memories if there's an associated project
            entity.projectId?.let { loadProjectMemories(it) }
            // Load agent if there's an associated agent
            entity.agentId?.let { loadAgent(it) }
            resolveUtilityModel()
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
            lastTurnUsedThinking = false,
            modelContextLength = null,
            tokensUsedLastTurn = 0
        )
        checkThinkingSupport(currentServer!!.baseUrl, modelName)
        viewModelScope.launch {
            serverRepo.saveLastUsed(serverHost, serverPort, modelName)
        }
    }

    /** Load available MCP tools plus built-in tools for the current session. */
    fun loadTools() {
        viewModelScope.launch { refreshToolsList() }
    }

    /**
     * Rebuilds the available-tools list and updates [_uiState].
     * Must be called from a coroutine (suspend).  Adds `form_memory` only when an agent
     * workspace is active so the tool is invisible outside of that context.
     */
    private suspend fun refreshToolsList() {
        val toolMap = app.mcpRepository.getAllEnabledTools()
        val mcpTools = toolMap.values.map { (_, mcpTool) -> mcpTool.toOllamaTool() }
        val builtInTools = buildList {
            add(app.webSearchRepository.tool.toOllamaTool())
            add(app.webFetchRepository.tool.toOllamaTool())
            if (_uiState.value.currentAgentId != null) {
                add(app.formMemoryRepository.tool.toOllamaTool())
                add(app.skillActivationRepository.tool.toOllamaTool())
                val currentAgentId = _uiState.value.currentAgentId!!
                val agentConvoTool = app.agentConversationRepository.buildTool(currentAgentId, activeAgents.value)
                if (agentConvoTool != null) add(agentConvoTool)
            }
        }
        _uiState.value = _uiState.value.copy(availableTools = builtInTools + mcpTools)
    }

    /** Toggle a specific tool on/off for the current conversation. */
    fun toggleTool(toolName: String) {
        val current = _uiState.value.disabledToolNames
        _uiState.value = _uiState.value.copy(
            disabledToolNames = if (toolName in current) current - toolName else current + toolName
        )
    }

    /** Toggle extended reasoning on/off and persist the preference for this conversation. */
    fun toggleThinking() {
        val newValue = !_uiState.value.thinkingEnabled
        _uiState.value = _uiState.value.copy(thinkingEnabled = newValue)
        currentConversationId?.let { convId ->
            viewModelScope.launch { repo.updateThinkingEnabled(convId, newValue) }
        }
    }

    /** Set the thinking token budget (clamped to 0..32768). */
    fun setThinkingBudget(tokens: Int) {
        _uiState.value = _uiState.value.copy(
            thinkingBudget = tokens.coerceIn(0, 32768)
        )
    }

    // ── @mention agent picker ─────────────────────────────────────────────────

    /** Called on every input change; detects `@` to open/update the agent mention picker. */
    fun onInputTextChanged(text: String) {
        val atIndex = text.lastIndexOf('@')
        if (atIndex >= 0) {
            val query = text.substring(atIndex + 1)
            if (!query.contains(' ')) {
                val others = activeAgents.value.filter { it.id != _uiState.value.currentAgentId }
                val filtered = if (query.isBlank()) others
                    else others.filter { it.name.startsWith(query, ignoreCase = true) }
                _uiState.value = _uiState.value.copy(
                    agentMentionQuery = query,
                    mentionableAgents = filtered
                )
                return
            }
        }
        if (_uiState.value.agentMentionQuery != null) {
            _uiState.value = _uiState.value.copy(agentMentionQuery = null, mentionableAgents = emptyList())
        }
    }

    fun dismissAgentMentionPicker() {
        _uiState.value = _uiState.value.copy(agentMentionQuery = null, mentionableAgents = emptyList())
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
        val tools = if (_uiState.value.supportsTools)
            _uiState.value.availableTools
                .filter { it.function.name !in disabled }
                .takeIf { it.isNotEmpty() }
        else null
        val contextLength = _uiState.value.modelContextLength
        // Always provide a num_ctx floor so Ollama never silently falls back to its 2048-token
        // default. Without this, the second streaming turn (after tool results are appended to
        // the history) can overflow the 2048-token window and the model returns empty content.
        val safeContextLength = contextLength ?: 32768
        val request = OllamaChatRequest(
            model = currentModel,
            messages = history,
            stream = true,
            tools = tools,
            // null think = model decides; true/false = explicit override
            think = if (_uiState.value.supportsThinking) _uiState.value.thinkingEnabled else null,
            options = if (_uiState.value.supportsThinking && _uiState.value.thinkingEnabled)
                OllamaRequestOptions(thinkingBudget = _uiState.value.thinkingBudget, numCtx = safeContextLength)
            else
                OllamaRequestOptions(numCtx = safeContextLength)
        )

        // Drain any leftover events from a previous (completed or cancelled) request.
        while (true) { app.chatServiceChannel.tryReceive().getOrNull() ?: break }

        // Build the available-skill map for the agent (skills assigned to agent but not yet active)
        val agentSkillMap: Map<String, Pair<String, String>> = run {
            val agent = _uiState.value.currentAgent ?: return@run emptyMap()
            val activeIds = _uiState.value.activeSkillIds
            val assignedIds = agentRepo.parseSkillIds(agent.defaultSkillIds).toSet()
            _uiState.value.availableSkills
                .filter { it.id in assignedIds && it.id !in activeIds }
                .associate { it.name to (it.id to it.content) }
        }

        app.pendingChatRequest = PendingChatRequest(
            assistantMsgId = assistantMsgId,
            baseUrl = server.baseUrl,
            request = request,
            agentId = _uiState.value.currentAgentId,
            agentName = _uiState.value.currentAgent?.name,
            agentMood = _uiState.value.currentAgent
                ?.takeIf { it.isMoodTrackingEnabled }
                ?.currentMood,
            conversationId = currentConversationId,
            availableSkillMap = agentSkillMap
        )
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // *** Must be called here — synchronously on the main thread — before we ever suspend. ***
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), ChatForegroundService::class.java)
        )

        // Fire-and-forget utility agent calls for the first message (skill suggestion + similarity)
        val utilityBaseUrl = _uiState.value.utilityBaseUrl
        val utilityModel = _uiState.value.utilityModelName
        val settings = currentSettings.value
        if (settings.utilityAgentEnabled) {
            if (settings.skillSuggestionEnabled && _uiState.value.currentAgentId == null) {
                viewModelScope.launch {
                    val activeIds = _uiState.value.activeSkillIds
                    val skillNames = _uiState.value.availableSkills
                        .filter { it.id !in activeIds }
                        .map { it.name }
                    val suggestion = utilityAgentRepo.suggestSkill(utilityBaseUrl, utilityModel, text, skillNames)
                    if (suggestion != null) {
                        _uiState.value = _uiState.value.copy(suggestedSkillName = suggestion)
                    }
                }
            }
            if (settings.conversationSimilarityEnabled && _uiState.value.currentAgentId == null) {
                viewModelScope.launch {
                    val conversations = repo.allConversations.first()
                        .filter { it.id != currentConversationId }
                        .take(20)
                        .map { it.id to it.title }
                    val similar = utilityAgentRepo.findSimilarConversations(utilityBaseUrl, utilityModel, text, conversations)
                    if (similar.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(similarConversationIds = similar)
                    }
                }
            }
        }

        sendJob = viewModelScope.launch {
            // Ensure a conversation entity exists; auto-title from the first user message.
            val convId = ensureConversation(server, userMsg)
            repo.saveMessage(convId, userMsg)

            // Guards a single auto-retry when the server returns an empty turn (silent drop).
            var hasRetried = false
            // True once the server sends any tokens, tool calls, or tool results — distinguishes
            // a true silent drop (nothing at all) from a legitimate empty follow-up after tools.
            var hadAnyServerActivity = false

            for (event in app.chatServiceChannel) {
                when (event) {
                    is ChatServiceEvent.Token -> {
                        hadAnyServerActivity = true
                        updateStreamingMessage(event.msgId, event.content)
                    }

                    is ChatServiceEvent.ThinkingToken -> {
                        hadAnyServerActivity = true
                        _uiState.value = _uiState.value.copy(
                            streamingThinkingContent = event.thinking,
                            // Auto-sync the button to ON so the user can see thinking is active
                            // and has the option to disable it for the next turn.
                            lastTurnUsedThinking = true
                        )
                    }

                    is ChatServiceEvent.FinalizeMessage -> {
                        hadAnyServerActivity = true
                        if (event.content.isBlank()) {
                            _messages.value = _messages.value.filter { it.id != event.msgId }
                        } else {
                            finalizeMessage(event.msgId, event.content)
                        }
                    }

                    is ChatServiceEvent.AppendMessage -> {
                        hadAnyServerActivity = true
                        _messages.value = _messages.value + event.message
                        repo.saveMessage(convId, event.message)
                    }

                    is ChatServiceEvent.MemoryFormed -> {
                        // Reload the agent's memory list so the new pinned entry is visible
                        // immediately (e.g. in the agent memory sheet).
                        loadAgent(event.agentId)
                    }

                    is ChatServiceEvent.SkillActivated -> {
                        // Mark the skill as active in the conversation so future turns include it.
                        val skill = _uiState.value.availableSkills.find { it.id == event.skillId }
                        if (skill != null) activateSkill(skill)
                    }

                    is ChatServiceEvent.TokenUsage ->
                        _uiState.value = _uiState.value.copy(
                            tokensUsedLastTurn = event.promptTokens + event.generatedTokens,
                            showCompressionButton = run {
                                val ctxLen = _uiState.value.modelContextLength
                                if (ctxLen != null && ctxLen > 0)
                                    (event.promptTokens + event.generatedTokens).toFloat() / ctxLen >= 0.8f
                                else false
                            }
                        )

                    is ChatServiceEvent.TurnComplete -> {
                        if (event.error != null) {
                            finalizeMessage(event.msgId, "Error: ${event.error}")
                            _uiState.value = _uiState.value.copy(error = event.error)
                        } else {
                            finalizeMessageWithThinking(
                                event.msgId,
                                event.lastContent,
                                event.thinkingContent
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            streamingThinkingContent = ""
                        )

                        // Persist the finalized assistant message.
                        val finalMsg = _messages.value.find { it.id == event.msgId }

                        // Silent-drop retry: the server completed the turn but returned no content
                        // and no error, and sent no tokens or tool activity at all.
                        // Only retry on a true cold silent drop — not when the model legitimately
                        // returns empty text after using tools.
                        if (event.error == null && finalMsg?.content.isNullOrBlank()
                            && !hadAnyServerActivity && !hasRetried) {
                            hasRetried = true
                            _messages.value = _messages.value
                                .filter { it.id != event.msgId && it.id != userMsg.id }
                            viewModelScope.launch { sendMessage(userMsg.content) }
                            break
                        }

                        if (finalMsg != null) repo.saveMessage(convId, finalMsg)
                        repo.touchConversation(convId)

                        // Post-turn utility agent tasks (fire-and-forget)
                        val postUtilityBase = _uiState.value.utilityBaseUrl
                        val postUtilityModel = _uiState.value.utilityModelName
                        val postSettings = settingsRepo.settings.first()
                        if (postSettings.utilityAgentEnabled) {
                            val assistantSnippet = finalMsg?.content?.take(200) ?: ""
                            val exchange = buildString {
                                append("User: ${userMsg.content.take(400)}")
                                if (assistantSnippet.isNotBlank()) {
                                    append("\nAssistant: $assistantSnippet")
                                }
                            }

                            // Title generation (replaces the old ollamaRepo.generateTitle path)
                            if (!titleGenerated && postSettings.titleGenerationEnabled) {
                                titleGenerated = true
                                viewModelScope.launch {
                                    val generated = utilityAgentRepo.generateTitle(
                                        postUtilityBase, postUtilityModel,
                                        userMsg.content, assistantSnippet
                                    )
                                    if (!generated.isNullOrBlank()) {
                                        repo.updateTitle(convId, generated)
                                    }
                                }
                            } else if (!titleGenerated) {
                                // Fallback: use original title logic
                                titleGenerated = true
                                val server = currentServer
                                val firstUserContent = userMsg.content
                                if (server != null) {
                                    viewModelScope.launch {
                                        val generated = ollamaRepo.generateTitle(
                                            server.baseUrl, currentModel, firstUserContent
                                        )
                                        if (!generated.isNullOrBlank()) {
                                            repo.updateTitle(convId, generated)
                                        }
                                    }
                                }
                            }

                            // Auto-tagging
                            if (postSettings.autoTaggingEnabled) {
                                viewModelScope.launch {
                                    val tags = utilityAgentRepo.generateTags(
                                        postUtilityBase, postUtilityModel, exchange
                                    )
                                    if (tags.isNotEmpty()) {
                                        app.database.conversationDao().updateTags(convId, tags.joinToString(","))
                                    }
                                }
                            }

                            // Project memory extraction
                            val projectId = _uiState.value.currentProjectId
                            if (postSettings.projectMemoryEnabled && projectId != null) {
                                viewModelScope.launch {
                                    val memories = utilityAgentRepo.extractMemories(
                                        postUtilityBase, postUtilityModel, exchange
                                    )
                                    memories.forEach { fact ->
                                        app.database.projectMemoryDao().insert(
                                            com.ongrid.app.data.local.ProjectMemoryEntity(
                                                projectId = projectId,
                                                content = fact,
                                                sourceConversationId = convId
                                            )
                                        )
                                    }
                                    if (memories.isNotEmpty()) {
                                        loadProjectMemories(projectId)
                                    }
                                }
                            }

                            // Agent brief update, memory extraction, and mood tracking
                            val currentAgentId = _uiState.value.currentAgentId
                            val currentAgent = _uiState.value.currentAgent
                            if (currentAgentId != null && currentAgent != null) {
                                val agentExchange = exchange
                                val agentRole = currentAgent.role
                                val agentPrompt = currentAgent.systemPrompt
                                val currentBrief = currentAgent.brief
                                val existingMemories = _uiState.value.agentMemories
                                    .map { it.content }
                                // Richer exchange for mood — last 3 turns + tail of current response
                                // (agent may express feelings at the END of a long message)
                                val moodExchange = buildString {
                                    // Include up to 3 prior turns for emotional context
                                    val allMsgs = _messages.value
                                    val priorTurns = allMsgs.dropLast(1)
                                        .takeLast(6)
                                        .filter { it.role == com.ongrid.app.data.model.MessageRole.USER || it.role == com.ongrid.app.data.model.MessageRole.ASSISTANT }
                                    priorTurns.forEach { m ->
                                        val label = if (m.role == com.ongrid.app.data.model.MessageRole.USER) "User" else "Assistant"
                                        append("$label: ${m.content.take(300)}\n")
                                    }
                                    // Current turn: full user message + TAIL of assistant response
                                    // so explicit mood statements at the end aren't truncated
                                    append("User: ${userMsg.content.take(400)}\n")
                                    val assistantFull = finalMsg?.content ?: ""
                                    val assistantExcerpt = if (assistantFull.length > 900)
                                        assistantFull.take(450) + "…" + assistantFull.takeLast(450)
                                    else assistantFull
                                    if (assistantExcerpt.isNotBlank()) append("Assistant: $assistantExcerpt")
                                }

                                // Run brief update, memory extraction, and mood in parallel
                                viewModelScope.launch {
                                    val briefDeferred = if (currentAgent.isAutoBriefEnabled) {
                                        viewModelScope.async {
                                            utilityAgentRepo.updateAgentBrief(
                                                postUtilityBase, postUtilityModel,
                                                currentBrief, agentRole, agentPrompt, agentExchange
                                            )
                                        }
                                    } else null

                                    val memoriesDeferred = viewModelScope.async {
                                        utilityAgentRepo.extractAgentMemories(
                                            postUtilityBase, postUtilityModel,
                                            agentRole, agentExchange, existingMemories
                                        )
                                    }

                                    val moodDeferred = if (currentAgent.isMoodTrackingEnabled) {
                                        viewModelScope.async {
                                            utilityAgentRepo.calculateMood(
                                                postUtilityBase, postUtilityModel, moodExchange
                                            )
                                        }
                                    } else null

                                    val newBrief = briefDeferred?.await()
                                    val newMemories = memoriesDeferred.await()
                                    val newMood = moodDeferred?.await()

                                    if (!newBrief.isNullOrBlank()) {
                                        agentRepo.updateBrief(currentAgentId, newBrief)
                                    }
                                    newMemories.forEach { fact ->
                                        agentRepo.insertMemory(
                                            com.ongrid.app.data.local.AgentMemoryEntity(
                                                agentId = currentAgentId,
                                                content = fact,
                                                sourceConversationId = convId
                                            )
                                        )
                                    }
                                    if (!newMood.isNullOrBlank() && newMood != currentAgent.currentMood) {
                                        agentRepo.updateMood(currentAgentId, newMood)
                                    }
                                    if (!newBrief.isNullOrBlank() || newMemories.isNotEmpty() || !newMood.isNullOrBlank()) {
                                        loadAgent(currentAgentId)
                                    }
                                }
                            }
                        } else if (!titleGenerated) {
                            // Utility agent disabled — fall back to original title logic
                            titleGenerated = true
                            val server = currentServer
                            val firstUserContent = userMsg.content
                            if (server != null) {
                                viewModelScope.launch {
                                    val generated = ollamaRepo.generateTitle(
                                        server.baseUrl, currentModel, firstUserContent
                                    )
                                    if (!generated.isNullOrBlank()) {
                                        repo.updateTitle(convId, generated)
                                    }
                                }
                            }
                        }

                        // Index conversation for semantic recall (fire-and-forget)
                        val postAgent = _uiState.value.currentAgent
                        if (postAgent?.isSemanticRecallEnabled == true) {
                            indexCompletedConversation(convId, postAgent.id)
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
        _uiState.value = _uiState.value.copy(activeSkillIds = emptySet())
        val convId = currentConversationId ?: return
        viewModelScope.launch { repo.clearMessages(convId) }
    }

    // ── Skill management ──────────────────────────────────────────────────────

    /** Called when the user types '/' at the start of the input field. */
    fun showSkillPicker() {
        _uiState.value = _uiState.value.copy(showSkillPicker = true)
    }

    fun dismissSkillPicker() {
        _uiState.value = _uiState.value.copy(showSkillPicker = false)
    }

    /** Activate a skill: inject its content into the conversation as a system message. */
    fun activateSkill(skill: SkillEntity) {
        if (skill.id in _uiState.value.activeSkillIds) return
        val skillMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            content = skill.content,
            isSkill = true,
            skillName = skill.name
        )
        _messages.value = _messages.value + skillMsg
        _uiState.value = _uiState.value.copy(
            activeSkillIds = _uiState.value.activeSkillIds + skill.id,
            showSkillPicker = false
        )
    }

    /** Remove an active skill from the conversation. */
    fun deactivateSkill(skillId: String) {
        val skillName = _uiState.value.availableSkills.find { s -> s.id == skillId }?.name
        _messages.value = _messages.value.filter { !(it.isSkill && it.skillName == skillName) }
        _uiState.value = _uiState.value.copy(
            activeSkillIds = _uiState.value.activeSkillIds - skillId
        )
    }

    /** Dismiss the utility-agent skill suggestion without activating it. */
    fun dismissSuggestedSkill() {
        _uiState.value = _uiState.value.copy(suggestedSkillName = null)
    }

    /** Dismiss the similar-conversations banner. */
    fun dismissSimilarConversations() {
        _uiState.value = _uiState.value.copy(similarConversationIds = emptyList())
    }

    // ── Agent association ─────────────────────────────────────────────────────

    /**
     * Load an agent and its memories into state, apply default skill/tool config.
     * If agentId is null, clears any loaded agent.
     */
    fun setAgent(agentId: String?) {
        val convId = currentConversationId
        if (convId != null) {
            viewModelScope.launch {
                app.database.conversationDao().updateAgent(convId, agentId)
            }
        }
        if (agentId == null) {
            _uiState.value = _uiState.value.copy(
                currentAgentId = null,
                currentAgent = null,
                agentMemories = emptyList()
            )
            // Remove form_memory from the tool list now that no agent is active.
            viewModelScope.launch { refreshToolsList() }
            return
        }
        viewModelScope.launch { loadAgent(agentId) }
    }

    /** Populate the chat input with pre-filled text (e.g. from a share intent). */
    fun setPrefillText(text: String) {
        _uiState.value = _uiState.value.copy(prefillText = text)
    }

    // ── Semantic Recall ───────────────────────────────────────────────────────

    /**
     * Pre-fetch semantic recall results for [userText] from the current agent's indexed
     * conversations and store them in [ChatUiState.semanticRecallSnippets].
     *
     * Call this from the UI when the user's input changes (debounced) so that results are ready
     * by the time [sendMessage] is called.  If the agent has semantic recall disabled, or the
     * embedding call fails, the state is simply cleared — no crash, no delay at send time.
     */
    fun prepareSemanticRecall(userText: String) {
        val agent = _uiState.value.currentAgent ?: return
        if (!agent.isSemanticRecallEnabled) return
        if (userText.isBlank()) return

        val baseUrl = _uiState.value.utilityBaseUrl
        val model = _uiState.value.utilityModelName
        if (baseUrl.isBlank() || model.isBlank()) return

        viewModelScope.launch {
            val snippets = withContext(Dispatchers.IO) {
                embeddingRepo.search(
                    agentId = agent.id,
                    queryText = userText,
                    baseUrl = baseUrl,
                    modelName = model
                )
            }
            _uiState.value = _uiState.value.copy(semanticRecallSnippets = snippets)
        }
    }

    /**
     * Toggle semantic recall for the current agent and index all existing agent conversations
     * when enabling for the first time.
     */
    fun updateSemanticRecallEnabled(enabled: Boolean) {
        val agentId = _uiState.value.currentAgentId ?: return
        viewModelScope.launch {
            agentRepo.updateSemanticRecallEnabled(agentId, enabled)
            loadAgent(agentId)

            if (enabled) {
                // Index all existing conversations for this agent so recall is useful immediately.
                indexAgentConversations(agentId)
            }
        }
    }

    /** Toggle the recent-conversation-context (last 48 h) feature for the current agent. */
    fun updateRecentContextEnabled(enabled: Boolean) {
        val agentId = _uiState.value.currentAgentId ?: return
        viewModelScope.launch {
            agentRepo.updateRecentContextEnabled(agentId, enabled)
            loadAgent(agentId)
        }
    }

    /** Index all conversations belonging to [agentId] that have not yet been indexed. */
    private suspend fun indexAgentConversations(agentId: String) {
        val baseUrl = _uiState.value.utilityBaseUrl
        val model = _uiState.value.utilityModelName
        if (baseUrl.isBlank() || model.isBlank()) return

        withContext(Dispatchers.IO) {
            val conversations = repo.allConversations.first()
                .filter { it.agentId == agentId }
            for (conv in conversations) {
                val messages = app.database.messageDao().getByConversation(conv.id)
                embeddingRepo.indexConversation(agentId, conv.id, messages, baseUrl, model)
            }
        }
    }

    /** Index a completed conversation exchange for future semantic recall. Called post-turn. */
    private fun indexCompletedConversation(convId: String, agentId: String) {
        val baseUrl = _uiState.value.utilityBaseUrl
        val model = _uiState.value.utilityModelName
        if (baseUrl.isBlank() || model.isBlank()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val messages = app.database.messageDao().getByConversation(convId)
                embeddingRepo.reindexConversation(agentId, convId, messages, baseUrl, model)
            }
        }
    }

    /** Pin a message to the current agent's memory. */
    fun pinMessageToAgentMemory(messageId: String, messageContent: String) {
        val agentId = _uiState.value.currentAgentId ?: return
        val convId = currentConversationId
        viewModelScope.launch {
            agentRepo.insertMemory(
                com.ongrid.app.data.local.AgentMemoryEntity(
                    agentId = agentId,
                    content = messageContent.take(500),
                    isPinned = true,
                    sourceConversationId = convId,
                    sourceMessageId = messageId
                )
            )
            loadAgent(agentId)
        }
    }

    private suspend fun loadAgent(agentId: String) {
        val agent = agentRepo.getAgent(agentId) ?: return
        val memories = agentRepo.memoriesForAgentOnce(agentId)

        // Resolve utility model for this agent
        val globalSettings = settingsRepo.settings.first()
        val globalHost = globalSettings.utilityModelHost.ifBlank { currentServer?.baseUrl ?: "" }
        val globalModel = globalSettings.utilityModelName.ifBlank { currentModel }
        val (resolvedHost, resolvedModel) = agentRepo.resolveUtilityModel(agentId, globalHost, globalModel)

        // Build recent-context snippet (last 48 h of this agent's conversations)
        val recentContextSnippet = if (agent.isRecentContextEnabled) {
            val since48h = System.currentTimeMillis() - 48L * 60 * 60 * 1000
            val recentConvs = withContext(Dispatchers.IO) {
                app.database.conversationDao().getRecentByAgent(agentId, since48h)
            }.filter { it.id != currentConversationId }
            if (recentConvs.isEmpty()) ""
            else {
                val sb = StringBuilder("What you've been working on recently (last 48 hours):")
                for (conv in recentConvs.take(5)) {
                    val msgs = withContext(Dispatchers.IO) {
                        app.database.messageDao().getByConversation(conv.id)
                    }
                    val lastUser = msgs.lastOrNull { it.role == "user" }?.content?.take(250)
                    val lastAssistant = msgs.lastOrNull { it.role == "assistant" }?.content?.take(250)
                    sb.append("\n\u2022 ${conv.title}")
                    if (!lastUser.isNullOrBlank()) sb.append("\n  You: ${lastUser.trimEnd()}")
                    if (!lastAssistant.isNullOrBlank()) sb.append("\n  You responded: ${lastAssistant.trimEnd()}")
                }
                sb.toString()
            }
        } else ""

        _uiState.value = _uiState.value.copy(
            currentAgentId = agentId,
            currentAgent = agent,
            agentMemories = memories,
            utilityBaseUrl = resolvedHost,
            utilityModelName = resolvedModel,
            recentContextSnippet = recentContextSnippet
        )

        // Apply agent's default disabled tools
        val disabledTools = agentRepo.parseDisabledTools(agent.defaultDisabledToolNames).toSet()
        _uiState.value = _uiState.value.copy(disabledToolNames = disabledTools)

        // Ensure form_memory and use_skill are included now that an agent is active.
        refreshToolsList()
    }

    // ── Project association ───────────────────────────────────────────────────

    /**
     * Associate (or disassociate) this conversation with a project.
     * Persists to Room and loads project memories into state.
     */
    fun setProjectForConversation(projectId: String?) {
        val convId = currentConversationId ?: return
        _uiState.value = _uiState.value.copy(currentProjectId = projectId)
        viewModelScope.launch {
            repo.assignToProject(convId, projectId)
            if (projectId != null) {
                loadProjectMemories(projectId)
            } else {
                _uiState.value = _uiState.value.copy(projectMemories = emptyList())
            }
        }
    }

    private suspend fun loadProjectMemories(projectId: String) {
        val memories = app.database.projectMemoryDao().memoriesForProjectOnce(projectId)
        _uiState.value = _uiState.value.copy(projectMemories = memories)
    }

    /**
     * Create a new project with the given name and immediately assign this conversation to it.
     * Returns the created ProjectEntity.
     */
    suspend fun createProjectAndAssign(name: String): com.ongrid.app.data.local.ProjectEntity {
        val project = repo.createProject(name)
        setProjectForConversation(project.id)
        return project
    }

    // ── Context compression ───────────────────────────────────────────────────

    /**
     * Summarise older messages into a single compact system message to free up context space.
     * Keeps the most recent 6 turns (12 messages) intact.
     */
    fun compressContext() {
        viewModelScope.launch {
            val toCompress = _messages.value
                .filter { !it.isStreaming && !it.isSkill && it.role != MessageRole.SYSTEM }
                .dropLast(12)
            if (toCompress.isEmpty()) return@launch

            val ollamaMessages = toCompress.map { msg ->
                OllamaChatMessage(
                    role = msg.role.name.lowercase(),
                    content = msg.content
                )
            }
            val summary = utilityAgentRepo.summariseMessages(
                _uiState.value.utilityBaseUrl,
                _uiState.value.utilityModelName,
                ollamaMessages
            ) ?: return@launch

            val summaryMsg = ChatMessage(
                role = MessageRole.SYSTEM,
                content = "Summary of earlier conversation: $summary",
                isSkill = true,
                skillName = "Context Summary"
            )
            _messages.value = listOf(summaryMsg) + _messages.value.drop(toCompress.size)
            _uiState.value = _uiState.value.copy(showCompressionButton = false)
        }
    }

    // ── Utility model resolution ──────────────────────────────────────────────

    /**
     * Reads utility model settings and resolves the effective base URL and model name,
     * falling back to the current conversation server/model when the setting is empty.
     */
    private suspend fun resolveUtilityModel() {
        val s = settingsRepo.settings.first()
        val effectiveHost = s.utilityModelHost.ifBlank { currentServer?.baseUrl ?: "" }
        val effectiveModel = s.utilityModelName.ifBlank { currentModel }
        _uiState.value = _uiState.value.copy(
            utilityBaseUrl = effectiveHost,
            utilityModelName = effectiveModel
        )
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
            title = title,
            thinkingEnabled = _uiState.value.thinkingEnabled,
            projectId = _uiState.value.currentProjectId,
            agentId = _uiState.value.currentAgentId
        )
        currentConversationId = entity.id
        // Persist the model choice as lastUsed so the next "New Chat" uses it directly.
        serverRepo.saveLastUsed(server.host, server.port, currentModel)
        return entity.id
    }

    private fun buildOllamaHistory(): List<OllamaChatMessage> {
        val all = _messages.value.filter { !it.isStreaming }
        // Order: main system messages → project memory message → skill system messages → conversation messages
        val mainSystem = all.filter { it.role == MessageRole.SYSTEM && !it.isSkill }
        val skillSystem = all.filter { it.role == MessageRole.SYSTEM && it.isSkill }
        val conversation = all.filter { it.role != MessageRole.SYSTEM }

        val agent = _uiState.value.currentAgent
        val agentMemories = _uiState.value.agentMemories
        val tokenBudget = agent?.maxContextTokens

        // Build injected context messages (1–3 from spec)
        val injected = mutableListOf<OllamaChatMessage>()

        if (agent != null) {
            // 0. Date/time awareness — always injected so the agent knows when "now" is
            val now = LocalDateTime.now(ZoneId.systemDefault())
            val dateTimeStr = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
            injected += OllamaChatMessage(role = "system", content = "Current date and time: $dateTimeStr")

            // Mood injection — prepend before the system prompt when mood tracking is on
            if (agent.isMoodTrackingEnabled && agent.currentMood.isNotBlank()) {
                val toneInstruction = moodToneInstruction(agent.currentMood)
                val moodContent = buildString {
                    append("Your current disposition is ${agent.currentMood}.")
                    if (toneInstruction.isNotBlank()) append(" $toneInstruction")
                }
                injected += OllamaChatMessage(role = "system", content = moodContent)
            }

            // 1. Agent system prompt (replaces global system prompt if set)
            if (agent.systemPrompt.isNotBlank()) {
                injected += OllamaChatMessage(role = "system", content = agent.systemPrompt)
            } else {
                // Fall back to global system prompt messages from the conversation
                injected += mainSystem.map { msg ->
                    OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
                }
            }

            // 2. Agent brief
            if (agent.brief.isNotBlank()) {
                injected += OllamaChatMessage(
                    role = "system",
                    content = "Your current context:\n${agent.brief}"
                )
            }

            // 3. Agent memories — pinned first, then recent.
            //    If a token budget is set, drop oldest non-pinned memories until we fit.
            val pinned = agentMemories.filter { it.isPinned }
            val unpinned = agentMemories.filter { !it.isPinned }
            val budgetedMemories = if (tokenBudget != null && tokenBudget > 0) {
                val fixed = injected.sumOf { (it.content?.length ?: 0) / 4 }
                val pinnedTokens = pinned.sumOf { it.content.length / 4 }
                var remaining = tokenBudget - fixed - pinnedTokens
                val trimmedUnpinned = mutableListOf<com.ongrid.app.data.local.AgentMemoryEntity>()
                for (mem in unpinned.reversed()) {           // keep most-recent first
                    val cost = mem.content.length / 4
                    if (remaining >= cost) { trimmedUnpinned.add(0, mem); remaining -= cost }
                    // else drop this memory to stay within budget
                }
                pinned + trimmedUnpinned
            } else {
                (pinned + unpinned).take(10)
            }

            if (budgetedMemories.isNotEmpty()) {
                val bullet = budgetedMemories.joinToString("\n") { "• ${it.content}" }
                injected += OllamaChatMessage(
                    role = "system",
                    content = "What you remember from previous conversations:\n$bullet"
                )
            }

            // 4. Semantic recall — relevant excerpts from past conversations
            val recallSnippets = _uiState.value.semanticRecallSnippets
            if (agent.isSemanticRecallEnabled && recallSnippets.isNotEmpty()) {
                val excerpts = recallSnippets.joinToString("\n\n---\n\n")
                injected += OllamaChatMessage(
                    role = "system",
                    content = "Relevant excerpts from your previous conversations:\n\n$excerpts"
                )
                // Consume snippets so they don't repeat on the next turn
                _uiState.value = _uiState.value.copy(semanticRecallSnippets = emptyList())
            }

            // 5. Recent conversation context — titles and last exchange from the past 48 h
            val recentSnippet = _uiState.value.recentContextSnippet
            if (agent.isRecentContextEnabled && recentSnippet.isNotEmpty()) {
                injected += OllamaChatMessage(
                    role = "system",
                    content = recentSnippet
                )
            }
        } else {
            // No agent: use existing global system prompt messages as normal
            injected += mainSystem.map { msg ->
                OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
            }
        }

        // Available-but-not-yet-active skills assigned to this agent
        if (agent != null) {
            val assignedIds = agentRepo.parseSkillIds(agent.defaultSkillIds).toSet()
            val activeIds = _uiState.value.activeSkillIds
            val availableForAgent = _uiState.value.availableSkills
                .filter { it.id in assignedIds && it.id !in activeIds }
            if (availableForAgent.isNotEmpty()) {
                val list = availableForAgent.joinToString("\n") { "• ${it.name}: ${it.description}" }
                injected += OllamaChatMessage(
                    role = "system",
                    content = "Available skills you can activate with the `use_skill` tool:\n$list"
                )
            }
        }

        // Project memories (kept for backward compatibility with project-only conversations)
        val projectMemories = _uiState.value.projectMemories
        val memoryMessages = if (projectMemories.isNotEmpty() && agent == null) {
            val bullet = projectMemories.joinToString("\n") { "• ${it.content}" }
            listOf(
                OllamaChatMessage(
                    role = "system",
                    content = "Memories from previous conversations in this project:\n$bullet"
                )
            )
        } else emptyList()

        // 4. Skills
        val skillMessages = skillSystem.map { msg ->
            OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
        }

        // 5. Conversation history
        val conversationMessages = conversation.map { msg ->
            OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
        }

        return injected + memoryMessages + skillMessages + conversationMessages
    }

    /** Maps a mood label to a brief tonal instruction injected with the mood preamble. */
    private fun moodToneInstruction(mood: String): String = when (mood.lowercase()) {
        "enthusiastic" -> "Bring energy and enthusiasm to your responses."
        "frustrated"   -> "Be patient, methodical, and reassuring."
        "meticulous"   -> "Pay meticulous attention to detail and precision."
        "focused"      -> "Maintain laser focus on the task at hand."
        "curious"      -> "Engage with curiosity and explore ideas thoroughly."
        else           -> ""
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

    /** Finalize an assistant message, attaching reasoning content if available. */
    private fun finalizeMessageWithThinking(id: String, content: String, thinking: String?) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id)
                msg.copy(content = content, isStreaming = false, thinkingContent = thinking)
            else msg
        }
    }

    /** Probe the server for model capabilities (thinking, tools, context length) in one call. */
    private fun checkThinkingSupport(baseUrl: String, modelName: String) {
        viewModelScope.launch {
            val caps = ollamaRepo.fetchModelCapabilities(baseUrl, modelName)
            _uiState.value = _uiState.value.copy(
                supportsThinking = caps.supportsThinking,
                supportsTools = caps.supportsTools,
                modelContextLength = caps.contextLength
            )
        }
    }
}
