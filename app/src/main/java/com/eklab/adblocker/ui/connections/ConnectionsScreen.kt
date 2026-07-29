package com.eklab.adblocker.ui.connections

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.eklab.adblocker.core.ProtocolType
import com.eklab.adblocker.core.SelectorType
import com.eklab.adblocker.db.entities.ConnectionLog
import com.eklab.adblocker.repository.InstalledApp
import com.eklab.adblocker.ui.common.AppIconImage
import com.eklab.adblocker.ui.common.formatBytes
import com.eklab.adblocker.ui.common.formatTimestamp
import com.eklab.adblocker.ui.common.relativeTime
import com.eklab.adblocker.ui.rules.RuleDraft
import com.eklab.adblocker.ui.rules.RuleEditDialog

@Composable
fun ConnectionsScreen(viewModel: ConnectionsViewModel = hiltViewModel()) {
    val pagingItems = viewModel.pagingFlow.collectAsLazyPagingItems()
    val search by viewModel.searchQueryState.collectAsStateWithLifecycle()
    val blockedOnly by viewModel.blockedOnlyState.collectAsStateWithLifecycle()
    val appFilter by viewModel.appFilterState.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val hostSuggestions by viewModel.hostSuggestions.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<ConnectionLog?>(null) }
    var ruleDraft by remember { mutableStateOf<RuleDraft?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Connections",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        OutlinedTextField(
            value = search,
            onValueChange = { viewModel.setSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search host or IP") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = blockedOnly,
                onClick = { viewModel.setBlockedOnly(!blockedOnly) },
                label = { Text("Blocked only") },
            )
            AppFilterDropdown(
                selected = appFilter,
                apps = installedApps,
                onSelect = { viewModel.setAppFilter(it) },
            )
        }

        if (pagingItems.loadState.refresh is LoadState.Loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey { it.id },
            ) { index ->
                pagingItems[index]?.let { log ->
                    ConnectionRow(
                        log = log,
                        appIcon = viewModel.appIcon(log.appPackage),
                        onClick = { selected = log },
                    )
                }
            }

            if (pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0) {
                item {
                    Text(
                        "No connections recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (pagingItems.loadState.append is LoadState.Loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    selected?.let { log ->
        ConnectionDetailSheet(
            log = log,
            onDismiss = { selected = null },
            onCreateRule = {
                selected = null
                val host = log.sni ?: log.dnsHostname ?: log.resolvedHostname
                ruleDraft = if (host != null) {
                    RuleDraft(selectorType = SelectorType.HOST, selectorValue = host)
                } else {
                    RuleDraft(selectorType = SelectorType.IP, selectorValue = log.destIp)
                }
            },
        )
    }

    ruleDraft?.let { draft ->
        RuleEditDialog(
            initial = draft,
            installedApps = installedApps,
            hostSuggestions = hostSuggestions,
            onPreview = viewModel::previewMatchCount,
            onDismiss = { ruleDraft = null },
            onConfirm = { rule ->
                viewModel.addRule(rule)
                ruleDraft = null
            },
        )
    }
}

@Composable
private fun AppFilterDropdown(
    selected: String?,
    apps: List<InstalledApp>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.let { pkg ->
        apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    } ?: "All apps"
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All apps") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            apps.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(app.packageName)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    log: ConnectionLog,
    appIcon: Drawable?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconImage(
            drawable = appIcon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    log.appName ?: log.appPackage ?: "Unknown app",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    relativeTime(log.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                log.sni ?: log.dnsHostname ?: log.resolvedHostname ?: log.destIp,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProtocolBadge(log.protocol)
                Icon(
                    imageVector = if (log.blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                    contentDescription = if (log.blocked) "Blocked" else "Allowed",
                    tint = if (log.blocked) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    formatBytes(log.bytesSent + log.bytesReceived),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: ProtocolType) {
    val color = when (protocol) {
        ProtocolType.HTTPS -> Color(0xFF2E7D32)
        ProtocolType.HTTP -> Color(0xFF1565C0)
        ProtocolType.DNS -> Color(0xFF6A1B9A)
        ProtocolType.QUIC -> Color(0xFFEF6C00)
        ProtocolType.OTHER_TCP -> Color(0xFF546E7A)
        ProtocolType.OTHER_UDP -> Color(0xFF546E7A)
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            protocol.name,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDetailSheet(
    log: ConnectionLog,
    onDismiss: () -> Unit,
    onCreateRule: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Connection details", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow("App", log.appName ?: "—")
            DetailRow("Package", log.appPackage ?: "—")
            DetailRow("UID", log.uid.toString())
            DetailRow("Time", formatTimestamp(log.timestamp))
            DetailRow("Protocol", log.protocol.name)
            DetailRow("Destination", "${log.destIp}:${log.destPort}")
            DetailRow("SNI", log.sni ?: "—")
            DetailRow("DNS hostname", log.dnsHostname ?: "—")
            DetailRow("Resolved hostname", log.resolvedHostname ?: "—")
            DetailRow("Bytes sent", formatBytes(log.bytesSent))
            DetailRow("Bytes received", formatBytes(log.bytesReceived))
            DetailRow("Duration", "${log.durationMs} ms")
            DetailRow("Verdict", if (log.blocked) "Blocked" else "Allowed")
            DetailRow("Matched rule", log.matchedRuleId?.toString() ?: "—")
            DetailRow("Log ID", log.id.toString())
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onCreateRule, modifier = Modifier.fillMaxWidth()) {
                Text("Create block rule from this connection")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
