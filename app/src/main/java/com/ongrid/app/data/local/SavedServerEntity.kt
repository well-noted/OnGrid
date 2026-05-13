package com.ongrid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_servers")
data class SavedServerEntity(
    /** "host:port" — used as a stable, unique key. */
    @PrimaryKey val id: String,
    val host: String,
    val port: Int,
    val version: String = "",
    /** JSON array of model name strings, e.g. ["llama3:8b","mistral:latest"]. */
    val modelsJson: String = "[]",
    val addedAt: Long = System.currentTimeMillis()
) {
    val displayName: String get() = "$host:$port"
    val baseUrl: String get() = "http://$host:$port"
}
