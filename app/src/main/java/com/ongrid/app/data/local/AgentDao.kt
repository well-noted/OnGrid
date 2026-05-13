package com.ongrid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY createdAt ASC")
    fun allAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE status = 'ACTIVE' ORDER BY createdAt ASC")
    fun activeAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: AgentEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE agents SET systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun updateSystemPrompt(id: String, systemPrompt: String)

    @Query("UPDATE agents SET brief = :brief, briefUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBrief(id: String, brief: String, updatedAt: Long)

    @Query("UPDATE agents SET status = :status, retiredAt = :retiredAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: AgentStatus, retiredAt: Long?)

    @Query("UPDATE agents SET defaultSkillIds = :skillIds WHERE id = :id")
    suspend fun updateDefaultSkills(id: String, skillIds: String)

    @Query("UPDATE agents SET defaultDisabledToolNames = :toolNames WHERE id = :id")
    suspend fun updateDefaultDisabledTools(id: String, toolNames: String)

    @Query("UPDATE agents SET utilityModelHost = :host, utilityModelName = :model WHERE id = :id")
    suspend fun updateUtilityModel(id: String, host: String, model: String)

    @Query("UPDATE agents SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("UPDATE agents SET role = :role WHERE id = :id")
    suspend fun updateRole(id: String, role: String)

    @Query("UPDATE agents SET color = :color WHERE id = :id")
    suspend fun updateColor(id: String, color: Int)

    @Query("UPDATE agents SET avatarIcon = :icon WHERE id = :id")
    suspend fun updateAvatarIcon(id: String, icon: String)

    @Query("UPDATE agents SET isDreamingEnabled = :enabled WHERE id = :id")
    suspend fun updateDreamingEnabled(id: String, enabled: Boolean)

    @Query("UPDATE agents SET isMoodTrackingEnabled = :enabled WHERE id = :id")
    suspend fun updateMoodTrackingEnabled(id: String, enabled: Boolean)

    @Query("UPDATE agents SET isAutoBriefEnabled = :enabled WHERE id = :id")
    suspend fun updateAutoBriefEnabled(id: String, enabled: Boolean)

    @Query("UPDATE agents SET maxContextTokens = :tokens WHERE id = :id")
    suspend fun updateMaxContextTokens(id: String, tokens: Int)

    @Query("UPDATE agents SET currentMood = :mood WHERE id = :id")
    suspend fun updateMood(id: String, mood: String)

    @Query("UPDATE agents SET lastDreamedAt = :timestamp WHERE id = :id")
    suspend fun updateLastDreamedAt(id: String, timestamp: Long)

    @Query("UPDATE agents SET isSemanticRecallEnabled = :enabled WHERE id = :id")
    suspend fun updateSemanticRecallEnabled(id: String, enabled: Boolean)

    @Query("UPDATE agents SET isRecentContextEnabled = :enabled WHERE id = :id")
    suspend fun updateRecentContextEnabled(id: String, enabled: Boolean)
}
