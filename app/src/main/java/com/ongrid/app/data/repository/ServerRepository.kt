package com.ongrid.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.AppDatabase
import com.ongrid.app.data.local.SavedServerEntity
import com.ongrid.app.data.model.OllamaModel
import com.ongrid.app.data.model.OllamaServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_prefs")
private val LAST_USED_HOST = stringPreferencesKey("last_used_host")
private val LAST_USED_PORT = intPreferencesKey("last_used_port")
private val LAST_USED_MODEL = stringPreferencesKey("last_used_model")
private val DEFAULT_THINKING_ON = booleanPreferencesKey("default_thinking_on")

data class LastUsedPrefs(
    val serverHost: String? = null,
    val serverPort: Int = 11434,
    val modelName: String? = null
)

class ServerRepository(
    private val db: AppDatabase,
    private val context: Context
) {
    private val gson = Gson()

    val savedServers: Flow<List<SavedServerEntity>> = db.savedServerDao().getAllServers()

    val lastUsedPrefs: Flow<LastUsedPrefs> = context.serverDataStore.data.map { prefs ->
        LastUsedPrefs(
            serverHost = prefs[LAST_USED_HOST],
            serverPort = prefs[LAST_USED_PORT] ?: 11434,
            modelName = prefs[LAST_USED_MODEL]
        )
    }

    suspend fun getSavedServersOnce(): List<SavedServerEntity> =
        db.savedServerDao().getAllServersOnce()

    suspend fun saveServer(server: OllamaServer) {
        val modelsJson = gson.toJson(server.models.map { it.name })
        db.savedServerDao().insert(
            SavedServerEntity(
                id = "${server.host}:${server.port}",
                host = server.host,
                port = server.port,
                version = server.version,
                modelsJson = modelsJson
            )
        )
    }

    suspend fun removeServer(host: String, port: Int) {
        db.savedServerDao().deleteById("$host:$port")
    }

    suspend fun saveLastUsed(serverHost: String, serverPort: Int, modelName: String) {
        context.serverDataStore.edit { prefs ->
            prefs[LAST_USED_HOST] = serverHost
            prefs[LAST_USED_PORT] = serverPort
            prefs[LAST_USED_MODEL] = modelName
        }
    }

    val defaultThinkingOn: Flow<Boolean> = context.serverDataStore.data.map { prefs ->
        prefs[DEFAULT_THINKING_ON] ?: false
    }

    suspend fun setDefaultThinkingOn(enabled: Boolean) {
        context.serverDataStore.edit { prefs ->
            prefs[DEFAULT_THINKING_ON] = enabled
        }
    }
}

// ── Conversion helpers ────────────────────────────────────────────────────────

fun SavedServerEntity.toOllamaServer(): OllamaServer {
    val modelNames: List<String> = try {
        Gson().fromJson(modelsJson, object : TypeToken<List<String>>() {}.type)
    } catch (e: Exception) {
        emptyList()
    }
    return OllamaServer(
        host = host,
        port = port,
        version = version,
        models = modelNames.map { OllamaModel(name = it) }
    )
}
