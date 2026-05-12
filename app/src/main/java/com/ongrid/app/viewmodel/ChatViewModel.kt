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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableTools: List<OllamaTool> = emptyList(),
    val disabledToolNames: Set<String> = emptySet(),
    /** True when the current model advertises the "thinking" capability. */
    val supportsThinking: Boolean = false,
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
    /** Resolved utility server base URL (falls back to conversation server). */
    val utilityBaseUrl: String = "",
    /** Resolved utility model name (falls back to conversation model). */
    val utilityModelName: String = ""
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
        _uiState.value = _uiState.value.copy(
            disabledToolNames = emptySet(),
            supportsThinking = false,
            thinkingEnabled = false,
            lastTurnUsedThinking = false,
            streamingThinkingContent = "",
            modelContextLength = null,
            tokensUsedLastTurn = 0,
            currentProjectId = null,
            projectMemories = emptyList(),
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
        viewModelScope.launch {
            currentConversationId = conversationId
            titleGenerated = true  // existing conversations already have a title
            val entity = repo.getConversation(conversationId) ?: return@launch
            currentServer = OllamaServer(host = entity.serverHost, port = entity.serverPort)
            currentModel = entity.modelName
            _messages.value = repo.getMessages(conversationId)
            val hadThinking = _messages.value.any { it.thinkingContent != null }
            _uiState.value = _uiState.value.copy(
                thinkingEnabled = entity.thinkingEnabled,
                lastTurnUsedThinking = hadThinking,
                currentProjectId = entity.projectId
            )
            // Load project memories if there's an associated project
            entity.projectId?.let { loadProjectMemories(it) }
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
        viewModelScope.launch {
            val toolMap = app.mcpRepository.getAllEnabledTools()
            val mcpTools = toolMap.values.map { (_, mcpTool) -> mcpTool.toOllamaTool() }
            val builtInTools = listOf(app.webSearchRepository.tool.toOllamaTool())
            _uiState.value = _uiState.value.copy(availableTools = builtInTools + mcpTools)
        }
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
        val contextLength = _uiState.value.modelContextLength
        val request = OllamaChatRequest(
            model = currentModel,
            messages = history,
            stream = true,
            tools = tools,
            // null think = model decides; true/false = explicit override
            think = if (_uiState.value.supportsThinking) _uiState.value.thinkingEnabled else null,
            options = if (_uiState.value.thinkingEnabled)
                OllamaRequestOptions(thinkingBudget = _uiState.value.thinkingBudget, numCtx = contextLength)
            else if (contextLength != null)
                OllamaRequestOptions(numCtx = contextLength)
            else null
        )

        // Drain any leftover events from a previous (completed or cancelled) request.
        while (true) { app.chatServiceChannel.tryReceive().getOrNull() ?: break }

        app.pendingChatRequest = PendingChatRequest(assistantMsgId, server.baseUrl, request)
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
            if (settings.skillSuggestionEnabled) {
                viewModelScope.launch {
                    val skillNames = _uiState.value.availableSkills.map { it.name }
                    val suggestion = utilityAgentRepo.suggestSkill(utilityBaseUrl, utilityModel, text, skillNames)
                    if (suggestion != null) {
                        _uiState.value = _uiState.value.copy(suggestedSkillName = suggestion)
                    }
                }
            }
            if (settings.conversationSimilarityEnabled) {
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

            for (event in app.chatServiceChannel) {
                when (event) {
                    is ChatServiceEvent.Token ->
                        updateStreamingMessage(event.msgId, event.content)

                    is ChatServiceEvent.ThinkingToken -> {
                        _uiState.value = _uiState.value.copy(
                            streamingThinkingContent = event.thinking,
                            // Auto-sync the button to ON so the user can see thinking is active
                            // and has the option to disable it for the next turn.
                            lastTurnUsedThinking = true
                        )
                    }

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
            projectId = _uiState.value.currentProjectId
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

        val projectMemories = _uiState.value.projectMemories
        val memoryMessages = if (projectMemories.isNotEmpty()) {
            val bullet = projectMemories.joinToString("\n") { "• ${it.content}" }
            listOf(
                OllamaChatMessage(
                    role = "system",
                    content = "Memories from previous conversations in this project:\n$bullet"
                )
            )
        } else emptyList()

        return (mainSystem.map { msg ->
            OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
        } + memoryMessages + skillSystem.map { msg ->
            OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
        } + conversation.map { msg ->
            OllamaChatMessage(role = msg.role.name.lowercase(), content = msg.content)
        })
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

    /** Probe the server to see if [modelName] supports thinking; updates [ChatUiState] accordingly. */
    private fun checkThinkingSupport(baseUrl: String, modelName: String) {
        viewModelScope.launch {
            val supported = ollamaRepo.checkThinkingSupport(baseUrl, modelName)
            _uiState.value = _uiState.value.copy(
                supportsThinking = supported
            )
        }
        viewModelScope.launch {
            val contextLength = ollamaRepo.detectContextLength(baseUrl, modelName)
            _uiState.value = _uiState.value.copy(modelContextLength = contextLength)
        }
    }
}
