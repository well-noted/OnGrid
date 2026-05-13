package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DreamScheduleDao {

    @Query("SELECT * FROM dream_schedules WHERE agentId = :agentId ORDER BY createdAt ASC")
    fun schedulesForAgent(agentId: String): Flow<List<DreamScheduleEntity>>

    @Query("SELECT * FROM dream_schedules WHERE isEnabled = 1")
    suspend fun allEnabledSchedules(): List<DreamScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: DreamScheduleEntity)

    @Update
    suspend fun update(schedule: DreamScheduleEntity)

    @Query("DELETE FROM dream_schedules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE dream_schedules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM dream_schedules WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DreamScheduleEntity?
}
