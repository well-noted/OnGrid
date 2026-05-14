package com.ongrid.app.data.model

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL, TYPING }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    /** Reasoning/thinking content produced by the model before its final answer. */
    val thinkingContent: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** True when this message was injected by an active skill. */
    val isSkill: Boolean = false,
    /** The display name of the skill that generated this message. */
    val skillName: String? = null,
    /** For AGENT_HANDOFF conversations: which agent sent this message. */
    val senderAgentId: String? = null
)

data class ToolCall(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val arguments: Map<String, Any> = emptyMap()
)

/**
 * Optional model-level parameters forwarded inside `options` in the Ollama API request.
 */
data class OllamaRequestOptions(
    @com.google.gson.annotations.SerializedName("thinking_budget")
    val thinkingBudget: Int? = null,
    @com.google.gson.annotations.SerializedName("num_ctx")
    val numCtx: Int? = null
)

// Ollama chat API request/response models
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = true,
    val tools: List<OllamaTool>? = null,
    /** When non-null, explicitly enables or disables extended reasoning for models that support it. */
    val think: Boolean? = null,
    val options: OllamaRequestOptions? = null
)

data class OllamaChatMessage(
    val role: String,
    val content: String?,
    val tool_calls: List<OllamaToolCall>? = null,
    /** Populated in responses when the model emits reasoning/thinking tokens. */
    val thinking: String? = null
)

data class OllamaChatResponse(
    val model: String = "",
    val message: OllamaChatMessage? = null,
    val done: Boolean = false,
    val done_reason: String? = null,
    @com.google.gson.annotations.SerializedName("prompt_eval_count") val promptEvalCount: Int? = null,
    @com.google.gson.annotations.SerializedName("eval_count") val evalCount: Int? = null
)

data class OllamaTool(
    val type: String = "function",
    val function: OllamaToolFunction
)

data class OllamaToolFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class OllamaToolCall(
    val function: OllamaToolCallFunction
)

data class OllamaToolCallFunction(
    val name: String,
    val arguments: Map<String, Any> = emptyMap()
)
