package com.ongrid.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
private val KEY_AGENT_PLANNING = booleanPreferencesKey("agent_planning_enabled")

const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful AI assistant running on OnGrid, an offline-first Android app powered by Ollama."

class SettingsRepository(private val context: Context) {

    val systemPrompt: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
    }

    val agentPlanningEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_AGENT_PLANNING] ?: true
    }

    suspend fun saveSystemPrompt(prompt: String) {
        context.settingsDataStore.edit { it[KEY_SYSTEM_PROMPT] = prompt }
    }

    suspend fun saveAgentPlanningEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AGENT_PLANNING] = enabled }
    }
}
