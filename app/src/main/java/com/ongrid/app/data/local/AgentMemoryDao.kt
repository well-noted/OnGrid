package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memories WHERE agentId = :agentId ORDER BY isPinned DESC, extractedAt DESC")
    fun memoriesForAgent(agentId: String): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memories WHERE agentId = :agentId ORDER BY isPinned DESC, extractedAt DESC")
    suspend fun memoriesForAgentOnce(agentId: String): List<AgentMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: AgentMemoryEntity)

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM agent_memories WHERE agentId = :agentId AND isPinned = 0")
    suspend fun deleteNonPinned(agentId: String)

    @Query("UPDATE agent_memories SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}
