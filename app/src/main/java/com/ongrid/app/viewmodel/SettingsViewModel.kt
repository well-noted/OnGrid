package com.ongrid.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.repository.DEFAULT_SYSTEM_PROMPT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val agentPlanningEnabled: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as OnGridApplication).settingsRepository

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.systemPrompt.collect { prompt ->
                _uiState.value = _uiState.value.copy(systemPrompt = prompt)
            }
        }
        viewModelScope.launch {
            repo.agentPlanningEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(agentPlanningEnabled = enabled)
            }
        }
    }

    fun setSystemPrompt(text: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = text)
    }

    fun saveSystemPrompt() {
        viewModelScope.launch { repo.saveSystemPrompt(_uiState.value.systemPrompt) }
    }

    fun resetSystemPrompt() {
        _uiState.value = _uiState.value.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT)
        viewModelScope.launch { repo.saveSystemPrompt(DEFAULT_SYSTEM_PROMPT) }
    }

    fun toggleAgentPlanning() {
        val newVal = !_uiState.value.agentPlanningEnabled
        _uiState.value = _uiState.value.copy(agentPlanningEnabled = newVal)
        viewModelScope.launch { repo.saveAgentPlanningEnabled(newVal) }
    }
}
