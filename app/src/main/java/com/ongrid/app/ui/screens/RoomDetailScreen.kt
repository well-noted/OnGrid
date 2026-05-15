package com.ongrid.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentRoomEntity
import com.ongrid.app.data.local.ConversationEntity
import com.ongrid.app.data.local.RoomMemoryEntity
import com.ongrid.app.viewmodel.RoomViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailScreen(
    roomId: String,
    viewModel: RoomViewModel,
    onNavigateBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit
) {
    LaunchedEffect(roomId) { viewModel.selectRoom(roomId) }

    val room by viewModel.selectedRoom.collectAsState()
    val agents by viewModel.activeAgents.collectAsState()
    val memories by viewModel.roomMemories.collectAsState()
    val conversations by viewModel.roomConversations.collectAsState()

    var showEditSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLaunchDialog by remember { mutableStateOf(false) }
    var showMemoryInput by remember { mutableStateOf(false) }
    var newMemoryText by remember { mutableStateOf("") }
    var overflowExpanded by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    val r = room ?: return

    val roomColor = Color(r.color)
    val participantAgents = viewModel.parseAgentIds(r.agentIds)
        .mapNotNull { id -> agents.find { it.id == id } }
    val orchestrator = r.orchestratorAgentId?.let { id -> agents.find { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                                .background(roomColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Psychology, contentDescription = null,
                                modifier = Modifier.size(16.dp), tint = Color.White)
                        }
                        Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditSheet = true }) {
                        Icon(Icons.Default.Edit, "Edit room")
                    }
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete room", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null,
                                        tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    overflowExpanded = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {

            // ── Hero card ─────────────────────────────────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    roomColor.copy(alpha = 0.18f),
                                    roomColor.copy(alpha = 0.04f)
                                )
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Agent avatar stack
                        Row {
                            participantAgents.take(6).forEachIndexed { index, agent ->
                                val aColor = if (agent.color != 0) Color(agent.color)
                                else MaterialTheme.colorScheme.primary
                                Box(
                                    modifier = Modifier
                                        .offset(x = (-index * 10).dp)
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(aColor.copy(alpha = 0.2f))
                                        .then(
                                            Modifier.background(
                                                Brush.radialGradient(listOf(aColor.copy(0.3f), aColor.copy(0.1f)))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        agent.name.first().uppercase(),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = aColor
                                    )
                                }
                            }
                            if (participantAgents.size > 6) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = (-60).dp)
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+${participantAgents.size - 6}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // Participant names
                        Text(
                            participantAgents.joinToString(" · ") { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Orchestrator badge
                        if (orchestrator != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(roomColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Outlined.StarOutline, null,
                                    modifier = Modifier.size(13.dp), tint = roomColor)
                                Text("${orchestrator.name} orchestrates",
                                    fontSize = 11.sp, color = roomColor,
                                    fontWeight = FontWeight.Medium)
                            }
                        }

                        // Schedule badge
                        if (r.scheduleEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Outlined.AccessTime, null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "Daily at %02d:%02d".format(r.scheduleHour, r.scheduleMinute),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Default goal
                        if (r.goalTemplate.isNotBlank()) {
                            Text(
                                r.goalTemplate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ── Launch button ─────────────────────────────────────────────────
            item {
                Button(
                    onClick = { showLaunchDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = roomColor),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Launch session", fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp)
                Spacer(Modifier.height(4.dp))
            }

            // ── Pooled memories ───────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Shared memories", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { showMemoryInput = !showMemoryInput }) {
                        Icon(Icons.Outlined.Add, "Add memory",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (showMemoryInput) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newMemoryText,
                            onValueChange = { newMemoryText = it },
                            placeholder = { Text("Add a shared memory…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (newMemoryText.isNotBlank()) {
                                    viewModel.addMemory(roomId, newMemoryText)
                                    newMemoryText = ""
                                    showMemoryInput = false
                                }
                            })
                        )
                        TextButton(
                            onClick = {
                                if (newMemoryText.isNotBlank()) {
                                    viewModel.addMemory(roomId, newMemoryText)
                                    newMemoryText = ""
                                    showMemoryInput = false
                                }
                            }
                        ) { Text("Add") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (memories.isEmpty()) {
                item {
                    Text(
                        "No shared memories yet. Add context that all agents should know.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                items(memories, key = { it.id }) { memory ->
                    RoomMemoryItem(
                        memory = memory,
                        onPin = { if (memory.isPinned) viewModel.unpinMemory(memory.id) else viewModel.pinMemory(memory.id) },
                        onDelete = { viewModel.deleteMemory(memory.id) }
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp)
                Spacer(Modifier.height(4.dp))
            }

            // ── Past sessions ─────────────────────────────────────────────────
            item {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No sessions yet. Launch one above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            } else {
                items(conversations, key = { it.id }) { conv ->
                    RoomSessionItem(
                        conversation = conv,
                        roomColor = roomColor,
                        onClick = { onOpenConversation(conv.id) },
                        onDeleteRequest = { sessionToDelete = conv }
                    )
                }
            }
        }
    }

    // ── Dialogs & sheets ──────────────────────────────────────────────────────

    if (showLaunchDialog) {
        var goalOverride by remember { mutableStateOf(r.goalTemplate) }
        AlertDialog(
            onDismissRequest = { showLaunchDialog = false },
            title = { Text("Launch session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optionally override the goal for this session:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = goalOverride,
                        onValueChange = { goalOverride = it },
                        label = { Text("Goal") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLaunchDialog = false
                        viewModel.launchRoom(r, goalOverride.ifBlank { null }) { convId ->
                            onOpenConversation(convId)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = roomColor)
                ) { Text("Launch") }
            },
            dismissButton = { TextButton(onClick = { showLaunchDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete room?") },
            text = { Text("\"${r.name}\" and all its shared memories will be deleted. Past conversations are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRoom(r.id)
                        onNavigateBack()
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete session?") },
            text = { Text("\"${session.title}\" and all its messages will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(session.id)
                        sessionToDelete = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") } }
        )
    }

    if (showEditSheet) {
        CreateEditRoomSheet(
            availableAgents = agents,
            existingRoom = r,
            onDismiss = { showEditSheet = false },
            onSave = { name, systemPrompt, agentIds, color, host, port, model,
                       schedEnabled, schedHour, schedMinute, goal, orchestId ->
                viewModel.updateRoom(
                    id = r.id,
                    name = name,
                    systemPrompt = systemPrompt,
                    agentIds = agentIds,
                    color = color,
                    serverHost = host,
                    serverPort = port,
                    modelName = model,
                    scheduleEnabled = schedEnabled,
                    scheduleHour = schedHour,
                    scheduleMinute = schedMinute,
                    goalTemplate = goal,
                    orchestratorAgentId = orchestId
                )
                showEditSheet = false
            }
        )
    }
}

@Composable
private fun RoomMemoryItem(
    memory: RoomMemoryEntity,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (memory.isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                .align(Alignment.CenterVertically)
        )
        Text(
            memory.content,
            style = MaterialTheme.typography.bodySmall,
            color = if (memory.isPinned) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onPin, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.PushPin, null,
                modifier = Modifier.size(16.dp),
                tint = if (memory.isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoomSessionItem(
    conversation: ConversationEntity,
    roomColor: Color,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(3.dp).clip(RoundedCornerShape(2.dp))
                .background(roomColor).height(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(conversation.title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conversation.goal.isNotBlank()) {
                    Text(conversation.goal, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                relativeTime(conversation.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onDeleteRequest, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete session",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff) / 7}w"
    }
}
