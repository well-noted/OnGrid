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
     * Returns true if the given model advertises the "thinking" capability via /api/show.
     * Falls back to false on any error so the UI simply hides the thinking button.
     */
    suspend fun checkThinkingSupport(baseUrl: String, modelName: String): Boolean =
        withContext(Dispatchers.IO) {
            api.showModel(baseUrl, modelName)?.capabilities?.contains("thinking") == true
        }

    /**
     * Returns the model's maximum context length from /api/show, or null if unavailable.
     */
    suspend fun detectContextLength(baseUrl: String, modelName: String): Int? =
        withContext(Dispatchers.IO) {
            api.showModel(baseUrl, modelName)?.contextLength
        }

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
