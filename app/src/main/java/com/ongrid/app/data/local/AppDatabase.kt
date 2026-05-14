package com.ongrid.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, ConversationEntity::class, MessageEntity::class, SavedServerEntity::class, SkillEntity::class, ProjectMemoryEntity::class, AgentEntity::class, AgentMemoryEntity::class, DreamLogEntity::class, DreamScheduleEntity::class, ConversationEmbeddingEntity::class],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun savedServerDao(): SavedServerDao
    abstract fun skillDao(): SkillDao
    abstract fun projectMemoryDao(): ProjectMemoryDao
    abstract fun agentDao(): AgentDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun dreamLogDao(): DreamLogDao
    abstract fun dreamScheduleDao(): DreamScheduleDao
    abstract fun conversationEmbeddingDao(): ConversationEmbeddingDao
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN conversationType TEXT NOT NULL DEFAULT 'STANDARD'")
        db.execSQL("ALTER TABLE conversations ADD COLUMN participantAgentIds TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE conversations ADD COLUMN goal TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN senderAgentId TEXT")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN avatarIcon TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN isRecentContextEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_embeddings (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                chunkText TEXT NOT NULL,
                embeddingJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_embeddings_agentId ON conversation_embeddings(agentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_embeddings_conversationId ON conversation_embeddings(conversationId)")
        db.execSQL("ALTER TABLE agents ADD COLUMN isSemanticRecallEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dream_schedules (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL,
                scheduleType TEXT NOT NULL,
                timeHour INTEGER NOT NULL DEFAULT 2,
                timeMinute INTEGER NOT NULL DEFAULT 0,
                label TEXT NOT NULL DEFAULT '',
                isEnabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Phase 2 cognition fields on agents
        db.execSQL("ALTER TABLE agents ADD COLUMN isDreamingEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE agents ADD COLUMN isMoodTrackingEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE agents ADD COLUMN isAutoBriefEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE agents ADD COLUMN maxContextTokens INTEGER NOT NULL DEFAULT 4096")
        db.execSQL("ALTER TABLE agents ADD COLUMN currentMood TEXT NOT NULL DEFAULT 'Neutral'")
        db.execSQL("ALTER TABLE agents ADD COLUMN lastDreamedAt INTEGER NOT NULL DEFAULT 0")

        // Dream logs table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dream_logs (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                summary TEXT NOT NULL,
                fullLogJson TEXT NOT NULL,
                moodChange TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dream_logs_agentId ON dream_logs(agentId)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skills (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                content TEXT NOT NULL,
                importedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN thinkingEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE projects ADD COLUMN description TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_memories (
                id TEXT PRIMARY KEY NOT NULL,
                projectId TEXT NOT NULL,
                content TEXT NOT NULL,
                sourceConversationId TEXT NOT NULL,
                extractedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add agentId to conversations
        db.execSQL("ALTER TABLE conversations ADD COLUMN agentId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations(agentId)")

        // Create agents table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agents (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT '',
                systemPrompt TEXT NOT NULL DEFAULT '',
                brief TEXT NOT NULL DEFAULT '',
                briefUpdatedAt INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                defaultSkillIds TEXT NOT NULL DEFAULT '[]',
                defaultDisabledToolNames TEXT NOT NULL DEFAULT '[]',
                color INTEGER NOT NULL DEFAULT 0,
                utilityModelHost TEXT NOT NULL DEFAULT '',
                utilityModelName TEXT NOT NULL DEFAULT '',
                retiredAt INTEGER,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Create agent_memories table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_memories (
                id TEXT PRIMARY KEY NOT NULL,
                agentId TEXT NOT NULL,
                content TEXT NOT NULL,
                isPinned INTEGER NOT NULL DEFAULT 0,
                sourceConversationId TEXT,
                sourceMessageId TEXT,
                extractedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
