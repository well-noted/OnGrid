package com.ongrid.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, ConversationEntity::class, MessageEntity::class, SavedServerEntity::class, SkillEntity::class, ProjectMemoryEntity::class, AgentEntity::class, AgentMemoryEntity::class],
    version = 7,
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
