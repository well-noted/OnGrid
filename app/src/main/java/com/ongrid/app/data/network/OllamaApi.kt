package com.ongrid.app.data.network

import android.util.Log
import com.google.gson.Gson
import com.ongrid.app.data.model.OllamaChatRequest
import com.ongrid.app.data.model.OllamaChatResponse
import com.ongrid.app.data.model.OllamaTagsResponse
import com.ongrid.app.data.model.OllamaVersionResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val TAG = "OllamaApi"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class OllamaApi(private val client: OkHttpClient) {

    private val gson = Gson()

    /** Fetch the Ollama version from the server. Returns null if unreachable or not Ollama. */
    suspend fun getVersion(baseUrl: String): OllamaVersionResponse? = try {
        val request = Request.Builder().url("$baseUrl/api/version").get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                gson.fromJson(body, OllamaVersionResponse::class.java)
            } else null
        }
    } catch (e: IOException) {
        Log.d(TAG, "getVersion failed for $baseUrl: ${e.message}")
        null
    }

    /** List all available models on the server. */
    suspend fun listModels(baseUrl: String): OllamaTagsResponse? = try {
        val request = Request.Builder().url("$baseUrl/api/tags").get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                gson.fromJson(body, OllamaTagsResponse::class.java)
            } else null
        }
    } catch (e: IOException) {
        Log.d(TAG, "listModels failed for $baseUrl: ${e.message}")
        null
    }

    /** Stream a chat response from Ollama, emitting each partial response as it arrives. */
    fun streamChat(baseUrl: String, request: OllamaChatRequest): Flow<OllamaChatResponse> = flow {
        val jsonBody = gson.toJson(request).toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(jsonBody)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            val source = response.body?.source() ?: throw IOException("Empty response body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                try {
                    val chunk = gson.fromJson(line, OllamaChatResponse::class.java)
                    emit(chunk)
                    if (chunk.done) break
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse chunk: $line", e)
                }
            }
        }
    }
}
