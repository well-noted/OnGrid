package com.ongrid.app.data.repository

import com.ongrid.app.data.local.DreamScheduleDao
import com.ongrid.app.data.local.DreamScheduleEntity
import kotlinx.coroutines.flow.Flow

class DreamScheduleRepository(private val dao: DreamScheduleDao) {

    fun schedulesForAgent(agentId: String): Flow<List<DreamScheduleEntity>> =
        dao.schedulesForAgent(agentId)

    suspend fun addSchedule(schedule: DreamScheduleEntity) = dao.insert(schedule)

    suspend fun deleteSchedule(id: String) = dao.deleteById(id)

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)
}
