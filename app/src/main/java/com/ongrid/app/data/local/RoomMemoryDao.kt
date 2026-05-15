package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomMemoryDao {
    @Query("SELECT * FROM room_memories WHERE roomId = :roomId ORDER BY extractedAt DESC")
    fun memoriesForRoom(roomId: String): Flow<List<RoomMemoryEntity>>

    @Query("SELECT * FROM room_memories WHERE roomId = :roomId ORDER BY extractedAt DESC")
    suspend fun memoriesForRoomOnce(roomId: String): List<RoomMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: RoomMemoryEntity)

    @Query("UPDATE room_memories SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: String, isPinned: Boolean)

    @Query("DELETE FROM room_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM room_memories WHERE roomId = :roomId AND isPinned = 0")
    suspend fun deleteNonPinned(roomId: String)

    @Query("DELETE FROM room_memories WHERE roomId = :roomId")
    suspend fun deleteAllForRoom(roomId: String)
}
