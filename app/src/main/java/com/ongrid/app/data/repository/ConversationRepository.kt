package com.ongrid.app.data.repository

import com.ongrid.app.data.local.AppDatabase
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.MessageEntity
import com.ongrid.app.data.local.ProjectEntity
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.MessageRole
import kotlinx.coroutines.flow.Flow

class ConversationRepository(private val db: AppDatabase) {

    val allProjects: Flow<List<ProjectEntity>> = db.projectDao().getAllProjects()
    val allConversations: Flow<List<ConversationEntity>> = db.conversationDao().getAllConversations()

    // ── Projects ──────────────────────────────────────────────────────────────

    suspend fun createProject(name: String): ProjectEntity {
        val project = ProjectEntity(name = name.trim())
        db.projectDao().insert(project)
        return project
    }

    suspend fun deleteProject(id: String) {
        db.projectDao().deleteById(id)
    }

    suspend fun updateProject(id: String, name: String, description: String) {
        db.projectDao().update(id, name, description)
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    suspend fun getConversation(id: String): ConversationEntity? =
        db.conversationDao().getById(id)

    suspend fun createConversation(
        serverHost: String,
        serverPort: Int,
        modelName: String,
        projectId: String? = null,
        agentId: String? = null,
        title: String = "New Conversation",
        thinkingEnabled: Boolean = false
    ): ConversationEntity {
        val conv = ConversationEntity(
            serverHost = serverHost,
            serverPort = serverPort,
            modelName = modelName,
            projectId = projectId,
            agentId = agentId,
            title = title,
            thinkingEnabled = thinkingEnabled
        )
        db.conversationDao().insert(conv)
        return conv
    }

    suspend fun updateThinkingEnabled(id: String, thinkingEnabled: Boolean) {
        db.conversationDao().updateThinkingEnabled(id, thinkingEnabled)
    }

    suspend fun updateTitle(id: String, title: String) {
        db.conversationDao().updateTitle(id, title, System.currentTimeMillis())
    }

    suspend fun touchConversation(id: String) {
        db.conversationDao().updateTimestamp(id, System.currentTimeMillis())
    }

    suspend fun assignToProject(conversationId: String, projectId: String?) {
        db.conversationDao().updateProject(conversationId, projectId)
    }

    suspend fun assignToAgent(conversationId: String, agentId: String?) {
        db.conversationDao().updateAgent(conversationId, agentId)
    }

    fun conversationsForAgent(agentId: String) =
        db.conversationDao().getByAgent(agentId)

    suspend fun deleteConversation(id: String) {
        db.conversationDao().deleteById(id)
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    suspend fun getMessages(conversationId: String): List<ChatMessage> =
        db.messageDao().getByConversation(conversationId).map { it.toChatMessage() }

    suspend fun saveMessage(conversationId: String, message: ChatMessage) {
        db.messageDao().insert(
            MessageEntity(
                id = message.id,
                conversationId = conversationId,
                role = message.role.name,
                content = message.content,
                thinkingContent = message.thinkingContent ?: "",
                timestamp = message.timestamp
            )
        )
    }

    suspend fun clearMessages(conversationId: String) {
        db.messageDao().deleteByConversation(conversationId)
    }
}

private fun MessageEntity.toChatMessage() = ChatMessage(
    id = id,
    role = MessageRole.valueOf(role),
    content = content,
    thinkingContent = thinkingContent.ifEmpty { null },
    timestamp = timestamp
)
