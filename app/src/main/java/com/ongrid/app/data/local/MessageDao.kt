package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY CASE WHEN role = 'TYPING' THEN 1 ELSE 0 END ASC, timestamp ASC")
    suspend fun getByConversation(conversationId: String): List<MessageEntity>

    /** Live-updating version — use this to observe messages in AGENT_HANDOFF conversations. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY CASE WHEN role = 'TYPING' THEN 1 ELSE 0 END ASC, timestamp ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Update only the content of an existing row — used to stream tokens into the TYPING placeholder. */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    /** Update only the thinkingContent of an existing row — streams reasoning tokens into the TYPING placeholder. */
    @Query("UPDATE messages SET thinkingContent = :thinking WHERE id = :id")
    suspend fun updateThinkingContent(id: String, thinking: String)

    /**
     * Promote stale TYPING rows that have content into real ASSISTANT messages so the
     * partial text is preserved for the user and the other agent to read.
     */
    @Query("UPDATE messages SET role = 'ASSISTANT' WHERE conversationId = :conversationId AND role = 'TYPING' AND length(content) > 0")
    suspend fun promoteTypingWithContent(conversationId: String)

    /**
     * Delete TYPING rows that have no content — the worker was cancelled before generating
     * anything, so there is nothing useful to preserve.
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND role = 'TYPING' AND length(content) = 0")
    suspend fun deleteEmptyTyping(conversationId: String)
}
