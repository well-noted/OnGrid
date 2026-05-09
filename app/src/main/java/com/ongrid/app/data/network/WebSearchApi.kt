package com.ongrid.app.data.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "WebSearchApi"

/**
 * Performs web searches using DuckDuckGo's Instant Answer API.
 *
 * The API is free, requires no key, and returns structured data suitable for
 * injecting into an LLM context: direct answers, Wikipedia-style abstracts,
 * definitions, and related-topic snippets.
 *
 * Limitations: this is an *instant answer* API, not a full-text search index.
 * It works well for factual/knowledge queries; for breaking news or very recent
 * events the results may be sparse.
 */
class WebSearchApi(private val client: OkHttpClient) {

    /**
     * Search for [query] and return a human-readable summary of the top results,
     * formatted to be fed back as a tool result to the model.
     */
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val url = "https://api.duckduckgo.com/".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("no_html", "1")
            ?.addQueryParameter("skip_disambig", "1")
            ?.build()
            ?: return@withContext "Search error: could not build request URL."

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OnGrid/1.0 (Android; +https://github.com/ongrid)")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext "Search failed: HTTP ${response.code}."
            }
            val body = response.body?.string()
                ?: return@withContext "Search returned an empty response."
            formatResponse(query, body)
        } catch (e: Exception) {
            Log.e(TAG, "Search request failed", e)
            "Search failed: ${e.message ?: "unknown error"}."
        }
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private fun formatResponse(query: String, json: String): String {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val sb = StringBuilder()

            // 1. Instant answer (calculations, conversions, etc.)
            obj.stringOrNull("Answer")?.let { sb.appendLine("Answer: $it") }

            // 2. Abstract (Wikipedia / knowledge-graph summary)
            obj.stringOrNull("AbstractText")?.let { abstract ->
                sb.appendLine("Summary: $abstract")
                obj.stringOrNull("AbstractURL")?.let { sb.appendLine("Source: $it") }
            }

            // 3. Definition
            obj.stringOrNull("Definition")?.let { def ->
                sb.appendLine("Definition: $def")
                obj.stringOrNull("DefinitionURL")?.let { sb.appendLine("Source: $it") }
            }

            // 4. Related topics — top 5 text snippets
            val related = obj.getAsJsonArray("RelatedTopics")
            if (related != null) {
                val snippets = mutableListOf<String>()
                for (element in related) {
                    if (snippets.size >= 5) break
                    if (!element.isJsonObject) continue
                    val topic = element.asJsonObject
                    // Skip topic groupings (they have a "Topics" array instead of "Text")
                    topic.stringOrNull("Text")?.let { snippets += "- $it" }
                }
                if (snippets.isNotEmpty()) {
                    sb.appendLine("Related results:")
                    snippets.forEach { sb.appendLine(it) }
                }
            }

            if (sb.isBlank()) {
                "No results found for \"$query\". " +
                    "The query may be too specific or require a real-time data source."
            } else {
                sb.toString().trimEnd()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse search response", e)
            "Failed to parse search results."
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        runCatching { get(key)?.asString?.takeIf { it.isNotBlank() } }.getOrNull()
}
