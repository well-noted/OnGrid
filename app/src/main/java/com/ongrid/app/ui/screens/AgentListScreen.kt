package com.ongrid.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentRoomEntity
import com.ongrid.app.viewmodel.AgentViewModel
import com.ongrid.app.viewmodel.RoomViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    viewModel: AgentViewModel,
    navigateToAgent: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenRooms: () -> Unit = {},
    roomViewModel: RoomViewModel? = null
) {
    val agents by viewModel.allAgents.collectAsState()
    val fallbackRooms = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<AgentRoomEntity>()) }
    val rooms by (roomViewModel?.allRooms ?: fallbackRooms).collectAsState()
    val fallbackAgents = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<AgentEntity>()) }
    val roomAgents by (roomViewModel?.activeAgents ?: fallbackAgents).collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            // ── Agent Rooms section ───────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Rooms", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (rooms.isNotEmpty()) {
                        Text("${rooms.size} room${if (rooms.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenRooms() })
                    }
                }
            }

            if (rooms.isEmpty()) {
                item {
                    // Invite card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenRooms() }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Groups, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("Create a room", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            Text("Group agents into persistent multi-agent workspaces",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 15.sp)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 10.dp), thickness = 0.5.dp)
                }
            } else {
                items(rooms.take(3), key = { "room_${it.id}" }) { room ->
                    val roomColor = Color(room.color)
                    val participantIds = roomViewModel?.parseAgentIds(room.agentIds) ?: emptyList()
                    val participantAgents = participantIds.mapNotNull { id ->
                        roomAgents.find { it.id == id }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRooms() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Color dot
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(roomColor))
                        // Stacked initials
                        Row {
                            participantAgents.take(4).forEachIndexed { index, agent ->
                                val aColor = if (agent.color != 0) Color(agent.color)
                                else MaterialTheme.colorScheme.primary
                                Box(
                                    modifier = Modifier
                                        .offset(x = (-index * 6).dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(aColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(agent.name.first().uppercase(),
                                        fontSize = 9.sp, fontWeight = FontWeight.Bold, color = aColor)
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(room.name, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (room.goalTemplate.isNotBlank()) {
                                Text(room.goalTemplate,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
                if (rooms.size > 3) {
                    item {
                        Text(
                            "See all ${rooms.size} rooms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onOpenRooms() }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }

            item { HorizontalDivider(thickness = 0.5.dp) }

            // ── Share hint banner ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Your agents appear in Android's share sheet — share content from any app directly to an agent",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            // Agent list items
            items(agents, key = { it.id }) { agent ->
                AgentListItem(
                    agent = agent,
                    onClick = { navigateToAgent(agent.id) }
                )
                HorizontalDivider(thickness = 0.5.dp)
            }

            // New agent item
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            "New agent",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "New agent",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.clickable { onCreateAgent() }
                )
            }
        }
    }
}

@Composable
private fun AgentListItem(agent: AgentEntity, onClick: () -> Unit) {
    val agentColor = Color(agent.color)
    ListItem(
        headlineContent = {
            Text(agent.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                agent.role,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(agentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = agentColor
                )
            }
        },
        trailingContent = {
            Text(
                text = relativeTimestamp(agent.createdAt),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
}

private fun relativeTimestamp(epochMillis: Long): String {
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
        diff < TimeUnit.DAYS.toMillis(30) -> "${TimeUnit.MILLISECONDS.toDays(diff) / 7}w"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff) / 30}mo"
    }
}
