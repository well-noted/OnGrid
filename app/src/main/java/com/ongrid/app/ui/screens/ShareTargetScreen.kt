package com.ongrid.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ongrid.app.PendingSharedContent
import com.ongrid.app.data.local.AgentEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetScreen(
    sharedContent: PendingSharedContent,
    activeAgents: List<AgentEntity>,
    onSelectAgent: (agentId: String, prefillText: String) -> Unit,
    onNewConversation: (prefillText: String) -> Unit,
    onDismiss: () -> Unit
) {
    val prefillText = buildPrefillText(sharedContent)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            Text(
                "Share with OnGrid",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            // Content preview
            ContentPreviewCard(sharedContent = sharedContent)

            Spacer(Modifier.height(16.dp))

            if (activeAgents.isNotEmpty()) {
                Text(
                    "Talk to an agent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeAgents, key = { it.id }) { agent ->
                        AgentChip(
                            agent = agent,
                            onClick = { onSelectAgent(agent.id, prefillText) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            TextButton(
                onClick = { onNewConversation(prefillText) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("New conversation (no agent)")
            }
        }
    }
}

@Composable
private fun ContentPreviewCard(sharedContent: PendingSharedContent) {
    var expanded by remember { mutableStateOf(false) }
    val text = sharedContent.text
    val subject = sharedContent.subject
    val isUrl = text.startsWith("http://") || text.startsWith("https://")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (isUrl) {
                if (!subject.isNullOrBlank()) {
                    Text(
                        subject,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                val maxLines = if (expanded) Int.MAX_VALUE else 3
                Text(
                    "\u201C${text}\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = maxLines,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
                )
                if (text.lines().size > 3 || text.length > 200) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { expanded = !expanded }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentChip(agent: AgentEntity, onClick: () -> Unit) {
    val chipColor = if (agent.color != 0) androidx.compose.ui.graphics.Color(agent.color)
    else MaterialTheme.colorScheme.primary
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = chipColor.copy(alpha = 0.15f))
    ) {
        Text(
            agent.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildPrefillText(content: PendingSharedContent): String {
    val text = content.text
    val isUrl = text.startsWith("http://") || text.startsWith("https://")
    return if (isUrl) {
        "I'd like to discuss this: $text"
    } else {
        text
    }
}
