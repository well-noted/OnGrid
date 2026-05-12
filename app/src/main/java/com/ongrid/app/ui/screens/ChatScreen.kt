package com.ongrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ongrid.app.data.model.ChatMessage
import com.ongrid.app.data.model.MessageRole
import com.ongrid.app.ui.theme.AssistantBubble
import com.ongrid.app.ui.theme.SkillBubble
import com.ongrid.app.ui.theme.ToolBubble
import com.ongrid.app.ui.theme.ToolErrorBubble
import com.ongrid.app.ui.theme.UserBubble
import com.ongrid.app.viewmodel.ChatViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onOpenMcpServers: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val savedServersWithModels by viewModel.savedServersWithModels.collectAsState()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    val modelPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showToolSheet by remember { mutableStateOf(false) }
    val toolSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showThinkingSheet by remember { mutableStateOf(false) }
    val thinkingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val skillPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        TextButton(
                            onClick = { showModelPicker = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                viewModel.currentModel.substringBefore(":") + " ▾",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        viewModel.currentServer?.let { server ->
                            Text(
                                server.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.supportsThinking) {
                        IconButton(onClick = { showThinkingSheet = true }) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = "Thinking settings",
                                tint = if (uiState.thinkingEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    LocalContentColor.current
                            )
                        }
                    }
                    if (uiState.availableTools.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onOpenMcpServers) {
                        Icon(Icons.Default.Build, contentDescription = "MCP Tools")
                    }
                    IconButton(onClick = { viewModel.clearMessages() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // Active tools indicator
            if (uiState.availableTools.isNotEmpty()) {
                val activeCount = uiState.availableTools.count { it.function.name !in uiState.disabledToolNames }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showToolSheet = true },
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "\uD83D\uDD27 $activeCount/${uiState.availableTools.size} tool(s) active  \u25BE",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live streaming thinking/reasoning banner
            if (uiState.streamingThinkingContent.isNotEmpty()) {
                val thinkingScrollState = rememberScrollState()
                LaunchedEffect(uiState.streamingThinkingContent) {
                    thinkingScrollState.animateScrollTo(thinkingScrollState.maxValue)
                }
                var thinkingBannerExpanded by remember { mutableStateOf(true) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    onClick = { thinkingBannerExpanded = !thinkingBannerExpanded }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "\uD83E\uDDE0 Reasoning…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                if (thinkingBannerExpanded) "\u25B2" else "\u25BC",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (thinkingBannerExpanded) {
                            Text(
                                uiState.streamingThinkingContent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(thinkingScrollState)
                                    .padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
                if (uiState.isLoading && messages.lastOrNull()?.isStreaming == false) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }

            // Active skill chips — shown just above the input bar
            if (uiState.activeSkillIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val activeSkills = uiState.availableSkills.filter { it.id in uiState.activeSkillIds }
                    activeSkills.forEach { skill ->
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.deactivateSkill(skill.id) },
                            label = { Text(skill.name, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove ${skill.name} skill",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Input area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column {
                    // Token usage indicator — only shown when context length is known
                    val contextLength = uiState.modelContextLength
                    if (contextLength != null && uiState.tokensUsedLastTurn > 0) {
                        val usageRatio = uiState.tokensUsedLastTurn.toFloat() / contextLength
                        val usageColor = if (usageRatio >= 0.8f)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            text = "%,d / %,d tokens".format(uiState.tokensUsedLastTurn, contextLength),
                            style = MaterialTheme.typography.labelSmall,
                            color = usageColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { newValue ->
                            inputText = newValue
                            if (newValue == "/") {
                                viewModel.showSkillPicker()
                            } else if (uiState.showSkillPicker) {
                                viewModel.dismissSkillPicker()
                            }
                        },
                        placeholder = { Text("Message… (type / for skills)") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank() && !uiState.isLoading) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        }),
                        shape = RoundedCornerShape(24.dp)
                    )
                    // Skill picker button
                    IconButton(
                        onClick = { viewModel.showSkillPicker() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Add skill",
                            tint = if (uiState.activeSkillIds.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            if (uiState.isLoading) {
                                viewModel.stopGeneration()
                            } else if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = uiState.isLoading || inputText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isLoading || inputText.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        Icon(
                            if (uiState.isLoading) Icons.Default.Stop else Icons.Default.Send,
                            contentDescription = if (uiState.isLoading) "Stop" else "Send",
                            tint = if (uiState.isLoading || inputText.isNotBlank())
                                Color.White
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                }
            }
        }
    }

    // ── Skill picker sheet ────────────────────────────────────────────────────
    if (uiState.showSkillPicker) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissSkillPicker() },
            sheetState = skillPickerSheetState
        ) {
            Text(
                "Activate a Skill",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            if (uiState.availableSkills.isEmpty()) {
                Text(
                    "No skills imported yet. Add skills from Settings.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    uiState.availableSkills.forEach { skill ->
                        val isActive = skill.id in uiState.activeSkillIds
                        Card(
                            onClick = {
                                if (!isActive) {
                                    viewModel.activateSkill(skill)
                                    inputText = ""
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .padding(end = 4.dp),
                                    tint = if (isActive)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        skill.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            skill.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                }
                                if (isActive) {
                                    Text(
                                        "Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // ── Thinking settings sheet ───────────────────────────────────────────────
    if (showThinkingSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThinkingSheet = false },
            sheetState = thinkingSheetState
        ) {
            Text(
                "Thinking / Reasoning",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable thinking", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Model produces a reasoning trace before answering",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.thinkingEnabled,
                    onCheckedChange = { viewModel.toggleThinking() }
                )
            }
            if (uiState.thinkingEnabled) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Token budget: ${uiState.thinkingBudget}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = uiState.thinkingBudget.toFloat(),
                        onValueChange = { viewModel.setThinkingBudget(it.roundToInt()) },
                        valueRange = 512f..32768f,
                        steps = 0
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // ── Per-conversation tool picker ──────────────────────────────────────────
    if (showToolSheet) {
        ModalBottomSheet(
            onDismissRequest = { showToolSheet = false },
            sheetState = toolSheetState
        ) {
            Text(
                "Active Tools",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                uiState.availableTools.forEach { tool ->
                    val isEnabled = tool.function.name !in uiState.disabledToolNames
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                tool.function.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                tool.function.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { viewModel.toggleTool(tool.function.name) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── In-chat model picker ──────────────────────────────────────────────────
    if (showModelPicker) {
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            sheetState = modelPickerSheetState
        ) {
            Text(
                "Switch model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()

            if (savedServersWithModels.isEmpty()) {
                Text(
                    "No servers configured.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                savedServersWithModels.forEach { (serverDisplayName, models) ->
                    val (host, port) = serverDisplayName.split(":").let {
                        it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 11434)
                    }
                    Text(
                        serverDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
                    )
                    models.forEach { modelName ->
                        Card(
                            onClick = {
                                viewModel.changeModel(host, port, modelName)
                                scope.launch { modelPickerSheetState.hide() }
                                    .invokeOnCompletion { showModelPicker = false }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (modelName == viewModel.currentModel &&
                                    host == viewModel.currentServer?.host)
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
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isTool = message.role == MessageRole.TOOL
    val isSkill = message.isSkill

    // Skill messages are rendered as a compact banner, not a full bubble.
    if (isSkill) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SkillBubble.copy(alpha = 0.85f),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Skill active: ${message.skillName ?: "Unknown"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        return
    }

    var toolExpanded by remember { mutableStateOf(false) }
    var thinkingExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isTool) {
            val isToolError = message.isError
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { toolExpanded = !toolExpanded },
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                        }
                    ),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 16.dp
                ),
                colors = CardDefaults.cardColors(containerColor = if (isToolError) ToolErrorBubble else ToolBubble)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "\uD83D\uDD27 ${message.toolCallId ?: "Tool Result"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            if (toolExpanded) "\u25B2" else "\u25BC",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    if (toolExpanded) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Reasoning card (only for finalized assistant messages with thinking content)
                if (!isUser && message.thinkingContent != null) {
                    Card(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .combinedClickable(
                                onClick = { thinkingExpanded = !thinkingExpanded },
                                onLongClick = {
                                    clipboardManager.setText(AnnotatedString(message.thinkingContent))
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "\uD83E\uDDE0 Reasoning",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    if (thinkingExpanded) "\u25B2" else "\u25BC",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            if (thinkingExpanded) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = message.thinkingContent,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboardManager.setText(AnnotatedString(message.content))
                            }
                        ),
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser) UserBubble else AssistantBubble
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = buildAnnotatedString {
                                append(message.content)
                                if (message.isStreaming) {
                                    withStyle(SpanStyle(color = Color.White.copy(alpha = cursorAlpha))) {
                                        append("\u258C")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
