package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedServerDao {
    @Query("SELECT * FROM saved_servers ORDER BY addedAt ASC")
    fun getAllServers(): Flow<List<SavedServerEntity>>

    @Query("SELECT * FROM saved_servers ORDER BY addedAt ASC")
    suspend fun getAllServersOnce(): List<SavedServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: SavedServerEntity)

    @Query("DELETE FROM saved_servers WHERE id = :id")
    suspend fun deleteById(id: String)
}
