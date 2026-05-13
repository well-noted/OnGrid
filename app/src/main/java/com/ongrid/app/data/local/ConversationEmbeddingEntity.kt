package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A single embeddable chunk of a past conversation, associated with an agent.
 *
 * Each row represents a user+assistant turn (or a standalone user message) serialised as plain
 * text in [chunkText].  [embeddingJson] holds the raw float array produced by the embedding
 * model, stored as a compact JSON array so it survives any database schema changes without
 * migration complexity.
 */
@Entity(
    tableName = "conversation_embeddings",
    indices = [Index("agentId"), Index("conversationId")]
)
data class ConversationEmbeddingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val conversationId: String,
    /** Plain-text content of the chunk (user + assistant exchange). */
    val chunkText: String,
    /** JSON-serialised List<Float> produced by the embedding model. */
    val embeddingJson: String,
    val createdAt: Long = System.currentTimeMillis()
)
