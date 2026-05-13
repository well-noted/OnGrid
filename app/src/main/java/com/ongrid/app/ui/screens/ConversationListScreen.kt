package com.ongrid.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.ProjectEntity
import com.ongrid.app.data.local.SavedServerEntity
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.viewmodel.ConversationListViewModel
import com.ongrid.app.viewmodel.AgentViewModel
import com.ongrid.app.viewmodel.ServerSetupState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onOpenConversation: (conversationId: String) -> Unit,
    onNewChat: (server: OllamaServer, modelName: String) -> Unit,
    onManageServers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProject: (projectId: String) -> Unit = {},
    agentViewModel: AgentViewModel? = null,
    onOpenAgent: (agentId: String) -> Unit = {},
    onShowAllAgents: () -> Unit = {}
) {
    val serverSetupState by viewModel.serverSetupState.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val conversations by viewModel.displayedConversations.collectAsState()
    val selectedProjectId by viewModel.selectedProjectId.collectAsState()
    val activeAgents by (agentViewModel?.activeAgents?.collectAsState() ?: remember { mutableStateOf(emptyList()) })

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProjectsSheet by remember { mutableStateOf(false) }
    var showCreateAgentSheet by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val projectsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Build server+models list for the picker (only relevant when state is Ready)
    val savedServers: List<SavedServerEntity> =
        (serverSetupState as? ServerSetupState.Ready)?.servers ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OnGrid") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = onManageServers) {
                        Icon(Icons.Default.Settings, contentDescription = "Manage Servers")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.MenuBook, contentDescription = "Skills & Settings")
                    }
                    IconButton(onClick = { showProjectsSheet = true }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Projects")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val state = serverSetupState
                if (state is ServerSetupState.Ready) {
                    val lastUsed = state.lastUsed
                    if (lastUsed.serverHost != null && lastUsed.modelName != null) {
                        onNewChat(
                            OllamaServer(host = lastUsed.serverHost, port = lastUsed.serverPort),
                            lastUsed.modelName
                        )
                    } else {
                        showModelPicker = true
                    }
                }
            }) {
                Icon(Icons.Default.Chat, contentDescription = "New chat")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Agent rail — shown when agentViewModel is provided
            if (agentViewModel != null) {
                AgentRail(
                    agents = activeAgents,
                    onOpenAgent = onOpenAgent,
                    onCreateAgent = { showCreateAgentSheet = true },
                    onSeeAll = onShowAllAgents
                )
            }

            // Active project filter
            val activeProject = projects.find { it.id == selectedProjectId }
            if (activeProject != null) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = { viewModel.selectProject(null) },
                        label = { Text(activeProject.name) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear filter",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "No conversations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            "Tap the button below to start one",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        SwipeToDeleteContainer(
                            onDelete = { viewModel.deleteConversation(conversation.id) }
                        ) {
                            ConversationCard(
                                conversation = conversation,
                                project = projects.find { it.id == conversation.projectId },
                                allProjects = projects,
                                onClick = { onOpenConversation(conversation.id) },
                                onMoveToProject = { projectId ->
                                    viewModel.assignToProject(conversation.id, projectId)
                                },
                                onDelete = { viewModel.deleteConversation(conversation.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Projects sheet ────────────────────────────────────────────────────────
    if (showProjectsSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { projectsSheetState.hide() }.invokeOnCompletion { showProjectsSheet = false }
            },
            sheetState = projectsSheetState
        ) {
            Text(
                "Projects",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            // "All" row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.selectProject(null)
                        scope.launch { projectsSheetState.hide() }.invokeOnCompletion { showProjectsSheet = false }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("All conversations", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (selectedProjectId == null) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            // Each project
            projects.forEach { project ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectProject(project.id)
                            scope.launch { projectsSheetState.hide() }.invokeOnCompletion { showProjectsSheet = false }
                        }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(project.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (selectedProjectId == project.id) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = {
                        scope.launch { projectsSheetState.hide() }.invokeOnCompletion {
                            showProjectsSheet = false
                            onOpenProject(project.id)
                        }
                    }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Open project")
                    }
                }
            }
            if (projects.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            TextButton(
                onClick = {
                    scope.launch { projectsSheetState.hide() }.invokeOnCompletion {
                        showProjectsSheet = false
                        showNewProjectDialog = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New project")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // ── New Project dialog ────────────────────────────────────────────────────
    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewProjectDialog = false
                newProjectName = ""
            },
            title = { Text("New Project") },
            text = {
                OutlinedTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    label = { Text("Project name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProjectName.isNotBlank()) {
                            viewModel.createProject(newProjectName.trim())
                        }
                        showNewProjectDialog = false
                        newProjectName = ""
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewProjectDialog = false
                    newProjectName = ""
                }) { Text("Cancel") }
            }
        )
    }

    // ── Model picker bottom sheet ─────────────────────────────────────────────
    if (showModelPicker) {
        ModelPickerBottomSheet(
            sheetState = bottomSheetState,
            savedServers = savedServers,
            onDismiss = {
                scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
                    showModelPicker = false
                }
            },
            onModelSelected = { server, modelName ->
                scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
                    showModelPicker = false
                    onNewChat(server, modelName)
                }
            }
        )
    }

    // ── Create Agent sheet ────────────────────────────────────────────────────
    if (showCreateAgentSheet && agentViewModel != null) {
        CreateAgentSheet(
            onDismiss = { showCreateAgentSheet = false },
            onCreated = { created ->
                showCreateAgentSheet = false
                onOpenAgent(created.id)
            },
            viewModel = agentViewModel
        )
    }
}

@Composable
private fun AgentRail(
    agents: List<AgentEntity>,
    onOpenAgent: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onSeeAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(agents, key = { it.id }) { agent ->
                val dotColor = if (agent.color != 0) androidx.compose.ui.graphics.Color(agent.color)
                else MaterialTheme.colorScheme.primary
                Row(
                    modifier = Modifier
                        .clickable { onOpenAgent(agent.id) }
                        .background(
                            dotColor.copy(alpha = 0.12f),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(agent.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .clickable(onClick = onCreateAgent)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        TextButton(
            onClick = onSeeAll,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 12.dp)
        ) {
            Text("See all agents", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerBottomSheet(
    sheetState: androidx.compose.material3.SheetState,
    savedServers: List<SavedServerEntity>,
    onDismiss: () -> Unit,
    onModelSelected: (OllamaServer, String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            "Choose a model to start chatting",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        HorizontalDivider()

        if (savedServers.isEmpty()) {
            Text(
                "No servers configured.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            savedServers.forEach { entity ->
                val models: List<String> = remember(entity.modelsJson) {
                    try {
                        Gson().fromJson(entity.modelsJson, object : TypeToken<List<String>>() {}.type)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                Text(
                    entity.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
                )
                models.forEach { modelName ->
                    Card(
                        onClick = {
                            onModelSelected(
                                OllamaServer(host = entity.host, port = entity.port),
                                modelName
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationEntity,
    project: ProjectEntity?,
    allProjects: List<ProjectEntity>,
    onClick: () -> Unit,
    onMoveToProject: (String?) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${conversation.modelName.substringBefore(":")} · ${conversation.serverHost}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (project != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier
                                .width(12.dp)
                                .height(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            project.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatRelativeTime(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Move to project") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showMoveDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Move to project dialog ────────────────────────────────────────────────
    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Move to Project") },
            text = {
                LazyColumn {
                    item {
                        TextButton(
                            onClick = {
                                onMoveToProject(null)
                                showMoveDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("No project") }
                    }
                    items(allProjects, key = { it.id }) { p ->
                        TextButton(
                            onClick = {
                                onMoveToProject(p.id)
                                showMoveDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(p.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        content()
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000}h ago"
        diff < 172_800_000L -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
