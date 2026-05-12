package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name ASC")
    fun allSkills(): Flow<List<SkillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: String): SkillEntity?
}
