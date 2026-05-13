package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTimestamp(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET projectId = :projectId WHERE id = :id")
    suspend fun updateProject(id: String, projectId: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE conversations SET thinkingEnabled = :thinkingEnabled WHERE id = :id")
    suspend fun updateThinkingEnabled(id: String, thinkingEnabled: Boolean)

    @Query("UPDATE conversations SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: String, tags: String)

    @Query("SELECT * FROM conversations WHERE ',' || tags || ',' LIKE '%,' || :tag || ',%' ORDER BY updatedAt DESC")
    fun getByTag(tag: String): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET agentId = :agentId WHERE id = :id")
    suspend fun updateAgent(id: String, agentId: String?)

    @Query("SELECT * FROM conversations WHERE agentId = :agentId ORDER BY updatedAt DESC")
    fun getByAgent(agentId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE (agentId IS NULL OR agentId = '') AND (projectId IS NULL OR projectId = '') ORDER BY updatedAt DESC")
    fun standaloneConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE agentId = :agentId AND updatedAt >= :sinceMs ORDER BY updatedAt DESC")
    suspend fun getRecentByAgent(agentId: String, sinceMs: Long): List<ConversationEntity>
}
