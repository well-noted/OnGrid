package com.ongrid.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ongrid.app.data.local.AgentEntity
import com.ongrid.app.viewmodel.AgentViewModel

private val AgentColorPalette = listOf(
    Color(0xFF6750A4), // Purple
    Color(0xFF0061A4), // Blue
    Color(0xFF006C4C), // Green
    Color(0xFF984716), // Orange
    Color(0xFF8B0000), // Red
    Color(0xFF006874), // Teal
    Color(0xFF535F70), // Grey-blue
    Color(0xFF984061), // Pink
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAgentSheet(
    onDismiss: () -> Unit,
    onCreated: (AgentEntity) -> Unit,
    viewModel: AgentViewModel
) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(AgentColorPalette[0]) }
    val showRoleField = name.isNotBlank()
    val nameFocusRequester = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "New Agent",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // 1. Name field — auto-focused
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester),
                label = { Text("What do you want to call this agent?") },
                placeholder = { Text("Filing Agent, Research Agent…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            LaunchedEffect(Unit) { nameFocusRequester.requestFocus() }

            // 2. Role field — animates in after name is entered
            AnimatedVisibility(
                visible = showRoleField,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("What does this agent do?") },
                    placeholder = { Text("One sentence.") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // 3. Color swatches
            AnimatedVisibility(
                visible = showRoleField,
                enter = fadeIn()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AgentColorPalette.forEach { color ->
                        val isSelected = selectedColor == color
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 36.dp else 32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { selectedColor = color },
                            contentAlignment = Alignment.Center
                        ) {}
                    }
                }
            }

            // Create button
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        viewModel.createAgent(name, role, selectedColor.toArgb()) { created ->
                            onCreated(created)
                        }
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Create")
            }
        }
    }
}
