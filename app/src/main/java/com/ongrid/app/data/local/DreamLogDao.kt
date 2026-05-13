package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DreamLogDao {

    @Query("SELECT * FROM dream_logs WHERE agentId = :agentId ORDER BY timestamp DESC")
    fun logsForAgent(agentId: String): Flow<List<DreamLogEntity>>

    @Query("SELECT * FROM dream_logs WHERE agentId = :agentId ORDER BY timestamp DESC")
    suspend fun logsForAgentOnce(agentId: String): List<DreamLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: DreamLogEntity)

    /** Keep at most 20 most-recent logs per agent to prevent unbounded growth. */
    @Query(
        """DELETE FROM dream_logs WHERE agentId = :agentId AND id NOT IN
           (SELECT id FROM dream_logs WHERE agentId = :agentId ORDER BY timestamp DESC LIMIT 20)"""
    )
    suspend fun pruneOldLogs(agentId: String)
}
