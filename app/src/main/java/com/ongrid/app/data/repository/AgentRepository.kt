package com.ongrid.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.AgentDao
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentMemoryDao
import com.ongrid.app.data.local.AgentMemoryEntity
import com.ongrid.app.data.local.AgentStatus
import com.ongrid.app.data.local.DreamLogDao
import com.ongrid.app.data.local.DreamLogEntity
import kotlinx.coroutines.flow.Flow

class AgentRepository(
    private val agentDao: AgentDao,
    private val memoryDao: AgentMemoryDao,
    private val dreamLogDao: DreamLogDao
) {
    private val gson = Gson()

    fun activeAgents(): Flow<List<AgentEntity>> = agentDao.activeAgents()
    fun allAgents(): Flow<List<AgentEntity>> = agentDao.allAgents()
    fun memoriesForAgent(agentId: String): Flow<List<AgentMemoryEntity>> = memoryDao.memoriesForAgent(agentId)
    fun dreamLogsForAgent(agentId: String): Flow<List<DreamLogEntity>> = dreamLogDao.logsForAgent(agentId)

    suspend fun getAgent(id: String): AgentEntity? = agentDao.getById(id)

    suspend fun createAgent(name: String, role: String, systemPrompt: String, color: Int): AgentEntity {
        val agent = AgentEntity(name = name.trim(), role = role.trim(), systemPrompt = systemPrompt.trim(), color = color)
        agentDao.insert(agent)
        return agent
    }

    suspend fun updateName(agentId: String, name: String) { agentDao.updateName(agentId, name) }
    suspend fun updateRole(agentId: String, role: String) { agentDao.updateRole(agentId, role) }
    suspend fun updateSystemPrompt(agentId: String, systemPrompt: String) { agentDao.updateSystemPrompt(agentId, systemPrompt) }
    suspend fun updateBrief(agentId: String, brief: String) { agentDao.updateBrief(agentId, brief, System.currentTimeMillis()) }

    suspend fun updateStatus(agentId: String, status: AgentStatus) {
        val retiredAt = if (status == AgentStatus.RETIRED) System.currentTimeMillis() else null
        agentDao.updateStatus(agentId, status, retiredAt)
    }

    suspend fun updateColor(agentId: String, color: Int) { agentDao.updateColor(agentId, color) }
    suspend fun updateUtilityModel(agentId: String, host: String, model: String) { agentDao.updateUtilityModel(agentId, host, model) }
    suspend fun setDefaultSkills(agentId: String, skillIds: List<String>) { agentDao.updateDefaultSkills(agentId, gson.toJson(skillIds)) }
    suspend fun setDefaultDisabledTools(agentId: String, toolNames: List<String>) { agentDao.updateDefaultDisabledTools(agentId, gson.toJson(toolNames)) }

    suspend fun resolveUtilityModel(agentId: String, globalHost: String, globalModel: String): Pair<String, String> {
        val agent = agentDao.getById(agentId)
        val host = agent?.utilityModelHost?.ifBlank { globalHost } ?: globalHost
        val model = agent?.utilityModelName?.ifBlank { globalModel } ?: globalModel
        return host to model
    }

    suspend fun pinMemory(memoryId: String) { memoryDao.setPinned(memoryId, true) }
    suspend fun unpinMemory(memoryId: String) { memoryDao.setPinned(memoryId, false) }
    suspend fun deleteNonPinnedMemories(agentId: String) { memoryDao.deleteNonPinned(agentId) }
    suspend fun deleteMemory(memoryId: String) { memoryDao.deleteById(memoryId) }
    suspend fun insertMemory(memory: AgentMemoryEntity) { memoryDao.insert(memory) }
    suspend fun memoriesForAgentOnce(agentId: String): List<AgentMemoryEntity> = memoryDao.memoriesForAgentOnce(agentId)

    // Phase 2: Cognition Settings

    suspend fun updateCognitionSettings(
        agentId: String,
        isDreamingEnabled: Boolean,
        isMoodTrackingEnabled: Boolean,
        isAutoBriefEnabled: Boolean,
        maxContextTokens: Int
    ) {
        agentDao.updateDreamingEnabled(agentId, isDreamingEnabled)
        agentDao.updateMoodTrackingEnabled(agentId, isMoodTrackingEnabled)
        agentDao.updateAutoBriefEnabled(agentId, isAutoBriefEnabled)
        agentDao.updateMaxContextTokens(agentId, maxContextTokens)
    }

    suspend fun updateMood(agentId: String, mood: String) { agentDao.updateMood(agentId, mood) }
    suspend fun resetMood(agentId: String) { agentDao.updateMood(agentId, "Neutral") }
    suspend fun updateLastDreamedAt(agentId: String, timestamp: Long) { agentDao.updateLastDreamedAt(agentId, timestamp) }

    // Phase 2: Dream Logs

    suspend fun insertDreamLog(log: DreamLogEntity) {
        dreamLogDao.insert(log)
        dreamLogDao.pruneOldLogs(log.agentId)
    }

    suspend fun dreamLogsForAgentOnce(agentId: String): List<DreamLogEntity> = dreamLogDao.logsForAgentOnce(agentId)

    fun parseSkillIds(json: String): List<String> = try {
        gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun parseDisabledTools(json: String): List<String> = try {
        gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }
}