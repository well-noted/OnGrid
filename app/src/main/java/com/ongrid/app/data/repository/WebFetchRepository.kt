package com.ongrid.app.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

private const val TAG = "WebFetchRepository"
private const val MAX_CHARS = 8000

class WebFetchRepository(private val client: OkHttpClient) {

    val tool = com.ongrid.app.data.model.McpTool(
        name = "fetch_url",
        description = "Fetch and read the text content of a webpage given its URL. " +
            "Returns the main readable text of the page, stripped of navigation, scripts, and HTML. " +
            "Does not execute JavaScript — best suited for articles, documentation, Wikipedia, GitHub, " +
            "and similar static content. Returns an error for paywalled or JS-rendered pages.",
        inputSchema = com.ongrid.app.data.model.McpInputSchema(
            properties = mapOf(
                "url" to mapOf(
                    "type" to "string",
                    "description" to "The full URL of the page to fetch (must begin with http:// or https://)"
                )
            ),
            required = listOf("url")
        )
    )

    suspend fun fetch(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val url = args["url"]?.toString()?.trim()
            ?: return@withContext "Error: 'url' argument is required."

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext "Error: URL must begin with http:// or https://"
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; OnGrid/1.0)")
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type", "") ?: ""
                if (!response.isSuccessful) {
                    return@use "Error: HTTP ${response.code} — ${response.message}"
                }
                if ("text/html" !in contentType && "text/plain" !in contentType) {
                    return@use "Error: Content-Type '$contentType' is not readable text. " +
                        "This tool only supports HTML and plain-text pages."
                }

                val html = response.body?.string()
                    ?: return@use "Error: Empty response body."

                val text = extractReadableText(html, url)
                if (text.isBlank()) {
                    return@use "Error: Could not extract readable text from this page. " +
                        "It may be JavaScript-rendered or require authentication."
                }

                val truncated = if (text.length > MAX_CHARS) {
                    text.take(MAX_CHARS) + "\n\n[Content truncated at $MAX_CHARS characters]"
                } else text

                "Source: $url\n\n$truncated"
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetch failed for $url: ${e.message}")
            "Error: Could not reach '$url' — ${e.message}"
        } catch (e: IllegalArgumentException) {
            "Error: Invalid URL — ${e.message}"
        }
    }

    private fun extractReadableText(html: String, url: String): String {
        val doc = Jsoup.parse(html, url)

        // Remove non-content elements
        doc.select("script, style, nav, footer, aside, header, [role=navigation], " +
            "[role=banner], [role=complementary], .advertisement, .cookie-banner").remove()

        // Prefer semantic content containers
        val mainContent = doc.selectFirst("article, main, [role=main], .content, .post, .entry-content")
        val root = mainContent ?: doc.body() ?: return ""

        return root.wholeText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
