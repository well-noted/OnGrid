package com.ongrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.data.local.AgentRoomEntity

/** Swatches offered in the room color picker. */
private val ROOM_COLORS = listOf(
    Color(0xFF1A73E8), // Blue
    Color(0xFF00897B), // Teal
    Color(0xFF7B1FA2), // Purple
    Color(0xFFE53935), // Red
    Color(0xFFF4511E), // Orange
    Color(0xFF0097A7), // Cyan
    Color(0xFF558B2F), // Green
    Color(0xFF6D4C41), // Brown
)

/**
 * Bottom sheet for creating or editing an agent room.
 *
 * Pass [existingRoom] to pre-fill the form for editing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateEditRoomSheet(
    availableAgents: List<AgentEntity>,
    existingRoom: AgentRoomEntity? = null,
    defaultServerHost: String = "",
    defaultServerPort: Int = 11434,
    defaultModelName: String = "",
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        systemPrompt: String,
        agentIds: List<String>,
        color: Int,
        serverHost: String,
        serverPort: Int,
        modelName: String,
        scheduleEnabled: Boolean,
        scheduleHour: Int,
        scheduleMinute: Int,
        goalTemplate: String,
        orchestratorAgentId: String?
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Pre-fill state from existing room if editing
    var name by remember { mutableStateOf(existingRoom?.name ?: "") }
    var systemPrompt by remember { mutableStateOf(existingRoom?.systemPrompt ?: "") }
    var goalTemplate by remember { mutableStateOf(existingRoom?.goalTemplate ?: "") }
    var serverHost by remember { mutableStateOf(existingRoom?.serverHost ?: defaultServerHost) }
    var serverPort by remember { mutableStateOf((existingRoom?.serverPort ?: defaultServerPort).toString()) }
    var modelName by remember { mutableStateOf(existingRoom?.modelName ?: defaultModelName) }
    var scheduleEnabled by remember { mutableStateOf(existingRoom?.scheduleEnabled ?: false) }
    var selectedColor by remember {
        mutableStateOf(if (existingRoom != null) Color(existingRoom.color) else ROOM_COLORS[0])
    }
    var orchestratorAgentId by remember { mutableStateOf(existingRoom?.orchestratorAgentId) }
    var showTimePicker by remember { mutableStateOf(false) }

    val timePickerState = rememberTimePickerState(
        initialHour = existingRoom?.scheduleHour ?: 9,
        initialMinute = existingRoom?.scheduleMinute ?: 0,
        is24Hour = true
    )

    val selectedAgentIds = remember {
        mutableStateListOf<String>().also { list ->
            if (existingRoom != null) {
                // We'll parse these from the passed room's agentIds JSON in the parent
                // For simplicity the parent passes already-resolved IDs via existingRoom.agentIds
            }
        }
    }

    // Seed agent selection from existing room
    LaunchedEffect(existingRoom) {
        if (existingRoom != null && selectedAgentIds.isEmpty()) {
            // Parse agentIds JSON - simple approach
            val json = existingRoom.agentIds
            val ids = json.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
            selectedAgentIds.addAll(ids)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Text(
                text = if (existingRoom != null) "Edit Room" else "New Agent Room",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            // ── Room name ─────────────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Room name") },
                leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // ── Color picker ──────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Color", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ROOM_COLORS.forEach { color ->
                        val isSelected = color == selectedColor
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                                .clickable { selectedColor = color },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null,
                                    tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            // ── Agent selection ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Participants", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium)
                }
                Text("Select 2 or more agents",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableAgents.forEach { agent ->
                        val isSelected = agent.id in selectedAgentIds
                        val agentColor = if (agent.color != 0) Color(agent.color)
                        else MaterialTheme.colorScheme.primary
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    selectedAgentIds.remove(agent.id)
                                    if (orchestratorAgentId == agent.id) orchestratorAgentId = null
                                } else {
                                    selectedAgentIds.add(agent.id)
                                }
                            },
                            label = { Text(agent.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape)
                                        .background(agentColor)
                                )
                            }
                        )
                    }
                }
            }

            // ── Orchestrator picker ───────────────────────────────────────────
            if (selectedAgentIds.size >= 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.StarOutline, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Orchestrator", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium)
                    }
                    Text(
                        "The orchestrator decides who speaks next. Leave blank for round-robin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // None option
                        FilterChip(
                            selected = orchestratorAgentId == null,
                            onClick = { orchestratorAgentId = null },
                            label = { Text("None (round-robin)") }
                        )
                        // Only agents already selected as participants
                        availableAgents.filter { it.id in selectedAgentIds }.forEach { agent ->
                            FilterChip(
                                selected = orchestratorAgentId == agent.id,
                                onClick = {
                                    orchestratorAgentId =
                                        if (orchestratorAgentId == agent.id) null else agent.id
                                },
                                label = { Text(agent.name) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp)

            // ── Room system prompt ─────────────────────────────────────────────
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("Room guidelines (shared system prompt)") },
                placeholder = { Text("E.g. Stay focused. Be concise. Prioritise action items.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 5,
                shape = RoundedCornerShape(12.dp)
            )

            // ── Goal template ──────────────────────────────────────────────────
            OutlinedTextField(
                value = goalTemplate,
                onValueChange = { goalTemplate = it },
                label = { Text("Default goal") },
                placeholder = { Text("What should each session accomplish?") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )

            HorizontalDivider(thickness = 0.5.dp)

            // ── Server settings ────────────────────────────────────────────────
            Text("Server", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = serverHost,
                    onValueChange = { serverHost = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            HorizontalDivider(thickness = 0.5.dp)

            // ── Schedule ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.AccessTime, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (scheduleEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        Text("Daily schedule", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium)
                        if (scheduleEnabled) {
                            Text(
                                "Runs every day at %02d:%02d".format(
                                    timePickerState.hour, timePickerState.minute
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text("Off", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Switch(checked = scheduleEnabled, onCheckedChange = { scheduleEnabled = it })
            }

            if (scheduleEnabled) {
                if (showTimePicker) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()) {
                        TimePicker(state = timePickerState)
                        TextButton(onClick = { showTimePicker = false }) { Text("Done") }
                    }
                } else {
                    TextButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Outlined.AccessTime, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Change time (%02d:%02d)".format(
                            timePickerState.hour, timePickerState.minute))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Save button ───────────────────────────────────────────────────
            val canSave = name.isNotBlank() && selectedAgentIds.size >= 2 &&
                serverHost.isNotBlank() && modelName.isNotBlank()

            Button(
                onClick = {
                    onSave(
                        name, systemPrompt, selectedAgentIds.toList(),
                        selectedColor.toArgb(),
                        serverHost, serverPort.toIntOrNull() ?: 11434, modelName,
                        scheduleEnabled, timePickerState.hour, timePickerState.minute,
                        goalTemplate, orchestratorAgentId
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(if (existingRoom != null) "Save changes" else "Create room",
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
