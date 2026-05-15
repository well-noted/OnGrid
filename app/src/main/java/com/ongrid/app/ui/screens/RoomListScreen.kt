package com.ongrid.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentRoomEntity
import com.ongrid.app.viewmodel.RoomViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    viewModel: RoomViewModel,
    onNavigateBack: () -> Unit,
    onOpenRoom: (roomId: String) -> Unit,
    defaultServerHost: String = "",
    defaultServerPort: Int = 11434,
    defaultModelName: String = ""
) {
    val rooms by viewModel.allRooms.collectAsState()
    val agents by viewModel.activeAgents.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Rooms") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "New room", tint = Color.White)
            }
        }
    ) { innerPadding ->
        if (rooms.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(40.dp)
                ) {
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Groups, null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("No rooms yet", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "Rooms are persistent multi-agent workspaces. Create one to set up a group of agents that collaborate on recurring goals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 16.dp, bottom = 100.dp
                )
            ) {
                items(rooms, key = { it.id }) { room ->
                    RoomCard(
                        room = room,
                        agents = agents,
                        parseAgentIds = viewModel::parseAgentIds,
                        onClick = { onOpenRoom(room.id) }
                    )
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateEditRoomSheet(
            availableAgents = agents,
            defaultServerHost = defaultServerHost,
            defaultServerPort = defaultServerPort,
            defaultModelName = defaultModelName,
            onDismiss = { showCreateSheet = false },
            onSave = { name, systemPrompt, agentIds, color, host, port, model,
                       schedEnabled, schedHour, schedMinute, goal, orchestId ->
                viewModel.createRoom(
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
                showCreateSheet = false
            }
        )
    }
}

@Composable
fun RoomCard(
    room: AgentRoomEntity,
    agents: List<AgentEntity>,
    parseAgentIds: (String) -> List<String>,
    onClick: () -> Unit
) {
    val roomColor = Color(room.color)
    val participantIds = parseAgentIds(room.agentIds)
    val participantAgents = participantIds.mapNotNull { id -> agents.find { it.id == id } }
    val orchestratorName = room.orchestratorAgentId
        ?.let { id -> agents.find { it.id == id }?.name }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    ) {
        // Colored left accent bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .matchParentSize()
                .background(
                    Brush.verticalGradient(listOf(roomColor, roomColor.copy(alpha = 0.4f)))
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row: name + timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        room.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (room.goalTemplate.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            room.goalTemplate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                    }
                }
                Text(
                    relativeTimestamp(room.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Agent avatar stack
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Stacked initials
                Row {
                    participantAgents.take(5).forEachIndexed { index, agent ->
                        val aColor = if (agent.color != 0) Color(agent.color)
                        else MaterialTheme.colorScheme.primary
                        Box(
                            modifier = Modifier
                                .offset(x = (-index * 8).dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(aColor.copy(alpha = 0.15f))
                                .then(
                                    if (index == 0) Modifier
                                    else Modifier.background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                agent.name.first().uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = aColor
                            )
                        }
                    }
                    if (participantAgents.size > 5) {
                        Box(
                            modifier = Modifier
                                .offset(x = (-40).dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+${participantAgents.size - 5}",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.width(
                    (if (participantAgents.size > 1) (participantAgents.size.coerceAtMost(5) - 1) * 8 else 0).dp
                ))

                Text(
                    participantAgents.take(3).joinToString(", ") { it.name }.let {
                        if (participantAgents.size > 3) "$it +${participantAgents.size - 3}" else it
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Badges row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (orchestratorName != null) {
                    RoomBadge(
                        icon = { Icon(Icons.Outlined.StarOutline, null,
                            modifier = Modifier.size(10.dp), tint = roomColor) },
                        label = orchestratorName,
                        tint = roomColor.copy(alpha = 0.12f),
                        textColor = roomColor
                    )
                }
                if (room.scheduleEnabled) {
                    RoomBadge(
                        icon = { Icon(Icons.Outlined.AccessTime, null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        label = "%02d:%02d".format(room.scheduleHour, room.scheduleMinute),
                        tint = MaterialTheme.colorScheme.surfaceVariant,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomBadge(
    icon: @Composable () -> Unit,
    label: String,
    tint: Color,
    textColor: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        icon()
        Text(label, fontSize = 9.sp, color = textColor, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun relativeTimestamp(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
        diff < TimeUnit.DAYS.toMillis(30) -> "${TimeUnit.MILLISECONDS.toDays(diff) / 7}w"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff) / 30}mo"
    }
}
