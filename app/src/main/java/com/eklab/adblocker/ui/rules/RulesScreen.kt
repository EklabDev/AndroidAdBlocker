package com.eklab.adblocker.ui.rules

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eklab.adblocker.core.RuleAction
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.entities.Rule
import kotlin.math.roundToInt

@Composable
fun RulesScreen(viewModel: RulesViewModel = hiltViewModel()) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val hostSuggestions by viewModel.hostSuggestions.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var rulePendingDelete by remember { mutableStateOf<Rule?>(null) }

    // Local, user-reorderable copy of the rule list. While a drag is in progress
    // (draggedItemId != null) DB emissions are ignored so they cannot clobber it.
    var localRules by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var draggedItemId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(rules) {
        if (draggedItemId == null) localRules = rules
    }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // Measured height of one row including its bottom spacing; used as the swap stride.
    var rowStridePx by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Rules",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            if (localRules.isEmpty()) {
                Text(
                    "No rules yet. Tap + to block or allow traffic.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            ) {
                items(localRules, key = { it.id }) { rule ->
                    val isDragging = draggedItemId == rule.id
                    RuleCard(
                        rule = rule,
                        onToggleEnabled = { viewModel.setEnabled(rule.id, it) },
                        onDelete = { rulePendingDelete = rule },
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else 0f
                            }
                            .onSizeChanged { if (rowStridePx <= 0) rowStridePx = it.height },
                        dragHandleModifier = Modifier.pointerInput(rule.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedItemId = rule.id
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    draggedItemId = null
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    val orderedIds = localRules.map { it.id }
                                    draggedItemId = null
                                    dragOffsetY = 0f
                                    viewModel.reorder(orderedIds)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    if (rowStridePx > 0) {
                                        val from = localRules.indexOfFirst { it.id == rule.id }
                                        if (from >= 0) {
                                            val to = (from + (dragOffsetY / rowStridePx).roundToInt())
                                                .coerceIn(0, localRules.lastIndex)
                                            if (to != from) {
                                                localRules = localRules.toMutableList().apply {
                                                    add(to, removeAt(from))
                                                }
                                                dragOffsetY -= (to - from) * rowStridePx.toFloat()
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add rule")
        }
    }

    if (showAddDialog) {
        RuleEditDialog(
            installedApps = installedApps,
            hostSuggestions = hostSuggestions,
            onPreview = viewModel::previewMatchCount,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                viewModel.addRule(rule)
                showAddDialog = false
            },
        )
    }

    rulePendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { rulePendingDelete = null },
            title = { Text("Delete rule?") },
            text = { Text("\"${rule.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRule(rule)
                        rulePendingDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { rulePendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = dragHandleModifier.padding(8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    selectorSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionBadge(rule.action)
                    Text(
                        "Priority ${rule.priority}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete rule",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionBadge(action: RuleAction) {
    val color = when (action) {
        RuleAction.BLOCK -> Color(0xFFD32F2F)
        RuleAction.ALLOW -> Color(0xFF2E7D32)
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = action.name,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun selectorSummary(rule: Rule): String {
    val label = when (rule.selectorType) {
        SelectorType.APP -> "APP"
        SelectorType.HOST -> "HOST"
        SelectorType.HOST_SUFFIX -> "DOMAIN"
        SelectorType.IP -> "IP"
        SelectorType.TYPE -> "TYPE"
    }
    return "$label · ${rule.selectorValue}"
}
