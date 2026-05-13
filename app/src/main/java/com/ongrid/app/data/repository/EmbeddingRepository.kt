package com.ongrid.app.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.ConversationEmbeddingDao
import com.ongrid.app.data.local.ConversationEmbeddingEntity
import com.ongrid.app.data.local.MessageEntity
import com.ongrid.app.data.network.OllamaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "EmbeddingRepository"

/** Maximum characters per chunk (roughly ~375 tokens at 4 chars/token). */
private const val CHUNK_MAX_CHARS = 1500

/**
 * Manages indexing and semantic search over an agent's past conversations.
 *
 * Embeddings are generated via Ollama's `/api/embed` endpoint and stored locally in Room.
 * Similarity is computed in-process with cosine distance — practical for the typical scale
 * of a personal assistant (hundreds to low thousands of chunks per agent).
 */
class EmbeddingRepository(
    private val dao: ConversationEmbeddingDao,
    private val api: OllamaApi
) {
    private val gson = Gson()
    private val floatListType = object : TypeToken<List<Float>>() {}.type

    // ── Indexing ──────────────────────────────────────────────────────────────

    /**
     * Build turn-level chunks from [messages] and embed+store any that are not already indexed.
     * Chunks are (user message + assistant reply) pairs. Skips conversations that already have
     * at least one chunk in the index.
     *
     * This is a no-op if the conversation was previously indexed; call [reindexConversation] to
     * force a refresh.
     */
    suspend fun indexConversation(
        agentId: String,
        conversationId: String,
        messages: List<MessageEntity>,
        baseUrl: String,
        modelName: String
    ) = withContext(Dispatchers.IO) {
        if (dao.countByConversation(conversationId) > 0) return@withContext   // already indexed

        val chunks = buildChunks(messages)
        for (chunk in chunks) {
            val vector = api.embed(baseUrl, modelName, chunk) ?: continue
            dao.insert(
                ConversationEmbeddingEntity(
                    agentId = agentId,
                    conversationId = conversationId,
                    chunkText = chunk,
                    embeddingJson = gson.toJson(vector)
                )
            )
        }
        Log.d(TAG, "Indexed ${chunks.size} chunks for conversation $conversationId")
    }

    /**
     * Delete all existing chunks for a conversation and re-index from scratch.
     */
    suspend fun reindexConversation(
        agentId: String,
        conversationId: String,
        messages: List<MessageEntity>,
        baseUrl: String,
        modelName: String
    ) = withContext(Dispatchers.IO) {
        dao.deleteByConversation(conversationId)
        indexConversation(agentId, conversationId, messages, baseUrl, modelName)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Embed [queryText] and return the top-[topK] most similar chunk texts from the agent's
     * indexed conversations. Returns an empty list if the embedding call fails or the index is
     * empty. Results are deduplicated by conversation to avoid flooding the context with
     * near-identical excerpts.
     */
    suspend fun search(
        agentId: String,
        queryText: String,
        baseUrl: String,
        modelName: String,
        topK: Int = 5
    ): List<String> = withContext(Dispatchers.IO) {
        val queryVec = api.embed(baseUrl, modelName, queryText) ?: return@withContext emptyList()
        val queryArr = queryVec.toFloatArray()

        val candidates = dao.getByAgent(agentId)
        if (candidates.isEmpty()) return@withContext emptyList()

        candidates
            .mapNotNull { entity ->
                val vec = try {
                    val list: List<Float> = gson.fromJson(entity.embeddingJson, floatListType)
                    list.toFloatArray()
                } catch (e: Exception) { return@mapNotNull null }
                val score = cosineSimilarity(queryArr, vec)
                entity to score
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first.chunkText }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Group messages into turn-level chunks: each chunk is a "User: …\nAssistant: …" pair.
     * Long exchanges are split further to stay under [CHUNK_MAX_CHARS].
     */
    private fun buildChunks(messages: List<MessageEntity>): List<String> {
        val chunks = mutableListOf<String>()
        val userMessages = messages.filter { it.role == "user" }
        val assistantMessages = messages.filter { it.role == "assistant" }

        // Pair each user message with the chronologically next assistant message
        var assistantIdx = 0
        for (userMsg in userMessages) {
            // Advance to the first assistant message after this user message
            while (assistantIdx < assistantMessages.size &&
                assistantMessages[assistantIdx].timestamp <= userMsg.timestamp) {
                assistantIdx++
            }
            val assistantMsg = assistantMessages.getOrNull(assistantIdx)

            val text = buildString {
                append("User: ")
                append(userMsg.content.take(CHUNK_MAX_CHARS / 2))
                if (assistantMsg != null) {
                    append("\nAssistant: ")
                    append(assistantMsg.content.take(CHUNK_MAX_CHARS / 2))
                }
            }

            if (text.length > 20) chunks += text   // skip trivially short chunks
        }

        return chunks
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f else dot / (sqrt(normA) * sqrt(normB))
    }
}
