package com.ongrid.app.data.repository

import com.ongrid.app.data.model.OllamaChatMessage
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaModel
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.data.network.OllamaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OllamaRepository(private val api: OllamaApi) {

    /** Verify the server and fetch its available models. */
    suspend fun fetchServerDetails(server: OllamaServer): OllamaServer? =
        withContext(Dispatchers.IO) {
            val version = api.getVersion(server.baseUrl) ?: return@withContext null
            val tags = api.listModels(server.baseUrl) ?: return@withContext null
            server.copy(version = version.version, models = tags.models)
        }

    /** List models for a given server base URL. */
    suspend fun listModels(baseUrl: String): List<OllamaModel> =
        withContext(Dispatchers.IO) {
            api.listModels(baseUrl)?.models ?: emptyList()
        }

    /**
     * Fetches all model capabilities in a single /api/show call.
     * Falls back to safe defaults on any error.
     */
    suspend fun fetchModelCapabilities(baseUrl: String, modelName: String): ModelCapabilities =
        withContext(Dispatchers.IO) {
            val show = api.showModel(baseUrl, modelName)
            ModelCapabilities(
                supportsThinking = show?.capabilities?.contains("thinking") == true,
                supportsTools = show?.capabilities?.contains("tools") == true,
                contextLength = show?.contextLength
            )
        }

    /** @deprecated Use [fetchModelCapabilities] to avoid duplicate /api/show calls. */
    suspend fun checkThinkingSupport(baseUrl: String, modelName: String): Boolean =
        fetchModelCapabilities(baseUrl, modelName).supportsThinking

    /** @deprecated Use [fetchModelCapabilities] to avoid duplicate /api/show calls. */
    suspend fun detectContextLength(baseUrl: String, modelName: String): Int? =
        fetchModelCapabilities(baseUrl, modelName).contextLength

    fun streamChat(
        baseUrl: String,
        request: com.ongrid.app.data.model.OllamaChatRequest
    ) = api.streamChat(baseUrl, request)

    /**
     * Ask the model to produce a short conversation title (≤ 6 words) based on the first
     * user message. Returns null if the request fails.
     */
    suspend fun generateTitle(baseUrl: String, modelName: String, firstUserMessage: String): String? {
        val titleRequest = OllamaChatRequest(
            model = modelName,
            messages = listOf(
                OllamaChatMessage(
                    role = "user",
                    content = "Summarize the following message as a short conversation title " +
                            "(maximum 6 words, no quotes, no punctuation at the end):\n\n$firstUserMessage"
                )
            ),
            stream = false
        )
        return api.chatOnce(baseUrl, titleRequest)?.trim()
            ?.replace(Regex("^[\"']|[\"']$"), "")
            ?.take(80)
    }
}

data class ModelCapabilities(
    val supportsThinking: Boolean = false,
    val supportsTools: Boolean = false,
    val contextLength: Int? = null
)
