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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.ongrid.app.data.model.OllamaServer
import com.ongrid.app.viewmodel.ConversationListViewModel
import com.ongrid.app.viewmodel.ServerSetupState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    viewModel: ConversationListViewModel,
    onNavigateBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    onNewChat: (server: OllamaServer, modelName: String) -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    val project = projects.find { it.id == projectId }
    val conversations by viewModel.displayedConversations.collectAsState()
    val serverSetupState by viewModel.serverSetupState.collectAsState()

    var showBriefEditSheet by remember { mutableStateOf(false) }
    var briefEditText by remember { mutableStateOf("") }
    val briefEditSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val projectColor = remember(projectId) { projectCardColor(projectId) }

    LaunchedEffect(projectId) {
        viewModel.selectProject(projectId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        project?.name ?: "Project",
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
                    colors = CardDefaults.cardColors(containerColor = projectColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Project",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.08.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            project?.name ?: "",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (!project?.description.isNullOrBlank()) {
                            Text(
                                project!!.description,
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
                                val state = serverSetupState
                                if (state is ServerSetupState.Ready) {
                                    val last = state.lastUsed
                                    if (last.serverHost != null && last.modelName != null) {
                                        onNewChat(
                                            OllamaServer(host = last.serverHost, port = last.serverPort),
                                            last.modelName
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
                            Text("Start a conversation", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // ── Brief card ────────────────────────────────────────────────────
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
                                "Brief",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = {
                                    briefEditText = project?.description ?: ""
                                    showBriefEditSheet = true
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "Edit brief",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "The utility agent will build this after your first conversation",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }

            // ── Skills & tools row ─────────────────────────────────────────────
            item {
                Text(
                    "Skills & Tools",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                .background(projectColor)
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
                                formatConversationDate(conversation.updatedAt),
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

    // ── Brief edit bottom sheet ────────────────────────────────────────────────
    if (showBriefEditSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch { briefEditSheetState.hide() }.invokeOnCompletion { showBriefEditSheet = false }
            },
            sheetState = briefEditSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Edit Brief", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = briefEditText,
                    onValueChange = { briefEditText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Describe the goal and context of this project.") },
                    minLines = 8,
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = {
                        if (project != null) {
                            viewModel.updateProject(projectId, project.name, briefEditText.trim())
                        }
                        scope.launch { briefEditSheetState.hide() }.invokeOnCompletion { showBriefEditSheet = false }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }
}

private fun formatConversationDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))
