package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String,    // first non-blank line of the file content
    val content: String,        // full file text
    val importedAt: Long = System.currentTimeMillis()
)
