package com.ongrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ongrid.app.data.local.AgentMemoryEntity
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.viewmodel.AgentViewModel
import com.ongrid.app.viewmodel.ServerSetupState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    agentId: String,
    viewModel: AgentViewModel,
    onNavigateBack: () -> Unit,
    onTalkToAgent: (agentId: String, server: OllamaServer, modelName: String) -> Unit,
    onOpenConversation: (conversationId: String) -> Unit
) {
    val allAgents by viewModel.allAgents.collectAsState()
    val agent = allAgents.find { it.id == agentId }
    val memories by viewModel.agentMemories.collectAsState()
    val conversations by viewModel.agentConversations.collectAsState()
    val savedServers by viewModel.savedServers.collectAsState()
    val allSkills by viewModel.allSkills.collectAsState()

    var showSystemPromptSheet by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var systemPromptText by remember { mutableStateOf("") }
    val systemPromptSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val memorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(agentId) {
        viewModel.selectAgent(agentId)
    }

    LaunchedEffect(agent?.systemPrompt) {
        systemPromptText = agent?.systemPrompt ?: ""
    }

    val agentColor = remember(agent?.color) { if (agent != null) Color(agent.color) else Color.Gray }

    val defaultSkillIds: List<String> = remember(agent?.defaultSkillIds) {
        try {
            Gson().fromJson(agent?.defaultSkillIds ?: "[]", object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    val pinnedMemories = memories.filter { it.isPinned }.take(3)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        agent?.name ?: "Agent",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSystemPromptSheet = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit system prompt")
                    }
                    IconButton(onClick = { /* overflow menu placeholder */ }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
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
                .padding(innerPadding)
        ) {
            // ── Hero card ─────────────────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = agentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Agent",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.08.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            agent?.name ?: "",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (!agent?.role.isNullOrBlank()) {
                            Text(
                                agent!!.role,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                        Button(
                            onClick = {
                                val server = savedServers.firstOrNull()
                                if (server != null) {
                                    val models: List<String> = try {
                                        Gson().fromJson(
                                            server.modelsJson,
                                            object : TypeToken<List<String>>() {}.type
                                        )
                                    } catch (e: Exception) { emptyList() }
                                    val model = models.firstOrNull() ?: ""
                                    if (model.isNotBlank()) {
                                        onTalkToAgent(
                                            agentId,
                                            OllamaServer(host = server.host, port = server.port),
                                            model
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black.copy(alpha = 0.18f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "Talk to ${agent?.name ?: "agent"}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ── State (brief) card ─────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "State",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (agent?.brief.isNullOrBlank()) {
                            Text(
                                "The utility agent will build this after your first conversation",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                        } else {
                            // Parse brief as key: value lines
                            val briefLines = (agent?.brief ?: "").lines()
                                .filter { it.contains(":") }
                                .take(3)
                            if (briefLines.isEmpty()) {
                                Text(
                                    agent?.brief ?: "",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                briefLines.forEach { line ->
                                    val parts = line.split(":", limit = 2)
                                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                        Text(
                                            parts[0].trim(),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(0f, fill = false)
                                                .then(Modifier.size(58.dp, 20.dp))
                                        )
                                        Text(
                                            parts.getOrElse(1) { "" }.trim(),
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Memory card ────────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Memory",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Manage",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showMemorySheet = true }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (pinnedMemories.isEmpty()) {
                            Text(
                                "No memories yet",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                        } else {
                            pinnedMemories.forEachIndexed { index, memory ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        memory.content,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (index < pinnedMemories.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Active skills section ──────────────────────────────────────────
            item {
                Text(
                    "Active Skills",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(defaultSkillIds) { skillId ->
                        val skill = allSkills.find { it.id == skillId }
                        FilterChip(
                            selected = true,
                            onClick = { /* no-op */ },
                            label = { Text(skill?.name ?: skillId) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { /* open skill selection */ },
                            label = { Text("Add skill") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── Conversations section ──────────────────────────────────────────
            item {
                Text(
                    "Conversations",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(conversations, key = { it.id }) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenConversation(conversation.id) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(agentColor)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(
                                conversation.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                                    .format(Date(conversation.updatedAt)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // ── System prompt editor bottom sheet ─────────────────────────────────────
    if (showSystemPromptSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { systemPromptSheetState.hide() }.invokeOnCompletion {
                    showSystemPromptSheet = false
                }
            },
            sheetState = systemPromptSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("System Prompt", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = systemPromptText,
                    onValueChange = { systemPromptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Describe who this agent is and what it does. This becomes the agent's identity in every conversation.")
                    },
                    minLines = 8,
                    shape = RoundedCornerShape(12.dp)
                )
                Text(
                    "This replaces the global system prompt for conversations with this agent.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        viewModel.updateSystemPrompt(agentId, systemPromptText.trim())
                        scope.launch { systemPromptSheetState.hide() }.invokeOnCompletion {
                            showSystemPromptSheet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }

    // ── Memory manage bottom sheet ─────────────────────────────────────────────
    if (showMemorySheet) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { memorySheetState.hide() }.invokeOnCompletion { showMemorySheet = false }
            },
            sheetState = memorySheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    "Memory",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val pinned = memories.filter { it.isPinned }
                val unpinned = memories.filter { !it.isPinned }
                if (pinned.isNotEmpty()) {
                    Text(
                        "Pinned",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    pinned.forEach { memory ->
                        MemorySwipeItem(
                            memory = memory,
                            isPinned = true,
                            onPin = { viewModel.unpinMemory(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory.id) }
                        )
                    }
                }
                if (unpinned.isNotEmpty()) {
                    Text(
                        "Recent",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    unpinned.forEach { memory ->
                        MemorySwipeItem(
                            memory = memory,
                            isPinned = false,
                            onPin = { viewModel.pinMemory(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory.id) }
                        )
                    }
                }
                if (unpinned.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearUnpinnedMemories(agentId) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear unpinned")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySwipeItem(
    memory: AgentMemoryEntity,
    isPinned: Boolean,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.StartToEnd -> { onPin(); false }
                else -> false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    "Delete",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp
                )
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isPinned) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                memory.content,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}
