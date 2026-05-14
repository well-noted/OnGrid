package com.ongrid.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.OnGridApplication
import com.ongrid.app.data.local.SkillEntity
import com.ongrid.app.data.repository.UtilitySettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenServers: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as OnGridApplication
    val skillRepository = app.skillRepository
    val serverRepository = app.serverRepository
    val settingsRepository = app.settingsRepository

    val skills by skillRepository.allSkills.collectAsState(initial = emptyList())
    val defaultThinkingOn by serverRepository.defaultThinkingOn.collectAsState(initial = false)
    val utilitySettings by settingsRepository.settings.collectAsState(
        initial = UtilitySettings()
    )
    val savedServers by serverRepository.savedServers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Build server → model list for the utility model picker
    val savedServersWithModels = savedServers.map { entity ->
        val models: List<String> = try {
            Gson().fromJson(entity.modelsJson, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }
        "${entity.host}:${entity.port}" to models
    }

    var showUtilityModelSheet by remember { mutableStateOf(false) }
    val utilityModelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch { skillRepository.importFromUri(context, uri) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Servers section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text("Servers", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Manage Servers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Add, remove, or scan for Ollama servers on your network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenServers) { Text("Open") }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MCP Servers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Connect tools and data sources via the Model Context Protocol.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenMcpServers) { Text("Open") }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            // Thinking section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Thinking",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Extended reasoning lets the model think before answering. Applies to models that support it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Default thinking on for new conversations",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = defaultThinkingOn,
                        onCheckedChange = { enabled ->
                            scope.launch { serverRepository.setDefaultThinkingOn(enabled) }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            // Utility Agent section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Utility Agent",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Runs a background model in parallel to handle titling, tagging, memory, and suggestions. Disabling this turns off all background processing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Utility Agent", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = utilitySettings.utilityAgentEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settingsRepository.setUtilityAgentEnabled(enabled) }
                        }
                    )
                }

                if (utilitySettings.utilityAgentEnabled) {
                    Spacer(Modifier.height(4.dp))

                    // Utility Model picker row
                    val currentUtilityLabel = if (utilitySettings.utilityModelName.isBlank())
                        "Same as conversation model"
                    else
                        "${utilitySettings.utilityModelHost}  •  ${utilitySettings.utilityModelName}"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Utility Model", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                currentUtilityLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showUtilityModelSheet = true }) {
                            Text("Change")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Features",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )

                    UtilityFeatureToggle(
                        label = "Conversation titles",
                        description = "Automatically generates a meaningful title after the first exchange.",
                        checked = utilitySettings.titleGenerationEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setTitleGenerationEnabled(it) } }
                    )
                    UtilityFeatureToggle(
                        label = "Auto-tagging",
                        description = "Tags each conversation with relevant topics for easier search.",
                        checked = utilitySettings.autoTaggingEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setAutoTaggingEnabled(it) } }
                    )
                    UtilityFeatureToggle(
                        label = "Project memories",
                        description = "Extracts reusable facts from conversations and stores them per project.",
                        checked = utilitySettings.projectMemoryEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setProjectMemoryEnabled(it) } }
                    )
                    UtilityFeatureToggle(
                        label = "Skill suggestions",
                        description = "Suggests a relevant skill when you start a new conversation.",
                        checked = utilitySettings.skillSuggestionEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setSkillSuggestionEnabled(it) } }
                    )
                    UtilityFeatureToggle(
                        label = "Project grouping suggestions",
                        description = "Detects when a new conversation is similar to existing ones and offers to group them.",
                        checked = utilitySettings.conversationSimilarityEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setConversationSimilarityEnabled(it) } }
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            // Skills section header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Skills",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Skills are instruction files injected into the conversation to guide the assistant's behavior.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
            }

            if (skills.isEmpty()) {
                item {
                    Text(
                        "No skills imported yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(skills, key = { it.id }) { skill ->
                    SkillListItem(
                        skill = skill,
                        onDelete = { scope.launch { skillRepository.remove(skill.id) } }
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/plain",
                                "text/markdown",
                                "text/x-markdown",
                                "application/zip",
                                "application/octet-stream"  // .skill files often have no known MIME
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Skill")
                }
            }
        }
    }

    // Utility model picker bottom sheet
    if (showUtilityModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showUtilityModelSheet = false },
            sheetState = utilityModelSheetState
        ) {
            Text(
                "Select Utility Model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // "Same as conversation model" option
                Card(
                    onClick = {
                        scope.launch {
                            settingsRepository.setUtilityModel("", "")
                            showUtilityModelSheet = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (utilitySettings.utilityModelName.isBlank())
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "Same as conversation model",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                savedServersWithModels.forEach { (serverDisplayName, models) ->
                    val parts = serverDisplayName.split(":")
                    val host = parts[0]
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 11434
                    val baseUrl = "http://$host:$port"
                    Text(
                        serverDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
                    )
                    models.forEach { modelName ->
                        Card(
                            onClick = {
                                scope.launch {
                                    settingsRepository.setUtilityModel(baseUrl, modelName)
                                    showUtilityModelSheet = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (modelName == utilitySettings.utilityModelName &&
                                    baseUrl == utilitySettings.utilityModelHost)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                modelName,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SkillListItem(
    skill: SkillEntity,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 2.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                if (skill.description.isNotBlank()) {
                    Text(
                        skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${skill.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun UtilityFeatureToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
