package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectMemoryDao {
    @Query("SELECT * FROM project_memories WHERE projectId = :projectId ORDER BY extractedAt DESC")
    fun memoriesForProject(projectId: String): Flow<List<ProjectMemoryEntity>>

    @Query("SELECT * FROM project_memories WHERE projectId = :projectId ORDER BY extractedAt DESC")
    suspend fun memoriesForProjectOnce(projectId: String): List<ProjectMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: ProjectMemoryEntity)

    @Query("DELETE FROM project_memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM project_memories WHERE projectId = :projectId")
    suspend fun deleteAllForProject(projectId: String)
}
