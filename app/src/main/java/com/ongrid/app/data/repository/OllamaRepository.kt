package com.ongrid.app.data.repository

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

    fun streamChat(
        baseUrl: String,
        request: com.ongrid.app.data.model.OllamaChatRequest
    ) = api.streamChat(baseUrl, request)
}
