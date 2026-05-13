package com.ongrid.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentMemoryEntity
import com.ongrid.app.data.local.AgentStatus
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.SavedServerEntity
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.data.local.SkillEntity
import com.ongrid.app.viewmodel.AgentViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    agentId: String,
    viewModel: AgentViewModel,
    availableSkills: List<SkillEntity>,
    availableToolNames: List<String>,
    onNavigateBack: () -> Unit,
    onTalkToAgent: (agentId: String, server: OllamaServer, modelName: String) -> Unit,
    onOpenConversation: (conversationId: String) -> Unit
) {
    LaunchedEffect(agentId) { viewModel.selectAgent(agentId) }

    val agent by viewModel.selectedAgent.collectAsState()
    val memories by viewModel.agentMemories.collectAsState()
    val conversations by viewModel.agentConversations.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Sheet states
    var showSystemPromptSheet by remember { mutableStateOf(false) }
    var showUtilityModelSheet by remember { mutableStateOf(false) }
    var showBriefEditSheet by remember { mutableStateOf(false) }
    var showMemoryManageSheet by remember { mutableStateOf(false) }
    var showSkillsToolsSheet by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var showModelPickerForTalk by remember { mutableStateOf(false) }

    // Collapsible card states
    var briefExpanded by remember { mutableStateOf(true) }
    var memoriesExpanded by remember { mutableStateOf(false) }

    val currentAgent = agent ?: return

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Agent Workspace") },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Identity Card ─────────────────────────────────────────────────
            item {
                AgentIdentityCard(
                    agent = currentAgent,
                    onNameChange = { viewModel.updateName(currentAgent.id, it) },
                    onRoleChange = { viewModel.updateRole(currentAgent.id, it) },
                    onEditSystemPrompt = { showSystemPromptSheet = true },
                    onConfigureUtilityModel = { showUtilityModelSheet = true },
                    onStatusMenuClick = { showStatusMenu = true },
                    showStatusMenu = showStatusMenu,
                    onStatusSelected = { status ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.updateStatus(currentAgent.id, status)
                        showStatusMenu = false
                    },
                    onDismissStatusMenu = { showStatusMenu = false },
                    onTalkClick = { showModelPickerForTalk = true }
                )
            }

            // ── Brief Card ────────────────────────────────────────────────────
            item {
                AgentBriefCard(
                    agent = currentAgent,
                    expanded = briefExpanded,
                    onToggleExpand = { briefExpanded = !briefExpanded },
                    onEditBrief = { showBriefEditSheet = true }
                )
            }

            // ── Memory Card ───────────────────────────────────────────────────
            item {
                AgentMemoryCard(
                    memories = memories,
                    agentName = currentAgent.name,
                    expanded = memoriesExpanded,
                    onToggleExpand = { memoriesExpanded = !memoriesExpanded },
                    onManage = { showMemoryManageSheet = true }
                )
            }

            // ── Skills & Tools Row ────────────────────────────────────────────
            item {
                val activeSkillCount = viewModel.parseSkillIds(currentAgent.defaultSkillIds).size
                val disabledToolCount = viewModel.parseDisabledTools(currentAgent.defaultDisabledToolNames).size
                AgentSkillsToolsRow(
                    activeSkillCount = activeSkillCount,
                    disabledToolCount = disabledToolCount,
                    onManage = { showSkillsToolsSheet = true }
                )
            }

            // ── Conversation History ──────────────────────────────────────────
            item {
                Text(
                    "Conversations",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet. Tap \"Talk to ${currentAgent.name}\" to start one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                items(conversations, key = { it.id }) { conv ->
                    AgentConversationCard(
                        conversation = conv,
                        agentColor = currentAgent.color,
                        onClick = { onOpenConversation(conv.id) },
                        onRemoveFromAgent = { viewModel.removeConversationFromAgent(conv.id) }
                    )
                }
            }
        }
    }

    // ── System Prompt Sheet ───────────────────────────────────────────────────
    if (showSystemPromptSheet) {
        var draftPrompt by remember { mutableStateOf(currentAgent.systemPrompt) }
        ModalBottomSheet(
            onDismissRequest = { showSystemPromptSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "System Prompt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draftPrompt,
                    onValueChange = { draftPrompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    placeholder = {
                        Text(
                            "Describe who this agent is and what it does. This becomes the agent's identity in every conversation.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    maxLines = Int.MAX_VALUE,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This replaces the global system prompt for conversations with this agent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.updateSystemPrompt(currentAgent.id, draftPrompt)
                        showSystemPromptSheet = false
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save")
                }
            }
        }
    }

    // ── Utility Model Sheet ───────────────────────────────────────────────────
    if (showUtilityModelSheet) {
        val currentHost = currentAgent.utilityModelHost
        val currentModel = currentAgent.utilityModelName
        ModalBottomSheet(
            onDismissRequest = { showUtilityModelSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            UtilityModelPicker(
                savedServers = savedServers,
                currentHost = currentHost,
                currentModel = currentModel,
                onSelectGlobal = {
                    viewModel.clearUtilityModel(currentAgent.id)
                    showUtilityModelSheet = false
                },
                onSelectModel = { host, model ->
                    viewModel.updateUtilityModel(currentAgent.id, host, model)
                    showUtilityModelSheet = false
                }
            )
        }
    }

    // ── Brief Edit Sheet ──────────────────────────────────────────────────────
    if (showBriefEditSheet) {
        var draftBrief by remember { mutableStateOf(currentAgent.brief) }
        ModalBottomSheet(
            onDismissRequest = { showBriefEditSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text("Edit Brief", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draftBrief,
                    onValueChange = { draftBrief = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    label = { Text("Brief") },
                    maxLines = Int.MAX_VALUE,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        viewModel.updateBrief(currentAgent.id, draftBrief)
                        showBriefEditSheet = false
                    },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Save") }
            }
        }
    }

    // ── Memory Manage Sheet ───────────────────────────────────────────────────
    if (showMemoryManageSheet) {
        MemoryManageSheet(
            memories = memories,
            onDismiss = { showMemoryManageSheet = false },
            onPin = { viewModel.pinMemory(it) },
            onUnpin = { viewModel.unpinMemory(it) },
            onDelete = { viewModel.deleteMemory(it) },
            onClearUnpinned = {
                viewModel.clearUnpinnedMemories(currentAgent.id)
                showMemoryManageSheet = false
            }
        )
    }

    // ── Skills & Tools Sheet ──────────────────────────────────────────────────
    if (showSkillsToolsSheet) {
        val activeSkillIds = viewModel.parseSkillIds(currentAgent.defaultSkillIds).toMutableSet()
        val disabledTools = viewModel.parseDisabledTools(currentAgent.defaultDisabledToolNames).toMutableSet()
        AgentSkillsToolsSheet(
            agentId = currentAgent.id,
            availableSkills = availableSkills,
            initialActiveSkillIds = activeSkillIds,
            availableToolNames = availableToolNames,
            initialDisabledToolNames = disabledTools,
            onDismiss = { showSkillsToolsSheet = false },
            onSave = { skillIds, toolNames ->
                viewModel.setDefaultSkills(currentAgent.id, skillIds.toList())
                viewModel.setDefaultDisabledTools(currentAgent.id, toolNames.toList())
                showSkillsToolsSheet = false
            }
        )
    }

    // ── Model Picker for Talk ─────────────────────────────────────────────────
    if (showModelPickerForTalk) {
        AgentTalkModelPicker(
            savedServers = savedServers,
            onDismiss = { showModelPickerForTalk = false },
            onSelected = { server, modelName ->
                showModelPickerForTalk = false
                onTalkToAgent(currentAgent.id, server, modelName)
            }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun AgentIdentityCard(
    agent: AgentEntity,
    onNameChange: (String) -> Unit,
    onRoleChange: (String) -> Unit,
    onEditSystemPrompt: () -> Unit,
    onConfigureUtilityModel: () -> Unit,
    onStatusMenuClick: () -> Unit,
    showStatusMenu: Boolean,
    onStatusSelected: (AgentStatus) -> Unit,
    onDismissStatusMenu: () -> Unit,
    onTalkClick: () -> Unit
) {
    val seedColor = if (agent.color != 0) Color(agent.color) else MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = seedColor.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Name — inline edit
            var editingName by remember { mutableStateOf(false) }
            var nameText by remember(agent.name) { mutableStateOf(agent.name) }
            val nameFocusRequester = remember { FocusRequester() }

            if (editingName) {
                androidx.compose.foundation.text.BasicTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                        .onFocusChanged { if (!it.isFocused && editingName) {
                            onNameChange(nameText)
                            editingName = false
                        }},
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    ),
                    singleLine = true
                )
                LaunchedEffect(Unit) { nameFocusRequester.requestFocus() }
            } else {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingName = true }
                )
            }

            Spacer(Modifier.height(4.dp))

            // Role — inline edit
            var editingRole by remember { mutableStateOf(false) }
            var roleText by remember(agent.role) { mutableStateOf(agent.role) }
            val roleFocusRequester = remember { FocusRequester() }

            if (editingRole) {
                androidx.compose.foundation.text.BasicTextField(
                    value = roleText,
                    onValueChange = { roleText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(roleFocusRequester)
                        .onFocusChanged { if (!it.isFocused && editingRole) {
                            onRoleChange(roleText)
                            editingRole = false
                        }},
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    singleLine = true
                )
                LaunchedEffect(Unit) { roleFocusRequester.requestFocus() }
            } else {
                Text(
                    text = agent.role.ifBlank { "Tap to add a role…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (agent.role.isBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingRole = true }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Icon row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEditSystemPrompt) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit system prompt")
                }
                IconButton(onClick = onConfigureUtilityModel) {
                    Icon(Icons.Default.Settings, contentDescription = "Configure utility model")
                }
                Box {
                    IconButton(onClick = onStatusMenuClick) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Status menu")
                    }
                    DropdownMenu(
                        expanded = showStatusMenu,
                        onDismissRequest = onDismissStatusMenu
                    ) {
                        AgentStatus.values().forEach { status ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (agent.status == status) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(status.name.lowercase().replaceFirstChar { it.uppercase() })
                                    }
                                },
                                onClick = { onStatusSelected(status) }
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = agent.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (agent.status) {
                        AgentStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        AgentStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                        AgentStatus.RETIRED -> MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onTalkClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Talk to ${agent.name}")
            }
        }
    }
}

@Composable
private fun AgentBriefCard(
    agent: AgentEntity,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onEditBrief: () -> Unit
) {
    val recentlyUpdated = System.currentTimeMillis() - agent.briefUpdatedAt < TimeUnit.HOURS.toMillis(24)

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Brief", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (recentlyUpdated && agent.brief.isNotBlank()) {
                    PulsingDot()
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onEditBrief, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit brief", modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                HorizontalDivider()
                if (agent.brief.isBlank()) {
                    Text(
                        "The utility agent will build this after your first conversation with ${agent.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        agent.brief.split("/").map { it.trim() }.forEach { section ->
                            if (section.contains(":")) {
                                val (label, value) = section.split(":", limit = 2)
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        text = label.trim() + ":",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(64.dp)
                                    )
                                    Text(
                                        text = value.trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Text(section, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (agent.briefUpdatedAt > 0L) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Last updated ${relativeTime(agent.briefUpdatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

@Composable
private fun AgentMemoryCard(
    memories: List<AgentMemoryEntity>,
    agentName: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onManage: () -> Unit
) {
    val pinnedMemories = memories.filter { it.isPinned }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${memories.size} ${if (memories.size == 1) "memory" else "memories"}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onManage) { Text("Manage") }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                HorizontalDivider()
                if (pinnedMemories.isEmpty()) {
                    Text(
                        "No pinned memories. Pin important facts from conversations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    pinnedMemories.take(3).forEach { memory ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp).padding(top = 2.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                memory.content,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AgentSkillsToolsRow(
    activeSkillCount: Int,
    disabledToolCount: Int,
    onManage: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManage)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Skills & Tools", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                buildString {
                    if (activeSkillCount > 0) append("$activeSkillCount skill${if (activeSkillCount != 1) "s" else ""}")
                    if (disabledToolCount > 0) {
                        if (activeSkillCount > 0) append(", ")
                        append("$disabledToolCount disabled")
                    }
                    if (activeSkillCount == 0 && disabledToolCount == 0) append("defaults")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentConversationCard(
    conversation: ConversationEntity,
    agentColor: Int,
    onClick: () -> Unit,
    onRemoveFromAgent: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart }
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemoveFromAgent()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    "Remove",
                    modifier = Modifier.padding(end = 16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    ) {
        val borderColor = if (agentColor != 0) Color(agentColor) else MaterialTheme.colorScheme.primary
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .border(
                    width = 2.dp,
                    color = borderColor.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 12.dp, bottomEnd = 12.dp)
                ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Left color accent
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(72.dp)
                        .background(borderColor, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        relativeTime(conversation.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryManageSheet(
    memories: List<AgentMemoryEntity>,
    onDismiss: () -> Unit,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearUnpinned: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Manage Memories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            val pinned = memories.filter { it.isPinned }
            val recent = memories.filter { !it.isPinned }

            if (pinned.isNotEmpty()) {
                Text("Pinned", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                pinned.forEach { memory ->
                    MemoryItem(
                        memory = memory,
                        onPin = onPin,
                        onUnpin = onUnpin,
                        onDelete = onDelete
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (recent.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                recent.forEach { memory ->
                    MemoryItem(
                        memory = memory,
                        onPin = onPin,
                        onUnpin = onUnpin,
                        onDelete = onDelete
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onClearUnpinned,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Clear unpinned", color = MaterialTheme.colorScheme.error) }
            }

            if (memories.isEmpty()) {
                Text(
                    "No memories yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryItem(
    memory: AgentMemoryEntity,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { if (memory.isPinned) onUnpin(memory.id) else onPin(memory.id) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = if (memory.isPinned) "Unpin" else "Pin",
                modifier = Modifier.size(16.dp),
                tint = if (memory.isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        Text(
            memory.content,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = { onDelete(memory.id) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UtilityModelPicker(
    savedServers: List<SavedServerEntity>,
    currentHost: String,
    currentModel: String,
    onSelectGlobal: () -> Unit,
    onSelectModel: (host: String, model: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Utility Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "This agent's background processing will use this model independently of your global setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // Global option
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelectGlobal)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = currentHost.isBlank() && currentModel.isBlank(),
                onClick = onSelectGlobal
            )
            Spacer(Modifier.width(8.dp))
            Text("Use global utility agent setting", style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        val gson = Gson()
        savedServers.forEach { server ->
            val models: List<String> = try {
                gson.fromJson(server.modelsJson, object : TypeToken<List<String>>() {}.type)
            } catch (e: Exception) { emptyList() }

            if (models.isNotEmpty()) {
                Text(
                    "${server.host}:${server.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(server.baseUrl, model) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentHost == server.baseUrl && currentModel == model,
                            onClick = { onSelectModel(server.baseUrl, model) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(model, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSkillsToolsSheet(
    agentId: String,
    availableSkills: List<SkillEntity>,
    initialActiveSkillIds: MutableSet<String>,
    availableToolNames: List<String>,
    initialDisabledToolNames: MutableSet<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>, Set<String>) -> Unit
) {
    var activeSkillIds by remember { mutableStateOf(initialActiveSkillIds.toSet()) }
    var disabledToolNames by remember { mutableStateOf(initialDisabledToolNames.toSet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Default Skills & Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "These activate for every conversation with this agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (availableSkills.isNotEmpty()) {
                Text("Skills", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                availableSkills.forEach { skill ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                activeSkillIds = if (skill.id in activeSkillIds)
                                    activeSkillIds - skill.id
                                else activeSkillIds + skill.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = skill.id in activeSkillIds,
                            onCheckedChange = {
                                activeSkillIds = if (it) activeSkillIds + skill.id
                                else activeSkillIds - skill.id
                            }
                        )
                        Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (availableToolNames.isNotEmpty()) {
                Text("Disabled Tools", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Checked = disabled for this agent by default",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                availableToolNames.forEach { toolName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                disabledToolNames = if (toolName in disabledToolNames)
                                    disabledToolNames - toolName
                                else disabledToolNames + toolName
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = toolName in disabledToolNames,
                            onCheckedChange = {
                                disabledToolNames = if (it) disabledToolNames + toolName
                                else disabledToolNames - toolName
                            }
                        )
                        Text(toolName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = { onSave(activeSkillIds, disabledToolNames) },
                modifier = Modifier.align(Alignment.End)
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTalkModelPicker(
    savedServers: List<SavedServerEntity>,
    onDismiss: () -> Unit,
    onSelected: (OllamaServer, String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Choose model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            val gson = Gson()
            savedServers.forEach { server ->
                val models: List<String> = try {
                    gson.fromJson(server.modelsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) { emptyList() }

                if (models.isNotEmpty()) {
                    Text(
                        "${server.host}:${server.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    models.forEach { model ->
                        TextButton(
                            onClick = {
                                onSelected(
                                    OllamaServer(host = server.host, port = server.port),
                                    model
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                model,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
