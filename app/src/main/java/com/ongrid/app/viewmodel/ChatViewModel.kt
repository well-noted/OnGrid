package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.data.model.OllamaTool
import com.ongrid.app.data.model.OllamaToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val availableTools: List<OllamaTool> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OnGridApplication

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    var currentServer: OllamaServer? = null
    var currentModel: String = ""

    /** Load available MCP tools for the current session. */
    fun loadTools() {
        viewModelScope.launch {
            val toolMap = app.mcpRepository.getAllEnabledTools()
            val tools = toolMap.values.map { (_, mcpTool) -> mcpTool.toOllamaTool() }
            _uiState.value = _uiState.value.copy(availableTools = tools)
        }
    }

    /** Send a user message and stream the assistant's response. */
    fun sendMessage(text: String) {
        val server = currentServer ?: return
        if (currentModel.isBlank()) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = text)
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Build placeholder for streaming assistant response
            val assistantMsgId = java.util.UUID.randomUUID().toString()
            val assistantMsg = ChatMessage(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            )
            _messages.value = _messages.value + assistantMsg

            try {
                val history = buildOllamaHistory()
                val tools = _uiState.value.availableTools.takeIf { it.isNotEmpty() }
                val request = OllamaChatRequest(
                    model = currentModel,
                    messages = history,
                    stream = true,
                    tools = tools
                )

                var accumulatedContent = StringBuilder()
                var pendingToolCalls: List<OllamaToolCall> = emptyList()

                app.ollamaRepository.streamChat(server.baseUrl, request).collect { chunk ->
                    val delta = chunk.message?.content ?: ""
                    if (delta.isNotEmpty()) {
                        accumulatedContent.append(delta)
                        updateStreamingMessage(assistantMsgId, accumulatedContent.toString())
                    }
                    chunk.message?.tool_calls?.let { calls ->
                        if (calls.isNotEmpty()) pendingToolCalls = calls
                    }
                    if (chunk.done) {
                        finalizeMessage(assistantMsgId, accumulatedContent.toString())
                    }
                }

                // Handle tool calls if the model requested them
                if (pendingToolCalls.isNotEmpty()) {
                    handleToolCalls(pendingToolCalls)
                }
            } catch (e: Exception) {
                finalizeMessage(assistantMsgId, "Error: ${e.message}")
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /** Clear the conversation history. */
    fun clearMessages() {
        _messages.value = emptyList()
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

    private suspend fun handleToolCalls(
        toolCalls: List<OllamaToolCall>
    ) {
        val toolMap = app.mcpRepository.getAllEnabledTools()

        for (toolCall in toolCalls) {
            val funcName = toolCall.function.name
            val args = toolCall.function.arguments
            val serverEntry = toolMap[funcName]

            val resultText = if (serverEntry != null) {
                app.mcpRepository.callTool(serverEntry.first, funcName, args)
            } else {
                "Tool '$funcName' not found in any connected MCP server."
            }

            val toolResultMsg = ChatMessage(
                role = MessageRole.TOOL,
                content = resultText,
                toolCallId = funcName
            )
            _messages.value = _messages.value + toolResultMsg
        }

        // After tool results, continue the conversation with another assistant turn
        sendFollowUpAfterToolResults()
    }

    private suspend fun sendFollowUpAfterToolResults() {
        val server = currentServer ?: return
        val assistantMsgId = java.util.UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        _messages.value = _messages.value + assistantMsg

        try {
            val history = buildOllamaHistory()
            val request = OllamaChatRequest(
                model = currentModel,
                messages = history,
                stream = true
            )
            var accumulatedContent = StringBuilder()
            app.ollamaRepository.streamChat(server.baseUrl, request).collect { chunk ->
                val delta = chunk.message?.content ?: ""
                if (delta.isNotEmpty()) {
                    accumulatedContent.append(delta)
                    updateStreamingMessage(assistantMsgId, accumulatedContent.toString())
                }
                if (chunk.done) {
                    finalizeMessage(assistantMsgId, accumulatedContent.toString())
                }
            }
        } catch (e: Exception) {
            finalizeMessage(assistantMsgId, "Error: ${e.message}")
        }
    }
}
