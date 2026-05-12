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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "utility_settings")

private val UTILITY_AGENT_ENABLED = booleanPreferencesKey("utility_agent_enabled")
private val UTILITY_MODEL_HOST = stringPreferencesKey("utility_model_host")
private val UTILITY_MODEL_NAME = stringPreferencesKey("utility_model_name")
private val TITLE_GENERATION_ENABLED = booleanPreferencesKey("title_generation_enabled")
private val AUTO_TAGGING_ENABLED = booleanPreferencesKey("auto_tagging_enabled")
private val PROJECT_MEMORY_ENABLED = booleanPreferencesKey("project_memory_enabled")
private val SKILL_SUGGESTION_ENABLED = booleanPreferencesKey("skill_suggestion_enabled")
private val CONVERSATION_SIMILARITY_ENABLED = booleanPreferencesKey("conversation_similarity_enabled")

data class UtilitySettings(
    val utilityAgentEnabled: Boolean = true,
    val utilityModelHost: String = "",
    val utilityModelName: String = "",
    val titleGenerationEnabled: Boolean = true,
    val autoTaggingEnabled: Boolean = true,
    val projectMemoryEnabled: Boolean = true,
    val skillSuggestionEnabled: Boolean = true,
    val conversationSimilarityEnabled: Boolean = true
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<UtilitySettings> = context.settingsDataStore.data.map { prefs ->
        UtilitySettings(
            utilityAgentEnabled = prefs[UTILITY_AGENT_ENABLED] ?: true,
            utilityModelHost = prefs[UTILITY_MODEL_HOST] ?: "",
            utilityModelName = prefs[UTILITY_MODEL_NAME] ?: "",
            titleGenerationEnabled = prefs[TITLE_GENERATION_ENABLED] ?: true,
            autoTaggingEnabled = prefs[AUTO_TAGGING_ENABLED] ?: true,
            projectMemoryEnabled = prefs[PROJECT_MEMORY_ENABLED] ?: true,
            skillSuggestionEnabled = prefs[SKILL_SUGGESTION_ENABLED] ?: true,
            conversationSimilarityEnabled = prefs[CONVERSATION_SIMILARITY_ENABLED] ?: true
        )
    }

    suspend fun setUtilityAgentEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[UTILITY_AGENT_ENABLED] = enabled }
    }

    suspend fun setUtilityModel(host: String, modelName: String) {
        context.settingsDataStore.edit {
            it[UTILITY_MODEL_HOST] = host
            it[UTILITY_MODEL_NAME] = modelName
        }
    }

    suspend fun setTitleGenerationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[TITLE_GENERATION_ENABLED] = enabled }
    }

    suspend fun setAutoTaggingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_TAGGING_ENABLED] = enabled }
    }

    suspend fun setProjectMemoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[PROJECT_MEMORY_ENABLED] = enabled }
    }

    suspend fun setSkillSuggestionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SKILL_SUGGESTION_ENABLED] = enabled }
    }

    suspend fun setConversationSimilarityEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[CONVERSATION_SIMILARITY_ENABLED] = enabled }
    }
}
