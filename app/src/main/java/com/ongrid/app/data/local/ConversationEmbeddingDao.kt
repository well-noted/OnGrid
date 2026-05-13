package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationEmbeddingEntity)

    /** Load all chunks belonging to a given agent for similarity search. */
    @Query("SELECT * FROM conversation_embeddings WHERE agentId = :agentId")
    suspend fun getByAgent(agentId: String): List<ConversationEmbeddingEntity>

    /** Remove all chunks for a conversation (e.g. when conversation is deleted). */
    @Query("DELETE FROM conversation_embeddings WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** Remove all chunks for an agent (e.g. when agent is deleted). */
    @Query("DELETE FROM conversation_embeddings WHERE agentId = :agentId")
    suspend fun deleteByAgent(agentId: String)

    /** Check whether a conversation has already been indexed. */
    @Query("SELECT COUNT(*) FROM conversation_embeddings WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: String): Int
}
